package com.taolesi.mcthread.experiment.furnace;

import com.taolesi.mcthread.experiment.OffloadSession;
import com.taolesi.mcthread.experiment.hopper.ItemCopy;
import com.taolesi.mcthread.mixin.AbstractFurnaceBlockEntityAccessor;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;

import java.util.Optional;

public final class McFurnaceTickSession implements OffloadSession<FurnaceTickSnapshot, FurnaceTickDecision> {

    public static final McFurnaceTickSession INSTANCE = new McFurnaceTickSession();

    private McFurnaceTickSession() {
    }

    public static Optional<FurnaceTickSnapshot> tryCapture(Level level, BlockPos pos, BlockState state,
                                                           AbstractFurnaceBlockEntity furnace) {
        if (level == null || level.isClientSide || furnace == null || state == null) {
            return Optional.empty();
        }
        AbstractFurnaceBlockEntityAccessor acc = (AbstractFurnaceBlockEntityAccessor) furnace;
        ItemStack ingredient = furnace.getItem(0);
        ItemStack fuel = furnace.getItem(1);
        if (acc.mcthread$getLitTime() <= 0 && ingredient.isEmpty() && fuel.isEmpty()
                && acc.mcthread$getCookingProgress() <= 0) {
            return Optional.empty();
        }
        RecipeType<? extends AbstractCookingRecipe> type = recipeType(state.getBlock());
        Optional<? extends AbstractCookingRecipe> recipe = findRecipe(level, furnace, type);
        ItemStack recipeResult = recipe.map(r -> r.assemble(furnace, level.registryAccess())).orElse(ItemStack.EMPTY);
        int cookTime = recipe.map(AbstractCookingRecipe::getCookingTime).orElse(200);
        ItemStack remainder = fuel.isEmpty() ? ItemStack.EMPTY : fuel.getCraftingRemainingItem();
        return Optional.of(new FurnaceTickSnapshot(
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                copy(ingredient), copy(fuel), copy(furnace.getItem(2)),
                acc.mcthread$getLitTime(),
                acc.mcthread$getLitDuration(),
                acc.mcthread$getCookingProgress(),
                acc.mcthread$getCookingTotalTime(),
                ForgeHooks.getBurnTime(fuel, type),
                cookTime,
                copy(recipeResult),
                copy(remainder),
                ingredient.is(Items.WET_SPONGE)));
    }

    @Override
    public FurnaceTickDecision decide(FurnaceTickSnapshot snapshot) {
        return FurnaceTickRules.decide(snapshot);
    }

    @Override
    public boolean shouldApply(FurnaceTickDecision decision) {
        return decision.apply();
    }

    @Override
    public boolean validate(FurnaceTickSnapshot snapshot, FurnaceTickDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            return false;
        }
        BlockPos pos = new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }
        if (!id(furnace.getBlockState().getBlock()).equals(snapshot.blockId())) {
            return false;
        }
        AbstractFurnaceBlockEntityAccessor acc = (AbstractFurnaceBlockEntityAccessor) furnace;
        return same(copy(furnace.getItem(0)), snapshot.ingredient())
                && same(copy(furnace.getItem(1)), snapshot.fuel())
                && same(copy(furnace.getItem(2)), snapshot.result())
                && acc.mcthread$getLitTime() == snapshot.litTime()
                && acc.mcthread$getCookingProgress() == snapshot.cookingProgress();
    }

    @Override
    public void apply(FurnaceTickSnapshot snapshot, FurnaceTickDecision decision) {
        ServerLevel level = findLevel(snapshot.dimension());
        if (level == null) {
            throw new IllegalStateException("level gone: " + snapshot.dimension());
        }
        BlockPos pos = new BlockPos(snapshot.x(), snapshot.y(), snapshot.z());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            throw new IllegalStateException("furnace gone at " + pos);
        }
        furnace.setItem(0, toStack(decision.ingredient()));
        furnace.setItem(1, toStack(decision.fuel()));
        furnace.setItem(2, toStack(decision.result()));
        AbstractFurnaceBlockEntityAccessor acc = (AbstractFurnaceBlockEntityAccessor) furnace;
        acc.mcthread$setLitTime(decision.litTime());
        acc.mcthread$setLitDuration(decision.litDuration());
        acc.mcthread$setCookingProgress(decision.cookingProgress());
        acc.mcthread$setCookingTotalTime(decision.cookingTotalTime());
        BlockState state = furnace.getBlockState();
        boolean lit = decision.litTime() > 0;
        if (state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(AbstractFurnaceBlock.LIT, lit), 3);
        }
        furnace.setChanged();
    }

    private static Optional<? extends AbstractCookingRecipe> findRecipe(
            Level level, AbstractFurnaceBlockEntity furnace, RecipeType<? extends AbstractCookingRecipe> type) {
        if (type == RecipeType.BLASTING) {
            return level.getRecipeManager().getRecipeFor(RecipeType.BLASTING, furnace, level);
        }
        if (type == RecipeType.SMOKING) {
            return level.getRecipeManager().getRecipeFor(RecipeType.SMOKING, furnace, level);
        }
        return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, furnace, level);
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

    private static ItemCopy copy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemCopy.EMPTY;
        }
        return new ItemCopy(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(),
                stack.getMaxStackSize());
    }

    private static boolean same(ItemCopy a, ItemCopy b) {
        ItemCopy left = a == null ? ItemCopy.EMPTY : a;
        ItemCopy right = b == null ? ItemCopy.EMPTY : b;
        if (left.isEmpty() && right.isEmpty()) {
            return true;
        }
        return !left.isEmpty() && !right.isEmpty()
                && left.id().equals(right.id())
                && left.count() == right.count();
    }

    private static ItemStack toStack(ItemCopy copy) {
        if (copy == null || copy.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(copy.id()));
        return new ItemStack(item, copy.count());
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
}
