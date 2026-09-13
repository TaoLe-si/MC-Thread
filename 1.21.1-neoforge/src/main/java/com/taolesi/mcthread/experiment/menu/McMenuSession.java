package com.taolesi.mcthread.experiment.menu;

import com.taolesi.mcthread.experiment.OffloadSession;
import com.taolesi.mcthread.experiment.furnace.McFurnaceSession;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Generic MenuProvider OPEN + chest-like menu clicks (not {@link AbstractFurnaceMenu}).
 */
public final class McMenuSession implements OffloadSession<MenuSnapshot, MenuDecision> {

    public static final McMenuSession INSTANCE = new McMenuSession();

    private McMenuSession() {
    }

    public static Optional<MenuSnapshot> tryCaptureOpen(ServerPlayer player, Level level, ItemStack stack,
                                                        InteractionHand hand, BlockPos pos, BlockHitResult hit) {
        if (player == null || level == null || level.isClientSide || player.isSpectator()) {
            return Optional.empty();
        }
        if (player.isShiftKeyDown() && stack != null && !stack.isEmpty()) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        if (McFurnaceSession.isFurnaceBlock(state.getBlock())) {
            return Optional.empty();
        }
        if (state.getMenuProvider(level, pos) == null) {
            return Optional.empty();
        }
        Direction face = hit == null ? Direction.UP : hit.getDirection();
        return Optional.of(new MenuSnapshot(
                "OPEN", -1, 0,
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                -1, 0, new String[0],
                MenuSnapshot.emptySlots(0),
                MenuSlotCopy.EMPTY, MenuSlotCopy.EMPTY,
                player.getAbilities().instabuild,
                hand == InteractionHand.OFF_HAND ? "off_hand" : "main_hand",
                face.getSerializedName(),
                player.getUUID()));
    }

    public static Optional<MenuSnapshot> tryCaptureClick(AbstractContainerMenu menu, ServerPlayer player,
                                                         int slot, int button, ClickType clickType) {
        if (menu == null || player == null || player.isSpectator() || clickType == null) {
            return Optional.empty();
        }
        if (menu instanceof AbstractFurnaceMenu) {
            return Optional.empty();
        }
        if (!supported(clickType)) {
            return Optional.empty();
        }
        if (menu.slots.size() > 90) {
            return Optional.empty();
        }
        List<String> types = new ArrayList<>();
        for (Slot s : menu.slots) {
            addType(types, s.getItem());
        }
        addType(types, menu.getCarried());
        addType(types, player.getOffhandItem());
        if (types.size() > 63) {
            return Optional.empty();
        }
        String[] typeArr = types.toArray(new String[0]);
        MenuSlotCopy[] slots = new MenuSlotCopy[menu.slots.size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = copySlot(menu.getSlot(i), player, typeArr);
        }
        int playerInvStart = slots.length;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).container == player.getInventory()) {
                playerInvStart = i;
                break;
            }
        }
        BlockPos pos = BlockPos.ZERO;
        String blockId = "minecraft:air";
        String dimension = player.level().dimension().location().toString();
        for (Slot s : menu.slots) {
            if (s.container instanceof BlockEntity be && be.getLevel() != null) {
                pos = be.getBlockPos();
                blockId = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
                dimension = be.getLevel().dimension().location().toString();
                break;
            }
        }
        if (pos.equals(BlockPos.ZERO) && !(menu.slots.get(0).container instanceof BlockEntity)) {
            pos = player.blockPosition();
        }
        return Optional.of(new MenuSnapshot(
                clickType.name(), slot, button,
                dimension, pos.getX(), pos.getY(), pos.getZ(),
                blockId, menu.containerId, playerInvStart, typeArr, slots,
                copyItem(menu.getCarried(), typeArr, true, ~0L),
                copyItem(player.getOffhandItem(), typeArr, true, ~0L),
                player.getAbilities().instabuild,
                "main_hand", Direction.UP.getSerializedName(),
                player.getUUID()));
    }

    private static boolean supported(ClickType type) {
        return type == ClickType.PICKUP
                || type == ClickType.QUICK_MOVE
                || type == ClickType.SWAP
                || type == ClickType.THROW
                || type == ClickType.PICKUP_ALL
                || type == ClickType.CLONE;
    }

    @Override
    public MenuDecision decide(MenuSnapshot snapshot) {
        return MenuRules.decide(snapshot);
    }

    @Override
    public boolean shouldApply(MenuDecision decision) {
        return decision.apply();
    }

    @Override
    public boolean validate(MenuSnapshot snapshot, MenuDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            return false;
        }
        BlockPos pos = new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
        BlockState state = level.getBlockState(pos);
        if (snapshot.isOpen() || decision.opensMenu()) {
            return snapshot.blockId().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
                    && (findPlayer(snapshot.playerId()) == null || state.getMenuProvider(level, pos) != null);
        }
        ServerPlayer player = findPlayer(snapshot.playerId());
        if (player == null) {
            BlockEntity be = level.getBlockEntity(pos);
            return be instanceof Container;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null
                || menu instanceof AbstractFurnaceMenu
                || menu.containerId != snapshot.containerId()
                || !menu.stillValid(player)
                || menu.slots.size() != snapshot.slots().length) {
            return false;
        }
        for (int i = 0; i < snapshot.slots().length; i++) {
            if (!sameItem(copyItem(menu.getSlot(i).getItem(), snapshot.types(), true, 0L), snapshot.slots()[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void apply(MenuSnapshot snapshot, MenuDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            throw new IllegalStateException("level gone: " + snapshot.dimension());
        }
        BlockPos pos = new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
        ServerPlayer player = findPlayer(snapshot.playerId());
        if (decision.opensMenu()) {
            if (player != null) {
                BlockState state = level.getBlockState(pos);
                InteractionHand hand = "off_hand".equals(snapshot.hand())
                        ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                Direction face = Direction.byName(snapshot.hitFace());
                if (face == null) {
                    face = Direction.UP;
                }
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                ItemStack held = player.getItemInHand(hand);
                if (held.isEmpty()) {
                    state.useWithoutItem(level, player, hit);
                } else {
                    state.useItemOn(held, level, player, hand, hit);
                }
            }
            return;
        }
        if (decision.slots() == null) {
            return;
        }
        if (player != null && player.containerMenu.containerId == snapshot.containerId()) {
            AbstractContainerMenu menu = player.containerMenu;
            for (int i = 0; i < decision.slots().length && i < menu.slots.size(); i++) {
                menu.getSlot(i).set(toStack(decision.slots()[i]));
            }
            menu.setCarried(toStack(decision.carried()));
            player.getInventory().setItem(40, toStack(decision.offhand()));
            if (decision.drop() != null && !decision.drop().isEmpty()) {
                player.drop(toStack(decision.drop()), true);
            }
            menu.sendAllDataToRemote();
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            int n = Math.min(container.getContainerSize(), decision.slots().length);
            for (int i = 0; i < n; i++) {
                container.setItem(i, toStack(decision.slots()[i]));
            }
            container.setChanged();
        }
    }

    private static void addType(List<String> types, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!types.contains(id)) {
            types.add(id);
        }
    }

    private static MenuSlotCopy copySlot(Slot slot, ServerPlayer player, String[] types) {
        long mask = 0L;
        for (int i = 0; i < types.length; i++) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(types[i]));
            if (slot.mayPlace(new ItemStack(item))) {
                mask |= 1L << i;
            }
        }
        return copyItem(slot.getItem(), types, slot.mayPickup(player), mask);
    }

    private static MenuSlotCopy copyItem(ItemStack stack, String[] types, boolean mayTake, long placeMask) {
        if (stack == null || stack.isEmpty()) {
            return new MenuSlotCopy("minecraft:air", 0, 64, mayTake, placeMask);
        }
        return new MenuSlotCopy(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(),
                stack.getMaxStackSize(),
                mayTake,
                placeMask);
    }

    private static boolean sameItem(MenuSlotCopy a, MenuSlotCopy b) {
        MenuSlotCopy left = a == null ? MenuSlotCopy.EMPTY : a;
        MenuSlotCopy right = b == null ? MenuSlotCopy.EMPTY : b;
        if (left.isEmpty() && right.isEmpty()) {
            return true;
        }
        return !left.isEmpty() && !right.isEmpty()
                && left.id().equals(right.id())
                && left.count() == right.count();
    }

    private static ItemStack toStack(MenuSlotCopy copy) {
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

    private static ServerPlayer findPlayer(UUID playerId) {
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        MinecraftServer server = rt == null ? null : rt.server();
        if (server == null || playerId == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(playerId);
    }
}
