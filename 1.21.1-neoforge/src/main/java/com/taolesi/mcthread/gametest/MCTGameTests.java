package com.taolesi.mcthread.gametest;

import com.taolesi.mcthread.MCThread;
import com.taolesi.mcthread.api.MCT;
import com.taolesi.mcthread.api.MCTRuntime;
import com.taolesi.mcthread.api.Snapshot;
import com.taolesi.mcthread.api.Transaction;
import com.taolesi.mcthread.config.MCThreadConfig;
import com.taolesi.mcthread.experiment.furnace.McFurnaceSession;
import com.taolesi.mcthread.experiment.furnace.FurnaceSnapshot;
import com.taolesi.mcthread.experiment.furnace.SlotCopy;
import com.taolesi.mcthread.experiment.till.McTillSession;
import com.taolesi.mcthread.experiment.till.TillRules;
import com.taolesi.mcthread.experiment.till.TillSnapshot;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-game integration tests for the runtime (run via runGameTestServer). */
@GameTestHolder(MCThread.MODID)
@PrefixGameTestTemplate(false)
public final class MCTGameTests {

    private MCTGameTests() {
    }

    @SubscribeEvent
    public static void register(final RegisterGameTestsEvent event) {
        event.register(MCTGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void snapshotComputeValidateCommit(final GameTestHelper helper) {
        final MCTRuntime rt = MCT.runtime();
        final int[] target = {40};

        // snapshot on the server thread, compute on the pool, commit on the server thread
        final Snapshot<Integer> snap = rt.snapshot(40, 1L);
        final boolean[] computedOffServerThread = {false};
        final int result = rt.submitCompute(() -> {
            computedOffServerThread[0] = !rt.isServerThread();
            return snap.get() + 2;
        }).join();

        helper.assertTrue(result == 42, "compute result mismatch: " + result);
        helper.assertTrue(computedOffServerThread[0], "compute task ran on the server thread");

        final Transaction<int[]> tx = rt.beginTransaction();
        tx.addChange(t -> t[0] += result, t -> t[0] -= result);
        tx.commit(target);
        helper.assertTrue(target[0] == 82, "commit did not apply: " + target[0]);
        helper.assertTrue(tx.isTerminated(), "transaction not terminated after commit");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void interactionExecutorFifo(final GameTestHelper helper) {
        final MCTRuntime rt = MCT.runtime();
        final List<String> order = Collections.synchronizedList(new ArrayList<>());

        rt.scheduleInteraction(() -> order.add("a")).join();
        rt.scheduleInteraction(() -> order.add("b")).join();

        helper.assertTrue(order.equals(List.of("a", "b")), "interaction order broken: " + order);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void optimisticWorkflowAsync(final GameTestHelper helper) {
        // Regression test for the server-thread deadlock: optimistic() must be
        // consumed asynchronously (never join()ed on the server thread).
        final MCTRuntime rt = MCT.runtime();
        final AtomicBoolean done = new AtomicBoolean();
        rt.optimistic(
                () -> rt.snapshot(40L, 1L),
                snapshot -> snapshot.get() + 2,
                (snapshot, value) -> snapshot.isValid(1L),
                value -> {
                },
                2).whenComplete((result, error) -> done.set(error == null && result == 42L));
        helper.succeedWhen(done::get);
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadTillDirtAppliesFarmland(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.DIRT);
        helper.setBlock(BlockPos.ZERO.above(), Blocks.AIR);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        TillSnapshot snap = tillSnapshot(helper, abs, TillRules.DIRT);
        MCTRuntimeImpl.get().interactionOffload().submit(snap, McTillSession.INSTANCE);
        helper.succeedWhen(() -> helper.assertTrue(
                helper.getBlockState(BlockPos.ZERO).is(Blocks.FARMLAND), "expected farmland"));
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadTillRejectsStaleBlock(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.DIRT);
        helper.setBlock(BlockPos.ZERO.above(), Blocks.AIR);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        TillSnapshot snap = tillSnapshot(helper, abs, TillRules.DIRT);
        helper.setBlock(BlockPos.ZERO, Blocks.STONE);
        long staleBefore = MCTRuntimeImpl.get().interactionOffload().stats().rejectedStale();
        MCTRuntimeImpl.get().interactionOffload().submit(snap, McTillSession.INSTANCE);
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(BlockPos.ZERO).is(Blocks.STONE), "stale world should stay stone");
            helper.assertTrue(MCTRuntimeImpl.get().interactionOffload().stats().rejectedStale() > staleBefore,
                    "stale snapshot should be rejected");
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadMixinInterceptsUseItemOn(final GameTestHelper helper) {
        boolean previous = MCThreadConfig.offloadPlayerUseItem;
        MCThreadConfig.offloadPlayerUseItem = true;
        helper.setBlock(BlockPos.ZERO, Blocks.DIRT);
        helper.setBlock(BlockPos.ZERO.above(), Blocks.AIR);
        FakePlayer player = FakePlayerFactory.get(
                helper.getLevel(), new GameProfile(java.util.UUID.randomUUID(), "mct-till"));
        ItemStack hoe = new ItemStack(Items.IRON_HOE);
        long appliedBefore = MCTRuntimeImpl.get().interactionOffload().stats().applied();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        InteractionResult result = player.gameMode.useItemOn(
                player, helper.getLevel(), hoe, InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(result == InteractionResult.SUCCESS, "mixin should return SUCCESS, got " + result);
        helper.succeedWhen(() -> {
            helper.assertTrue(MCTRuntimeImpl.get().interactionOffload().stats().applied() > appliedBefore,
                    "mixin path must increment applied (vanilla till would not)");
            MCThreadConfig.offloadPlayerUseItem = previous;
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadFurnaceInsertFuel(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.FURNACE);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        FurnaceSnapshot snap = new FurnaceSnapshot(
                "PICKUP", FurnaceSnapshot.FUEL, 0,
                helper.getLevel().dimension().location().toString(),
                abs.getX(), abs.getY(), abs.getZ(),
                "minecraft:furnace", -1,
                FurnaceSnapshot.emptySlots(),
                new SlotCopy("minecraft:coal", 8, 64, false, true),
                true,
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        MCTRuntimeImpl.get().interactionOffload().submit(snap, McFurnaceSession.INSTANCE);
        helper.succeedWhen(() -> {
            BlockEntity be = helper.getLevel().getBlockEntity(abs);
            helper.assertTrue(be instanceof AbstractFurnaceBlockEntity, "furnace BE missing");
            helper.assertTrue(((AbstractFurnaceBlockEntity) be).getItem(1).is(Items.COAL),
                    "fuel slot should be coal");
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadFurnaceInsertIngredient(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.FURNACE);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        FurnaceSnapshot snap = new FurnaceSnapshot(
                "PICKUP", FurnaceSnapshot.INGREDIENT, 0,
                helper.getLevel().dimension().location().toString(),
                abs.getX(), abs.getY(), abs.getZ(),
                "minecraft:furnace", -1,
                FurnaceSnapshot.emptySlots(),
                new SlotCopy("minecraft:raw_iron", 1, 64, true, false),
                true,
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        MCTRuntimeImpl.get().interactionOffload().submit(snap, McFurnaceSession.INSTANCE);
        helper.succeedWhen(() -> {
            BlockEntity be = helper.getLevel().getBlockEntity(abs);
            helper.assertTrue(be instanceof AbstractFurnaceBlockEntity, "furnace BE missing");
            helper.assertTrue(((AbstractFurnaceBlockEntity) be).getItem(0).is(Items.RAW_IRON),
                    "ingredient slot should be raw iron");
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadFurnaceTakeResult(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.FURNACE);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(abs);
        helper.assertTrue(furnace != null, "furnace BE missing");
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT));
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        slots[2] = new SlotCopy("minecraft:iron_ingot", 1, 64, false, false);
        FurnaceSnapshot snap = new FurnaceSnapshot(
                "PICKUP", FurnaceSnapshot.RESULT, 0,
                helper.getLevel().dimension().location().toString(),
                abs.getX(), abs.getY(), abs.getZ(),
                "minecraft:furnace", -1,
                slots,
                SlotCopy.EMPTY,
                true,
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        MCTRuntimeImpl.get().interactionOffload().submit(snap, McFurnaceSession.INSTANCE);
        helper.succeedWhen(() -> helper.assertTrue(
                ((AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(abs)).getItem(2).isEmpty(),
                "result slot should be empty after take"));
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadMixinOpensFurnace(final GameTestHelper helper) {
        boolean previous = MCThreadConfig.offloadPlayerUseItem;
        MCThreadConfig.offloadPlayerUseItem = true;
        helper.setBlock(BlockPos.ZERO, Blocks.FURNACE);
        FakePlayer player = FakePlayerFactory.get(
                helper.getLevel(), new GameProfile(java.util.UUID.randomUUID(), "mct-furnace"));
        long appliedBefore = MCTRuntimeImpl.get().interactionOffload().stats().applied();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        InteractionResult result = player.gameMode.useItemOn(
                player, helper.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(result.consumesAction(), "furnace open should consume, got " + result);
        helper.succeedWhen(() -> {
            helper.assertTrue(MCTRuntimeImpl.get().interactionOffload().stats().applied() > appliedBefore,
                    "furnace OPEN must go through the offload pipeline");
            MCThreadConfig.offloadPlayerUseItem = previous;
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadMixinOpensChest(final GameTestHelper helper) {
        boolean previous = MCThreadConfig.offloadPlayerUseItem;
        MCThreadConfig.offloadPlayerUseItem = true;
        helper.setBlock(BlockPos.ZERO, Blocks.CHEST);
        FakePlayer player = FakePlayerFactory.get(
                helper.getLevel(), new GameProfile(java.util.UUID.randomUUID(), "mct-chest"));
        long appliedBefore = MCTRuntimeImpl.get().interactionOffload().stats().applied();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        InteractionResult result = player.gameMode.useItemOn(
                player, helper.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(result.consumesAction(), "chest open should consume, got " + result);
        helper.succeedWhen(() -> {
            helper.assertTrue(MCTRuntimeImpl.get().interactionOffload().stats().applied() > appliedBefore,
                    "chest OPEN must go through the offload pipeline");
            MCThreadConfig.offloadPlayerUseItem = previous;
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadChestInsertCobble(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.CHEST);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        com.taolesi.mcthread.experiment.menu.MenuSlotCopy[] slots =
                com.taolesi.mcthread.experiment.menu.MenuSnapshot.emptySlots(63);
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new com.taolesi.mcthread.experiment.menu.MenuSlotCopy(
                    "minecraft:air", 0, 64, true, 1L);
        }
        com.taolesi.mcthread.experiment.menu.MenuSnapshot snap =
                new com.taolesi.mcthread.experiment.menu.MenuSnapshot(
                        "PICKUP", 0, 0,
                        helper.getLevel().dimension().location().toString(),
                        abs.getX(), abs.getY(), abs.getZ(),
                        "minecraft:chest", -1, 27,
                        new String[]{"minecraft:cobblestone"},
                        slots,
                        new com.taolesi.mcthread.experiment.menu.MenuSlotCopy(
                                "minecraft:cobblestone", 8, 64, true, 1L),
                        com.taolesi.mcthread.experiment.menu.MenuSlotCopy.EMPTY,
                        true, "main_hand", "up",
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        MCTRuntimeImpl.get().interactionOffload().submit(snap, com.taolesi.mcthread.experiment.menu.McMenuSession.INSTANCE);
        helper.succeedWhen(() -> {
            BlockEntity be = helper.getLevel().getBlockEntity(abs);
            helper.assertTrue(be instanceof net.minecraft.world.Container, "chest missing");
            helper.assertTrue(((net.minecraft.world.Container) be).getItem(0).is(Items.COBBLESTONE),
                    "chest slot 0 should be cobble");
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadHopperPushesIntoChest(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.HOPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HopperBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(1, 0, 0), Blocks.CHEST);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        net.minecraft.world.level.block.entity.HopperBlockEntity hopper =
                (net.minecraft.world.level.block.entity.HopperBlockEntity) helper.getLevel().getBlockEntity(abs);
        helper.assertTrue(hopper != null, "hopper BE missing");
        hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        java.util.Optional<com.taolesi.mcthread.experiment.hopper.HopperSnapshot> captured =
                com.taolesi.mcthread.experiment.hopper.McHopperSession.tryCapture(
                        helper.getLevel(), abs, helper.getLevel().getBlockState(abs), hopper);
        helper.assertTrue(captured.isPresent(), "hopper snapshot should capture chest dest");
        MCTRuntimeImpl.get().interactionOffload().submit(
                captured.get(), com.taolesi.mcthread.experiment.hopper.McHopperSession.INSTANCE);
        BlockPos chestAbs = helper.absolutePos(new BlockPos(1, 0, 0));
        helper.succeedWhen(() -> {
            BlockEntity chest = helper.getLevel().getBlockEntity(chestAbs);
            helper.assertTrue(chest instanceof net.minecraft.world.Container, "chest missing");
            helper.assertTrue(((net.minecraft.world.Container) chest).getItem(0).is(Items.COBBLESTONE),
                    "hopper should have pushed cobble into chest");
            helper.assertTrue(hopper.getItem(0).getCount() == 3, "hopper should have 3 cobble left");
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadFurnaceTickIgnites(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.FURNACE);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(abs);
        helper.assertTrue(furnace != null, "furnace BE missing");
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        furnace.setItem(1, new ItemStack(Items.COAL));
        java.util.Optional<com.taolesi.mcthread.experiment.furnace.FurnaceTickSnapshot> captured =
                com.taolesi.mcthread.experiment.furnace.McFurnaceTickSession.tryCapture(
                        helper.getLevel(), abs, helper.getLevel().getBlockState(abs), furnace);
        helper.assertTrue(captured.isPresent(), "furnace tick snapshot should capture fuel+ingredient");
        MCTRuntimeImpl.get().interactionOffload().submit(
                captured.get(), com.taolesi.mcthread.experiment.furnace.McFurnaceTickSession.INSTANCE);
        helper.succeedWhen(() -> {
            AbstractFurnaceBlockEntity live =
                    (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(abs);
            helper.assertTrue(live != null, "furnace BE missing after tick");
            helper.assertTrue(live.getItem(1).isEmpty(), "coal should be consumed");
            helper.assertTrue(helper.getBlockState(BlockPos.ZERO).getValue(
                    net.minecraft.world.level.block.AbstractFurnaceBlock.LIT), "LIT block state");
        });
    }

    @GameTest(template = "empty", batch = "mcthread.offload", timeoutTicks = 300)
    public static void offloadDropperInsertsIntoChest(final GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, Blocks.DROPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DropperBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(1, 0, 0), Blocks.CHEST);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        net.minecraft.world.level.block.entity.DropperBlockEntity dropper =
                (net.minecraft.world.level.block.entity.DropperBlockEntity) helper.getLevel().getBlockEntity(abs);
        helper.assertTrue(dropper != null, "dropper BE missing");
        dropper.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        java.util.Optional<com.taolesi.mcthread.experiment.dropper.DropperSnapshot> captured =
                com.taolesi.mcthread.experiment.dropper.McDropperSession.tryCapture(helper.getLevel(), abs);
        helper.assertTrue(captured.isPresent(), "dropper snapshot should capture chest dest");
        MCTRuntimeImpl.get().interactionOffload().submit(
                captured.get(), com.taolesi.mcthread.experiment.dropper.McDropperSession.INSTANCE);
        BlockPos chestAbs = helper.absolutePos(new BlockPos(1, 0, 0));
        helper.succeedWhen(() -> {
            BlockEntity chest = helper.getLevel().getBlockEntity(chestAbs);
            helper.assertTrue(chest instanceof net.minecraft.world.Container, "chest missing");
            helper.assertTrue(((net.minecraft.world.Container) chest).getItem(0).is(Items.COBBLESTONE),
                    "dropper should have inserted cobble into chest");
            helper.assertTrue(dropper.getItem(0).getCount() == 3, "dropper should have 3 cobble left");
        });
    }

    private static TillSnapshot tillSnapshot(GameTestHelper helper, BlockPos abs, String blockId) {
        return new TillSnapshot(
                System.nanoTime(),
                helper.getLevel().dimension().location().toString(),
                abs.getX(), abs.getY(), abs.getZ(),
                blockId,
                true,
                "minecraft:iron_hoe",
                true,
                true,
                true,
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }
}
