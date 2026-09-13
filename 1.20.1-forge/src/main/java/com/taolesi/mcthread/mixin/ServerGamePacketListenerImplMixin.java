package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.network.protocol.game.ServerboundBlockEntityTagQuery;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundEntityTagQuery;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Relocates vanilla packet handlers onto the interaction thread so Mixins on
 * {@code useItemOn}, {@code clicked}, {@code interact}, etc. run there too.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleUseItemOn(packet), ci);
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleUseItem(packet), ci);
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleInteract(packet), ci);
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocatePlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handlePlayerAction(packet), ci);
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleContainerClick(packet), ci);
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateContainerButton(ServerboundContainerButtonClickPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleContainerButtonClick(packet), ci);
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateContainerClose(ServerboundContainerClosePacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleContainerClose(packet), ci);
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateCreativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSetCreativeModeSlot(packet), ci);
    }

    @Inject(method = "handlePickItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocatePickItem(ServerboundPickItemPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handlePickItem(packet), ci);
    }

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocatePlaceRecipe(ServerboundPlaceRecipePacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handlePlaceRecipe(packet), ci);
    }

    @Inject(method = "handleRenameItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateRename(ServerboundRenameItemPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleRenameItem(packet), ci);
    }

    @Inject(method = "handleSetBeaconPacket", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateBeacon(ServerboundSetBeaconPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSetBeaconPacket(packet), ci);
    }

    @Inject(method = "handleSelectTrade", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateTrade(ServerboundSelectTradePacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSelectTrade(packet), ci);
    }

    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateBook(ServerboundEditBookPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleEditBook(packet), ci);
    }

    @Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateSign(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSignUpdate(packet), ci);
    }

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateCarried(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSetCarriedItem(packet), ci);
    }

    @Inject(method = "handleSetCommandBlock", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateCommandBlock(ServerboundSetCommandBlockPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSetCommandBlock(packet), ci);
    }

    @Inject(method = "handleSetCommandMinecart", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateCommandMinecart(ServerboundSetCommandMinecartPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSetCommandMinecart(packet), ci);
    }

    @Inject(method = "handleSetStructureBlock", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateStructure(ServerboundSetStructureBlockPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSetStructureBlock(packet), ci);
    }

    @Inject(method = "handleSetJigsawBlock", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateJigsaw(ServerboundSetJigsawBlockPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSetJigsawBlock(packet), ci);
    }

    @Inject(method = "handleJigsawGenerate", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateJigsawGenerate(ServerboundJigsawGeneratePacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleJigsawGenerate(packet), ci);
    }

    @Inject(method = "handleAnimate", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateAnimate(ServerboundSwingPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleAnimate(packet), ci);
    }

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateChat(ServerboundChatPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleChat(packet), ci);
    }

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleChatCommand(packet), ci);
    }

    @Inject(method = "handleChatAck", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateChatAck(ServerboundChatAckPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleChatAck(packet), ci);
    }

    @Inject(method = "handleChatSessionUpdate", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateChatSession(ServerboundChatSessionUpdatePacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleChatSessionUpdate(packet), ci);
    }

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocatePayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleCustomPayload(packet), ci);
    }

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateClientCommand(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleClientCommand(packet), ci);
    }

    @Inject(method = "handleClientInformation", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateClientInfo(ServerboundClientInformationPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleClientInformation(packet), ci);
    }

    @Inject(method = "handleChangeDifficulty", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateDifficulty(ServerboundChangeDifficultyPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleChangeDifficulty(packet), ci);
    }

    @Inject(method = "handleLockDifficulty", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateLockDifficulty(ServerboundLockDifficultyPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleLockDifficulty(packet), ci);
    }

    @Inject(method = "handleRecipeBookSeenRecipePacket", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateRecipeSeen(ServerboundRecipeBookSeenRecipePacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleRecipeBookSeenRecipePacket(packet), ci);
    }

    @Inject(method = "handleRecipeBookChangeSettingsPacket", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateRecipeSettings(ServerboundRecipeBookChangeSettingsPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleRecipeBookChangeSettingsPacket(packet), ci);
    }

    @Inject(method = "handleSeenAdvancements", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateAdvancements(ServerboundSeenAdvancementsPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleSeenAdvancements(packet), ci);
    }

    @Inject(method = "handleCustomCommandSuggestions", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateSuggestions(ServerboundCommandSuggestionPacket packet, CallbackInfo ci) {
        stealPacket(() -> self().handleCustomCommandSuggestions(packet), ci);
    }

    @Inject(method = "handleEntityTagQuery", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateEntityTag(ServerboundEntityTagQuery packet, CallbackInfo ci) {
        stealPacket(() -> self().handleEntityTagQuery(packet), ci);
    }

    @Inject(method = "handleBlockEntityTagQuery", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateBlockTag(ServerboundBlockEntityTagQuery packet, CallbackInfo ci) {
        stealPacket(() -> self().handleBlockEntityTagQuery(packet), ci);
    }

    private ServerGamePacketListenerImpl self() {
        return (ServerGamePacketListenerImpl) (Object) this;
    }

    private static void stealPacket(Runnable rerun, CallbackInfo ci) {
        if (InteractionRelocator.steal(rerun)) {
            ci.cancel();
        }
    }
}
