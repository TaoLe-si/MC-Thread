# Changelog

## [0.1.0] - 2026-08-06

### 新增

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
- **游戏内命令**：`/mcthread prof / replay / bench / monitor / adapters / runtime / demo / selftest`。
- **测试**：25 个 JUnit 单元测试 + 3 个 GameTest（含乐观工作流异步回归用例）。

### 修复

- 修复 `/mcthread demo run` 在服务器线程 `join()` 导致死锁的问题（改为异步回调，新增回归 GameTest）。

### 工程

- Gradle Wrapper 8.14.5（腾讯云镜像）；阿里云 Maven Central 镜像；`libs/` vendor mixin/gson。
