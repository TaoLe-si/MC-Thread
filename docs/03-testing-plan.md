# MC Thread 详细测试方案

> 版本：v0.1（对应 M1 度量体系 + M2 运行时核心）
> 原则：正确性优先于性能（Law-004）、Profiler/Replay/Benchmark 先于优化（ADR-007/008/009）、每项优化必须可重复验证（Rule-008）。

## 1. 测试目标

1. 证明运行时原语（快照/事务/线程纪律）在单机与服务器内**行为正确**；
2. 证明度量体系（Profiler/Replay/Benchmark）**可重复、可比较**；
3. 为后续每项优化建立 **A/B 门槛（≥5% 提升才保留）** 的客观依据；
4. 用确定性手段排除 Heisenbug（幽灵 BUG）风险。

## 2. 测试分层总览

| 层级 | 执行环境 | 工具/命令 | 覆盖内容 | 频率 |
| --- | --- | --- | --- | --- |
| L1 单元测试 | JVM，无需游戏 | `gradlew test`（JUnit 5） | 计算池、交互执行器、快照、事务 | 每次提交 |
| L2 服务器内 GameTest | 开发环境 GameTestServer | `gradlew runGameTestServer` | 服务器线程纪律、快照-计算-校验-提交闭环、FIFO 顺序 | 每次合入前 |
| L3 命令级自检 | 游戏内 | `/mcthread selftest` | 运行时可观测行为汇总 | 冒烟/人工 |
| L4 基准 A/B | 固定种子 + 固定操作序列 | `/mcthread bench` + replay | 优化前后 Tick 耗时对比 | 每个优化项 |
| L5 长跑 soak | 专用服务器 100h+ | TPS/GC/日志监控 | 稳定性、内存、Heisenbug | 发布前 |
| L6 兼容矩阵 | 客户端/单机/服务器 × 模组组合 | 手动 + 脚本 | 共存、冲突、降级 | 每个里程碑 |
| L7 发布回归 | 与 L1-L6 全量 | CI | 发布候选 | 发布前 |

## 3. L1 单元测试清单

测试文件：`src/test/java/com/taolesi/mcthread/runtime/`

| 用例 | 验证点 | 通过标准 |
| --- | --- | --- |
| ComputePool.computesValue | 计算任务返回值 | 结果正确 |
| ComputePool.propagatesFailure | 异常传播 | 包装为 `CompletionException`，原因保留 |
| ComputePool.threadsAreDaemonAndNamed | 线程属性 | 守护线程、名称前缀 `MCT-Compute-` |
| InteractionExecutor.fifoOrder | 单线程 FIFO | 任务按提交顺序执行 |
| InteractionExecutor.failureDoesNotKillExecutor | 异常隔离 | 单任务失败后执行器仍可用 |
| SnapshotTest.holdsValueAndVersion | 快照不可变 + 版本 | `get()`/`version()` 正确，`isValid` 按版本判定 |
| Transaction.commitAppliesInOrder | 提交顺序 | 按添加顺序应用 |
| Transaction.commitRejectedOffServerThread | 线程纪律 | 非服务器线程提交抛 `IllegalStateException`，事务保持 OPEN |
| Transaction.rollbackDiscardsChanges | 回滚 | 已终止，不能再提交/添加变更 |
| Transaction.partialFailureRollsBackAppliedChanges | 部分更新防护 | 中间变更失败时已应用变更全部撤销，无半成品状态 |
| Transaction.addChangeAfterTerminationRejected | 状态机 | 终止后禁止新增变更 |
| OptimisticRunner.successPath | 乐观工作流成功路径 | 计算在工作线程、提交在服务器线程，结果正确 |
| OptimisticRunner.staleSnapshotRetriesUntilValid | 过期重试 | 版本变化后自动重试并成功，重试次数正确 |
| OptimisticRunner.retryBudgetExhaustedThrowsStaleSnapshot | 重试预算 | 超过预算抛 `StaleSnapshotException`，且 commit 从未执行 |
| OptimisticRunner.computeFailurePropagates | 计算异常 | 计算失败原样传播，不触发提交 |
| DeltaCache.hitWhenVersionStable | Delta 命中 | 版本不变时复用结果，compute 只执行一次 |
| DeltaCache.recomputeOnVersionChange | 版本失效 | 版本变化强制重算 |
| DeltaCache.markDirtyForcesRecompute | 脏标记 | 外部脏信号强制失效 |
| DeltaCache.clearInvalidatesAll | 全量失效 | clear 后全部重算 |
| AdapterRegistry.registersAndFinds | 注册/查找 | 重复 domainId 拒绝，可按 ID 查找 |
| AdapterRegistry.attachAndDetachFollowTargetModAvailability | 挂载生命周期 | 目标模组加载→attach，卸载→detach，无依赖域始终可用 |
| SyntheticCraftAdapter.computePlanIsDeterministic | 纯计算 | 相同输入相同输出 |
| SyntheticCraftAdapter.planIsCachedUntilVersionChanges | 适配器缓存 | 版本不变缓存命中，版本变化重算 |
| ReplayLogger.exportWritesJsonEntries | Replay 导出 | 会话内 tick 正确写入 JSON（含 overrun 标记） |
| ReplayLogger.recordBeforeSessionIsIgnored | 会话边界 | 会话开始前的记录不进入导出 |

**运行**：`gradlew test`（纯 JVM，不启动游戏）。

## 4. L2 服务器内 GameTest 清单

测试类：`com.taolesi.mcthread.gametest.MCTGameTests`，注册于 `RegisterGameTestsEvent`。

| 用例 | 验证点 |
| --- | --- |
| snapshotComputeValidateCommit | 服务器线程建快照 → 计算线程执行（断言不在服务器线程）→ 服务器线程提交事务 → 结果正确且事务终止 |
| interactionExecutorFifo | 交互执行器在两个任务间保持 FIFO 顺序 |

**运行**：`gradlew runGameTestServer`（自动创建 `run/` 目录，全部通过后退出）。

后续 M2 扩展用例（规划）：
- 快照过期校验：版本变化后 `isValid` 返回 false，提交被拒；
- 越权写拒绝：在工作线程调用 `commit` 抛异常；
- 事务部分失败回滚（与 L1 相同场景的服务器内复现）；
- 高频压力：同一 tick 内提交 1000 个事务，验证顺序与回滚正确。

## 5. L4 基准与 A/B 测试方案

### 5.1 固定场景

- 固定种子、固定存档、固定操作序列（参考笔记 Small/Medium/Large Grid、Craft Storm 思路）；
- 每次对比必须使用**同一存档副本**，避免世界状态漂移；
- 场景命名写入报告（`scenario` 字段），同一场景才可比较。

### 5.2 测量流程

```text
/mcthread bench start <ticks>     # 默认 600 ticks
# 运行固定操作序列
/mcthread bench report [json]     # avg/max tick、overrun、按 mod 采样占比、GC 时间
/mcthread replay export           # 导出事件流用于回归复现
```

### 5.6 游戏变化监测验证

- 触发固定事件（生成 10 只生物、放置/破坏区块边界的方块），`/mcthread monitor status` 与 `MCThread.Monitor` 日志中的计数应与操作一致；
- replay JSON 中同一 tick 的 `entityJoins`/`chunkLoads` 字段与监测计数一致；
- 该数据用于将 Tick 尖峰与真实世界变化对齐（L5 soak 的核心关联手段）。

### 5.7 M3 优化验证

- 能力查询快速路径（`optimizations.capabilityCache`，默认关）：开启前后跑同一 `bench` 场景，Tick 平均耗时提升 ≥5% 且无行为差异才允许默认开启；重点回归能力动态注册/失效场景（动态附加能力的模组）；
- 异步导出（`optimizations.asyncExport`，默认开）：`replay export` / `report json` 返回"已提交（异步）"并在完成后提示路径；服务器线程不应因导出出现卡顿。

### 5.3 指标

- 主线程 Tick 平均/最大耗时（ms）；
- Overrun（>50ms）Tick 数；
- 每 mod 采样占比（采样法，相对热度）；
- GC 总耗时；
- 采样数/时长（保证样本量足够：建议 ≥1000 采样）。

### 5.4 误差控制

- 同一场景重复 3 次，取中位数；
- 两次运行误差 >10% 时判定环境不稳定，不采信；
- 对比时前后间隔内不得改变世界状态（同存档、同操作）。

### 5.5 优化门槛（强制）

- 每项优化开启前后 A/B：**Tick 平均耗时提升 ≥5% 且无行为差异**才允许默认开启；
- 未达门槛的优化保持默认关闭（配置项 `optimizations.*`），保留代码与测试，记录回滚理由。

## 6. L5 长跑 soak 方案

- 专用服务器，目标模组包（如 All of Create），模拟在线玩家行为脚本；
- 时长：≥100 小时；
- 监控：TPS 曲线、GC 日志（`-Xlog:gc`）、堆使用、`/mcthread runtime status` 定期采样；
- 通过标准：无崩溃、无物品丢失/重复、无 Tick 长时间停滞、内存曲线平稳；
- 回归：soak 期间发现的问题先用 replay 事件流 + 快照复现，再修复（Replay 先于调试）。

## 7. L6 兼容性矩阵

| 环境 | 验证项 |
| --- | --- |
| 纯客户端（单人） | 模组加载、命令可用、无副作用 |
| 单机局域网（Open to LAN） | 集成服务器运行时正常 |
| 专用服务器 | 全部命令、GameTest、长跑 |
| Forge 47.1.3+ | 与 NeoForge 同构件，安装即兼容 |
| NeoForge 47.1.x（至 47.1.106） | 加载、依赖范围 `[47.1.3,)` 满足 |
| 与常见优化/工具模组共存 | Mixin 注入点冲突检测、崩溃报告可定位 |
| 无任何其他模组 | 功能零依赖、降级安全 |

## 8. L7 发布回归与 CI

```text
gradlew test                  # L1
gradlew runGameTestServer     # L2
gradlew build                 # 打包
```

- CI 每次推送执行 L1 + build；
- 合入主分支前执行 L2；
- 发布候选执行 L5（至少 24h 冒烟）+ L6 矩阵；
- 所有报告（profile/bench/replay JSON）归档为发布附件。

## 9. 缺陷追踪与回归策略

- 任何行为差异：先关闭对应优化开关，再提交 issue；
- 每个 bug 必须附带复现路径（replay 文件或最小操作序列）；
- 修复后必须新增回归用例到对应层级，再跑全量 L1/L2。

## 10. 当前状态

- L1：23 个用例（见第 3 节）——已实现并通过；
- L2：2 个用例——已实现，待服务器内执行；
- L3：`/mcthread selftest`——已实现；
- L4：bench/replay 命令——已实现，基准场景需在目标模组包上建立；
- L5/L6/L7：随里程碑推进。

执行结果见 [执行记录](#执行记录)（构建与测试输出将回填至此）。
