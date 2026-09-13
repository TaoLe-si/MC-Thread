package com.taolesi.mcthread.experiment.furnace;

import com.taolesi.mcthread.experiment.OffloadSession;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;

import java.util.Optional;
import java.util.UUID;

/**
 * Capture / validate / apply for furnace, blast furnace, and smoker BE interactions.
 *
 * <p>OPEN is the block {@code use()} path. Menu clicks run the same slot rules as
 * {@code AbstractFurnaceMenu} on value copies, then write back through
 * {@link AbstractFurnaceBlockEntity#setItem(int, ItemStack)}.
 */
public final class McFurnaceSession implements OffloadSession<FurnaceSnapshot, FurnaceDecision> {

    public static final McFurnaceSession INSTANCE = new McFurnaceSession();

    private McFurnaceSession() {
    }

    public static boolean isFurnaceBlock(Block block) {
        return block instanceof FurnaceBlock
                || block instanceof BlastFurnaceBlock
                || block instanceof SmokerBlock;
    }

    public static Optional<FurnaceSnapshot> tryCaptureOpen(ServerPlayer player, Level level,
                                                           InteractionHand hand, BlockPos pos) {
        if (player == null || level == null || level.isClientSide || player.isSpectator()) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        if (!isFurnaceBlock(state.getBlock())) {
            return Optional.empty();
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            return Optional.empty();
        }
        RecipeType<? extends AbstractCookingRecipe> type = recipeType(state.getBlock());
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        for (int i = 0; i < 3; i++) {
            slots[i] = copySlot(furnace.getItem(i), type, level);
        }
        return Optional.of(new FurnaceSnapshot(
                "OPEN", -1, 0,
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                id(state.getBlock()),
                -1,
                slots,
                SlotCopy.EMPTY,
                player.getAbilities().instabuild,
                player.getUUID()));
    }

    public static Optional<FurnaceSnapshot> tryCaptureClick(AbstractFurnaceMenu menu, ServerPlayer player,
                                                            int slot, int button, ClickType clickType) {
        if (menu == null || player == null || player.isSpectator() || clickType == null) {
            return Optional.empty();
        }
        if (!supported(clickType)) {
            return Optional.empty();
        }
        Container container = menu.getSlot(0).container;
        if (!(container instanceof AbstractFurnaceBlockEntity furnace)) {
            return Optional.empty();
        }
        Level level = furnace.getLevel();
        if (level == null || level.isClientSide) {
            return Optional.empty();
        }
        BlockState state = furnace.getBlockState();
        RecipeType<? extends AbstractCookingRecipe> type = recipeType(state.getBlock());
        SlotCopy[] slots = new SlotCopy[FurnaceSnapshot.MENU_SIZE];
        for (int i = 0; i < FurnaceSnapshot.MENU_SIZE; i++) {
            slots[i] = copySlot(menu.getSlot(i).getItem(), type, level);
        }
        BlockPos pos = furnace.getBlockPos();
        return Optional.of(new FurnaceSnapshot(
                clickType.name(), slot, button,
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                id(state.getBlock()),
                menu.containerId,
                slots,
                copySlot(menu.getCarried(), type, level),
                player.getAbilities().instabuild,
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
    public FurnaceDecision decide(FurnaceSnapshot snapshot) {
        return FurnaceRules.decide(snapshot);
    }

    @Override
    public boolean shouldApply(FurnaceDecision decision) {
        return decision.apply();
    }

    @Override
    public boolean validate(FurnaceSnapshot snapshot, FurnaceDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            return false;
        }
        BlockPos pos = new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }
        if (!id(furnace.getBlockState().getBlock()).equals(snapshot.furnaceBlockId())) {
            return false;
        }
        if (snapshot.isOpen() || decision.opensMenu()) {
            ServerPlayer opener = findPlayer(snapshot.playerId());
            return opener == null || furnace.stillValid(opener);
        }
        for (int i = 0; i < 3; i++) {
            if (!sameItem(copySlot(furnace.getItem(i), recipeType(furnace.getBlockState().getBlock()), level),
                    snapshot.slots()[i])) {
                return false;
            }
        }
        ServerPlayer player = findPlayer(snapshot.playerId());
        if (player == null) {
            return true;
        }
        if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            return false;
        }
        return menu.containerId == snapshot.containerId() && menu.stillValid(player);
    }

    @Override
    public void apply(FurnaceSnapshot snapshot, FurnaceDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            throw new IllegalStateException("level gone: " + snapshot.dimension());
        }
        BlockPos pos = new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            throw new IllegalStateException("furnace BE gone at " + pos);
        }
        ServerPlayer player = findPlayer(snapshot.playerId());
        if (decision.opensMenu()) {
            if (player != null) {
                player.openMenu(furnace);
                awardOpenStat(player, furnace);
            }
            return;
        }
        if (decision.slots() == null) {
            return;
        }
        SlotCopy beforeResult = snapshot.slots() != null && snapshot.slots().length > 2
                ? snapshot.slots()[FurnaceSnapshot.RESULT] : SlotCopy.EMPTY;
        for (int i = 0; i < 3; i++) {
            furnace.setItem(i, toStack(decision.slots()[i]));
        }
        if (player != null) {
            Inventory inv = player.getInventory();
            for (int i = FurnaceSnapshot.PLAYER_INV_START; i < FurnaceSnapshot.PLAYER_INV_END; i++) {
                inv.setItem(i - FurnaceSnapshot.PLAYER_INV_START + 9, toStack(decision.slots()[i]));
            }
            for (int i = FurnaceSnapshot.HOTBAR_START; i < FurnaceSnapshot.HOTBAR_END; i++) {
                inv.setItem(i - FurnaceSnapshot.HOTBAR_START, toStack(decision.slots()[i]));
            }
            if (player.containerMenu instanceof AbstractFurnaceMenu menu
                    && menu.containerId == snapshot.containerId()) {
                menu.setCarried(toStack(decision.carried()));
                menu.sendAllDataToRemote();
            }
            if (decision.drop() != null && !decision.drop().isEmpty()) {
                player.drop(toStack(decision.drop()), true);
            }
            if (extractedResult(beforeResult, decision.slots()[FurnaceSnapshot.RESULT])) {
                furnace.awardUsedRecipesAndPopExperience(player);
            }
        }
        furnace.setChanged();
    }

    private static boolean extractedResult(SlotCopy before, SlotCopy after) {
        if (before == null || before.isEmpty()) {
            return false;
        }
        if (after == null || after.isEmpty()) {
            return true;
        }
        return after.count() < before.count();
    }

    private static void awardOpenStat(ServerPlayer player, AbstractFurnaceBlockEntity furnace) {
        if (furnace instanceof BlastFurnaceBlockEntity) {
            player.awardStat(Stats.INTERACT_WITH_BLAST_FURNACE);
        } else if (furnace instanceof SmokerBlockEntity) {
            player.awardStat(Stats.INTERACT_WITH_SMOKER);
        } else if (furnace instanceof FurnaceBlockEntity) {
            player.awardStat(Stats.INTERACT_WITH_FURNACE);
        }
    }

    private static boolean sameItem(SlotCopy a, SlotCopy b) {
        SlotCopy left = a == null ? SlotCopy.EMPTY : a;
        SlotCopy right = b == null ? SlotCopy.EMPTY : b;
        if (left.isEmpty() && right.isEmpty()) {
            return true;
        }
        return !left.isEmpty() && !right.isEmpty()
                && left.id().equals(right.id())
                && left.count() == right.count();
    }

    private static SlotCopy copySlot(ItemStack stack, RecipeType<? extends AbstractCookingRecipe> type, Level level) {
        if (stack == null || stack.isEmpty()) {
            return SlotCopy.EMPTY;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new SlotCopy(
                key.toString(),
                stack.getCount(),
                stack.getMaxStackSize(),
                canSmelt(stack, type, level),
                ForgeHooks.getBurnTime(stack, type) > 0 || stack.is(net.minecraft.world.item.Items.BUCKET));
    }

    private static boolean canSmelt(ItemStack stack, RecipeType<? extends AbstractCookingRecipe> type, Level level) {
        if (level == null) {
            return false;
        }
        return level.getRecipeManager()
                .getRecipeFor(type, new SimpleContainer(stack), level)
                .isPresent();
    }

    private static ItemStack toStack(SlotCopy copy) {
        if (copy == null || copy.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(copy.id()));
        return new ItemStack(item, copy.count());
    }

    private static RecipeType<? extends AbstractCookingRecipe> recipeType(Block block) {
        if (block instanceof BlastFurnaceBlock) {
            return RecipeType.BLASTING;
        }
        if (block instanceof SmokerBlock) {
            return RecipeType.SMOKING;
        }
        return RecipeType.SMELTING;
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
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
