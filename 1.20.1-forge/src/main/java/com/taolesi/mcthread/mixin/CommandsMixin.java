package com.taolesi.mcthread.mixin;

import com.mojang.brigadier.ParseResults;
import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocatePrefixed(CommandSourceStack source, String command,
                                          CallbackInfoReturnable<Integer> cir) {
        Commands self = (Commands) (Object) this;
        InteractionRelocator.stealReturning(1, () -> self.performPrefixedCommand(source, command))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateCommand(ParseResults<CommandSourceStack> parseResults, String command,
                                         CallbackInfoReturnable<Integer> cir) {
        Commands self = (Commands) (Object) this;
        InteractionRelocator.stealReturning(1, () -> self.performCommand(parseResults, command))
                .ifPresent(cir::setReturnValue);
    }
}
