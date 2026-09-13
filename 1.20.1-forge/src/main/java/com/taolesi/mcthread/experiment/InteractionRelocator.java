package com.taolesi.mcthread.experiment;

import com.taolesi.mcthread.config.MCThreadConfig;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Player place / use / entity interact follow one loop:
 *
 * <pre>
 *   A (main)     request, do not mutate
 *   Interaction  compute
 *   Interaction  apply-request to main
 *   A (main)     place / open / hurt …
 *   A (main)     update-request to interaction
 *   Interaction  compute
 *   Interaction  update-request to main
 *   A (main)     neighbor / block update
 * </pre>
 *
 * Live reads ({@code getBlockState}, {@code getBlockEntity}) and writes happen
 * only in the main-thread apply step. The interaction thread never touches
 * the live world. The owner thread never waits.
 */
public final class InteractionRelocator {

    private static final ThreadLocal<Integer> APPLY_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> APPLYING_UPDATE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> APPLYING_TICK = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> ON_INTERACTION = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> ON_TICK = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> ON_COMPUTE = ThreadLocal.withInitial(() -> false);

    private InteractionRelocator() {
    }

    public static boolean isInsideRelocatedVanilla() {
        return APPLY_DEPTH.get() > 0;
    }

    public static boolean isOnInteractionThread() {
        return ON_INTERACTION.get();
    }

    public static boolean isOnTickThread() {
        return ON_TICK.get();
    }

    public static boolean isComputing() {
        return ON_COMPUTE.get();
    }

    /**
     * Apply runs on the owner thread, which already is {@code Level.thread}.
     * Kept so leftover off-thread reads do not see a null BlockEntity.
     */
    public static boolean isWorldAccessorThread() {
        return APPLY_DEPTH.get() > 0 || ON_INTERACTION.get() || ON_TICK.get() || ON_COMPUTE.get();
    }

    public static void enterInteractionThread() {
        ON_INTERACTION.set(true);
    }

    public static void leaveInteractionThread() {
        ON_INTERACTION.remove();
    }

    public static void enterTickThread() {
        ON_TICK.set(true);
    }

    public static void leaveTickThread() {
        ON_TICK.remove();
    }

    public static void enterCompute() {
        ON_COMPUTE.set(true);
    }

    public static void leaveCompute() {
        ON_COMPUTE.remove();
    }

    /**
     * @return {@code true} if the caller must skip the original on this thread
     * (a request was queued; live work runs later on the owner thread)
     */
    public static boolean steal(Runnable vanilla) {
        if (!MCThreadConfig.offloadPlayerUseItem) {
            return false;
        }
        if (ON_INTERACTION.get()) {
            return false;
        }
        String name = callerName();
        if (ON_COMPUTE.get() || ON_TICK.get()) {
            if (isTicketWrite(name) || isWorldStructureWrite(name)) {
                return enqueueInteraction(name, vanilla);
            }
            return false;
        }
        if (APPLYING_TICK.get()) {
            if (isSimTick(name) || isBlockEntityTick(name)) {
                return false;
            }
            if (isWorldWrite(name) || isWorldInteraction(name)) {
                return enqueueInteraction(name, vanilla);
            }
            return false;
        }
        if (isBlockEntityTick(name)) {
            return stealTickNamed(name, vanilla);
        }
        if (isTickName(name)) {
            return false;
        }
        if (isWorldWrite(name)) {
            return false;
        }
        if (!isWorldInteraction(name)) {
            return false;
        }
        int apply = APPLY_DEPTH.get();
        if (apply >= 2) {
            return false;
        }
        if (apply == 1) {
            if (!isFollowUpUpdate(name) || APPLYING_UPDATE.get()) {
                return false;
            }
        }
        return enqueueIfServer(name, vanilla);
    }

    private static boolean enqueueIfServer(String name, Runnable vanilla) {
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        if (rt == null || rt.server() == null || !rt.isServerThread()) {
            return false;
        }
        return enqueueInteraction(name, vanilla);
    }

    private static boolean enqueueInteraction(String name, Runnable vanilla) {
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        InteractionOffloadPipeline pipeline = rt == null ? null : rt.interactionOffload();
        if (pipeline == null) {
            return false;
        }
        pipeline.relocateVanilla(name, vanilla);
        return true;
    }

    /**
     * Block-entity ticks: compute pool runs the ticker. Nested ticks and
     * recipe/item/block interactions stay on that worker. Only ticket
     * mutations go to the interaction FIFO. AE2 keeps its vanilla ticker
     * on the server thread because the grid also ticks there.
     *
     * <p>The same block entity is mutexed so extra ticks (entity speed
     * cards) cannot run over an in-flight compute ticker.
     */
    public static boolean stealTick(Runnable vanilla) {
        return stealTickNamed(callerName(), vanilla);
    }

    public static boolean stealTick(Object blockEntity, Runnable vanilla) {
        if (staysOnServerTicker(blockEntity)) {
            return false;
        }
        return stealTickNamed(callerName(), () -> runLockedBlockEntityTick(blockEntity, vanilla));
    }

    /**
     * Serialize ticks of one block entity across the compute pool and the
     * server thread. Different block entities do not share this lock.
     */
    public static void runLockedBlockEntityTick(Object blockEntity, Runnable vanilla) {
        if (blockEntity == null) {
            vanilla.run();
            return;
        }
        Object lock = blockEntity instanceof BlockEntityTickLock owner
                ? owner.mcthread$tickLock()
                : blockEntity;
        synchronized (lock) {
            vanilla.run();
        }
    }

    /**
     * AE2 (and addons) tick the same node from BoundTickingBlockEntity and
     * from {@code TickHandler} on the server thread. Offloading the vanilla
     * ticker races their HashMaps.
     */
    public static boolean staysOnServerTicker(Object blockEntity) {
        if (blockEntity == null) {
            return false;
        }
        if (ae2FamilyName(blockEntity.getClass().getName())) {
            return true;
        }
        Object machine = gtMetaMachine(blockEntity);
        return machine != null && ae2FamilyName(machine.getClass().getName());
    }

    private static boolean ae2FamilyName(String name) {
        String n = name.toLowerCase();
        return n.startsWith("appeng.")
                || n.startsWith("com.glodblock.")
                || n.startsWith("com.github.glodblock.")
                || n.startsWith("com.extendedae_plus.")
                || n.startsWith("net.pedroksl.")
                || n.contains(".ae2")
                || n.contains("ae2wtlib")
                || n.contains("extendedae")
                || n.contains("expatternprovider")
                || n.contains("merequester")
                || n.contains("ae2additions")
                || n.contains("advancedae")
                || n.contains("appliedenergistics")
                || n.contains("wirelessconnect")
                || n.contains("mepattern")
                || n.contains("mehatch")
                || n.contains("mebus")
                || n.contains("mestocking");
    }

    private static Object gtMetaMachine(Object blockEntity) {
        try {
            return blockEntity.getClass().getMethod("getMetaMachine").invoke(blockEntity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean stealTickNamed(String name, Runnable vanilla) {
        if (!isBlockEntityTick(name)) {
            return false;
        }
        if (!MCThreadConfig.offloadPlayerUseItem || ON_TICK.get() || ON_COMPUTE.get()
                || ON_INTERACTION.get() || APPLY_DEPTH.get() > 0) {
            return false;
        }
        if (ForgeGameTestHooks.isGametestEnabled()) {
            return false;
        }
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        if (rt == null || rt.server() == null || !rt.isServerThread()) {
            return false;
        }
        InteractionOffloadPipeline pipeline = rt.interactionOffload();
        if (pipeline == null) {
            return false;
        }
        pipeline.relocateTick(name, vanilla);
        return true;
    }

    public static boolean stealWorldSim(Runnable vanilla) {
        return false;
    }

    /**
     * True for player/entity/block interactions that are not live world writes.
     */
    private static boolean isWorldInteraction(String name) {
        String n = name.toLowerCase();
        return !(n.contains("tick")
                || n.contains("move")
                || n.contains("animate")
                || n.contains("input")
                || n.contains("paddle")
                || n.contains("teleport")
                || n.contains("look")
                || n.contains("swing")
                || n.contains("keepalive")
                || n.contains("broadcast")
                || n.contains("chat")
                || n.contains("payload")
                || n.contains("difficulty")
                || n.contains("recipe")
                || n.contains("advancement")
                || n.contains("suggestion")
                || n.contains("clientinfo")
                || n.contains("clientcommand")
                || n.contains("abilities")
                || n.contains("carried")
                || n.contains("playercommand"));
    }

    private static boolean isBlockEntityTick(String name) {
        String n = name.toLowerCase();
        return n.contains("blockentity")
                || n.contains("boundticking")
                || n.contains("hopper")
                || n.contains("furnace")
                || n.contains("brewing");
    }

    private static boolean isSimTick(String name) {
        return isTickName(name) && !isBlockEntityTick(name);
    }

    /**
     * Ticket sets are not thread-safe. Recipe/item IO stays on the compute
     * worker; block/chunk structure mutations still go to the interaction FIFO.
     */
    private static boolean isTicketWrite(String name) {
        String n = name.toLowerCase();
        return n.contains("ticket") || n.contains("distancemanager");
    }

    private static boolean isWorldStructureWrite(String name) {
        String n = name.toLowerCase();
        return n.contains("setblock")
                || n.contains("removeblock")
                || n.contains("destroyblock")
                || n.contains("blockentitychanged")
                || n.contains("blockchanged")
                || n.contains("sectionlight")
                || n.contains("addfresh")
                || n.contains("addwithuuid")
                || n.contains("setblockentity")
                || n.contains("removeblockentity")
                || n.contains("addfreshblockentities")
                || isFollowUpUpdate(name)
                || n.contains("blockevent")
                || n.contains("explode");
    }

    /**
     * Live-world writes that must stay inline during owner apply (the actual
     * place/insert) and only become interaction requests on compute/tick threads.
     */
    private static boolean isWorldWrite(String name) {
        String n = name.toLowerCase();
        return n.contains("setblock")
                || n.contains("removeblock")
                || n.contains("blockentitychanged")
                || n.contains("addfresh")
                || n.contains("addwithuuid")
                || n.contains("setitem")
                || n.contains("removeitem")
                || n.contains("setchanged")
                || isTicketWrite(name);
    }

    private static boolean isTickName(String name) {
        String n = name.toLowerCase();
        if (n.contains("ticket")) {
            return false;
        }
        return n.contains("tick") || n.contains("passenger");
    }

    private static boolean isFollowUpUpdate(String name) {
        String n = name.toLowerCase();
        return n.contains("neighbor") || n.contains("blockupdate") || n.contains("neighbour");
    }

    private static String callerName() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(frame -> simpleName(frame.getClassName()) + "." + stripPrefix(frame.getMethodName()))
                .filter(name -> !name.startsWith("InteractionRelocator."))
                .findFirst()
                .orElse("unknown"));
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        int inner = simple.lastIndexOf('$');
        return inner < 0 ? simple : simple.substring(inner + 1);
    }

    private static String stripPrefix(String methodName) {
        int marker = methodName.lastIndexOf("mcthread$");
        return marker >= 0 ? methodName.substring(marker + "mcthread$".length()) : methodName;
    }

    public static <T> Optional<T> stealReturning(T speculative, Supplier<T> vanilla) {
        if (!steal(() -> vanilla.get())) {
            return Optional.empty();
        }
        return Optional.ofNullable(speculative);
    }

    public static boolean stealAndCancel(CallbackInfo ci, Runnable vanilla) {
        if (!steal(vanilla)) {
            return false;
        }
        ci.cancel();
        return true;
    }

    public static boolean stealTickAndCancel(CallbackInfo ci, Runnable vanilla) {
        if (!stealTick(vanilla)) {
            return false;
        }
        ci.cancel();
        return true;
    }

    public static <T> boolean stealAndReturn(CallbackInfoReturnable<T> cir, T speculative, Supplier<T> vanilla) {
        if (!steal(() -> vanilla.get())) {
            return false;
        }
        cir.setReturnValue(speculative);
        return true;
    }

    public static <T> boolean stealTickAndReturn(CallbackInfoReturnable<T> cir, T speculative, Supplier<T> vanilla) {
        if (!stealTick(() -> vanilla.get())) {
            return false;
        }
        cir.setReturnValue(speculative);
        return true;
    }

    static void runApply(String request, Runnable vanilla) {
        int depth = APPLY_DEPTH.get() + 1;
        APPLY_DEPTH.set(depth);
        boolean update = isFollowUpUpdate(request);
        if (update) {
            APPLYING_UPDATE.set(true);
        }
        boolean tickApply = isTickName(request);
        if (tickApply) {
            APPLYING_TICK.set(true);
        }
        if (depth == 1) {
            InteractionDispatch.begin();
        }
        try {
            vanilla.run();
        } finally {
            if (depth == 1) {
                InteractionDispatch.flush();
                APPLY_DEPTH.remove();
            } else {
                APPLY_DEPTH.set(depth - 1);
            }
            if (update) {
                APPLYING_UPDATE.remove();
            }
            if (tickApply) {
                APPLYING_TICK.remove();
            }
        }
    }
}
