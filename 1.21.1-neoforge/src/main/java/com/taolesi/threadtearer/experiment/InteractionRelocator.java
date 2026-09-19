package com.taolesi.threadtearer.experiment;

import com.taolesi.threadtearer.config.MCThreadConfig;
import com.taolesi.threadtearer.runtime.MCTRuntimeImpl;
import net.minecraft.gametest.framework.GameTestServer;
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
 *
 * <p>Baseline edition: no third-party mod is patched here and no vanilla
 * ticker is offloaded automatically. Addon mods relocate their own tick
 * computation via {@link #stealTick} / {@link #runLockedBlockEntityTick} and
 * raw compute work via {@code MCT.submitCompute}.
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
        // No per-call instrumentation here on purpose. The in-game readout
        // showed 3.35M calls in 100 ticks (~33k/tick during chunk load, where
        // every light/section/block update of every mod passes through), and
        // two System.nanoTime() reads plus two LongAdder writes per call made
        // the hot path several times more expensive than the work it guards.
        // PhaseTimings now only records the paths that actually do something.
        return stealImpl(vanilla);
    }

    private static boolean stealImpl(Runnable vanilla) {
        // Order matters: this runs tens of thousands of times per tick during
        // chunk loading, so the gates are arranged cheapest-and-most-likely
        // first. Server-thread callers are the overwhelming majority and the
        // answer for them is always "leave it alone" — see below.
        if (!MCThreadConfig.offloadPlayerUseItem) {
            return false;
        }
        // Server-thread top-level call (player packet handlers, world ticks,
        // chunk loading, other mods' logic): run inline, untouched. Returning
        // false means the mixin does not cancel, so vanilla executes on this
        // very thread — exactly what the caller wanted.
        //
        // This also skips callerName() (a StackWalker walk, measured at
        // 1.7-2.2 µs) and the ~50 string operations the classification below
        // performs. The watchdog caught the server thread inside exactly that
        // stack walk during chunk load, where every light and block update of
        // every mod passes through here.
        //
        // It additionally fixes a mis-classification: names like
        // Level.blockEntityChanged match isBlockEntityTick and were being
        // handed to the compute pool as if they were tile-entity ticks.
        if (!ON_COMPUTE.get() && !ON_TICK.get() && APPLY_DEPTH.get() == 0 && isOnServerThread()) {
            return false;
        }
        if (ON_INTERACTION.get()) {
            return false;
        }
        String name = callerName();
        if (ON_COMPUTE.get() || ON_TICK.get()) {
            // Compute / tick worker threads push world writes into the
            // WriteCoalescer buffer; the next tick boundary flushes them on
            // the server thread in one task. Direct s.execute() was tried here
            // and it deadlocks with mods whose own end-of-tick hooks schedule
            // server-thread tasks on the same queue: an AE2 TickHandler
            // runnable that triggers a world write via our mixin would queue
            // its own apply onto s.execute and the server thread never gets
            // free to drain that queue (modernfix's waitUntilNextTick parks
            // for the next tick; if every tick NPEs on a poisoned queue, the
            // game freezes for tens of seconds). Going through the coalescer
            // collapses many writes into one server task and breaks the cycle:
            // a single drain processes the entire batch, returns, and the
            // next tick can fire.
            if (isTicketWrite(name) || isWorldStructureWrite(name)) {
                MCTRuntimeImpl rt = MCTRuntimeImpl.get();
                if (rt != null && rt.server() != null) {
                    rt.queueComputeWorldWrite(() -> runApply(name, vanilla));
                    return true;
                }
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
            return stealTickNow(name, vanilla);
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

    /**
     * The server thread, published by the runtime when the server starts. Kept
     * here as a plain static so the hot path costs one volatile read and one
     * {@code currentThread()} comparison instead of three volatile reads plus
     * a megamorphic call. {@code null} before the server starts, which is also
     * the correct "not the server thread" answer.
     */
    private static volatile Thread cachedServerThread;

    public static void cacheServerThread(Thread thread) {
        cachedServerThread = thread;
    }

    /**
     * True when the current thread is the Minecraft server thread. Used by the
     * top-level fast path in {@link #steal} — this runs tens of thousands of
     * times per tick during chunk loading, so it stays minimal on purpose.
     */
    private static boolean isOnServerThread() {
        Thread t = cachedServerThread;
        return t != null && Thread.currentThread() == t;
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
     * Addon API: hand a live world write discovered inside offloaded tick
     * compute to the interaction FIFO; the owner thread applies it later.
     * Returns {@code true} when the write was handed off (the caller must
     * skip its inline execution). On the server thread — or when the runtime
     * or the offload switch is unavailable — returns {@code false} and the
     * caller runs the write inline with vanilla semantics.
     */
    public static boolean stealWorldApply(String name, Runnable apply) {
        if (!MCThreadConfig.offloadPlayerUseItem || apply == null) {
            return false;
        }
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        if (rt == null || rt.server() == null || rt.isServerThread()) {
            return false;
        }
        rt.interactionOffload().scheduleWorldApply(name, apply);
        return true;
    }

    /**
     * Block-entity ticks: the compute pool runs the ticker; nested ticks and
     * recipe/item/block interactions stay on that worker, ticket mutations go
     * to the interaction FIFO. Addon mods call this from their own tick
     * mixins; the vanilla tick loop never offloads automatically.
     *
     * <p>The same block entity is mutexed so extra ticks cannot run over an
     * in-flight compute ticker.
     */
    public static boolean stealTick(Runnable vanilla) {
        String name = callerName();
        if (!isBlockEntityTick(name)) {
            return false;
        }
        return stealTickNow(name, vanilla);
    }

    /**
     * Addon entry point: the caller states outright that this is a block-entity
     * tick, so no caller-name guessing is involved.
     *
     * <p>This deliberately skips {@link #isBlockEntityTick}. {@code callerName()}
     * reports the <em>target</em> class and mixin's handler method, so an addon
     * mixin on {@code mekanism...TileEntityElectricMachine} arrives as
     * {@code TileEntityElectricMachine.handler$xxx$mek$relocateOnUpdateServer} —
     * which matches no vanilla tick naming rule. Gating on it silently offloaded
     * nothing at all (in-game readout: {@code computeTicks=0} with the addon
     * installed and its mixins confirmed applied).
     */
    public static boolean stealTick(Object blockEntity, Runnable vanilla) {
        return stealTickNow("addon-tick", () -> runLockedBlockEntityTick(blockEntity, vanilla));
    }

    /**
     * Serialize ticks of one block entity across the compute pool and the
     * server thread. Different block entities do not share this lock. Addon
     * mods call this from their own tick mixins when they offload a ticker.
     *
     * <p>Both directions use this: a worker takes the lock to run the ticker,
     * and the server thread takes it to run a deferred part of that same tick
     * (see {@code MCTRuntime.deferWorldWrite}). The wait is therefore measured
     * in {@link com.taolesi.threadtearer.monitor.PhaseTimings#LOCK_WAIT_NANOS}:
     * if the server thread ever blocks here for a long time, an addon is holding
     * the lock on a worker while the server thread wants it.
     */
    public static void runLockedBlockEntityTick(Object blockEntity, Runnable vanilla) {
        if (blockEntity == null) {
            vanilla.run();
            return;
        }
        Object lock = blockEntity instanceof BlockEntityTickLock owner
                ? owner.threadtearer$tickLock()
                : blockEntity;
        long t0 = System.nanoTime();
        synchronized (lock) {
            long waited = System.nanoTime() - t0;
            if (waited > 1_000_000L) {
                com.taolesi.threadtearer.monitor.PhaseTimings.LOCK_WAITS.increment();
                com.taolesi.threadtearer.monitor.PhaseTimings.LOCK_WAIT_NANOS.add(waited);
            }
            vanilla.run();
        }
    }

    private static boolean stealTickNow(String name, Runnable vanilla) {
        if (!MCThreadConfig.offloadPlayerUseItem || ON_TICK.get() || ON_COMPUTE.get()
                || ON_INTERACTION.get() || APPLY_DEPTH.get() > 0) {
            return false;
        }
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        if (rt == null || rt.server() == null || !rt.isServerThread()) {
            return false;
        }
        if (rt.server() instanceof GameTestServer) {
            return false;
        }
        // Backpressure: when the compute pool is already saturated, stealing
        // more ticks just queues work that will run against staler world state
        // while the producer keeps adding. Fall back to vanilla for this tick
        // instead of growing the queue without bound.
        if (rt.computeQueueDepth() > BACKPRESSURE_DEPTH) {
            return false;
        }
        InteractionOffloadPipeline pipeline = rt.interactionOffload();
        if (pipeline == null) {
            return false;
        }
        pipeline.relocateTick(name, vanilla);
        return true;
    }

    /**
     * Max queued compute tasks before tick offloading pauses. Sized as a few
     * ticks of work: deep enough to keep every worker fed, shallow enough that
     * a queued tick still sees roughly current world state.
     */
    private static final int BACKPRESSURE_DEPTH = 64;

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

    /**
     * True for names that mean "a block entity is being ticked". Names that
     * merely mention a block entity while belonging to the {@code Level} world
     * API ({@code Level.blockEntityChanged}, {@code Level.setBlockEntity},
     * {@code Level.removeBlockEntity}, {@code Level.addFreshBlockEntities}) are
     * world/structure calls, not ticks — matching them used to send chunk-load
     * bookkeeping into the compute pool (~98 "ticks"/tick).
     *
     * <p>Package-visible for tests; see {@code TickClassificationTest}.
     */
    static boolean isBlockEntityTick(String name) {
        String n = name.toLowerCase();
        if (n.startsWith("level.") || n.startsWith("levelchunk.") || n.startsWith("serverlevel.")) {
            return false;
        }
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
        int marker = methodName.lastIndexOf("threadtearer$");
        return marker >= 0 ? methodName.substring(marker + "threadtearer$".length()) : methodName;
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

    /**
     * Runs {@code vanilla} in the owner-apply context: packet sends are
     * deferred until the end and flushed in order, and world writes run
     * inline. Public so the runtime and addon integrations can execute
     * deferred writes with the same semantics as the pipeline's apply step.
     */
    public static void runApply(String request, Runnable vanilla) {
        long t0 = System.nanoTime();
        try {
            runApplyImpl(request, vanilla);
        } finally {
            com.taolesi.threadtearer.monitor.PhaseTimings.APPLY_CALLS.increment();
            com.taolesi.threadtearer.monitor.PhaseTimings.APPLY_NANOS.add(System.nanoTime() - t0);
        }
    }

    private static void runApplyImpl(String request, Runnable vanilla) {
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
