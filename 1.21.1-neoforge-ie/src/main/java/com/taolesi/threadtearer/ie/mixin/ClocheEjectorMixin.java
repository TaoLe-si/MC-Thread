package com.taolesi.threadtearer.ie.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.api.MCT;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The ejector split for {@code ClocheBlockEntity} — the third admitted
 * machine in IE.
 *
 * <p>{@code ClocheBlockEntity.tickServer} does heavy own-state work
 * (recipe matching, growth counter, internal inventory mutation, energy
 * consumption) plus one neighbour push per tick:
 *
 * <pre>
 *   stack = ItemHandlerHelper.insertItem(outputHandler, stack, false);
 * </pre>
 *
 * <p>{@code ItemHandlerHelper.insertItem} is a {@code public static} helper in
 * NeoForge ({@code net.neoforged.neoforge.items.ItemHandlerHelper}) that
 * internally walks the handler's slots and calls
 * {@code outputHandler.insertItem(slot, ...)} on each. The actual
 * {@code IItemHandler.insertItem} lives inside the helper and is not
 * reachable from outside it, so wrapping the inner call is not an option.
 * Wrapping the helper itself would affect every mod that uses it.
 *
 * <p>The mixin therefore targets the {@code invokestatic} site of
 * {@code ItemHandlerHelper.insertItem} from inside
 * {@code ClocheBlockEntity.tickServer}. On the worker thread the wrapper
 * defers the call to {@code deferWorldWrite}, which queues it to the
 * server-thread FIFO drained at the tick boundary — sequential, so
 * concurrent workers cannot race on the neighbour {@code IItemHandler}.
 * The {@code simulate} branch (rare, only used by the recipe pre-check)
 * is unaffected: simulations don't touch the world and the neighbour is
 * not mutated.
 *
 * <p>No {@code runLockedBlockEntityTick} is used here: the FIFO is already
 * serial on the server thread, so a per-BE lock adds nothing. The same
 * pattern is what mek's ejector and EIO's {@code distributeResources}
 * mixins use; this variant is simpler because the wrapped call site is
 * already a static method (no receiver to thread through).
 */
@Pseudo
@Mixin(targets = "blusunrize.immersiveengineering.common.blocks.metal.ClocheBlockEntity", remap = false)
public abstract class ClocheEjectorMixin {

    @WrapOperation(
            method = "tickServer()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/items/ItemHandlerHelper;insertItem("
                            + "Lnet/neoforged/neoforge/items/IItemHandler;"
                            + "Lnet/minecraft/world/item/ItemStack;Z)"
                            + "Lnet/minecraft/world/item/ItemStack;"),
            remap = false)
    private static ItemStack threadtearer$deferClocheEjector(
            IItemHandler handler,
            ItemStack stack,
            boolean simulate,
            Operation<ItemStack> original) {
        // simulate=true means the recipe pre-check; it never mutates the
        // neighbour, so leave it on whichever thread is calling.
        if (!InteractionRelocator.isComputing() || simulate) {
            return original.call(handler, stack, simulate);
        }
        // Defer the real push to the server thread. The FIFO is drained
        // sequentially at the tick boundary, so the neighbour's
        // IItemHandler.insertItem is reached from at most one thread at
        // a time.
        MCT.runtime().deferWorldWrite(() -> original.call(handler, stack, simulate));
        return stack;
    }
}