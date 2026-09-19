package com.taolesi.threadtearer.mek;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.api.MCT;
import com.taolesi.threadtearer.experiment.InteractionRelocator;

import java.lang.reflect.Method;

/**
 * The one place an offloaded Mekanism tick decides whether a world-reaching call
 * runs here or waits for the server thread.
 *
 * <p>Every multiblock family's heavy per-tick work is arithmetic against the
 * data object's own tanks and containers. The only things in it that touch the
 * world are the pushes into the neighbour valves, a few entity or neighbour
 * scans, and one shared static map. Those are the call sites the per-family
 * mixins wrap, and they all route through here so the rule lives in one method
 * instead of six copies. The generator's neighbour emit goes through the same
 * door.
 *
 * <p><b>This class must not live in the {@code mixin} package.</b> Mixin owns
 * that package: any class in it is assumed to be a mixin declared in the
 * config, and a class that is <em>not</em> declared there may not be referenced
 * from a transformed target. A mixin's own body is inlined into the target, so
 * a helper call inside a mixin becomes a call inside Mekanism's class — and the
 * first time that call is resolved, Mixin aborts the game with
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and
 * cannot be referenced directly}. That is exactly what happened when this class
 * was left in {@code ...mek.mixin}: the multiblock deferrals never fired in a
 * test world, so the mistake stayed hidden until the generator emit deferral
 * ran for real. {@code tools/verify_mixin_targets.py} now fails the build if a
 * non-mixin class reappears in that package.
 *
 * <p><b>Every deferral takes the structure lock.</b> The lock is the
 * {@code MultiblockData} instance itself — the same object
 * {@code TileEntityMultiblockDataTickMixin} locks on when it hands the data tick
 * to a worker. So a deferred push can never overlap the next tick's simulation
 * on the same structure, and two structures still run in parallel because they
 * have different data objects. Without this the deferred body would read tanks
 * the worker was already mutating.
 *
 * <p>Off a compute worker the call is handed to the core's tick-boundary write
 * batch, which runs the whole batch as ONE server-thread task per tick. If the
 * core refuses the deferral the push is skipped for this tick rather than run on
 * the worker — a missed emit is harmless, an off-thread capability write is not
 * (that is the 62k-NPE AE2 stall the ejector defer exists to prevent).
 */
public final class MekDeferral {

    private MekDeferral() {
    }

    /**
     * Run the wrapped call here, or defer it to the server thread under the
     * structure lock when this is a compute worker.
     */
    public static void runOrDefer(Object lock, Operation<Void> op, Object... args) {
        if (!InteractionRelocator.isComputing()) {
            op.call(args);
            return;
        }
        MCT.runtime().deferWorldWrite(() -> InteractionRelocator.runLockedBlockEntityTick(lock, () -> op.call(args)));
    }

    /**
     * Same, for the one wrapped call whose {@code boolean} result the tick body
     * discards ({@code BoilerMultiblockData.hotMap.put}). Returning {@code false}
     * keeps the worker-side bytecode valid; the value is popped at the call site.
     */
    public static boolean runOrDeferBool(Object lock, Operation<Boolean> op, Object... args) {
        if (!InteractionRelocator.isComputing()) {
            return op.call(args);
        }
        MCT.runtime().deferWorldWrite(() -> InteractionRelocator.runLockedBlockEntityTick(lock, () -> op.call(args)));
        return false;
    }

    /**
     * The turbine's vent emit and the drain of the fluid it moved are one unit:
     * vanilla passes the emit's return value straight into
     * {@code ventTank.extract}, so deferring only the emit would leave the drain
     * running on the worker with the zero the deferred emit returned — the
     * neighbour receives the water and the tank keeps it, duplicating it every
     * tick. The pair therefore moves together.
     */
    public static void runEmitThenDrain(Object lock, Operation<Integer> emit, Object[] emitArgs, Object tank) {
        if (!InteractionRelocator.isComputing()) {
            drainTank(tank, emit.call(emitArgs));
            return;
        }
        MCT.runtime().deferWorldWrite(() -> InteractionRelocator.runLockedBlockEntityTick(lock,
                () -> drainTank(tank, emit.call(emitArgs))));
    }

    /**
     * Drain {@code amount} out of a Mekanism {@code IExtendedFluidTank}, called
     * from the deferred body after the emit that moved it.
     *
     * <p>Reflection because that drain returns a {@code FluidStack} and this addon
     * has no compile-time Mekanism dependency, so the type cannot be named in the
     * mixin — and the call site cannot be wrapped either, because a handler may
     * only widen the return type to a supertype, and MixinExtras would then
     * insert a checkcast to {@code FluidStack} that this class cannot compile
     * against. Every other wrapped call here returns {@code void}, {@code int} or
     * {@code boolean} and needs no such treatment.
     *
     * <p>Resolved once, from the interface the field is declared as, so the
     * concrete tank implementation never has to be public.
     */
    public static void drainTank(Object tank, int amount) {
        if (tank == null || amount <= 0) {
            return;
        }
        try {
            Tank.DRAIN.invoke(tank, amount, Tank.EXECUTE, Tank.INTERNAL);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Thread Tearer: could not drain a deferred multiblock fluid emit", e);
        }
    }

    /**
     * The drain method and its two enum arguments, resolved on first use — a
     * holder rather than static fields on this class so the lookup never runs at
     * class-init, since the addon must load fine with Mekanism absent.
     */
    private static final class Tank {
        static final Method DRAIN = findDrain();
        static final Object EXECUTE = enumConstant("mekanism.api.Action", "EXECUTE");
        static final Object INTERNAL = enumConstant("mekanism.api.AutomationType", "INTERNAL");

        private Tank() {
        }

        private static Method findDrain() {
            try {
                Class<?> tank = Class.forName("mekanism.api.fluid.IExtendedFluidTank");
                Class<?> action = Class.forName("mekanism.api.Action");
                Class<?> automation = Class.forName("mekanism.api.AutomationType");
                Method found = tank.getMethod("extract", int.class, action, automation);
                found.setAccessible(true);
                return found;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Thread Tearer: IExtendedFluidTank.extract is missing", e);
            }
        }

        private static Object enumConstant(String owner, String field) {
            try {
                return Class.forName(owner).getField(field).get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Thread Tearer: " + owner + "." + field + " is missing", e);
            }
        }
    }
}
