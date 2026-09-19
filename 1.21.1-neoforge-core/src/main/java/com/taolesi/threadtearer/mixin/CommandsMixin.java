package com.taolesi.threadtearer.mixin;

import com.mojang.brigadier.ParseResults;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocatePrefixed(CommandSourceStack source, String command, CallbackInfo ci) {
        Commands self = (Commands) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> self.performPrefixedCommand(source, command));
    }

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateCommand(ParseResults<CommandSourceStack> parseResults, String command,
                                         CallbackInfo ci) {
        Commands self = (Commands) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> self.performCommand(parseResults, command));
    }
}
