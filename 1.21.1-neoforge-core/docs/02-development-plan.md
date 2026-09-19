# 线程撕裂者 开发计划

> 版本：v0.1（框架阶段）
> 原则：Profiler 先于线程（ADR-007）、Replay 先于 Runtime（ADR-008）、Benchmark 先于优化（ADR-009）、正确性优先于性能（Law-004）。

## 0. 阶段总览

| 阶段 | 名称 | 目标 | 关键产出 | 验证方式 |
| --- | --- | --- | --- | --- |
| M0 | 框架搭建（本次） | 可编译、可启动的 1.21.1 NeoForge 模组工程 | 工程 + Git 绑定 + 文档 | `gradlew build` 产出 jar |
| M1 | 度量体系 ✅基础版 | 先证明"哪里慢" | Tick 归因 Profiler、Replay 事件流、Benchmark、`/threadtearer prof/replay/bench` | 可重复输出热图 |
| M2 | 运行时核心 ✅完整版 | 计算与交互分离的 API | ComputePool、InteractionExecutor、Snapshot/Transaction、乐观并发工作流、Delta 缓存、Adapter 框架 + 参考适配器 | GameTest + 单元测试 |
| M3 | 通用低层优化 ✅首批实现 | 不要求适配的通用收益 | 能力查询快速路径（Mixin，默认关）、异步导出（默认开）、Mixin 基础设施；分配减少/事件减支待基准门槛 | 每项 A/B ≥5% 才保留 |
| M4 | 通用交互优化落地 | 端到端证明优化收益 | 异步存档序列化、事件减支等通用优化（A/B 门槛）；Adapter 框架可选 | Replay 同场景前后对比 |
| M5 | 通用性与兼容 | 可被生态采用 | API 文档、配置预设、测试矩阵、CI | 兼容性清单全绿 |
| M6 | 发布 | 交付可用模组 | 打包、soak 测试、基准报告、发布渠道 | 长跑 100h+ 无异常 |

## 1. M1 度量体系（最优先）

### 1.1 Tick 归因 Profiler

- 采样式与插桩式双模式：
  - 采样：每 tick 记录主线程调用栈热点，归因到模组类；
  - 插桩：对高频事件（`ServerTickEvent`、`LevelTickEvent`、BE/Entity tick）计时，归因到监听器方法与 BE/实体类型；
- 输出：`/threadtearer prof start|stop|report`，支持 JSON 导出与聚合热图；
- 目标：任何模组包，五分钟内得到"每模组每 tick 毫秒数"。

### 1.2 Replay 事件流（v1）

- 记录高层事件（Tick 计数、实体/方块实体状态变化、模组事件摘要），输出为带版本号的 NBT/JSON 事件流；
- 用途：复现"每晚 8 点 TPS 下降"、为 Benchmark 提供固定输入（Discovery-005：Replay 先是调试工具，再是优化工具）。

### 1.3 Benchmark 场景

- 固定种子 + 固定模组集 + 固定操作序列（参考笔记的 Small/Medium/Large Grid、Craft Storm）；
- 指标：平均/最大 Tick 耗时、TPS、GC 时间、事件分发耗时；
- 后续所有优化必须跑同一套测试（Rule-008）。

**M1 验收**：在目标模组包（如 All of Create）的服务器上可复现"热点热图 + 事件流"，两次运行误差 <10%。

## 2. M2 运行时核心

### 2.1 任务模型

- `InteractionExecutor`：单线程、FIFO，只接受"可延后/可批处理"的交互任务（存档 IO、批量网络、批量通知）；
- `ComputePool`：纯计算任务线程池（线程数可配，默认核数-1）；
- 纪律（Rule-011）：Worker 永不直接触碰 Live State；一切访问走快照。

### 2.2 一致性原语

- `Snapshot<T>`：版本化只读视图；
- `Validator`：提交前校验（快照版本、关键状态、依赖链三层，笔记 Chapter 10）；
- `Transaction<T>`：变化集合 + Write-on-Commit + Rollback（笔记 Chapter 11）；
- 乐观并发：Assume → Compute → Validate → Commit / Retry。

### 2.3 对外 API（`threadtearer.api`）

- 仅依赖 Minecraft/NeoForge 公共 API，供第三方模组编译期接入；
- 最小接口：`Runtime`, `Snapshot`, `Validator`, `Transaction`, `DomainAdapter`。

**M2 验收**：GameTest 覆盖——快照过期被拒、越权写被拒、事务回滚不产生半成品状态；恶意任务（在工作线程写世界）被运行时拦截。

## 3. M3 通用低层优化

逐项执行、逐项验证，顺序按"影响面/风险"排序：

1. **能力查询快速路径**：缓存 Provider 链解析结果 + 无能力快速失败；以分配热图与基准为前提；
2. **分配减少**：热循环复用缓冲、消除流式分配（由 M1 分配采样驱动）；
3. **事件减支**：若 Profiler 证明事件分发占比 >5%，再做去重/批量（必须先证明语义安全）；
4. **异步存档序列化**：接入 `InteractionExecutor`（M2 产物）。

**M3 验收**：每项优化独立开关；开启后 A/B 基准提升 ≥5% 且无行为差异；全部默认值保守（高风险项默认关）。

## 4. M4 通用交互优化落地（不绑定特定模组）

核心路线是通用运行时优化，`DomainAdapter` 仅作可选生态扩展，不与任何特定模组对接：

- **异步存档序列化**：接入 `InteractionExecutor`（单线程 FIFO），把可延后的序列化/IO 移出主线程；
- **事件分发减支**：若 Profiler 证明高频事件分发占比 >5%，再做去重/批量（先证明语义安全）；
- **分配减少**：由 Profiler 分配采样驱动，热循环复用缓冲；
- 每项均走 A/B 门槛（≥5%）后默认开启。

**M4 验收**：同一 Replay 场景，开启前后主线程 Tick 耗时对比报告；若收益 <10%，回到 M1 重新定位热点，不硬上。

## 5. M5 通用性与兼容

- `threadtearer.api` 文档与示例模组；
- 配置预设：`client` / `vanilla-server` / `tech-heavy` / `all-off`；
- 测试矩阵：纯客户端、单机局域网、专用服务器、与常见优化/工具模组共存；
- CI：每次提交跑 `build` + GameTest + 基准冒烟。

## 6. M6 发布

- 打包与发布元数据（Modrinth/CurseForge）、更新检查；
- 100h+ 长跑 soak（模拟服务器），关注 Heisenbug；
- 发布基准报告（含未达标的回滚记录）。

## 7. 全局验收指标

1. **TPS 恢复率**：目标模组包（如 All of Create）下，主线程 Tick 超时比例降低 ≥30%（以 M1 基线为准）；
2. **正确性**：100h soak + Replay 复现零幽灵 BUG；
3. **通用性**：零配置安装即生效的优化 ≥1 项（如能力缓存）；Adapter 全部可选；
4. **负优化防线**：所有上线优化均通过 A/B ≥5% 门槛。

## 8. 风险与回滚

- 任何优化导致行为差异：先关开关，再查证据；
- 任何领域收益不达门槛：M4 阶段即停，回 M1 重新分析；
- 架构级问题（快照成本 > 收益）：以笔记 Rule-006（任务必须足够粗）重新设计任务粒度。

## 9. 执行记录

- **M1 已实现**（对应测试方案见 [docs/03-testing-plan.md](03-testing-plan.md)）：
  - 单元测试 11 个（计算池/交互执行器/快照/事务）；
  - GameTest 2 个（快照-计算-提交闭环、FIFO 顺序）；
  - `/threadtearer` 命令树（prof/replay/bench/runtime/selftest）。
- **M2 完整版已实现**：
  - `optimistic(...)` 乐观并发工作流（快照→计算→校验→提交/重试，过期抛 `StaleSnapshotException`）；
  - `DeltaCache` 增量缓存（版本化脏标记）；
  - `DomainAdapter` 框架 + 注册表（按目标模组加载自动挂载/卸载）+ 参考适配器 `synthetic-craft`；
  - 新增单元测试 12 个（乐观工作流 4 / Delta 缓存 4 / 适配器注册表 2 / 参考适配器 2），总计 23 个。
- **M3 首批已实现**：
  - 能力查询快速路径：Mixin 于 `CapabilityProvider`，按 (capability, side) 缓存已解析 `LazyOptional`，`invalidateCaps/reviveCaps` 时清空；配置 `optimizations.capabilityCache` 默认关，等待游戏内 A/B；
  - 异步导出：profile/replay/bench JSON 写入通过 `InteractionExecutor` 在交互线程执行，配置 `optimizations.asyncExport` 默认开；
  - 新增 `ReplayLoggerTest` 2 个用例，单元测试总计 25 个；
  - 剩余项（事件减支、分配减少）按计划等待 Profiler 证据与基准门槛。
- **M3 通用低层优化**：等待 L4 基准建立后按 A/B 门槛逐项开启（当前 `optimizations.capabilityCache` 默认关）。
- **M4 通用交互优化**：待 L4 基准门槛后逐项落地；`DomainAdapter` 框架保留为可选扩展（不绑定特定模组）。
- **方案 A 回访实验（spike）**：把原版交互方法整段搬到交互线程，其它模组 Mixin 一并执行。默认开。见 [05-interaction-offload-experiment.md](05-interaction-offload-experiment.md)。
