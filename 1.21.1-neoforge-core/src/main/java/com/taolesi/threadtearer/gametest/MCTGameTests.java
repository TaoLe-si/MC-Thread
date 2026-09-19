package com.taolesi.threadtearer.gametest;

import com.taolesi.threadtearer.MCThread;
import com.taolesi.threadtearer.api.MCT;
import com.taolesi.threadtearer.api.MCTRuntime;
import com.taolesi.threadtearer.api.Snapshot;
import com.taolesi.threadtearer.api.Transaction;
import com.taolesi.threadtearer.config.MCThreadConfig;
import com.taolesi.threadtearer.runtime.MCTRuntimeImpl;
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

/**
 * In-game integration tests for the baseline runtime (run via
 * runGameTestServer): interaction thread separation plus the raw compute
 * submit API. No third-party mod and no automatic ticker offload is covered
 * here; addon mods test their own tick offload.
 */
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

    @GameTest(template = "empty", batch = "threadtearer.offload", timeoutTicks = 300)
    public static void computeSubmitsWorldInteraction(final GameTestHelper helper) {
        final MCTRuntime rt = MCT.runtime();
        final AtomicBoolean appliedOnServerThread = new AtomicBoolean();
        rt.submitCompute(() -> rt.scheduleWorldInteraction("gametest.compute.setblock", () -> {
            appliedOnServerThread.set(rt.isServerThread());
            helper.getLevel().setBlock(
                    helper.absolutePos(BlockPos.ZERO), Blocks.STONE.defaultBlockState(), 3);
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(BlockPos.ZERO).is(Blocks.STONE),
                    "compute-submitted world interaction did not land");
            helper.assertTrue(appliedOnServerThread.get(),
                    "world interaction must apply on the server thread");
        });
    }

    @GameTest(template = "empty", batch = "threadtearer.offload", timeoutTicks = 300)
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

    @GameTest(template = "empty", batch = "threadtearer.offload", timeoutTicks = 300)
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
}
