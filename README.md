# MC Thread

基于 Minecraft **1.20.1 NeoForge**（Forge 兼容）的运行时层优化模组。

## 当前功能（v0.1，M1+M2）

- **度量体系（M1）**：
  - `/mcthread prof start|stop|report [json]`：Tick 归因采样 Profiler（按模组/热点帧输出，含 avg/max tick、overrun、GC）；
  - `/mcthread replay start|stop|export`：按 tick 的事件流日志（TPS、tick 耗时、在线玩家数、overrun），导出 JSON；
  - `/mcthread bench start|stop|report [json]`：固定 tick 窗口基准测试，供后续优化 A/B。
- **游戏变化监测**：
  - 区块加载/卸载、实体生成/离开按 tick 计数，周期性写入 `MCThread.Monitor` 日志；
  - 计数同时进入 replay 事件流，便于把 Tick 尖峰与真实世界变化对齐；
  - `/mcthread monitor status` 查看最近 tick 变化。
- **M3 通用低层优化（首批）**：
  - 能力查询快速路径（Mixin 于 Forge `CapabilityProvider`，`optimizations.capabilityCache`，**默认关**，待 A/B ≥5% 门槛）；
  - 异步导出（`optimizations.asyncExport`，默认开）：profile/replay/bench 的 JSON 写入移入交互线程，不再阻塞服务器线程；
  - Mixin 基础设施已启用（`mcthread.mixins.json` + refmap），后续交互体系优化以此为基础扩展。
- **运行时核心（M2）**：
  - `mcthread.api` 公共 API（`MCT.runtime()`：计算池提交、单线程交互执行器、快照、事务、乐观并发工作流）；
  - 快照 → 计算线程 → 校验 → 服务器线程提交的完整原语，事务部分失败自动回滚；
  - `MCT.runtime().optimistic(...)`：乐观并发（Snapshot → Compute → Validate → Commit/Retry，快照过期自动重试）；
  - Delta 增量缓存（版本化脏标记，O(Δ) 而非 O(N) 的重复计算消除）；
  - Domain Adapter 框架：`/mcthread adapters list` 查看可用性，参考适配器 `synthetic-craft` 通过 `/mcthread demo run` 端到端演示；
  - `/mcthread runtime status`、`/mcthread selftest`。
- **测试**：23 个纯 JVM 单元测试 + 2 个服务器内 GameTest，详见 [测试方案](docs/03-testing-plan.md)。

## 项目目标

Minecraft 服务器在加载大量模组后，主线程（Server Thread）承担了几乎全部的世界状态交互：模组事件分发、方块实体/实体 Tick、能力查询、合成规划、存储查询、网络处理等。本模组的目标不是替换游戏逻辑，而是：

1. **度量先行**：建立 per-mod / per-event / per-BE 的 Tick 归因 Profiler，配合 Replay 事件流与可重复 Benchmark，先找到真正的热点；
2. **计算与交互分离**：提供一套“快照 → 计算线程 → 校验 → 主线程提交”的运行时 API，把高计算、低副作用的工作移出主线程；
3. **通用低层优化**：在不针对单一模组的前提下，对生态通用热路径（如能力查询）做可开关、可回滚的优化；
4. **通用性与可选扩展**：核心路线是通用运行时优化（不绑定任何特定模组）；`Domain Adapter` 框架作为可选的生态扩展保留，供社区模组自行接入。

详细论证见 [可行性分析](docs/01-feasibility-analysis.md)，分阶段计划见 [开发计划](docs/02-development-plan.md)。

## 技术栈

| 项目        | 版本                                                                                                                   |
| --------- | -------------------------------------------------------------------------------------------------------------------- |
| Minecraft | 1.20.1                                                                                                               |
| NeoForge  | 1.20.1（Forge 兼容线；官方 MDK 构件 `net.minecraftforge:forge:1.20.1-47.1.3`，可运行于 Forge 47.1.3+ 与 NeoForge 47.1.x 至 47.1.106） |
| Java      | 17                                                                                                                   |
| Gradle    | 8.14.5（Wrapper）                                                                                                      |
| 构建插件      | ModDevGradle Legacy `2.0.91`（`net.neoforged.moddev.legacyforge`）                                                     |
| 映射        | Mojang 官方 + Parchment 2023.09.03                                                                                     |

> **平台说明**：本项目为 **NeoForge 1.20.1** 模组（官方 NeoForge MDK 构建）。NeoForge 1.20.1 即 Forge 47.1.x 的兼容分支，loader id 为 `forge`，因此 `mods.toml` 声明依赖 `forge [47.1.3,)`；同一 jar 可运行于 NeoForge 47.1.x（至 47.1.106）与 Forge 47.1.3+（如 All of Create 使用的 47.4.2）。所有 Mixin 优化均针对 NeoForge/Forge 共有的通用类，不包含任何整合包相关代码。

## 环境要求

- JDK 21
- Git

## 构建与运行

```powershell
.\gradlew.bat build          # 编译并打包 jar（build/libs/mcthread-0.1.0.jar）
.\gradlew.bat copyToMods -PmodsDir="D:\...\mods"   # 复制 jar 到游戏 mods 目录
.\gradlew.bat runClient      # 启动客户端（自动生成 run/ 目录）
.\gradlew.bat runServer      # 启动专用服务器
.\gradlew.bat runGameTestServer  # 运行 GameTest
.\gradlew.bat runData        # 运行数据生成器
```

> 首次构建会下载 Gradle 发行版与 NeoForge 依赖，需要联网，耗时较长。

## 目录结构

```text
src/main/java/com/taolesi/mcthread/
├── MCThread.java            # 主模组类（@Mod("mcthread")）
├── api/                     # 公共 API（第三方模组可编译接入）
├── runtime/                 # 计算池、交互执行器、快照/事务实现
├── adapter/                 # Domain Adapter（参考适配器）
├── profiler/                # Tick 采样 Profiler、Replay、Benchmark
├── command/                 # /mcthread 命令
├── gametest/                # 服务器内集成测试
└── config/
    └── MCThreadConfig.java  # Forge 通用配置（运行时/Profiler/Replay/优化开关）
src/test/java/               # 纯 JVM 单元测试（JUnit 5）
src/main/templates/          # 经 Gradle 展开占位符的 mods.toml、pack.mcmeta
docs/                        # 可行性分析、开发计划、研究笔记摘要
```

安装到游戏的完整步骤与命令/日志说明见 [安装与使用指南](docs/04-install-and-usage.md)。

## Git

本仓库已初始化并绑定远端：

```text
origin  https://github.com/TaoLe-si/MC-Thread.git
```

按约定暂未提交任何内容。
