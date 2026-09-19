# 《Minecraft Runtime Research Notes》摘要

> 原文：D:/qq/download/Minecraft_Runtime_Research_Notes.docx（Draft 0.1）
> 全文提取见同目录 [research-notes-full.txt](research-notes-full.txt)

## 一句话主线

研究从"怎么让 Minecraft 用满多核"出发，九次转向后收敛为：**Minecraft 的问题是状态太多；先解决状态问题，再解决线程问题；先测量，再优化；领域解优先于万能解。**

## 九次转向（Volume I）

JIT 幻想 → 状态 → 副作用 → Profiler → Replay → Delta → Snapshot → Task Graph → Validation → Transaction → Domain VM → Folia（Ownership）→ World IR → Unified Runtime

## 核心定律

- **Law-001 第一状态定律**：可并行性取决于状态控制能力，而非计算复杂度。
- **Law-002**：无法测量的问题无法正确优化。
- **Law-003 快照第一定律**：不能快照的系统无法安全并行。
- **Law-004**：正确性优先于性能。
- **Law-005 事务定律**：不能表达变化的事务难以安全并行。
- **Law-006 领域定律**：只有适用于特定领域的优化模型。
- **Law-007 所有权定律**：共享状态最终会回到锁。
- **Law-008 行为定律**：世界的规律来自行为。
- **Law-009 运行时定律**：真正的系统是规则共同构成的 Runtime。

## 关键 ADR（本项目直接采用）

- ADR-001：状态隔离远比 JIT 重要。
- ADR-003/004：线程问题只是状态问题的表现；副作用本质是状态传播。
- ADR-005/006：线程化不是目标，收益才是目标；收益 = 热点比例 × 纯计算比例 × 快照可行性。
- ADR-007/008/009：Profiler 先于线程；Replay 先于 Runtime；Benchmark 先于优化。
- ADR-013/014/015：增量计算优先于重复计算；Replay 是 Delta 的前提；变化比状态更值得记录。
- ADR-017/018/019：Snapshot 先于 Worker；Validation 不可省略；Live State 属于主线程。
- ADR-021/022：线程不是第一公民，任务才是；Scheduler 比 Thread Pool 更重要。
- ADR-025/026/027：Validation 不可省略；快照必须可验证；乐观并发优于大规模锁。
- ADR-029/030/031：事务优先于直接修改世界；Rollback 是一等能力；事务本质是变化集合。
- ADR-032/034/035：Adapter 优先于 Universal VM；Domain VM 是现实路线；Universal VM 是长期研究课题。
- ADR-036/037/038/039：Folia 不是线程池方案，本质是 Ownership Runtime；运行时可以多种形态共存。
- ADR-040/041/042：行为优于实现；Task Graph 是 IR 的执行形式；World IR 是 Runtime 家族共同语言候选。
- ADR-043/044/045：Runtime 是状态管理系统；统一行为比统一代码更现实；生态优化优先于单模组优化。

## 失败路线（勿重蹈）

- **Failed Route-001 缓存 READ 结果**：Minecraft 是状态机，相同代码可能得到不同结果；不能作为通用方案。
- **Failed Route-002 万能 VM**：漏斗/红石/Create 的副作用无法忽略；AE2 成功是因为"纯函数 + 快照 + 无副作用"，不是 VM。

## 状态分类（Chapter 3）

- Class A Pure State：可复制、可快照、无副作用（Craft Planner 快照）。
- Class B Derived State：可重新计算（缓存、索引、路径结果）。
- Class C Authority State：真实状态必须唯一（Inventory / Chunk / World）。
- Class D Temporal State：依赖顺序（红石 Tick 链、实体更新）。

## Runtime 家族与领域评分（Chapter 12-13）

- Type A Pure Compute Domain（Craft Planning ★★★★★）
- Type B Snapshot Domain（Storage Query ★★★★☆）
- Type C Transaction Domain（Inventory Simulation ★★★★★）
- Type D Ownership Domain（Folia Region）
- Type E Temporal Domain（Redstone ★☆☆☆☆，Hopper ★☆☆☆☆，Entity AI ★★★☆☆）

## 对 线程撕裂者 的三条结论性启示

1. **先做度量**：没有 Profiler/Replay/Benchmark，任何优化都是猜测；
2. **不做万能解**：通用层只做"生态热路径"，高价值优化走 Domain Adapter；
3. **正确性 > 性能**：快照 + 校验 + 事务回滚是并行安全的底线，实验特性默认关闭。
