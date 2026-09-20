package com.taolesi.threadtearer.gametest;

import com.taolesi.threadtearer.MCThread;
import com.taolesi.threadtearer.api.MCT;
import com.taolesi.threadtearer.api.MCTRuntime;
import com.taolesi.threadtearer.api.Snapshot;
import com.taolesi.threadtearer.api.Transaction;
import com.taolesi.threadtearer.config.MCThreadConfig;
import com.taolesi.threadtearer.monitor.PhaseTimings;
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

    /**
     * A direct {@code gameMode} call from the server thread stays inline.
     *
     * <p>This replaces an earlier version that asserted the opposite — that the
     * mixin would relocate the call and return {@code SUCCESS} — and that had
     * not actually executed since the 1.21.1 port, because the gametest
     * structure folder is {@code data/<ns>/structure/} here and the file sat in
     * {@code structures/}. Run against the current core it fails immediately:
     * vanilla answers {@code PASS} and nothing is offloaded.
     *
     * <p>The reason is the design, not a bug: the offloaded player-use path is
     * the packet handler ({@code ServerGamePacketListenerImpl}), which runs
     * before this. By the time {@code ServerPlayerGameMode.useItemOn} is
     * reached, the caller is a top-level server-thread one, and
     * {@code stealImpl}'s first gate deliberately leaves those alone — that is
     * the gate that stopped the watchdog catching the server thread inside a
     * {@code StackWalker} walk during chunk load. Pinned here so the gate is
     * not "fixed" away by a future reader.
     *
     * <p>Own batch: the {@code applied} counter is global, and the other tests
     * in the offload batch increment it from their own offloaded work, which
     * would make "nothing was applied" untestable.
     */
    @GameTest(template = "empty", batch = "threadtearer.serverthreadgate", timeoutTicks = 300)
    public static void directServerThreadUseItemOnStaysInline(final GameTestHelper helper) {
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
        helper.assertTrue(result != null, "vanilla must answer the call");
        MCThreadConfig.offloadPlayerUseItem = previous;
        helper.succeedWhen(() -> helper.assertTrue(
                MCTRuntimeImpl.get().interactionOffload().stats().applied() == appliedBefore,
                "a server-thread top-level useItemOn must run inline, not through the offload FIFO"));
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

    /**
     * The bookkeeping batch must actually land. A compute worker asks for
     * {@code blockEntityChanged} — the call Titanium's progress bar makes
     * thousands of times per tick — and the chunk must come out unsaved once
     * the tick-boundary flush has run, with no apply task per call.
     *
     * <p>Own batch: {@code APPLY_CALLS} is global, and the other offload tests
     * legitimately drive {@code runApply} from their own work, which would make
     * "no apply was needed" untestable.
     */
    @GameTest(template = "empty", batch = "threadtearer.bookkeeping", timeoutTicks = 300)
    public static void computeWorldBookkeepingLandsThroughTheBatch(final GameTestHelper helper) {
        final MCTRuntime rt = MCT.runtime();
        final BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        helper.setBlock(BlockPos.ZERO, Blocks.STONE);
        long recordedBefore = PhaseTimings.POSITION_UPDATES.sum();
        long appliesBefore = PhaseTimings.APPLY_CALLS.sum();
        // helper.setBlock marked the chunk unsaved; clear it so the assertion
        // below can only be satisfied by our own flush.
        helper.getLevel().getChunkAt(abs).setUnsaved(false);

        rt.submitCompute(() -> {
            helper.getLevel().blockEntityChanged(abs);
            return null;
        }).join();

        helper.succeedWhen(() -> {
            helper.assertTrue(PhaseTimings.POSITION_UPDATES.sum() > recordedBefore,
                    "a compute-thread blockEntityChanged must be recorded in the batch");
            helper.assertTrue(helper.getLevel().getChunkAt(abs).isUnsaved(),
                    "the batch flush must mark the chunk unsaved");
            helper.assertTrue(PhaseTimings.APPLY_CALLS.sum() == appliesBefore,
                    "batched bookkeeping must not go through runApply");
        });
    }
}
