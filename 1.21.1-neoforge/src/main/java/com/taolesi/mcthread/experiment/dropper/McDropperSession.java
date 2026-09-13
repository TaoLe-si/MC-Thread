package com.taolesi.mcthread.experiment.dropper;

import com.taolesi.mcthread.experiment.OffloadSession;
import com.taolesi.mcthread.experiment.hopper.ItemCopy;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.Optional;

public final class McDropperSession implements OffloadSession<DropperSnapshot, DropperDecision> {

    public static final McDropperSession INSTANCE = new McDropperSession();

    private McDropperSession() {
    }

    public static Optional<DropperSnapshot> tryCapture(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DropperBlock)) {
            return Optional.empty();
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof DropperBlockEntity dropper)) {
            return Optional.empty();
        }
        Direction facing = state.getValue(DropperBlock.FACING);
        BlockPos destPos = pos.relative(facing);
        BlockEntity destBe = level.getBlockEntity(destPos);
        if (destBe != null && !(destBe instanceof Container)
                && level.getCapability(Capabilities.ItemHandler.BLOCK, destPos, facing.getOpposite()) != null) {
            return Optional.empty();
        }
        Container dest = HopperBlockEntity.getContainerAt(level, destPos);
        if (dest == null) {
            return Optional.empty();
        }
        int slot = dropper.getRandomSlot(level.random);
        if (slot < 0) {
            return Optional.empty();
        }
        ItemStack moving = dropper.getItem(slot);
        if (moving.isEmpty()) {
            return Optional.empty();
        }
        ItemCopy[] dropperSlots = copy(dropper);
        ItemCopy[] destSlots = copy(dest);
        boolean[] destPlace = new boolean[destSlots.length];
        ItemStack proto = moving.copy();
        proto.setCount(1);
        Direction inFace = facing.getOpposite();
        for (int d = 0; d < destSlots.length; d++) {
            destPlace[d] = canPlace(dest, proto, d, inFace);
        }
        return Optional.of(new DropperSnapshot(
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                destPos.getX(), destPos.getY(), destPos.getZ(),
                slot, dropperSlots, destSlots, destPlace));
    }

    @Override
    public DropperDecision decide(DropperSnapshot snapshot) {
        return DropperRules.decide(snapshot);
    }

    @Override
    public boolean shouldApply(DropperDecision decision) {
        return decision.apply();
    }

    @Override
    public boolean validate(DropperSnapshot snapshot, DropperDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(new BlockPos(snapshot.x(), snapshot.y(), snapshot.z()));
        if (!(be instanceof DropperBlockEntity dropper) || dropper.getContainerSize() != snapshot.dropper().length) {
            return false;
        }
        for (int i = 0; i < snapshot.dropper().length; i++) {
            if (!same(copyOne(dropper.getItem(i)), snapshot.dropper()[i])) {
                return false;
            }
        }
        Container dest = HopperBlockEntity.getContainerAt(level,
                new BlockPos(snapshot.destX(), snapshot.destY(), snapshot.destZ()));
        if (dest == null || dest.getContainerSize() != snapshot.dest().length) {
            return false;
        }
        for (int i = 0; i < snapshot.dest().length; i++) {
            if (!same(copyOne(dest.getItem(i)), snapshot.dest()[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void apply(DropperSnapshot snapshot, DropperDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            throw new IllegalStateException("level gone");
        }
        BlockEntity be = level.getBlockEntity(new BlockPos(snapshot.x(), snapshot.y(), snapshot.z()));
        if (!(be instanceof DropperBlockEntity dropper)) {
            throw new IllegalStateException("dropper gone");
        }
        for (int i = 0; i < decision.dropper().length && i < dropper.getContainerSize(); i++) {
            dropper.setItem(i, toStack(decision.dropper()[i]));
        }
        Container dest = HopperBlockEntity.getContainerAt(level,
                new BlockPos(snapshot.destX(), snapshot.destY(), snapshot.destZ()));
        if (dest != null && decision.dest() != null) {
            for (int i = 0; i < decision.dest().length && i < dest.getContainerSize(); i++) {
                dest.setItem(i, toStack(decision.dest()[i]));
            }
            dest.setChanged();
        }
        dropper.setChanged();
    }

    private static ItemCopy[] copy(Container container) {
        ItemCopy[] out = new ItemCopy[container.getContainerSize()];
        for (int i = 0; i < out.length; i++) {
            out[i] = copyOne(container.getItem(i));
        }
        return out;
    }

    private static ItemCopy copyOne(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemCopy.EMPTY;
        }
        return new ItemCopy(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), stack.getMaxStackSize());
    }

    private static boolean canPlace(Container dest, ItemStack stack, int slot, Direction face) {
        if (!dest.canPlaceItem(slot, stack)) {
            return false;
        }
        if (dest instanceof WorldlyContainer worldly) {
            return worldly.canPlaceItemThroughFace(slot, stack, face);
        }
        return true;
    }

    private static boolean same(ItemCopy a, ItemCopy b) {
        ItemCopy left = a == null ? ItemCopy.EMPTY : a;
        ItemCopy right = b == null ? ItemCopy.EMPTY : b;
        if (left.isEmpty() && right.isEmpty()) {
            return true;
        }
        return !left.isEmpty() && !right.isEmpty() && left.id().equals(right.id()) && left.count() == right.count();
    }

    private static ItemStack toStack(ItemCopy copy) {
        if (copy == null || copy.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(copy.id()));
        return new ItemStack(item, copy.count());
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
}
