# 线程撕裂者

英文名 **Thread Tearer**（modId `threadtearer`，命令 `/threadtearer`）。

把 Minecraft 服务器主线程上**可以并行的计算**卸到多核，把**必须串行的世界状态**留在主线程。目标不是“再开一条游戏线程”，而是在不改写第三方模组源码的前提下，让大量独立的方块实体 Tick 真正吃满 CPU。

当前仓库按 **Minecraft 版本 + 加载器 + 交付物** 拆分：核心 mod 与各第三方模组的附属 mod 各占一个分支，互不夹带。

| 分支 | 工程目录 | 游戏版本 | 加载器 | 说明 |
| --- | --- | --- | --- | --- |
| [`1.20.1-forge`](https://github.com/TaoLe-si/MC-Thread/tree/1.20.1-forge) | [`1.20.1-forge/`](1.20.1-forge/) | 1.20.1 | Forge / NeoForge 47.x | 稳定线 |
| [`1.21.1-neoforge-core`](https://github.com/TaoLe-si/MC-Thread/tree/1.21.1-neoforge-core) | [`1.21.1-neoforge-core/`](1.21.1-neoforge-core/) | 1.21.1 | NeoForge 21.1 | 核心：API、计算池、交互卸载 |
| [`1.21.1-neoforge-mek-addon`](https://github.com/TaoLe-si/MC-Thread/tree/1.21.1-neoforge-mek-addon) | [`1.21.1-neoforge-mek-addon/`](1.21.1-neoforge-mek-addon/) | 1.21.1 | NeoForge 21.1 | 附属：Mekanism |
| [`1.21.1-neoforge-if`](https://github.com/TaoLe-si/MC-Thread/tree/1.21.1-neoforge-if) | [`1.21.1-neoforge-if/`](1.21.1-neoforge-if/) | 1.21.1 | NeoForge 21.1 | 附属：Industrial Foregoing |
| [`1.21.1-neoforge-eio`](https://github.com/TaoLe-si/MC-Thread/tree/1.21.1-neoforge-eio) | [`1.21.1-neoforge-eio/`](1.21.1-neoforge-eio/) | 1.21.1 | NeoForge 21.1 | 附属：EnderIO |
| [`1.21.1-neoforge-mkx`](https://github.com/TaoLe-si/MC-Thread/tree/1.21.1-neoforge-mkx) | [`1.21.1-neoforge-mkx/`](1.21.1-neoforge-mkx/) | 1.21.1 | NeoForge 21.1 | 附属：Mekanism Extras（本分支） |

附属分支只含该附属自己的工程，核心以 mavenLocal 依赖引入，需先在核心分支构建并 `publishToMavenLocal`。克隆后请检出对应分支，并在对应目录里构建：

```powershell
git clone https://github.com/TaoLe-si/MC-Thread.git
git checkout 1.21.1-neoforge-mkx
cd 1.21.1-neoforge-mkx
.\gradlew.bat build
```

与 [AE2-VM](https://github.com/TaoLe-si/AE2-VM) 互不依赖：AE2-VM 加速合成规划域的纯计算；本模组负责运行时线程化与通用交互卸载。

---

## 1. 问题：为什么原版吃不满多核

Minecraft 专用服务器（以及单人世界里的 Integrated Server）把几乎所有游戏逻辑绑在 **一条 Server Thread** 上：

- 世界时间、区块 ticket、光照与邻居更新
- 实体 / 玩家移动、受伤、背包
- 方块实体（机器、漏斗、熔炉、GT 多方块）按列表 **逐个 `tick()`**
- 网络包处理、命令、大部分 Forge 事件

Tick 预算是 **50 ms（20 TPS）**。模组一多，这条线程会先于 CPU 饱和：16 核机器上也能 `Can't keep up`，因为另外 15 个核根本进不了 `BoundTickingBlockEntity.tick()`。

直接把“整个世界”丢进线程池是错的：

1. **世界状态没有所有权划分。** `Level`、`Chunk`、`BlockEntity`、`ItemStack` 都是可变共享对象。多线程同时 `setBlock` / 改库存会立刻 ConcurrentModification、丢物品、区块损坏。
2. **副作用藏在 getter 里。** 许多模组的 `getCapability`、配方查询、电网 `add()` 会写 HashMap。
3. **事件总线是同步收集结果的。** `EventBus.post` 的监听器可能改变事件结果；调用方必须等它结束。不能把 `post` 偷到别的线程再“稍后应用”。
4. **Amdahl 定律。** 即使无限核，也只能加速“可并行的那一段”。区块加载、实体 AI、AE2 电网、玩家移动仍然串行。多核优化的上限 = 方块实体计算占 tick 的比例。

因此 线程撕裂者 的答案是：**识别三类工作，分别放到三条执行路径上。**

---

## 2. 三条执行路径

```text
                    ┌─────────────────────────────────────────┐
  网络包 / 命令 /     │           Server Thread（所有者）         │
  世界·实体·区块 tick │  读/写 Live State                         │
  玩家移动            │  应用交互结果、邻居更新、ticket、setBlock   │
                    └──────────▲──────────────────┬────────────┘
                               │ 永不 join          │ 入队后立刻返回
                               │ apply              │ request
                    ┌──────────┴──────────────────▼────────────┐
                    │     MCT-Interaction（单线程 FIFO）         │
  玩家放置 / 右键     │  空计算（或快照判定）→ 把 apply 交回主线程   │
  世界结构写入转发    │  保证同一世界写入的顺序                     │
                    └─────────────────────────────────────────┘

                    ┌─────────────────────────────────────────┐
  方块实体 ticker     │     MCT-Compute-1 … N（计算池）            │
  配方 / 物品 IO      │  在 worker 上直接跑原版/模组 tick()         │
  GT 机器逻辑         │  同一 BE 有锁；不同 BE 并行                 │
                    │  遇到 ticket / setBlock 再转交 Interaction │
                    └─────────────────────────────────────────┘
```

| 线程 | 名字 | 数量 | 职责 |
| --- | --- | --- | --- |
| 所有者 | Minecraft Server Thread | 1 | Live State 的唯一写入者（玩家、实体、区块、世界模拟）；消费交互线程递来的 apply |
| 交互 | `MCT-Interaction` | 1，FIFO | 玩家放置/使用的请求计算；计算线程上偷到的世界结构写入排队 |
| 计算 | `MCT-Compute-*` | 默认 `CPU 核数 - 1` | 方块实体 Tick、配方与舱口 IO、适配器纯计算 |

主线程 **禁止** `join()` / `get()` 等待计算或交互结果。请求发出后立即继续下一个 BE / 下一个包。这是避免死锁的硬纪律：apply 最终会调度回主线程，主线程再等自己等于卡住。

运行时入口：`MCTRuntimeImpl`。服务器启动时捕获真正的 `serverThread` 引用；`isServerThread()` 比较的是这条捕获线程，而不是 `MinecraftServer.isSameThread()`（后者在计算线程上会被 Mixin 伪装，见第 6 节）。

---

## 3. 玩家交互：请求 → 计算 → 主线程应用

放置方块、右键方块（`BlockBehaviour.BlockStateBase.use`）、部分实体交互走 **交互卸载**，不是计算池。

```text
主线程 A                 MCT-Interaction              主线程 A
────────                 ───────────────              ────────
steal(use/place)
  入队 request
  立刻返回（不改世界）
                         enterCompute()
                         空计算（不碰 Live State）
                         emit apply-request
                                                      runApply()
                                                      真正 use / place
                                                      邻居更新等 follow-up
                                                      再请求 → 再计算 → 再 apply
```

要点：

- Mixin 打在 `BlockBehaviour.BlockStateBase`（所有方块子类的 `use` 都会经过这里），而不是 `BlockState`。
- 交互线程 **不读不写** 活世界。`getBlockState` / `getBlockEntity` / `setBlock` 只发生在 `InteractionRelocator.runApply` 里，此时已经回到主线程。
- 其它模组打在这些原版方法上的 Mixin，会在 **apply 那一次** 跟着跑，语义与原版单线程一致。
- 移动、视角、载具、能力包、玩家命令 **不 steal**。这些包必须在主线程即时处理，否则会触发移动校验踢人。

配置开关：`experiments.offloadPlayerUseItem`（默认开）。关掉后，交互与 BE Tick 卸载全部退回主线程。

---

## 4. 方块实体 Tick：多核真正干活的地方

原版每个维度在主线程遍历 `LevelChunk.BoundTickingBlockEntity`，依次调用 `tick()`。线程撕裂者 在这个入口注入：

```java
// BoundTickingBlockEntityMixin
if (InteractionRelocator.stealTick(this.blockEntity, self::tick)) {
    ci.cancel();   // 主线程不再执行这次 ticker
}
```

`stealTick` 成功后：

1. 主线程取消本次 `tick()`，立即处理列表里的下一个 BE。
2. `InteractionOffloadPipeline.relocateTick` 把 runnable 交给 `ComputePool.asComputeExecutor()`。
3. 某个 `MCT-Compute-*` worker 加上 **该 BE 自己的锁**，然后执行原来的 `tick()`。
4. ticker 内部的配方匹配、物品传输、GT 舱口 IO **就在这颗 worker 上发生**（活数据，不是再做一份快照重放）。
5. ticker 里若碰到 **非线程安全的世界结构写入**（`setBlock`、邻居更新、`DistanceManager` ticket、`ChunkHolder.blockChanged`），Mixin 再把这一小段偷到 `MCT-Interaction`，由主线程 apply。计算线程不等待 apply 完成。

漏斗、熔炉、酿造台还有专门的 Mixin，走同一条 `stealTick`，避免只搬到 BoundTicking 时漏掉原版静态 `serverTick`。

### 4.1 计算池怎么涨到多核

`ComputePool` 是带伸缩队列的 `ThreadPoolExecutor`：

| 参数 | 值 |
| --- | --- |
| core | 从 1 开始 |
| max | `runtime.computeThreads`；`0` = `availableProcessors() - 1` |
| 空闲回收 | 60 s，`allowCoreThreadTimeOut(true)` |
| 队列 | `ScalingQueue`：池还能扩容时 `offer` 返回 false，迫使 `execute` 新建 worker |

同一 tick 里有很多 BE 被 steal 时，任务会几乎同时 `execute`。队列故意在“未满 max”时拒绝入队，线程池就会把 worker 拉到 `核数-1`。空闲后线程退出，下次高峰再拉起来。线程名是 `MCT-Compute-N`（N 只增不复用），日志里会看到编号变大，但 `computeLive` 不会超过 max。

### 4.2 同一方块实体不能并行 tick

GT 超频卡、实体加速卡可能在同一 tick 给同一台机器打多次 ticker。计算池一旦并行，就会出现 `MetaMachine.executeTick` 的 ArrayList CME。

每个 `BlockEntity` 带一把 `BlockEntityTickLock`。`stealTick(be, runnable)` 包一层 `synchronized (be.mcthread$tickLock())`。

- 不同 BE → 不同锁 → 多核并行
- 同一 BE 的额外 tick → 排队，不会叠在两个 worker 上

### 4.3 哪些 BE 必须留在主线程

AE2 及其附属（ExtendedAE、无线连接器、ME 舱口/总线/样板/库存等）**不进计算池**。

原因：AE2 自己的 `TickHandler` 已经在主线程转电网节点；原版 `BoundTickingBlockEntity` 也会 tick 同一块 Tile。两边同时改 `Grid` 的 HashMap、`TickManagerService` 的 `PriorityQueue`，会变成 `ConcurrentModificationException` 或 `peek != poll` 的 `IllegalStateException`。

判定在 `InteractionRelocator.staysOnServerTicker`：

- 类名属于 `appeng.` / `glodblock` / `extendedae` / ME hatch·bus·pattern·stocking 等
- GTCEu 方块实体会 `getMetaMachine()`，若机器本身是 AE2 族（ME 舱口），也留在主线程

无线连接器并网、`Grid.add`、`GridConnection.create`、`NetworkCraftingProviders.addProvider`、`TickManagerService` 的入队/出队，另外用 `Ae2GridGuard` 串行化，防止主线程上的电网突变和残留的交叉访问打架。

这些 Mixin 都是 `@Pseudo` + `MCTMixinPlugin.shouldApplyMixin`：整合包没有 AE2 / GTCEu 时不会硬依赖。

---

## 5. 计算线程上的“偷写”：什么留下、什么转发

计算线程跑的是 **原版/模组的真 `tick()`**，不是纯函数模拟。因此必须区分：

| 操作 | 发生位置 | 原因 |
| --- | --- | --- |
| 配方查询、匹配、进度 | 计算线程，立刻执行 | 这才是 CPU 热点；做成快照再提交会把收益吃光 |
| 机器内部物品 / 舱口 IO | 计算线程，立刻执行 | 与 ticker 同一把 BE 锁保护 |
| `DistanceManager` ticket | 交互 FIFO → 主线程 apply | ticket 集合非线程安全 |
| `Level.setBlock` / destroy / 邻居更新 | 交互 FIFO → 主线程 apply | 区块、光照、更新顺序属于世界结构 |
| `ChunkHolder.blockChanged` | 交互 FIFO → 主线程 apply | 玩家可见性与 section 脏标记 |
| 生成实体、爆炸 | 交互 FIFO → 主线程 apply | 实体列表与世界扫描 |

`InteractionRelocator.steal` 在计算/tick 上下文里：只有 `isTicketWrite` 或 `isWorldStructureWrite` 才转发；其它调用（包括物品槽位）返回 false，继续在当前 worker 执行。

主线程 apply 期间如果再遇到 `setBlock`，**不再转发**（`isWorldWrite` 在 owner apply 上保持内联），避免把一次放置拆成无序的异步块。

### 5.1 把一个 tick 拆成两半：延迟写

上面的转发是**被动**的——worker 跑到某个世界调用，框架把它挑出来送走。但有些模组的 tick 是"一半纯计算、一半世界交互"粘在一个方法里的，`steal` 只能整段搬或整段留。

Mekanism 就是典型。`TileEntityConfigurableMachine.onUpdateServer` 的字节码只有两条语句：

```text
invokespecial TileEntityMekanism.onUpdateServer()Z   // 配方 / 能量 / 槽位 —— 昂贵且只碰自己
invokevirtual TileComponentEjector.tickServer()V     // 建 BlockCapabilityCache 推入邻居 —— 必须主线程
```

框架为此提供**主动**原语 `MCTRuntime.deferWorldWrite(Runnable)`：worker 算完这一半，把另一半交给主线程的**每 tick 批量队列**（`WriteCoalescer`），然后立刻返回继续干自己的活。附属 mod 用 `@WrapOperation` 包住那个调用点即可，不需要在附属里写任何 `Level` 的世界交互 mixin。

```java
// 附属的 tick 拆分：worker 上延迟，主线程上原样
if (!InteractionRelocator.isComputing()) { original.call(ejector); return; }
MCT.runtime().deferWorldWrite(
        () -> InteractionRelocator.runLockedBlockEntityTick(be, () -> original.call(ejector)));
```

三个性质：

- **主线程不等待计算。** 计算线程算完主动通知，主线程只在 tick 边界批量 drain 一次。满基地 200 台机器喷出 = 主线程**一个任务**，不是 200 个。
- **不重叠。** 延迟的那半重新进入 ticker 持有的同一把 per-BE 锁，所以不会与下一 tick 的配方计算同时读写同一批槽位。主线程在这里的等待记在 `phases[...] lockwait=`，是这套设计唯一新增的停顿来源，必须盯着。
- **晚一 tick。** 对"把当下槽位里的东西推出去"这类语义不可见（物品喷出本来就是 10 tick 一次，且推的是当下状态不是差值）。

与 `scheduleWorldInteraction` 的分工：需要与玩家交互**排定先后**的写走交互 FIFO；只要求"尽快发生"的写走 `deferWorldWrite`。

**附属的搬迁白名单必须与"`@WrapOperation` 包住了什么"绑在一起。** 只有继承 `TileEntityConfigurableMachine` 的类，其 tick 里唯一伸手进世界的那句才确定是被包住的那句；发电机族、激光族、多方块族自己的 tick 体里就建 `BlockCapabilityCache` 并 emit，不属于这个形状，必须留在主线程。`threadtearer-mek` 的 `MekOffloadPolicy` 就是按这条**形状规则**判定的（链上必须出现 `TileEntityConfigurableMachine`），而不是维护一份"哪些类危险"的清单——清单追不上模组更新，形状规则默认安全。

---

## 6. `isSameThread` 伪装（以及为什么 `execute` 仍回主线程）

GTCEu 等模组在配方/IO 里调用 `MinecraftServer.isSameThread()`。若计算线程如实返回 false，机器会拒绝工作或把逻辑推迟，BE 卸载等于没发生。

`BlockableEventLoopMixin` 只在 **当前是计算上下文** 且目标是 `MinecraftServer` 时，让 `isSameThread()` 返回 true。

同时注入 `execute`：只要当前线程不是捕获的 server thread，就强制 `tell()` 进服务器队列，**禁止** 因伪装成功而把 apply 内联在 worker 上。否则“主线程任务”会在计算线程里改世界。

```text
计算线程：isSameThread() == true     → GT 配方继续跑
计算线程：server.execute(task)       → 仍然 tell() 回真正的 Server Thread
```

`MCTRuntimeImpl.isServerThread()` 不走这条伪装，专门用来判断“现在能不能 steal / 能不能在本地 apply”。

---

## 7. 明确不碰的边界

| 项 | 策略 | 原因 |
| --- | --- | --- |
| 世界 tick / 实体 tick / 玩家 tick / 区块 ticket 推进 | 留在 Server Thread | 时序与碰撞必须全局有序 |
| 玩家移动、传送、输入、载具、能力包 | 不 steal | 移动校验踢人 |
| `MinecraftForge.EVENT_BUS.post` | 不 steal | 结果收集型事件必须同步返回；`AttachCapabilitiesEvent` 等 |
| AE2 原版 ticker 与电网 | 留在主线程 + `Ae2GridGuard` | HashMap / PriorityQueue |
| GameTest 服务器 | 不卸载 BE tick | 测试时序与原版一致 |

Forge 事件仍然可能在 **已经被 steal 的方法内部** 同步 `post`（例如 apply 里的 `use`）。监听器跟该方法在同一条线程上跑，这是原版语义，不是把总线搬到交互线程。

---

## 8. 多核收益从哪里来、什么时候没有

加速模型：

```text
tick 耗时 ≈ T_serial + T_be / P

T_serial  世界、实体、AE2 电网、网络、GC
T_be      可卸载的方块实体 Tick（GT 机器、原版熔炉/漏斗等）
P         实际并行度，上限 min(独立 BE 数, computeMax)
```

**收益大** 的场景：基地里有大量 **彼此独立**、CPU 重的非 AE2 方块实体（GT 处理机器、高炉、化工线）。主线程只负责把 ticker 丢进池子并处理偶发的 `setBlock`/ticket；十几颗 `MCT-Compute-*` 同时跑配方。

**收益小或看起来“没吃满核”** 的场景：

1. **AE2 电网很大。** 节点 tick 必须留在主线程。日志里 `computeTicks` 可能每 100 tick 只有几百次，而主线程仍在转三千多个 grid node。
2. **可并行 BE 太少。** 8 台机器无法喂饱 15 个 worker；`computeActive` 在 tick 结束采样时经常是 0～3，因为 burst 已经跑完。
3. **setBlock / 邻居更新风暴。** 计算很快结束，交互 FIFO 和主线程被更新顺序堵住。
4. **单 BE 超频。** 同一机器的多次 tick 被锁串行，不会线性加速。
5. **tick 里粘着世界交互。** 若一个 tick 的昂贵部分和"必须主线程"的部分写死在同一个方法里，只能整段留主线程，卸载收益归零。这正是 Mekanism 的情况，解法见 5.1（`deferWorldWrite` 拆分）。

判断卸载是否在工作，看 `logs/latest.log`：

```text
[MCT-Compute-1] MCT-Compute-1 started
...
MCThread.Monitor tick=... computeTicks=800 computeActive=2 computeLive=15 computeMax=15
```

| 字段 | 含义 |
| --- | --- |
| `computeTicks` | 上一个监测窗口内计算池跑完的 BE tick 次数 |
| `computeActive` | 采样瞬间正在跑的 worker（tick 末尾通常偏低） |
| `computeLive` | 当前存活的计算线程 |
| `computeMax` | 上限（核数-1 或配置值） |

`/mcthread runtime status` 同样输出上限 / 已创建 / 正在跑。

官方 Forge 需要 MANIFEST 的 `MixinConfigs: mcthread.mixins.json`（`build.gradle` 已写），否则生产环境 Mixin 不会加载，计算池会启动但 **没有任何 ticker 被 steal**。

---

## 9. 运行时 API 与度量

第三方模组可编译依赖 `mcthread.api`，通过 `MCT.runtime()` 接入；未安装时为 NOOP。

- `submitCompute`：纯计算任务进 `ComputePool`
- `scheduleInteraction`：可延后工作进交互 FIFO
- `scheduleWorldInteraction`：卸载中的 tick 把世界写交回交互 FIFO（与玩家交互排定先后）
- `deferWorldWrite`：卸载中的 tick 把世界写交给**每 tick 批量队列**（只要尽快发生、不需排定顺序）——见 5.1
- `snapshot` / `beginTransaction` / `optimistic`：快照 → 计算 → 校验 → 主线程提交（主线程禁止 join Future）

这套 API 给**主动适配**的模组用。对未适配的模组，靠第 3～4 节的 Mixin 卸载。

度量（先证明哪里慢）：

| 命令 | 作用 |
| --- | --- |
| `/mcthread prof start [intervalMs]` | Tick 归因采样 |
| `/mcthread replay start <场景>` | 按 tick 记录 TPS / 区块实体变化 |
| `/mcthread bench start <ticks>` | 固定窗口 A/B |
| `/mcthread monitor status` | 最近 tick 的 chunk/entity 计数 |
| `/mcthread runtime status` | 计算池与实验管线 |
| `/mcthread experiment [on\|off]` | 卸载总开关 |
| `/mcthread selftest` | 运行时自检 |

权限等级 2。导出 JSON 默认走交互线程（`optimizations.asyncExport`）。

---

## 10. 工程信息

| 项 | 值 |
| --- | --- |
| 名称 | 线程撕裂者 / Thread Tearer（modId `threadtearer`） |
| 核心分支 | `1.21.1-neoforge-core`（工程目录 `1.21.1-neoforge-core/`） |
| 本分支 | `1.21.1-neoforge-if`（工程目录 `1.21.1-neoforge-if/`，附属 mod `threadtearer_if`） |
| 平台 | NeoForge 1.21.1（`neo_version` 21.1.250） |
| 构建 | ModDevGradle `2.0.147` |
| 构件 | `net.neoforged:neoforge:21.1.250` |
| 编译 JDK | 21 |
| Gradle | Wrapper 8.14.5 |
| 映射 | Mojang + Parchment 2023.09.03 |
| 许可证 | All Rights Reserved（见 `mods.toml`） |

同一 jar 可运行于 NeoForge 47.1.x 与 Forge 47.1.3+。Mixin 只打在两套加载器共有的通用类上。

### 配置（`config/threadtearer-common.toml`，热重载）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `runtime.enabled` | `true` | 运行时总开关 |
| `runtime.computeThreads` | `0` | 计算线程上限，0 = 核数-1 |
| `profiler.enabled` | `true` | Profiler |
| `replay.enabled` | `true` | Replay 事件流 |
| `monitor.enabled` | `true` | 监测日志 |
| `monitor.logIntervalTicks` | `100` | 监测间隔（0=关闭周期日志） |
| `optimizations.capabilityCache` | `false` | Capability 查询缓存（待 A/B） |
| `optimizations.asyncExport` | `true` | JSON 导出走交互线程 |
| `experiments.offloadPlayerUseItem` | `true` | 交互 + BE Tick 卸载总开关 |

### 构建与安装

本附属的构建需要核心先发布到 mavenLocal：在 `1.21.1-neoforge-core/` 里执行一次 `.\gradlew.bat publishToMavenLocal`，然后回到 **`1.21.1-neoforge-if/`**（需要 JDK 21）：

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat copyToMods -PmodsDir="D:\...\mods"
```

`build` 会先跑两道闸门：`verifyMixins`（mixin 包卫生）与 `verifyMixinTargets`（用真 Titanium jar 校验每个 `@WrapOperation` 的 `@At` 目标是否仍存在）。

产物：`1.21.1-neoforge-if/build/libs/threadtearer_if-0.1.0.jar`。完整安装步骤见 [安装与使用](1.21.1-neoforge-core/docs/04-install-and-usage.md)。

修改后必须 **完整重启** 游戏（热替换 Mixin 无效）。

### 本版本源码结构

```text
1.21.1-neoforge-if/src/main/java/com/taolesi/threadtearer/industrial/
├── ThreadTearerIndustrial.java   # @Mod 入口，启动日志
├── IndustrialOffloadPolicy.java  # 14 台机器的封闭允许清单 + 链校验
├── IfDeferral.java               # 计算线程上「先算一半、世界写延后」的助手
├── IndustrialMixinPlugin.java    # IF 不在时整体跳过
└── mixin/
    ├── TitaniumTickerMixin.java          # 总闸：BasicTileBlock 的 ticker lambda
    └── ActiveTileFacingWorkMixin.java    # 邻居自动推送 → 交回主线程
```

### 附属的卸载范围（`threadtearer_if`）

IF 的每个机器都是 Titanium 的 `ActiveTile`，所以**总闸只有一个**：`BasicTileBlock.getTicker` 返回的 lambda 里那句 `((ITickableBlockEntity) blockEntity).serverTick(...)`。包住这一句就覆盖了 IF 全部机器，不需要逐机器写 mixin。

代价是这个 lambda 在 Titanium 里，不在 IF 里，所以 mixin 的 target 是 Titanium——用策略层把关：只有 `com.buuz135.industrial.*` 的类才可能被放行，别的 Titanium 模组永远走主线程。

与 Mekanism 不同，IF 没有「一条基类把安全机器和不安全机器分开」的形状，每台机器的差异都在自己的 `work()` / `onFinish()` 里，所以规则是**封闭允许清单**（`IndustrialOffloadPolicy.ALLOWED`，14 台）：一台机器只有在它的 `work()` / `onFinish()` 及其调用链被逐行读过、确认只碰自己的槽位/储罐/能量之后才进清单。被拒绝的族与其原因：

- **读值进入算式的**：Water Condensator（读六个邻居的流体状态，读数决定填充量）、Enchantment Factory / Applicator（从机器**上方**的流体槽抽取，抽出量决定附魔等级）。这类读不能延后——延后了 tick 就得凭空编一个数。
- **共享随机源**：Sludge Refiner 用 `this.level.random`，是主线程自己也在用的 level 级 `RandomSource`。
- **实体扫描与区域操作**：所有 `IndustrialAreaWorkingTile` 子类（收割/播种/施肥、喂动物/挤奶/分离幼崽、刷怪/复制、屠宰、抽水、方块破坏/放置、激光钻、水培床……）。
- **发电机**：`GeneratorTile` 每 tick 往六个邻居推能量，而**收到多少**又反过来决定抽多少，是涡轮那种「发-抽」耦合，单侧延后不成立。

邻居自动推送（`IFacingComponent.work`）不参与上面的判断：它由 `ActiveTileFacingWorkMixin` 对所有被放行的机器一律交回主线程，和 mek 附属围绕 `TileComponentEjector.tickServer` 做的是同一个切分。

开关：`-Dthreadtearer.industrial.offload=false` 让 IF 的所有 tick 回到主线程。

---

## 11. 文档

| 文档 | 内容 |
| --- | --- |
| [01 可行性分析](1.21.1-neoforge-core/docs/01-feasibility-analysis.md) | 方案论证与 Amdahl 上限 |
| [02 开发计划](1.21.1-neoforge-core/docs/02-development-plan.md) | 里程碑 |
| [03 测试方案](1.21.1-neoforge-core/docs/03-testing-plan.md) | 分层测试与 A/B 门槛 |
| [04 安装与使用](1.21.1-neoforge-core/docs/04-install-and-usage.md) | 客户端/服务端安装 |
| [05 交互卸载实验](1.21.1-neoforge-core/docs/05-interaction-offload-experiment.md) | 早期实验笔记 |
| [协同开发](1.21.1-neoforge-core/CONTRIBUTING.md) | 线程纪律、Mixin 规则、提交规范 |
| [变更记录](1.21.1-neoforge-core/CHANGELOG.md) | 版本历史 |
