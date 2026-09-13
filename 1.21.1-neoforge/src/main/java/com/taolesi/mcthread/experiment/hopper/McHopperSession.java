package com.taolesi.mcthread.experiment.hopper;

import com.taolesi.mcthread.experiment.OffloadSession;
import com.taolesi.mcthread.mixin.HopperBlockEntityAccessor;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.Optional;

/**
 * Capture / validate / apply for one hopper transfer (eject or pull).
 */
public final class McHopperSession implements OffloadSession<HopperSnapshot, HopperDecision> {

    public static final McHopperSession INSTANCE = new McHopperSession();

    private McHopperSession() {
    }

    public static Optional<HopperSnapshot> tryCapture(Level level, BlockPos pos, BlockState state,
                                                      HopperBlockEntity hopper) {
        if (level == null || level.isClientSide || hopper == null || state == null) {
            return Optional.empty();
        }
        Direction facing = state.getValue(net.minecraft.world.level.block.HopperBlock.FACING);
        BlockPos destPos = pos.relative(facing);
        BlockEntity destBe = level.getBlockEntity(destPos);
        if (destBe != null && !(destBe instanceof Container)
                && level.getCapability(Capabilities.ItemHandler.BLOCK, destPos, facing.getOpposite()) != null) {
            return Optional.empty();
        }
        Container dest = HopperBlockEntity.getContainerAt(level, destPos);
        BlockPos srcPos = pos.above();
        BlockEntity srcBe = level.getBlockEntity(srcPos);
        if (srcBe != null && !(srcBe instanceof Container)
                && level.getCapability(Capabilities.ItemHandler.BLOCK, srcPos, Direction.DOWN) != null) {
            return Optional.empty();
        }
        Container source = HopperBlockEntity.getContainerAt(level, srcPos);
        if (dest == null && source == null) {
            return Optional.empty();
        }
        ItemCopy[] hopperSlots = copyContainer(hopper);
        ItemCopy[] destSlots = dest == null ? ItemCopy.empty(0) : copyContainer(dest);
        ItemCopy[] srcSlots = source == null ? ItemCopy.empty(0) : copyContainer(source);
        boolean[] destPlace = new boolean[Math.max(1, destSlots.length) * hopperSlots.length];
        if (dest != null) {
            Direction inFace = facing.getOpposite();
            for (int h = 0; h < hopperSlots.length; h++) {
                ItemStack proto = oneOf(hopper.getItem(h));
                for (int d = 0; d < destSlots.length; d++) {
                    destPlace[d * hopperSlots.length + h] = proto.isEmpty() || canPlace(dest, proto, d, inFace);
                }
            }
        }
        boolean[] hopperPlace = new boolean[hopperSlots.length * Math.max(1, srcSlots.length)];
        boolean[] sourceTake = new boolean[srcSlots.length];
        if (source != null) {
            for (int s = 0; s < srcSlots.length; s++) {
                ItemStack stack = source.getItem(s);
                sourceTake[s] = !stack.isEmpty() && canTake(source, stack, s, Direction.DOWN);
                ItemStack proto = oneOf(stack);
                for (int h = 0; h < hopperSlots.length; h++) {
                    hopperPlace[h * srcSlots.length + s] = proto.isEmpty() || canPlace(hopper, proto, h, null);
                }
            }
        }
        return Optional.of(HopperSnapshot.transfer(
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                destPos.getX(), destPos.getY(), destPos.getZ(), dest != null,
                srcPos.getX(), srcPos.getY(), srcPos.getZ(), source != null,
                hopperSlots, destSlots, srcSlots,
                destPlace, hopperPlace, sourceTake));
    }

    public static Optional<HopperSnapshot> tryCaptureTick(Level level, BlockPos pos, BlockState state,
                                                          HopperBlockEntity hopper) {
        Optional<HopperSnapshot> move = tryCapture(level, pos, state, hopper);
        if (move.isEmpty()) {
            return Optional.empty();
        }
        HopperSnapshot base = move.get();
        int cooldown = ((HopperBlockEntityAccessor) hopper).mcthread$getCooldownTime();
        long gameTime = level.getGameTime();
        return Optional.of(new HopperSnapshot(
                base.dimension(), base.x(), base.y(), base.z(),
                base.destX(), base.destY(), base.destZ(), base.hasDest(),
                base.srcX(), base.srcY(), base.srcZ(), base.hasSource(),
                base.hopper(), base.dest(), base.source(),
                base.destPlace(), base.hopperPlace(), base.sourceTake(),
                cooldown, gameTime, true));
    }

    @Override
    public HopperDecision decide(HopperSnapshot snapshot) {
        return HopperRules.decide(snapshot);
    }

    @Override
    public boolean shouldApply(HopperDecision decision) {
        return decision.apply();
    }

    @Override
    public boolean validate(HopperSnapshot snapshot, HopperDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            return false;
        }
        BlockPos pos = new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HopperBlockEntity hopper)) {
            return false;
        }
        if (!sameContainer(hopper, snapshot.hopper())) {
            return false;
        }
        if (snapshot.fromTick()
                && ((HopperBlockEntityAccessor) hopper).mcthread$getCooldownTime() != snapshot.cooldown()) {
            return false;
        }
        if (snapshot.hasDest()) {
            Container dest = HopperBlockEntity.getContainerAt(level,
                    new BlockPos(snapshot.destX(), snapshot.destY(), snapshot.destZ()));
            if (dest == null || !sameContainer(dest, snapshot.dest())) {
                return false;
            }
        }
        if (snapshot.hasSource()) {
            Container source = HopperBlockEntity.getContainerAt(level,
                    new BlockPos(snapshot.srcX(), snapshot.srcY(), snapshot.srcZ()));
            if (source == null || !sameContainer(source, snapshot.source())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void apply(HopperSnapshot snapshot, HopperDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            throw new IllegalStateException("level gone: " + snapshot.dimension());
        }
        BlockPos pos = new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HopperBlockEntity hopper)) {
            throw new IllegalStateException("hopper gone at " + pos);
        }
        write(hopper, decision.hopper());
        if (snapshot.hasDest() && decision.dest() != null) {
            Container dest = HopperBlockEntity.getContainerAt(level,
                    new BlockPos(snapshot.destX(), snapshot.destY(), snapshot.destZ()));
            if (dest != null) {
                write(dest, decision.dest());
                dest.setChanged();
            }
        }
        if (snapshot.hasSource() && decision.source() != null) {
            Container source = HopperBlockEntity.getContainerAt(level,
                    new BlockPos(snapshot.srcX(), snapshot.srcY(), snapshot.srcZ()));
            if (source != null) {
                write(source, decision.source());
                source.setChanged();
            }
        }
        if (decision.writeCooldown()) {
            hopper.setCooldown(decision.cooldown());
        }
        if (snapshot.fromTick()) {
            ((HopperBlockEntityAccessor) hopper).mcthread$setTickedGameTime(snapshot.gameTime());
        }
        hopper.setChanged();
    }

    private static ItemCopy[] copyContainer(Container container) {
        ItemCopy[] out = new ItemCopy[container.getContainerSize()];
        for (int i = 0; i < out.length; i++) {
            out[i] = copy(container.getItem(i));
        }
        return out;
    }

    private static ItemCopy copy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemCopy.EMPTY;
        }
        return new ItemCopy(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(),
                stack.getMaxStackSize());
    }

    private static ItemStack oneOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack one = stack.copy();
        one.setCount(1);
        return one;
    }

    private static boolean canPlace(Container dest, ItemStack stack, int slot, Direction face) {
        if (!dest.canPlaceItem(slot, stack)) {
            return false;
        }
        if (dest instanceof WorldlyContainer worldly && face != null) {
            return worldly.canPlaceItemThroughFace(slot, stack, face);
        }
        return true;
    }

    private static boolean canTake(Container src, ItemStack stack, int slot, Direction face) {
        if (src instanceof WorldlyContainer worldly) {
            return worldly.canTakeItemThroughFace(slot, stack, face);
        }
        return true;
    }

    private static boolean sameContainer(Container live, ItemCopy[] expected) {
        if (expected == null || live.getContainerSize() != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            ItemCopy a = copy(live.getItem(i));
            ItemCopy b = expected[i] == null ? ItemCopy.EMPTY : expected[i];
            if (a.isEmpty() != b.isEmpty()) {
                return false;
            }
            if (!a.isEmpty() && (!a.id().equals(b.id()) || a.count() != b.count())) {
                return false;
            }
        }
        return true;
    }

    private static void write(Container container, ItemCopy[] copies) {
        if (copies == null) {
            return;
        }
        int n = Math.min(container.getContainerSize(), copies.length);
        for (int i = 0; i < n; i++) {
            container.setItem(i, toStack(copies[i]));
        }
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
