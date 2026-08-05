# MC Thread

基于 **Minecraft 1.20.1 / NeoForge**（Forge 兼容）的**通用运行时层优化模组**。

> 定位一句话：把主线程（Server Thread）上的模组计算与交互做**安全卸载、度量与通用优化**，作为不绑定任何特定模组的运行时底座。
> 与 [AE2-VM](https://github.com/TaoLe-si/AE2-VM) 各自独立：AE2-VM 负责合成规划域的纯计算加速，本模组负责游戏的线程化与模组交互优化，两者不互相依赖。

## 项目信息

| 项目 | 值 |
| --- | --- |
| 名称 | MC Thread（modId `mcthread`） |
| 目标版本 | Minecraft 1.20.1 |
| 平台 | NeoForge（Forge 兼容线，loader id `forge`） |
| 构建 | ModDevGradle Legacy `2.0.91`（`net.neoforged.moddev.legacyforge`） |
| 构件 | `net.minecraftforge:forge:1.20.1-47.1.3`（NeoForge 1.20.1 官方 MDK 同款） |
| 编译 JDK | 17（ModDevGradle 工具链需额外 JDK 21） |
| Gradle | 8.14.5（Wrapper） |
| 映射 | Mojang 官方 + Parchment 2023.09.03 |
| 许可证 | All Rights Reserved（暂定，见 `mods.toml`） |
| 仓库 | https://github.com/TaoLe-si/MC-Thread |

> **平台说明**：NeoForge 1.20.1 即 Forge 47.1.x 的兼容分支，loader id 为 `forge`，因此 `mods.toml` 依赖声明为 `forge [47.1.3,)`。同一 jar 可运行于 **NeoForge 47.1.x（至 47.1.106）** 与 **Forge 47.1.3+**（如 All of Create 使用的 47.4.2）。所有 Mixin 优化均针对 NeoForge/Forge 共有的通用类，不包含任何整合包相关代码。

## 核心特性

### M1 度量体系（先证明"哪里慢"）
- `/mcthread prof`：Tick 归因采样 Profiler（按模组/热点帧输出，含 avg/max tick、overrun、GC 时间、每模组预估 ms/tick）；
- `/mcthread replay`：按 tick 的事件流（TPS、tick 耗时、在线玩家、区块/实体变化、overrun），JSON 导出；
- `/mcthread bench`：固定 tick 窗口基准，供所有优化 A/B 对比。

### M2 运行时核心（计算与交互分离）
- `MCT.runtime()` 公共 API（`mcthread.api`，无硬依赖，未安装时自动降级 NOOP）；
- `ComputePool`：纯计算线程池（默认核数-1，守护线程）；
- `InteractionExecutor`：单线程 FIFO 交互执行器（可延后/可批处理工作，如异步 IO）；
- `Snapshot` / `Transaction` / `OptimisticRunner`：快照 → 计算 → 校验 → 提交/重试，事务部分失败自动回滚；
- `DeltaCache`：版本化增量缓存（O(Δ) 而非 O(N)）；
- `DomainAdapter` 框架：可选生态扩展（`synthetic-craft` 仅作参考演示）。

### M3 通用低层优化（Mixin + 交互线程）
- **能力查询快速路径**：Mixin 于 `CapabilityProvider`，按 (capability, side) 缓存已解析 `LazyOptional`；配置 `optimizations.capabilityCache` **默认关**，待 A/B ≥5% 门槛；
- **异步导出**：profile/replay/bench 的 JSON 写入移入交互线程（`optimizations.asyncExport` 默认开）；
- **游戏变化监测**：区块加载/卸载、实体加入/离开按 tick 计数，周期性写 `MCThread.Monitor` 日志，并进入 replay 事件流。

## 指令集（完整）

> 权限等级 2（单人需开启作弊，服务器需 OP）。所有命令输出同时写入 `logs/latest.log`。

| 命令 | 说明 | 示例 |
| --- | --- | --- |
| `/mcthread` | 根帮助（子命令列表） | `/mcthread` |
| `/mcthread prof start [intervalMs]` | 开始采样（间隔 1-1000ms，默认 5） | `/mcthread prof start 5` |
| `/mcthread prof stop` | 停止采样 | `/mcthread prof stop` |
| `/mcthread prof report [json]` | 输出/导出报告 | `/mcthread prof report json` |
| `/mcthread replay start <scenario>` | 开始事件流会话 | `/mcthread replay start mypack` |
| `/mcthread replay stop` | 结束会话 | `/mcthread replay stop` |
| `/mcthread replay export` | 导出 JSON（默认异步） | `/mcthread replay export` |
| `/mcthread bench start <ticks>` | 开始基准（10-72000，默认 600） | `/mcthread bench start 1200` |
| `/mcthread bench stop` | 提前结束并出报告 | `/mcthread bench stop` |
| `/mcthread bench report [json]` | 输出/导出基准报告 | `/mcthread bench report json` |
| `/mcthread monitor status` | 游戏变化监测（最近 tick 的区块/实体变化） | `/mcthread monitor status` |
| `/mcthread adapters list` | 适配器清单与可用状态 | `/mcthread adapters list` |
| `/mcthread runtime status` | 运行时总览（线程数、会话状态、适配器） | `/mcthread runtime status` |
| `/mcthread demo run <recipes> <iterations>` | 参考适配器端到端演示（快照/乐观工作流/Delta 缓存） | `/mcthread demo run 5000 100` |
| `/mcthread selftest` | 运行时自检 | `/mcthread selftest` |

## 配置项

配置文件：`config/mcthread-common.toml`（热重载）。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `runtime.enabled` | `true` | 运行时总开关 |
| `runtime.computeThreads` | `0` | 计算线程数，0=自动（核数-1） |
| `profiler.enabled` | `true` | Profiler 总开关 |
| `profiler.intervalTicks` | `100` | 采样聚合间隔 |
| `replay.enabled` | `true` | Replay 事件流开关 |
| `replay.maxEntries` | `100000` | 事件流环形缓冲上限 |
| `monitor.enabled` | `true` | 游戏变化监测开关 |
| `monitor.logIntervalTicks` | `100` | 监测日志间隔（0=关闭周期日志） |
| `optimizations.capabilityCache` | `false` | 能力查询快速路径（Mixin，**待 A/B 门槛**） |
| `optimizations.asyncExport` | `true` | JSON 导出走交互线程（异步） |

## 日志

日志按 logger 过滤（`logs/latest.log` / `logs/debug.log`）：

| Logger | 内容 |
| --- | --- |
| `[MC Thread]` | 模组生命周期（加载、服务器启动/停止、命令注册） |
| `MCThread.Monitor` | 周期性游戏变化计数 |
| `MCThread.Optimistic` | 乐观工作流重试/预算耗尽 |
| `MCThread.Transaction` | 事务部分失败回滚 |
| `MCThread.Adapters` | 适配器 attach/detach |
| `MCThread.Command` | 命令异常与导出日志 |

导出文件：`<服务器目录>/mcthread/`（开发环境 `run/mcthread/`）：`profile-*.json`、`bench-*.json`、`replay-*.json`。

## 快速开始

```powershell
.\gradlew.bat build                       # 构建并打包（build/libs/mcthread-0.1.0.jar）
.\gradlew.bat test                        # 单元测试（25 个，纯 JVM）
.\gradlew.bat runClient                   # NeoForge 开发环境客户端
.\gradlew.bat runServer                   # 开发环境服务器
.\gradlew.bat runGameTestServer           # 服务器内 GameTest
.\gradlew.bat copyToMods -PmodsDir="D:\...\mods"   # 复制 jar 到游戏 mods 目录
```

安装到客户端/服务器的完整步骤见 [安装与使用指南](docs/04-install-and-usage.md)。

## 目录结构

```text
src/main/java/com/taolesi/mcthread/
├── MCThread.java            # 主模组类（@Mod("mcthread")）
├── api/                     # 公共 API（第三方可编译接入，无 Minecraft 依赖设计）
├── runtime/                 # 计算池、交互执行器、快照/事务/乐观工作流、Delta 缓存、适配器注册表
├── adapter/                 # SyntheticCraftAdapter（参考演示）
├── profiler/                # TickProfiler、ReplayLogger、BenchmarkRunner、ModResolver
├── monitor/                 # GameChangeMonitor
├── command/                 # /mcthread 命令树
├── gametest/                # 服务器内集成测试
├── mixin/                   # CapabilityProviderMixin（能力查询快速路径）
└── config/                  # MCThreadConfig
src/main/resources/          # mcthread.mixins.json
src/main/templates/          # mods.toml / pack.mcmeta（Gradle 展开占位符）
src/test/java/               # 25 个 JUnit 单元测试
libs/                        # mixin-0.8.5 / gson-2.10.1（CN 网络 vendor，见 CONTRIBUTING）
docs/                        # 可行性分析、开发计划、测试方案、安装使用、研究笔记
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [01 可行性分析](docs/01-feasibility-analysis.md) | 方案论证、底层优化清单、最终架构 |
| [02 开发计划](docs/02-development-plan.md) | M0-M6 里程碑与验收标准 |
| [03 测试方案](docs/03-testing-plan.md) | L1-L7 分层测试、A/B 门槛、soak 方案 |
| [04 安装与使用](docs/04-install-and-usage.md) | 客户端/服务器安装、命令、日志解读 |
| [研究笔记摘要](docs/reference/research-notes-summary.md) | 《Minecraft Runtime Research Notes》摘要与全文 |

## 协同开发

环境准备、分支与提交规范、编码约定（线程纪律、Mixin 规则、A/B 门槛）、测试要求详见 [CONTRIBUTING.md](CONTRIBUTING.md)。变更记录见 [CHANGELOG.md](CHANGELOG.md)。
