package com.taolesi.mcthread.gametest;

import com.taolesi.mcthread.api.MCT;
import com.taolesi.mcthread.api.MCTRuntime;
import com.taolesi.mcthread.api.Snapshot;
import com.taolesi.mcthread.api.Transaction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-game integration tests for the runtime (run via runGameTestServer). */
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
}
