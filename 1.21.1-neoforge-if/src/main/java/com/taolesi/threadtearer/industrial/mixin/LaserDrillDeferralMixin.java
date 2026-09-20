package com.taolesi.threadtearer.industrial.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.industrial.IfDeferral;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import com.hrznstudio.titanium.component.progress.ProgressBarComponent;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The cross-block-entity write split for {@code LaserDrillTile.work()}.
 *
 * <p>The drill's {@code work()} finds its target by reading block entities
 * (live reads, worker-legal) and then advances the target LaserBase's
 * progress bar:
 *
 * <pre>
 *   laserBase.getBar().setProgress(laserBase.getBar().getProgress() + 1);
 *   laserBase.getBar().tickBar();
 * </pre>
 *
 * <p>That is the only write, and it crosses a block-entity boundary: the
 * bar belongs to the LaserBase, not the drill. The bar's
 * {@code tickBar()} fires {@code onFinishWork} — the LaserBase's
 * {@code onWork()} — which is the heavy half of a laser drill setup:
 * recipe scans, biome/rarity filters, and (for the fluid base variant)
 * entity scans and damage. On the server thread this whole chain runs
 * inline in the drill's tick; offloading the drill without splitting this
 * call would run it on a worker against {@code level.getRandom()} and
 * entity lists.
 *
 * <p>Both calls are deferred through {@link IfDeferral#runOrDeferVoid}
 * with the <b>LaserBase</b> as the lock — {@code ProgressBarComponent.getComponentHarness()}
 * is the owning tile. The LaserBase is a passive block entity: its bar has
 * {@code setProgressIncrease(0)} and nothing else drives it (the drill is
 * the sole writer), so the per-BE lock fully serialises every deferred
 * push against that base. The deferred drain runs on the server thread at
 * the tick boundary, which is what makes the {@code onWork()} body safe —
 * shared RNG draws and entity access all execute there.
 *
 * <p>The extra win: the LaserBase's heavy {@code onWork()} chain (recipes,
 * rarity filters, entity damage) leaves the server tick with the drill,
 * because it only ever runs from {@code tickBar()}, which only the drill
 * calls, which now runs in the deferred batch.
 *
 * <p>What stays on the worker: {@code isValidTarget}/{@code findTarget}
 * ({@code level.getBlockEntity} reads), the energy check, the
 * {@code WorkAction} construction. The drill's own {@code target} field is
 * only touched on the worker.
 */
@Pseudo
@Mixin(targets = "com.buuz135.industrial.block.resourceproduction.tile.LaserDrillTile", remap = false)
public abstract class LaserDrillDeferralMixin {

    @WrapOperation(
            method = "work()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
            at = @At(value = "INVOKE",
                    target = "Lcom/hrznstudio/titanium/component/progress/ProgressBarComponent;"
                            + "setProgress(I)V"),
            remap = false)
    private void threadtearer$deferSetProgress(ProgressBarComponent<?> bar, int value,
                                               Operation<Void> original) {
        if (!InteractionRelocator.isComputing()) {
            original.call(bar, value);
            return;
        }
        // Defer the caller's INTENT (advance by delta), not the absolute
        // value it computed from a worker-side read. The coalesced batch
        // lands on the server thread via server.execute(), which races the
        // next tick's steal: when the drain runs after the next worker
        // already read the same stale progress, two deferred
        // setProgress(p + 1) calls both write the same absolute value and
        // the bar advances one step per two ticks — the drill runs at an
        // inconsistent, mostly halved rate. Re-reading at drain time and
        // adding the delta makes every deferred advance count exactly
        // once, regardless of drain timing.
        int delta = value - bar.getProgress();
        IfDeferral.runOrDeferVoid(harness(bar), () -> bar.setProgress(bar.getProgress() + delta));
    }

    @WrapOperation(
            method = "work()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
            at = @At(value = "INVOKE",
                    target = "Lcom/hrznstudio/titanium/component/progress/ProgressBarComponent;"
                            + "tickBar()V"),
            remap = false)
    private void threadtearer$deferTickBar(ProgressBarComponent<?> bar, Operation<Void> original) {
        if (!InteractionRelocator.isComputing()) {
            original.call(bar);
            return;
        }
        IfDeferral.runOrDeferVoid(harness(bar), () -> original.call(bar));
    }

    /**
     * The bar's owning tile — the LaserBase — as the deferral lock. Falls
     * back to the bar itself when the harness is not a block entity (the
     * {@code runLockedBlockEntityTick} null branch then degrades to
     * running without a lock, which is still correct for a passive base).
     */
    private static Object harness(@Coerce ProgressBarComponent<?> bar) {
        Object harness = bar.getComponentHarness();
        return harness instanceof BlockEntity ? harness : bar;
    }
}