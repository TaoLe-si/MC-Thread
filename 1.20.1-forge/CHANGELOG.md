# Changelog

## Unreleased

- 仓库按版本拆分：当前工程位于 `1.20.1-forge/`，远程分支 `1.20.1-forge`。
- README 重写：说明 Server / Interaction / Compute 三条路径，以及方块实体 Tick 如何使用多核。

## [0.1.0] - 2026-08-06

### 新增

- **实验（方案 A 回访）**：把原版游戏逻辑入口整段搬到交互线程（玩家/物品/方块/实体 tick、世界模拟、爆炸/活塞、包处理、命令、菜单、BE ticker），使其它模组打在这些方法上的 Mixin 一并执行。`ServerLevel.tick` 在 GameTest 服上跳过。交互线程按条打印 `MCThread.Interaction` 日志。快照规则表仍用于单测。配置 `experiments.offloadPlayerUseItem` 默认开。见 `docs/05-interaction-offload-experiment.md`。
- **M1 度量体系**
  - Tick 归因采样 Profiler（按模组/热点帧、avg/max tick、overrun、GC、每模组预估 ms/tick）；
  - Replay 事件流（TPS、tick 耗时、在线玩家、区块/实体变化、overrun）与 JSON 导出；
  - 固定窗口 Benchmark 命令。
- **M2 运行时核心**
  - `mcthread.api` 公共 API（`MCT.runtime()`，未安装时 NOOP 降级）；
  - `ComputePool`（纯计算卸载）与 `InteractionExecutor`（单线程 FIFO 交互）；
  - `Snapshot` / `Transaction` / `OptimisticRunner`（快照→计算→校验→提交/重试，事务回滚）；
  - `DeltaCache` 版本化增量缓存；
  - `DomainAdapter` 框架 + 参考适配器 `synthetic-craft`。
- **M3 通用低层优化（首批）**
  - 能力查询快速路径（Mixin 于 `CapabilityProvider`，`optimizations.capabilityCache`，默认关）；
  - 异步导出（JSON 写入移入交互线程，`optimizations.asyncExport`，默认开）；
  - 游戏变化监测（`MCThread.Monitor`）。
- **游戏内命令**：`/mcthread prof / replay / bench / monitor / adapters / runtime / demo / experiment / selftest`。
- **测试**：JUnit 单元测试（含快照适配器 + `relocateVanilla`）+ GameTest（含 Mixin 搬迁路径）。

### 修复

- 修复 `/mcthread demo run` 在服务器线程 `join()` 导致死锁的问题（改为异步回调，新增回归 GameTest）。

### 工程

- Gradle Wrapper 8.14.5（腾讯云镜像）；阿里云 Maven Central 镜像；`libs/` vendor mixin/gson。
