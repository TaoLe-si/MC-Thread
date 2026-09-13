package com.taolesi.mcthread.experiment.till;

import com.taolesi.mcthread.experiment.OffloadSession;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbilities;

import java.util.Optional;
import java.util.UUID;

/**
 * Minecraft capture / validate / apply for the till-dirt spike.
 *
 * <p>Capture copies registry ids and flags only. {@link #decide} is delegated
 * to {@link TillRules} so the interaction thread never sees a {@link Level}.
 */
public final class McTillSession implements OffloadSession<TillSnapshot, TillDecision> {

    public static final McTillSession INSTANCE = new McTillSession();

    private McTillSession() {
    }

    public static Optional<TillSnapshot> tryCapture(ServerPlayer player, Level level, ItemStack stack,
                                                    InteractionHand hand, BlockPos pos) {
        if (player == null || level == null || level.isClientSide || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        if (player.isSpectator()) {
            return Optional.empty();
        }
        if (!stack.canPerformAction(ItemAbilities.HOE_TILL)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        String blockId = id(state.getBlock());
        if (!TillRules.isTillable(blockId)) {
            return Optional.empty();
        }
        boolean airAbove = level.getBlockState(pos.above()).isAir();
        return Optional.of(new TillSnapshot(
                System.nanoTime(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                blockId,
                airAbove,
                id(stack.getItem()),
                true,
                hand == InteractionHand.MAIN_HAND,
                player.getAbilities().instabuild,
                player.getUUID()));
    }

    @Override
    public TillDecision decide(TillSnapshot snapshot) {
        return TillRules.decide(snapshot);
    }

    @Override
    public boolean shouldApply(TillDecision decision) {
        return decision.apply() && decision.resultBlockId() != null;
    }

    @Override
    public boolean validate(TillSnapshot snapshot, TillDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            return false;
        }
        BlockPos pos = pos(snapshot);
        String liveBlock = id(level.getBlockState(pos).getBlock());
        if (!liveBlock.equals(snapshot.blockId())) {
            return false;
        }
        if (snapshot.airAbove() != level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        ServerPlayer player = findPlayer(snapshot.playerId());
        if (player == null) {
            // GameTest mock players are not on the player list; block apply is still valid.
            return true;
        }
        ItemStack held = player.getItemInHand(snapshot.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        return held.canPerformAction(ItemAbilities.HOE_TILL);
    }

    @Override
    public void apply(TillSnapshot snapshot, TillDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            throw new IllegalStateException("level gone: " + snapshot.dimension());
        }
        ResourceLocation resultId = ResourceLocation.parse(decision.resultBlockId());
        Block result = BuiltInRegistries.BLOCK.get(resultId);
        if (result == null) {
            throw new IllegalStateException("unknown result block: " + decision.resultBlockId());
        }
        BlockPos pos = pos(snapshot);
        level.setBlock(pos, result.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
        ServerPlayer player = findPlayer(snapshot.playerId());
        if (player == null || snapshot.creative()) {
            return;
        }
        InteractionHand hand = snapshot.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack held = player.getItemInHand(hand);
        if (held.canPerformAction(ItemAbilities.HOE_TILL)) {
            EquipmentSlot slot = snapshot.mainHand() ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            held.hurtAndBreak(1, player, slot);
        }
    }

    private static BlockPos pos(TillSnapshot snapshot) {
        return new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
    }

    private static String id(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key.toString();
    }

    private static String id(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key.toString();
    }

    private static ServerLevel findLevel(String dimension) {
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        MinecraftServer server = rt == null ? null : rt.server();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private static ServerPlayer findPlayer(UUID playerId) {
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        MinecraftServer server = rt == null ? null : rt.server();
        if (server == null || playerId == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(playerId);
    }
}
