package com.taolesi.mcthread.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.taolesi.mcthread.adapter.SyntheticCraftAdapter;
import com.taolesi.mcthread.api.DomainAdapter;
import com.taolesi.mcthread.api.MCT;
import com.taolesi.mcthread.api.MCTRuntime;
import com.taolesi.mcthread.api.Snapshot;
import com.taolesi.mcthread.api.Transaction;
import com.taolesi.mcthread.monitor.GameChangeMonitor;
import com.taolesi.mcthread.profiler.ProfileReport;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code /mcthread} command tree:
 * prof start/stop/report, replay start/stop/export, bench start/stop/report,
 * runtime status, selftest.
 */
public final class MCTCommand {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Command");

    private MCTCommand() {
    }

    public static void register(final RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("mcthread")
                .executes(MCTCommand::help)
                .then(Commands.literal("prof")
                        .then(Commands.literal("start")
                                .executes(ctx -> profStart(ctx, 5))
                                .then(Commands.argument("intervalMs", IntegerArgumentType.integer(1, 1000))
                                        .executes(ctx -> profStart(ctx, IntegerArgumentType.getInteger(ctx, "intervalMs")))))
                        .then(Commands.literal("stop").executes(MCTCommand::profStop))
                        .then(Commands.literal("report")
                                .executes(ctx -> profReport(ctx, false))
                                .then(Commands.literal("json").executes(ctx -> profReport(ctx, true)))))
                .then(Commands.literal("replay")
                        .then(Commands.literal("start")
                                .executes(ctx -> replayStart(ctx, "default"))
                                .then(Commands.argument("scenario", StringArgumentType.greedyString())
                                        .executes(ctx -> replayStart(ctx, StringArgumentType.getString(ctx, "scenario")))))
                        .then(Commands.literal("stop").executes(MCTCommand::replayStop))
                        .then(Commands.literal("export").executes(MCTCommand::replayExport)))
                .then(Commands.literal("bench")
                        .then(Commands.literal("start")
                                .executes(ctx -> benchStart(ctx, "default", 600, 5))
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(10, 72000))
                                        .executes(ctx -> benchStart(ctx, "default", IntegerArgumentType.getInteger(ctx, "ticks"), 5))))
                        .then(Commands.literal("stop").executes(MCTCommand::benchStop))
                        .then(Commands.literal("report")
                                .executes(ctx -> benchReport(ctx, false))
                                .then(Commands.literal("json").executes(ctx -> benchReport(ctx, true)))))
                .then(Commands.literal("runtime").then(Commands.literal("status").executes(MCTCommand::runtimeStatus)))
                .then(Commands.literal("monitor").then(Commands.literal("status").executes(MCTCommand::monitorStatus)))
                .then(Commands.literal("adapters").then(Commands.literal("list").executes(MCTCommand::adapterList)))
                .then(Commands.literal("demo")
                        .then(Commands.literal("run")
                                .executes(ctx -> demoRun(ctx, 1000, 100))
                                .then(Commands.argument("recipes", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(ctx -> demoRun(ctx, IntegerArgumentType.getInteger(ctx, "recipes"), 100))
                                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, 1_000_000))
                                                .executes(ctx -> demoRun(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "recipes"),
                                                        IntegerArgumentType.getInteger(ctx, "iterations")))))))
                .then(Commands.literal("selftest").executes(MCTCommand::selftest)));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        success(ctx, "MC Thread 命令:\n"
                + "  /mcthread prof start [intervalMs] | stop | report [json]\n"
                + "  /mcthread replay start <scenario> | stop | export\n"
                + "  /mcthread bench start <ticks> | stop | report [json]\n"
                + "  /mcthread monitor status\n"
                + "  /mcthread adapters list\n"
                + "  /mcthread runtime status\n"
                + "  /mcthread demo run <recipes> <iterations>\n"
                + "  /mcthread selftest");
        return 1;
    }

    // ---- prof ----

    private static int profStart(CommandContext<CommandSourceStack> ctx, int intervalMs) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        if (!rt.profiler().startCapture(intervalMs)) {
            fail(ctx, "无法开始采集：可能已在采集，或服务器线程尚未就绪");
            return 0;
        }
        success(ctx, "MC Thread profiler 开始采集（间隔 " + intervalMs + "ms），用 /mcthread prof stop 结束");
        return 1;
    }

    private static int profStop(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        if (!rt.profiler().isCapturing()) {
            fail(ctx, "当前没有正在进行的采集");
            return 0;
        }
        rt.profiler().stopCapture();
        success(ctx, "采集已停止，用 /mcthread prof report 查看结果");
        return 1;
    }

    private static int profReport(CommandContext<CommandSourceStack> ctx, boolean json) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        ProfileReport report = rt.profiler().report("manual");
        if (json) {
            writeJson(ctx, report, "profile");
        } else {
            sendProfileSummary(ctx, report);
        }
        return 1;
    }

    // ---- replay ----

    private static int replayStart(CommandContext<CommandSourceStack> ctx, String scenario) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        if (!rt.replay().startSession(scenario)) {
            fail(ctx, "无法开始 replay（可能未启用，配置项 replay.enabled）");
            return 0;
        }
        success(ctx, "Replay 会话开始（场景: " + scenario + "），用 /mcthread replay stop 结束");
        return 1;
    }

    private static int replayStop(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        rt.replay().stopSession();
        success(ctx, "Replay 会话已结束（记录 " + rt.replay().entryCount() + " 条），用 /mcthread replay export 导出");
        return 1;
    }

    private static int replayExport(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        if (com.taolesi.mcthread.config.MCThreadConfig.asyncExport) {
            Path dir = exportDir(ctx);
            AtomicReference<Path> fileRef = new AtomicReference<>();
            rt.scheduleInteraction(() -> fileRef.set(rt.replay().export(dir)))
                    .whenComplete((ignored, error) -> {
                        MinecraftServer server = rt.server();
                        Runnable feedback = () -> {
                            if (error != null) {
                                fail(ctx, "导出失败: " + error);
                            } else {
                                success(ctx, "Replay 已导出: " + fileRef.get().toAbsolutePath());
                            }
                        };
                        if (server != null) {
                            server.execute(feedback);
                        } else {
                            feedback.run();
                        }
                    });
            success(ctx, "导出任务已提交（异步）");
            return 1;
        }
        try {
            Path file = rt.replay().export(exportDir(ctx));
            success(ctx, "Replay 已导出: " + file.toAbsolutePath());
            return 1;
        } catch (IOException e) {
            fail(ctx, "导出失败: " + e.getMessage());
            return 0;
        }
    }

    // ---- bench ----

    private static int benchStart(CommandContext<CommandSourceStack> ctx, String scenario, int ticks, int intervalMs) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        if (!rt.benchmark().start(scenario, ticks, intervalMs)) {
            fail(ctx, "无法开始基准测试（可能已在运行）");
            return 0;
        }
        success(ctx, "基准测试开始（场景: " + scenario + ", " + ticks + " ticks），结束自动出报告");
        return 1;
    }

    private static int benchStop(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        ProfileReport report = rt.benchmark().stop();
        if (report == null) {
            fail(ctx, "没有正在运行的基准测试");
            return 0;
        }
        sendProfileSummary(ctx, report);
        return 1;
    }

    private static int benchReport(CommandContext<CommandSourceStack> ctx, boolean json) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        ProfileReport report = rt.benchmark().lastReport();
        if (report == null) {
            fail(ctx, "没有可用的基准报告");
            return 0;
        }
        if (json) {
            writeJson(ctx, report, "bench");
        } else {
            sendProfileSummary(ctx, report);
        }
        return 1;
    }

    // ---- runtime ----

    private static int runtimeStatus(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        List<DomainAdapter> adapters = rt.adapterRegistry().attachedAdapters();
        GameChangeMonitor.TickDelta delta = rt.monitor().lastDelta();
        String status = "MC Thread 运行时状态\n"
                + "  runtime: 已安装\n"
                + "  计算线程数: " + rt.computePoolSize() + "\n"
                + "  当前是否服务器线程: " + rt.isServerThread() + "\n"
                + "  profiler 采集中: " + rt.profiler().isCapturing() + "\n"
                + "  replay 会话: " + rt.replay().isSessionOpen() + "（已记录 " + rt.replay().entryCount() + " 条）\n"
                + "  benchmark 运行中: " + rt.benchmark().isRunning() + "\n"
                + "  最近 tick 变化: chunkLoads=" + delta.chunkLoads()
                + " chunkUnloads=" + delta.chunkUnloads()
                + " entityJoins=" + delta.entityJoins()
                + " entityLeaves=" + delta.entityLeaves() + "\n"
                + "  已挂载适配器: " + adapters.stream().map(DomainAdapter::domainId).toList();
        success(ctx, status);
        return 1;
    }

    private static int monitorStatus(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        GameChangeMonitor.TickDelta delta = rt.monitor().lastDelta();
        success(ctx, "MC Thread 游戏变化监测\n"
                + "  启用: " + com.taolesi.mcthread.config.MCThreadConfig.monitorEnabled + "\n"
                + "  日志间隔(ticks): " + com.taolesi.mcthread.config.MCThreadConfig.monitorLogIntervalTicks + "\n"
                + "  最近 tick=" + delta.tick()
                + " chunkLoads=" + delta.chunkLoads()
                + " chunkUnloads=" + delta.chunkUnloads()
                + " entityJoins=" + delta.entityJoins()
                + " entityLeaves=" + delta.entityLeaves());
        return 1;
    }

    private static int adapterList(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        StringBuilder sb = new StringBuilder("MC Thread adapters:\n");
        for (DomainAdapter adapter : rt.adapterRegistry().registeredAdapters()) {
            boolean available = rt.adapterRegistry().isAvailable(adapter);
            sb.append("  ").append(adapter.domainId())
                    .append(" target=").append(adapter.targetModId().isBlank() ? "(none)" : adapter.targetModId())
                    .append(" 状态=").append(available ? "可用" : "未加载").append('\n');
        }
        success(ctx, sb.toString());
        return 1;
    }

    private static int demoRun(CommandContext<CommandSourceStack> ctx, int recipes, int iterations) {
        MCTRuntimeImpl rt = runtime(ctx);
        if (rt == null) {
            return 0;
        }
        DomainAdapter adapter = rt.adapterRegistry().byDomain(SyntheticCraftAdapter.DOMAIN_ID).orElse(null);
        if (!(adapter instanceof SyntheticCraftAdapter craft)) {
            fail(ctx, "参考适配器不可用");
            return 0;
        }
        MCTRuntime api = MCT.runtime();
        long start = System.nanoTime();
        // IMPORTANT: never join() this future on the server thread - the optimistic
        // workflow's validate/commit stages are scheduled back onto the server thread,
        // so blocking would deadlock the server. Report via an async callback instead.
        api.optimistic(
                () -> api.snapshot(craft.stateVersion(), craft.stateVersion()),
                snapshot -> craft.plan(recipes, iterations),
                (snapshot, value) -> snapshot.isValid(craft.stateVersion()),
                value -> {
                },
                3).whenComplete((result, error) -> {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                    MinecraftServer server = rt.server();
                    Runnable feedback = () -> {
                        if (error != null) {
                            fail(ctx, "demo 失败: " + error);
                            LOGGER.warn("demo run failed: recipes={} iterations={}", recipes, iterations, error);
                        } else {
                            success(ctx, "demo [" + craft.domainId() + "] recipes=" + recipes
                                    + " iterations=" + iterations
                                    + " result=" + result
                                    + " 耗时=" + elapsedMs + "ms"
                                    + " cache(hits=" + craft.cacheHits() + ", misses=" + craft.cacheMisses()
                                    + ", size=" + craft.cacheSize() + ")");
                        }
                    };
                    if (server != null) {
                        server.execute(feedback);
                    } else {
                        feedback.run();
                    }
                });
        return 1;
    }

    // ---- selftest ----

    private static int selftest(CommandContext<CommandSourceStack> ctx) {
        MCTRuntime rt = MCT.runtime();
        List<String> failures = new ArrayList<>();
        try {
            Snapshot<Integer> snap = rt.snapshot(40, 1L);
            int computed = rt.submitCompute(() -> snap.get() + 2).join();
            if (computed != 42) {
                failures.add("compute result = " + computed);
            }

            int[] target = {0};
            Transaction<int[]> tx = rt.beginTransaction();
            tx.addChange(t -> t[0] += computed, t -> t[0] -= computed);
            tx.commit(target);
            if (target[0] != 42 || !tx.isTerminated()) {
                failures.add("commit result = " + target[0] + ", terminated = " + tx.isTerminated());
            }

            Transaction<int[]> tx2 = rt.beginTransaction();
            tx2.addChange(t -> t[0] += 1, t -> t[0] -= 1);
            tx2.rollback();
            if (target[0] != 42 || !tx2.isTerminated()) {
                failures.add("rollback failed");
            }

            List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
            rt.scheduleInteraction(() -> order.add("a")).join();
            rt.scheduleInteraction(() -> order.add("b")).join();
            if (!order.equals(List.of("a", "b"))) {
                failures.add("interaction order = " + order);
            }
        } catch (Throwable t) {
            failures.add(t.toString());
        }
        if (failures.isEmpty()) {
            success(ctx, "自检通过：compute/snapshot/transaction/interaction 全部正常");
            return 1;
        }
        fail(ctx, "自检失败: " + String.join("; ", failures));
        return 0;
    }

    // ---- helpers ----

    private static MCTRuntimeImpl runtime(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        if (rt == null) {
            fail(ctx, "MC Thread 运行时尚未初始化");
            return null;
        }
        return rt;
    }

    private static Path exportDir(CommandContext<CommandSourceStack> ctx) {
        MCTRuntimeImpl rt = MCTRuntimeImpl.get();
        if (rt != null && rt.server() != null) {
            return rt.server().getServerDirectory().toPath().resolve("mcthread");
        }
        return Path.of("mcthread");
    }

    private static void writeJson(CommandContext<CommandSourceStack> ctx, Object data, String prefix) {
        try {
            Path dir = exportDir(ctx);
            Files.createDirectories(dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            Path file = dir.resolve(prefix + "-" + stamp + ".json");
            String json = GSON.toJson(data);
            if (com.taolesi.mcthread.config.MCThreadConfig.asyncExport) {
                MCTRuntimeImpl rt = MCTRuntimeImpl.get();
                rt.scheduleInteraction(() -> Files.writeString(file, json))
                        .whenComplete((ignored, error) -> {
                            MinecraftServer server = rt.server();
                            Runnable feedback = () -> {
                                if (error != null) {
                                    fail(ctx, "导出失败: " + error);
                                } else {
                                    success(ctx, "已导出: " + file.toAbsolutePath());
                                }
                            };
                            if (server != null) {
                                server.execute(feedback);
                            } else {
                                feedback.run();
                            }
                        });
                success(ctx, "导出任务已提交（异步）: " + file.toAbsolutePath());
            } else {
                Files.writeString(file, json);
                success(ctx, "已导出: " + file.toAbsolutePath());
            }
        } catch (IOException e) {
            fail(ctx, "导出失败: " + e.getMessage());
        }
    }

    private static void sendProfileSummary(CommandContext<CommandSourceStack> ctx, ProfileReport r) {
        CommandSourceStack src = ctx.getSource();
        StringBuilder sb = new StringBuilder();
        sb.append("MC Thread profile [").append(r.scenario).append("]")
                .append(" 采样=").append(r.sampleCount)
                .append(" 时长=").append(r.captureDurationMs).append("ms\n");
        sb.append(String.format("tick avg=%.2fms max=%dms overrun=%d/%d gc=%dms%n",
                r.tickAvgMs, r.tickMaxMs, r.overrunTicks, r.tickCount, r.gcTimeMs));
        int n = Math.min(10, r.mods.size());
        for (int i = 0; i < n; i++) {
            ProfileReport.ModStat m = r.mods.get(i);
            sb.append(String.format("  %s: %d (%.1f%%) ~%.2fms/tick%n",
                    m.modId, m.samples, m.pct, m.estTickMs));
        }
        if (!r.hotspots.isEmpty()) {
            sb.append("top frames:\n");
            r.hotspots.stream().limit(5)
                    .forEach(f -> sb.append("  ").append(f.frame).append(" x").append(f.samples).append('\n'));
        }
        success(ctx, sb.toString());
    }

    private static void success(CommandContext<CommandSourceStack> ctx, String message) {
        // allowLogging=true so command output also lands in logs/latest.log for diagnosis.
        ctx.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.AQUA), true);
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
