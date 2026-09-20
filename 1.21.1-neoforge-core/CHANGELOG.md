# Changelog

## [0.3.11] - 2026-09-20

核心：**删掉计算池里那套手工扩容机制。** 它每派发一个任务就在服务器线程上抢四次池锁，而任务本身在 worker 上只跑 34µs。这一版把派发路径变成无锁，服务器线程省下约 10ms/tick。

### 怎么发现的

读游戏日志的 profiler 归属（`threadtearer/bench-*.json`，1853 采样 / 224 tick）：**threadtearer 占服务器线程 30.9% = 16.15ms/tick**，比第二名的 minecraft（26.7%）还高。热点帧的前两名：

```
ComputePool$ScalingQueue.offer     224 采样  →  6.3 ms/tick
ComputePool.adjustForLoad          154 采样  →  4.3 ms/tick
```

合计 10.6ms，占 52ms 服务器 tick 的 20%。同一份日志的监视器行给出对照：`computeTicks=1796 ... 34.3µs/task workers=15 parallel=0.485x`——**每个任务在 worker 上只花 34µs，服务器线程却要花约 6µs 才能把它递出去**，而且 15 个 worker 只有半个核在忙。生产者喂不快消费者，不是池子不够大。

### 原因

`ComputePool.asComputeExecutor()` 的 lambda 第一句是 `adjustForLoad()`，**它在服务器线程上跑**（`stealTick` 由 vanilla tick 循环触发）：

```java
void adjustForLoad() {
    int load = executor.getQueue().size()      // LinkedBlockingQueue.size() 拿两把锁
             + executor.getActiveCount() + 1;  // mainLock + 遍历所有 worker
    ...
    if (target != executor.getCorePoolSize()) {
        executor.setCorePoolSize(target);       // mainLock，缩容时还会中断空闲 worker
    }
}
```

再加上 `ScalingQueue.offer` 里的 `getPoolSize()`（又是一次 mainLock）。1796 次/tick × 每次 4 次加锁 ≈ 7200 次锁操作/tick，全压在服务器线程上。

### 改法

`corePoolSize` 直接设成 `maxPoolSize`，队列换成普通 `LinkedBlockingQueue`，删掉 `ScalingQueue` / `enqueueOnReject` / `adjustForLoad`。这样 `ThreadPoolExecutor.execute()` 的路径变成：

```java
if (workerCountOf(c) < corePoolSize) { if (addWorker(command, true)) return; }
```

每来一个任务加一个 worker 直到 max——**和 `ScalingQueue` 想要的效果完全一样**，但只需读一次 `c`（`ctl` 是单个 AtomicInteger，`workerCountOf` 是纯位运算），不碰任何锁。任务满了之后无界队列自然接管，`offer` 永远成功，所以拒绝处理器也不需要了。

净删代码。`ComputePoolTest` 那两个"4 个并发任务要用 4 个 worker"的断言在新实现下依然成立（`workerCountOf < corePoolSize` 逐任务加线程），另加一条 `coreSizeEqualsMaxSoSubmissionNeverWalksTheWorkerList` 钉住这个不变量——谁把手工扩容加回来，这条会红。

### 没做的一件事

`isWorldAccessorThread` 在热点里占 53 采样（约 1.5ms/tick），它每次要读 4 个 `ThreadLocal`，被 `LevelMixin` 的 `@Redirect(method = "*")` 和 `ServerChunkCacheMixin` 反复调用。合成一个位掩码 `ThreadLocal<Integer>` 就能省掉，但收益比上面小一个数量级，等这一版测完再看。

### 两个会误导人的读数（记下来免得再被绕进去）

- **`computeActive=` 永远是 0，不代表池子没在用。** 它是 `computePool.activeWorkers()`，由 lifecycle 线程在 tick 末尾采样——那时这一 tick 的突发早跑完了。采样点没信息量，不是坏了。该看的是 `parallel=` 和 `busiest=`。
- **`optimizations.capabilityCache` 是个死开关。** 它在 `MCThreadConfig` 里声明了，但全仓库零引用（配置注释也写了：NeoForge 1.21.1 上能力不再存在 `CapabilityProvider` 上）。别以为开着它在做什么。

## [0.3.10] - 2026-09-20

核心：**修掉服务器线程上那个每 tick 上万次的 apply 洪峰。** 这是一次只动核心、不改任何附属的修复，影响全部 6 个附属的每一台机器。

### 怎么发现的

读监视器日志时看到两个数字对不上：`apply=45979.23ms/8577521`、`flush=49510.57ms/898(10183147 writes, 1608209 deferred)`——900 tick 里约 9500 次 apply/tick，而附属主动 defer 的只有 1781 次/tick。**约 850 万次写不是我们要求延迟的。** 服务器线程 tick 墙钟 146ms，其中排空 55ms（runApply 51ms），占 38%，是服务器线程上最大的一块。

### 先证伪了"重入"

假设是"排空里执行 setBlock 又被 mixin 接住重新入队"（drain → worker → requeue → next drain），那既是性能问题也是"晚两 tick 落地"的正确性隐患。三处查完否掉了：

1. `stealTickAndReturn` 成功时确实 `cir.setReturnValue(speculative)`，原方法被取消，没有双跑。
2. `relocateTick` 走 `tickExecutor.execute`，是异步的。
3. **数量对不上**：`queueComputeWorldWrite` 的条目数（11,296/tick 减去附属 defer 的 1,781 = 9,515）与 `APPLY_CALLS`（9,513/tick）几乎 1:1。如果排空期间还有嵌套写被重新入队，APPLY_CALLS 会明显多于 coalescer 条目数。

所以排空期间的嵌套写是内联跑的，没有雪崩。

### 真正的来源：一条被误分类的调用链

每一台带进度条的 Titanium / IF 机器，每 tick 往延迟队列排 2 条写入项，只为标一个布尔 flag：

```
ActiveTile.serverTick                      无条件 multiProgressBarHandler.update()
  → ProgressBarComponent.tickBar()
  → ProgressBarComponent.setProgress()     无条件 componentHarness.markComponentForUpdate(true)
  → ActiveTile.markComponentDirty()
  → BlockEntity.setChanged()
  → Level.blockEntityChanged(pos)               ← 一条
  → Level.updateNeighbourForOutputSignal(pos)   ← 一条
```

这两句在 worker 上被 `stealImpl` 的 `ON_COMPUTE` 分支接住：`isWorldStructureWrite()` 的子串表里有 `"blockentitychanged"`，`isFollowUpUpdate()` 里有 `"neighbour"`，于是各排一条 `queueComputeWorldWrite(() -> runApply(name, vanilla))`。问题是 **vanilla 的 `Level.blockEntityChanged` 整个方法体只有一句 `chunk.setUnsaved(true)`**——一个布尔 flag，被当成结构性方块写来延迟。`InventoryComponent.onContentsChanged`、`FluidTankComponent`、`EnergyStorageComponent` 走同一个 `markComponentDirty`，物品/流体/能量每动一次再加 2 条。

### 改了什么

- **新 `PositionUpdateBatch`**（取代 `NeighborUpdateBatch`）：按 `(Level, BlockPos)` 去重，一条记录带三个 OR 合并的 flag——`notifyNeighbours`（`updateNeighborsAt`）、`signal`（比较器扇出）、`unsaved`（`chunk.setUnsaved`）。tick 边界在服务器线程上排一次，9500 条/tick 压到"每台机器每 tick 一条"。
- **`LevelMixin` 三处入口改走批**：`blockEntityChanged`、`updateNeighbourForOutputSignal`、`updateNeighborsAt`（后者在服务器上其实不触发，见下条）。判定条件是 `InteractionRelocator.shouldBatchWorldBookkeeping()`——必须同时是 compute/tick worker 且不在服务器线程。服务器线程和交互线程的调用语义一字不改。
- **`updateNeighborsAtExceptFromFacing` 故意不批**：批里的邻居 flag 重放的是 `updateNeighborsAt`（六面全通知），而这个是排除一面的。合并会通知 vanilla 不通知的邻居，所以它保持原样。
- **一处如实记录下来的"没生效"**：`ServerLevel` 覆写了 `updateNeighborsAt` / `updateNeighborsAtExceptFromFacing` 且**不调 `super`**（直接走 `neighborUpdater`），所以 `LevelMixin` 对这两个方法的注入在服务器上根本不触发。真正起作用的是 `setBlock` 轻写路径里那句显式的 `PositionUpdateBatch.record(…, notifyNeighbours=true, …)`。注入保留（非 `ServerLevel` 的 `Level` 实现若委托过来仍有意义），但 `LevelMixin` 的类 javadoc 把"哪两个注入真的会触发"写清楚了，免得后人照着不触发的那个去调试。`blockEntityChanged` 和 `updateNeighbourForOutputSignal` 是 `ServerLevel` 原样继承的，所以洪峰正是从这一对进来的。
- **比较器 flag 由调用方决定，不是无条件**：vanilla 自己在两处不一致——`setChanged` 对任何非空气方块都调，`setBlock` 只在**新状态带模拟信号**时才调。所以 setBlock 轻写路径传 `state.hasAnalogOutputSignal()`，`setChanged` 那条路照 vanilla 传 `true`。
- **`block` 参数在排空时重读**，不从 worker 带过来：worker 算出的值可能已经过了一 tick，而排空那一刻的 live 状态正是 vanilla 在那个瞬间会传的。
- **`Level.blockEntityChanged` 的 `isLoaded` 守卫移到排空里**（`PositionUpdateBatch.flush` 对每个位置重查），和已有的 `setBlock` 排空守卫同一套语义：位置在 worker 决策和排空之间卸载了就跳过，而不是让服务器线程同步加载区块。
- **`PhaseTimings` 新增 `POSITION_UPDATES`**：原始 record 次数。与 `NEIGHBOR_POSITIONS`（实际排空的位置数）相除就是压缩比，用来在游戏里直接验证这次改动有没有生效。

### 顺手修掉的两个陈旧物

- **gametest 结构文件放错目录**：文件在 `data/threadtearer/structures/empty.nbt`，而 1.21.1 的 `StructureTemplateManager` 读的是 `structure`（单数，`FileToIdConverter("structure", ".nbt")`）。所以整个 gametest 服是崩的——`IllegalStateException: Missing test structure: threadtearer:empty`。移正之后 7 个用例全部真跑起来。
- **`offloadMixinInterceptsUseItemOn` 断言的是错的行为**。它断言 mixin 会把 `ServerPlayerGameMode.useItemOn` 搬到交互线程并返回 `SUCCESS`；真跑起来 vanilla 返回 `PASS`、什么都没卸载。原因不是 bug 而是设计：被卸载的玩家交互路径在**包处理层**（`ServerGamePacketListenerImpl`），走到 `ServerPlayerGameMode` 时调用者已经是服务器线程顶层调用，`stealImpl` 的第一道闸门（就是挡住看门狗在区块加载期抓到服务器线程做 `StackWalker` 的那道）**故意**放它内联。用例改名为 `directServerThreadUseItemOnStaysInline`，断言反过来钉住这道闸门，免得后人把它"修掉"。放进独立 batch，因为 `APPLY_CALLS`/`applied` 是全局计数，同 batch 里其它用例会动它。

### 没做的一件事，和为什么

原本还打算"排空只包一层 `runApply`"（现在每条延迟项各自包一层）。改完上面那条之后**没做**：收益随条目数一起缩水，而它有一个改不掉的风险——统一包一层会让所有条目的 `APPLYING_UPDATE` 变成同一个值，而现在"名字像邻居更新"的条目（`Level.neighborChanged` 等）依赖它为 true 才会把嵌套的 `neighborChanged` 内联，其它条目会让嵌套的走 FIFO 推后一 tick。把这个语义在全局统一，等于在无法本地验证的地方动红石时序。要做得单独立项、单独测。

### 测试

- 单测 55 项全过（`TickClassificationTest` 加 1 项：`shouldBatchWorldBookkeeping` 在没有 runtime 的测试 JVM 里必须答 false）。
- gametest 7 项全过，含新增 `computeWorldBookkeepingLandsThroughTheBatch`：worker 上调 `blockEntityChanged` → 记录数增加、chunk 变 unsaved、`APPLY_CALLS` 不增加（即没走 runApply）。
- `verify_mixin_packages.py` 闸门过。

### 还没验证的

**收益幅度要进游戏看。** 预期是 `phases[...]` 里的 `apply` 从 ~9500/tick 掉到千级以下、`nb=` 后面的括号里出现 `(N recorded)` 且 N 远大于排空位置数。如果 `apply` 没降，说明洪峰还有第二个来源没找到。

## [0.3.9] - 2026-09-19

附属 threadtearer-mek：**两个 heater 搬上计算线程。** 这是 0.3.5（多方块族）和 0.3.6（发电机族）之后第三个被搬的族，也是第一个非 void 返回被 defer 的族。为它引入了 Mekanism 编译期依赖——之前 `@Pseudo` + 无 dep 是规则，现在为了 `HeatAPI.HeatTransfer` 这一种返回类型破例。

### 为什么是现在

读完真源码（[E:\mekanism-src](E:\mekanism-src)，`v1.21.1-10.7.19.85`，与已安装 jar 同一个 tag）后，heater 之前被拒绝的根因是两个错位的架构假设：

1. **`simulate()` 返回 `HeatAPI$HeatTransfer`（Mekanism 类型）**。`@WrapOperation` 的处理器必须用 Java 声明返回类型，附属之前没有 Mekanism dep，处理器只能写基本类型/`void`/已导入的类型——返回 `Object` 会让字节码插入 `checkcast HeatAPI$HeatTransfer`，运行期 `ClassCastException`。这一关只在破 dep 之后才能解。
2. **`BasicHeatCapacitor.heatToHandle` 是裸 `double`，`handleHeat` 是 `heatToHandle += transfer` 没有任何同步**。两个相邻 heater 同时搬上去 = 两个 worker 并发写对方电容 = lost update = 热值漂移。readiness 闸门（沿用 generator 的 `outputCaches` 那一招）只盖住了 `BlockCapabilityCache` **注册**那一刻的写（mutate level 的 capability-listener map），盖不住每 tick 的 heat 写。

解法：把整个 `simulate()` 搬到服务器线程。worker 端返回 `new HeatTransfer(0.0, 0.0)`，能量簿记（`energyContainer.extract`、`heatCapacitor.handleHeat(self)`、`setActive`、`soundScale`）继续在 worker 上跑；热交换（六次 `simulateAdjacent` + 邻居读 + `sink.handleHeat` 写对方电容）在服务器线程、tile 锁下原样执行。display 字段（`lastEnvironmentLoss`/`lastTransferLoss`）在 deferred body 里用 `@Shadow` 写真值，比 worker 先再写早一 tick，但 container sync 也在 tick boundary 上，净效果正确。

### 做了什么

| 改动 | 文件 |
| --- | --- |
| 加 `HEATER_SHAPES`（两个 FQCN），`chainAllowed` 接受；`verdict` 不变（heater 无 readiness 闸门） | `MekOffloadPolicy.java` |
| 新 mixin `TileEntityResistiveHeaterSimulateMixin` + `TileEntityFuelwoodHeaterSimulateMixin`，`@WrapOperation` 包 `ITileHeatHandler.simulate()`，`@Shadow lastEnvironmentLoss/lastTransferLoss` | `src/main/java/.../mixin/` |
| 注册进 `threadtearer_mek.mixins.json` | `src/main/resources/` |
| `tools/verify_mixin_targets.py` 加 2 条 CHECKS（javap 对 `this.simulate()` 这类 self-interface-call 不会带 owner 前缀，按 self 类匹配） | `tools/` |
| `gradle.properties` 加 `mekanism_compile_jar` / `mekanismgenerators_compile_jar`，`build.gradle` `compileOnly files(...)` |  |
| 新测试 `heatersAreOffloadedThroughSimulateDefer`；DENIED javadoc 里"Heat simulators"那段改为"已在 0.3.9 搬走" | `MekOffloadPolicyTest.java` + `MekOffloadPolicy.java` |

### 编译期 dep，运行时不变

`compileOnly files(...)` 只在编译时把 Mekanism jar 放上 classpath，运行时不带。附属 jar 还是薄的；运行时仍然依赖用户安装的 Mekanism（`neoforge.mods.toml` 里声明）。Mekanism 的版本是用户安装的版本，不固定——所以 dep 走绝对路径，本机用本机的 jar，下次 Mekanism 升级时改 `gradle.properties` 一行。

### "2"（我说的第二个核心改动）改了什么

第二个改动原本是"核心给 `TileEntityMekanism.tickServer` 整体加 wrapper"。读真源码后发现这个改动的问题和 heater 一样：`tickServer` 里 `frequencyComponent.tickServer(level, pos)` 摸 `MultiblockManager` 的共享 `multiblocksTicked` set，`sendUpdatePacket` 是网络——任一一个挪到 worker 都引入新的共享状态竞争，闸门盖不住。所以**没做这个**。改成了"用 dep 把下一个非 void 族也搬上来"，目标是 `TileEntityLogisticalSorter`。但读了 sorter 的 `TransitRequest`/`TransitResponse` 之后发现另一个问题：`emitItemToTransporter` 的返回值的 `useAll()` 是不是负责从 `back` 拿掉物品——不读清整个 `InventoryUtils.getEjectItemMap` 链路不敢动（拿错一个就是物品复制）。sorter 暂搁，等清完那个链路再说。所以 0.3.9 只搬了 heater。

### 测试

12 项单元测试全过（0.3.8 是 11，加了 `heatersAreOffloadedThroughSimulateDefer`），22 条 `@WrapOperation` target 断言全过（加 2 条 heater 的 `simulate()` 调用点）。编译期 `VERDICT: MIXIN PACKAGE HYGIENE FAILED` 闸门 + `VERIFY MIXIN PACKAGES CLEAN` 闸门都过。

**尚未进游戏。** 装上 0.3.9 后进游戏看：
1. 不能有 `IllegalClassLoadError`（这是上次崩的回潮检查）；
2. 两个 heater 的 `lastEnvironmentLoss`/`lastTransferLoss` 显示在电脑上不要一直是 0；
3. `computeTicks` / `parallel=` / `lockwait=` 的读数。如果 heater 实际搬上去的效果能看见，下一步就是清 `TransitRequest` 链路把 sorter 也搬了。

### 热修：0.3.9 启动崩

```
InjectionError: Critical injection failure: Callback method mek$deferSimulate
in threadtearer_mek.mixins.json:TileEntityFuelwoodHeaterSimulateMixin from mod
threadtearer_mek failed injection check, (0/1) succeeded. Scanned 0 target(s).
```

`@At` 写的是 `Lmekanism/common/capabilities/heat/ITileHeatHandler;simulate()L…;`。**字节码里的符号引用所有者不是接口，是 `TileEntityFuelwoodHeater`**——`invokevirtual` 调用接口 default method 时，JVM 规定符号引用由实现类拥有，不是由接口拥有。Mixin 按字面匹配 `Methodref` owner，结果扫到 0 个目标。

`verify_mixin_targets.py` 没抓到这个，因为脚本的 `method_invokes` 在 javap 显示 `simulate:()L…;`（没有 owner 前缀）时按"self-call 默认归属 self_owner"——`ITileHeatHandler` 反而过了（脚本里手工写的就是接口 owner）。这里 javap 的归约策略和 Mixin 的归约策略不一致，**javap 对 self-call 落到 self_owner，Mixin 对 self-call 落到实现类**。两条工具的语义错开了。

修复：两个 mixin 的 `@At` 都改成加热器类本身。`ResistiveHeater` 那条原本"过了"是因为 `interfaces_count=0`、`#14` 解析成加热器类，Mixin 实际扫到的是同一个加热器类的 `simulate`——但 owner 写错了按理也应该扫到 0。可能 Resistive 的注入在 Fuelwood 抛错之前没真正验证完毕（mod loading 一抛错就停）。不管怎样，统一改成加热器类 owner 都对。

`tools/verify_mixin_targets.py` 的 CHECKS 之前是 `mekanism.common.capabilities.heat.ITileHeatHandler`（错的），那次 deploy 之前我已经对过好—改成 `TileEntityResistiveHeater` / `TileEntityFuelwoodHeater`（对的）。所以 verify 全过也救不了这次——是脚本本身的 owner 约定和 Mixin 不一致。**这一类错配验证应该作为新闸门加到脚本里**：`@At` target 的 owner 必须从目标类的常量池 owner 推出来，而不是按 self-call 的语义猜。下一版加。

测试 12 项全过，22 条 mixin target 断言全过。md5 `2d7a5828…`。

### 热修 #2：还是 0.3.9 启动崩

`@At` owner 修对了之后，第二条崩出来了：

```
InvalidInjectionException: @WrapOperation operation wrapper method
mek$deferSimulate has an invalid signature. Found unexpected argument type
Operation at index 0, expected TileEntityFuelwoodHeater.

Expected signature:
(LTileEntityFuelwoodHeater;LOperation;)LHeatTransfer;
```

`invokevirtual` 的 `@WrapOperation` handler 必须把**调用点的接收者**作为第 0 个参数。我之前写成 `(Operation<HeatTransfer> original)`——只接了 Operation，丢了接收者。MixinExtras 直接把期望签名打出来。

修复：handler 改 `(@Coerce Object self, Operation<HeatTransfer> original)`，body 里 `original.call(self)`、`runLockedBlockEntityTick((BlockEntity) self, ...)`。两个 mixin 都改。两个 `@At` owner 也都是加热器类，热修 #1 没解决的部分由这条解决。

`tools/verify_mixin_targets.py` 还是抓不到这条——脚本验的是"调用点存在"，不验"handler 签名符合 MixinExtras 约定"。**下一版要把 handler 签名核对也加进去**：拿 mixin 类字节码反编译，对每个 `@At` 的 opcode 类型（`INVOKE`/`INVOKESTATIC`/`INVOKEVIRTUAL`/`INVOKEINTERFACE`/`INVOKESPECIAL`）核对 handler 第 0 个参数是否符合 MixinExtras 规则。

md5 `8f886629…`。

## [0.3.8] - 2026-09-19

附属 threadtearer-mek：**修复 0.3.7 的崩溃，并把这类崩溃变成构建期就能拦下的错误。**

### 崩溃

```
org.spongepowered.asm.mixin.transformer.throwables.IllegalClassLoadError:
com.taolesi.threadtearer.mek.mixin.MekDeferral is in a defined mixin package
com.taolesi.threadtearer.mek.mixin.* owned by threadtearer_mek.mixins.json
and cannot be referenced directly
    at ... TileEntityGenerator.wrapOperation$dji000$threadtearer_mek$mek$deferEnergyEmit(TileEntityGenerator.java:584)
        <- TileEntityGenerator.onUpdateServer
        <- TileEntityGasGenerator.onUpdateServer
        <- TileEntityMekanism.tickServer
```

### 根因

`MekDeferral` 是普通辅助类，不是 mixin，但它和 mixin 放在同一个包里。

Mixin 把配置里 `package` 字段声明的整个包当成自己的领地：这个包里的每个类都被当作该配置的 mixin，**没有在配置里声明的类，不允许被任何被转换过的目标引用**。而 mixin 的方法体是**内联**进目标类的——mixin 里对 `MekDeferral` 的调用，落到字节码里就是 Mekanism 自己的类在调用它。第一次解析这条调用时 Mixin 直接终止游戏。

为什么没在测试世界暴露：多方块族的 deferral（锅炉、涡轮、裂变、聚变、矩阵、SPS）**只有世界里真的存在那种结构才会触发**，而发电机 emit 的 deferral 是即时触发的——所以这个错误一直藏到第一台燃气发电机跑起来。**测试世界的覆盖范围决定了构建期检查的必要性：一个只有特定结构才应用的 mixin，它的每一行都在等待第一次真实触发。**

### 修复

- `MekDeferral` 从 `...mek.mixin` 移到 `com.taolesi.threadtearer.mek`，类和四个方法改为 `public`，7 个使用它的 mixin 加上 import。
- 类注释写清了这条约束和它造成的后果，下一个把它挪回去的人会先看到原因。

### 新增构建期闸门：`verifyMixins`

`tools/verify_mixin_targets.py` 增加第一道检查——**mixin 包卫生**：读每个 `*.mixins.json` 的 `package` 字段和 `mixins` 列表，包内任何未声明的 `.java` 文件直接失败。配置自己的 `plugin` 类是唯一合法的例外（Mixin 按名字从配置解析它，从不从被转换的类引用）。

这道检查和原有的 20 条 `@WrapOperation` target 断言一起挂在 `compileJava` 之前，编译不通过就没有产物。python 不存在时自动跳过，不阻塞无 python 的机器。

**没有改任何卸载逻辑，也没有搬新的 tick 族**——0.3.6 的发电机族和 0.3.5 的多方块族仍然没有进游戏实测读数，这一版只修崩溃。

## [0.3.7] - 2026-09-19

附属 threadtearer-mek：**不再靠人肉读字节码判断"这个 tick 能不能搬"，改成算出来的；算出来一个真漏洞并堵上。**

### 为什么要做这件事

形状规则（"继承 `TileEntityConfigurableMachine` / `TileEntityGenerator` 就能搬"）是对**没有编译期依赖的第三方代码**下的断言：这些子类的 tick 不碰世界。这个断言已经被证伪过一次——发电机族里四个子类在**自己的 tick 体里**读世界，最后只能按名字拒绝。

人肉读字节码不可扩展，也不会组合：一个 tick 通过私有辅助方法、或者通过接口 default 方法（`ITileHeatHandler.simulate()` → `simulateAdjacent()` → `getAdjacent()`）伸手进世界，任何"读一遍 tick 方法体"的做法都看不见。所以这次不读了，改成对**整个已安装 Mekanism jar** 建调用图，反向算出哪些 tick 体可达世界 API，并给出最短路径。

### `tools/audit_reach.py`

直接从 jar 里解析 class 文件（不反编译、不联网）：常量池 → 方法体 → 字节码 → 调用图，25,226 个方法体全部走到末尾（`malformed: 0`，这个数字是自检——操作码表错一格就会失步并报出来）。

边分三种，因为危险程度不同：

| 类别 | 含义 |
| --- | --- |
| **DEFERRED** | 本附属自己的 mixin 包住并交还服务器线程的调用（ejector push、generator emit、multiblock sim）。路径走到这里就停——worker 跑调用方，服务器线程跑被调用方，这是设计好的切分。 |
| **UNHANDLED** | 没有任何东西拦截的世界访问：NeoForge 能力缓存与它的全局监听器表、光照引擎、chunk source、`WorldUtils`、邻居 push、`BucketPickup`。worker 调用这些就是活的跨线程 bug。 |
| **HANDLED** | 核心 `LevelMixin` 拦截并改道的 vanilla 世界写入。worker 走这条路是正常路径。 |

两个设计细节决定了结论是否可信：

- **两条独立通道。** 一条算"这个 tick 停在哪里"（含 DEFERRED 边界），一条算"有没有东西是任何 mixin 都拦不住的"。如果只用一条、并在 DEFERRED 处停止，那么一个既 eject（已推迟）又在别处读世界的 tick 会**只报出较短的那条路径**，真漏洞被盖住。
- **确定性分级。** 静态调用图不知道虚调用的运行期类型，所以边分"必然"（`invokespecial`/`invokestatic`，编译期绑定；或者 `invokevirtual`/`invokeinterface` 只有一个子类实现）和"可能"（多个子类，运行期决定）。只有必然可达才算漏洞，可能的按"卡在哪个调用点"分组报出。

### 算出来的结果

40 个形状允许的方块实体：

- **15 个** 在推迟边界处停住，够不到任何未拦截的世界 API。
- **2 个** 必然可达：`TileEntityQuantumEntangloporter`（已在黑名单）和 **`TileEntitySolarNeutronActivator`**。
- **23 个** 可能可达，全部卡在少数几个虚调用点上。

### 堵上的洞：`TileEntitySolarNeutronActivator`

它的 tick 体里直接读太阳：

```java
// TileEntitySolarNeutronActivator.onUpdateServer 偏移 30
this.productionRate = recalculateProductionRate();
//   -> WorldUtils.getSunBrightness(level, 1.0f)
//   -> Level.isRaining() / isThundering()
```

读出来的值存进 `productionRate`，随后被 recipe cache 消费。**读进算术的值不能像 push 那样推迟**——推迟 `getSunBrightness` 就得凭空造一个数。而且这个读**不在**被包住的 ejector 调用点下面，形状规则覆盖不到它。已按名字加入黑名单，黑名单 6 → 7 项。

`tools/audit_instantiation.py` 回答了"可能"那一组的另一半：把可疑子类型（`StackedWasteBarrel`、两个 `Sending*HandlerTarget`）的所有构造点找出来。`StackedWasteBarrel` 只有一个构造点，在 `TileEntityRadioactiveWasteBarrel.getInitialChemicalTanks` —— 不在形状允许的族里，所以那个虚调用点上的接收者不可能是它。这一组不构成漏洞。

### 这一版没做的事

**没有继续搬新的 tick 族。** 上一步（0.3.6 发电机族）**尚未进游戏实测**，所以 `computeTicks` / `parallel=` 没有新读数，不能声称发电机卸载产生了效果。先把"形状规则到底还漏不漏"这件事从人肉判断变成可复算的，再往上加族。

测试 11 项全过（`denyListIsPinned` 6 → 7，`offloadablePopulationIsExactlyTheseChains` 去掉 SolarNeutronActivator），`verify_mixin_targets.py` 20 条断言全过。

**尚未进游戏。**

## [0.3.6] - 2026-09-19

附属 threadtearer-mek：**先如实记 0.3.5 的实测，再把发电机族搬上去。**

### 0.3.5 实测（真的进了游戏）

九个 mixin 全部应用，`Failed to apply mixin` 0 次，`InvalidMemberDescriptor` 0 次 —— 崩溃修好了，游戏正常跑。

但**读数与 0.3.3 逐项一致，多方块那一层没有产生可测量的变化**。0.3.3 在 tick=500 的样本与 0.3.5 在 tick=800 的样本，按每 tick 归一化之后：

| 读数 | 0.3.3 (tick=500) | 0.3.5 (tick=800) | 每 tick |
| --- | --- | --- | --- |
| `computeTicks` | 172 | 172 | 172（这是**每 tick** 值，`drainCompletedTicks()` 每 tick 清零） |
| flush writes | 85038 | 136354 | 170 → 170 |
| flush calls | 498 | 798 | ≈1 → ≈1 |
| `lockwait` 次数 / ms | 45 / 174.44 | 46 / 177.49 | ≈0.09 → ≈0.06 |
| `parallel` | 0.040x | 0.031x | — |

写入速率、flush 调用速率、锁等待速率**全都没有变**。多方块的 emit 推迟本应让写入数上升（每个结构每 tick 多几次 push），没有出现。两种可能：玩家的基地里当前没有在运行的多方块，或者 `TileEntityMultiblockDataTickMixin` 的偷取没生效。**从这份读数分不出来，不能声称多方块路径已验证。**

### 真正的问题：搬走了 3% 的 tick 预算

不管多方块如何，读数已经说清了收益的**量级**：

```
computeTicks=172                       // 每 tick 172 个 BE 搬到计算池
compute[cpu=149.4ms tasks=17200 8.7µs/task workers=15 parallel=0.031x busiest=14%]
```

- 172 个 BE/tick 确实在计算池里跑（17200 tasks / 100 ticks = 172/tick，与 `computeTicks` 对得上）。
- 但它们一个 tick 总共只消耗 **1.5 ms CPU**（172 × 8.7 µs），而 tick 预算是 50 ms。**搬走的是 3%。**
- `parallel=0.031x` 不是 bug，是"没有东西可并行"：15 个 worker 去抢 1.5 ms 的活，抢完就闲着。`busiest=14%` 同理 —— 活被摊平了，但总量太小。

所以下一步的杠杆不是"再加几个机器类"，而是**把真正重的族搬上去**。被拒绝的那些里，数字采矿机、电动泵、两个加热器的 tick 体是数量级更大的。

### 这一版：发电机族

`TileEntityGenerator` 是第二个受覆盖形状。它的 tick 里伸手进世界的地方只有两处，而且需要**相反**的处理：

```java
if (canFunction()) {
    if (outputCaches == null) {                    // 只能留在服务器线程
        for (side : getEnergySides()) outputCaches.add(
            BlockEnergyCapabilityCache.create((ServerLevel) level, ...));
    }
    CableUtils.emit(outputCaches, energyContainer, getMaxOutput());   // 推迟
}
```

缓存构建**不能推迟**——下一句就要读这个 list——而且它注册能力监听器，会改 `level` 的非线程安全 `byChunkThenBlock`。所以 `MekOffloadPolicy` 在 `outputCaches` 还是 null 时把整个 tick 留在服务器线程，从构建完成后的那一 tick 起才放行。判断用反射读字段（本模块没有编译期 Mekanism 依赖），每类解析一次、每 tick 一次字段读。

emit 是普通情况，和这个附属里其他所有 push 一样推迟。

### 族里另外四个被拒绝

它们**在自己的 tick 体里**读世界，而读出来的值是喂进后续算术的，不能像 push 那样推迟 —— 推迟 `getBoost()` 等于凭空造一个数：

| 类 | 读什么 |
| --- | --- |
| `TileEntityHeatGenerator` | `getBoost` → `WorldUtils.getFluidState` ×6 邻居 + `dimensionType().ultraWarm()` |
| `TileEntitySolarGenerator` | `checkCanSeeSun` → `SolarCheck` → `WorldUtils.canSeeSun` |
| `TileEntityAdvancedSolarGenerator` | 继承上一条 |
| `TileEntityWindGenerator` | `getMultiplier` → `Level.getFluidState` / `canSeeSky` / `dimensionType()` |

**形状规则分不开它们**——读在子类里，不在受覆盖的基类里——所以这次是**按名字**拒绝，黑名单从 2 项涨到 6 项。搬走的是 `TileEntityGenerator` / `TileEntityBioGenerator` / `TileEntityGasGenerator`，它们的 tick 体只有自己的罐、槽和能量容器。

### 测试与验证

附属 11 项测试全过，新增 `generatorsAreSplitIntoMovingAndRefusedMembers`（3 个搬走 + 4 个拒绝逐条钉死），`denyListIsPinned` 从 2 改到 6。`tools/verify_mixin_targets.py` 加到 20 条断言，全过。

**尚未进游戏。**

## [0.3.5] - 2026-09-19

附属 threadtearer-mek：**把多方块也搬上计算线程，这次不是靠拒绝，是靠把伸手进世界的那几句单独推迟回去。** 0.3.4 在启动时崩了，这一版是修好之后的第一版。已进游戏实测，启动正常、九个 mixin 全部应用；实测读数见 [0.3.6]。

### 0.3.4 为什么崩

0.3.4 的做法是给八个多方块数据类各写一个 mixin，包住它们 tick 里的 `Util.emit` 调用点。启动时挂在 `SPSMultiblockDataEjectMixin`：

```
InvalidMemberDescriptorException: Invalid owner: Lmekanism/common/util/ChemicalUtil;emit(//
Failed validating @At("INVOKE").target "Lmekanism/common/util/ChemicalUtil;emit(...)V"
```

从 0.3.4 的 jar 里 `javap -v` 读出来，那个 target 常量池里就是字面量 `Lmekanism/common/util/ChemicalUtil;emit(...)V` —— **我把 `(...)` 当成占位符写进了注解，从来没有填上真实描述符**。Mixin 在第一个 `(` 处截断，拿到 owner + 方法名之后无法确定该匹配哪个重载，解析在 mixin 应用阶段就失败，`Mekanism.<clinit>` 跟着炸，游戏起不来。同一批里其余七个 mixin 用的是真实描述符，所以只有这一个报了错。

### 修法：两层

**第一层，改挂在哪里。** 不再逐个去猜每个数据类 tick 里有哪些 emit，而是只包**一处**：

```java
// TileEntityMultiblock.onUpdateServer() 的字节码
187: invokevirtual MultiblockData.tick:(Lnet/minecraft/world/level/Level;)Z
```

`TileEntityMultiblockDataTickMixin` 包住这一个调用点。它上面是 `structure.tick()`（成型校验、读世界）、`recheckStructure` 重建、`markForSave`；下面是 `getManager().markTicked()`（共享队列）和子类钩子 `onUpdateServer(multiblock)`（外壳的邻居更新）。**只搬中间那句**——那正是整个结构的 CPU 开销，而且已经被 `isMaster()` 守住，一个结构只有一个 BE 会跑。锁用 `MultiblockData` 实例本身，所以不同结构并行、同一结构串行。

**第二层，把每一族里剩下的世界访问挑出来。** 逐族读 tick 字节码，凡是不碰数据对象自己的槽/罐/容器的调用都包住并推迟：

| 族 | 被推迟的调用 |
| --- | --- |
| Boiler | 两处 `ChemicalUtil.emit`（蒸汽罐、冷却剂罐）、`hotMap.put` |
| Matrix | `CableUtils.emit(…, J)`、`markDirtyComparator(Level)` |
| SPS | `ChemicalUtil.emit`、`kill(Level)` |
| Turbine | `FluidUtils.emit` **和消费它返回值的 `extract`**、`CableUtils.emit` |
| Fission | 两处 `ChemicalUtil.emit`、`burnFuel` 内的 `IRadiationManager.radiate`、`handleDamage`、`radiateEntities` |
| Fusion | `CableUtils.emit`、`ChemicalUtil.emit`、`kill(Level)` |

三个不显然的地方：

- **Boiler 的 `hotMap`** 是 `public static final Object2BooleanMap`，服务器上每个锅炉共用，底层是 `Object2BooleanOpenHashMap`。两台锅炉在两个 worker 上并发写它，和线程外写能力缓存是同一类 bug，所以也推迟。
- **Turbine 的 emit 和 drain 是一对**。原版是 `ventTank.extract(FluidUtils.emit(targets, ventTank.getFluid()), …)`——emit 的返回值就是随后从罐里抽走的量。只推迟 emit 会让 drain 在 worker 上拿到推迟 emit 返回的 0：邻居收到了水、罐里还留着，**每 tick 复制一份水**。所以两句一起走 `MekDeferral.runEmitThenDrain`。那个 drain 返回 `FluidStack`，而 mixin 没有编译期 Mekanism 依赖，方法名无法在 handler 里声明；调用点也不能包（handler 只能把返回类型放宽到超类型，MixinExtras 随后插入的 `checkcast FluidStack` 在本模块编译不过），所以用反射解析 `IExtendedFluidTank.extract`。
- **Fission 的 `burnFuel`** 是 tick 里的一个自调用，`radiate` 在它内部，所以 `@WrapOperation` 的 `method` 指 `burnFuel(Level)V` 而不是 `tick`。

### 顺带堵上一个洞：数据类的名单

`MultiblockData` 不是方块实体，走不到 `mayOffload`，所以它有自己的门：`MekOffloadPolicy.mayOffloadMultiblockData`，对照 `MULTIBLOCK_DATA` 这份**六个族的封闭清单**。清单外的一律留在服务器线程。这不是保守，是必需的——一个数据类的 tick 之所以能搬，前提是它的世界访问**全部**被上面的 mixin 包住了；Mekanism 更新新增第七族、或者附属 mod 自己写一个，都没有这层保护。动态储罐（`TankMultiblockData`）和热蒸发（`EvaporationMultiblockData`）读字节码后确实不碰邻居，但**本模块没有为它们写 defer mixin**，所以不进清单——一个基地一般只有一座，拒绝它们不损失可测量的性能。

### 验证方式

`tools/verify_mixin_targets.py`：从**真实的已安装 jar** 里提出每个被 mixin 目标的类，`javap -p -c -s` 反汇编，逐个断言 mixin 里写的 owner + 方法名 + 描述符 + ordinal 在目标方法里真的存在。0.3.4 那个崩溃，这个脚本会在构建前就报出来（把 `emit(...)` 这条断言拿掉后它会立刻 FAIL）。19 条断言现在全过。

### 测试

附属 10 项全过，其中新增 `multiblockDataClassesAreExactlyTheDeferredFamilies`，把上面那份六族清单钉死：多一个就说明有人加了族却没写 defer mixin。

## [0.3.3] - 2026-09-19

附属 threadtearer-mek：**0.3.1 / 0.3.2 的策略还有一个我自己挖出来的 bug，在进游戏之前堵上。** 0.3.1 没进过游戏；这一版是它进游戏前的最后一道修。

### Bug 的解剖

我把 0.3.0 的 `chainAllowed` 重写时，删掉了一个早返：

```java
// 0.3.0：
for (String name : superChain) {
    if (...) return false;
    if (MEKANISM_BASE.equals(name)) return true;   // 命中基类立即返 true
}
return false;

// 0.3.1 / 0.3.2（错的）：
boolean deferredShape = false, mekanismBase = false;
for (String name : superChain) {
    if (!name.startsWith("mekanism.") || DENIED.contains(name)) return false;
    deferredShape |= SHAPE.equals(name);
    mekanismBase |= BASE.equals(name);
}
return deferredShape && mekanismBase;
```

`c.getSuperclass()` 一路向上**必然**经过 `TileEntityMekanism`（基类） → `mekanism.common.tile.base.CapabilityTileEntity` → `net.minecraft.world.level.block.entity.BlockEntity` → `net.neoforged.neoforge.attachment.AttachmentHolder` → `java.lang.Object`，后几层都不在 `mekanism.` 包内。0.3.0 的早返让它走到 BASE 立刻停；我的版本走完整个 chain，被 `!startsWith("mekanism.")` 在 vanilla 祖先上击落。**每个类都被判 false**，所以 0.3.0 之前的 monitor 里 `computeTicks=0` 不是"没机器"，是"全部被我的 bug 拒绝"。

### 怎么找到的

部署 0.3.2（包含一次性的 per-class 诊断日志：每个 BE 类第一次 tick 时打印 `mayOffload={true/false} stealTick={true/false} reason={policy/offloaded/stealTick-false}`，最多 30 个类）。玩家的 Mekanism 基地里 log 出 30 行：

- **12 类 `offloaded`**：ItemStackToItemStackFactory、ItemStackChemicalToItemStackFactory、PressurizedReactionChamber、EnergyCube、Crusher、ElectrolyticSeparator、PrecisionSawmill、RotaryCondensentrator、SolarNeutronActivator、IsotopicCentrifuge、ChemicalInfuser、ChemicalOxidizer。
- **18 类 `reason=policy`**：DimensionalStabilizer、DigitalMiner、QuantumEntangloporter、TileEntityGasGenerator、所有 turbine/rotor/coil，加上所有第三方 addons（meklg、mekextras、mekaf、mekmm、CompactMekanismMachines、mekenergistics）。

12/30 搬走说明策略**开始工作了**（之前是 0/30）—— 修法对了。诊断日志达成目的，删除。

### 修法

命中 `MEKANISM_BASE` 立刻返 `deferredShape`（不看后面那段 vanilla 祖先），没到 BASE 返 false：

```java
static boolean chainAllowed(List<String> superChain) {
    boolean deferredShape = false;
    for (String name : superChain) {
        if (name == null || !name.startsWith(MEKANISM_PACKAGE) || DENIED.contains(name)) {
            return false;
        }
        deferredShape |= DEFERRED_SHAPE.equals(name);
        if (MEKANISM_BASE.equals(name)) {
            return deferredShape;
        }
    }
    return false;
}
```

加了一条回归测试 `walkStopsAtTheMekanismBaseAndIgnoresTheVanillaAncestorsAboveIt`：把"链过了 BASE + 后面还挂着 `CapabilityTileEntity` / `BlockEntity` / `AttachmentHolder`"作为入参，断言能过 —— 0.3.1/0.3.2 这条断言会 fail，防止有人未来再把早返删掉。

### 实测（这次真的进了游戏）

```
computeTicks=171
phases[... flush=302.60/498(85095 writes, 85138 deferred) ... lockwait=614.11/52]
AE2 NPE: 0
```

- 171 个 BE tick 跑到计算池
- 8.5 万次 flush write，全部是 eject 推迟（"一个 tick 一个 flush"对得上）
- 52 次 lockwait 总 614 ms（约 12 ms/事件）—— 形状规则之外的新增停顿，按设计就该由 `lockwait=` 暴露
- **0 个 AE2 NPE**，0.3.0 的洞（发电机族 / 加热器自己建 `BlockCapabilityCache`）一个都没复现

### 测试

附属 9 项测试（含新的回归测试）全过；核心 54 项全过。

## [0.3.1] - 2026-09-19

附属 threadtearer-mek：**0.3.0 的策略漏了一个洞，在实测之前先堵上。** 0.3.0 构建并部署了，但**没有进过游戏**；这一版是它进游戏前的最后一道审计，只改附属，核心不动。

### 洞在哪

0.3.0 的规则是"链上每一层都在 `mekanism.` 包内且不在黑名单里"。这条规则默认了**整条链上唯一伸手进世界的地方就是被包住的那个 ejector 调用点**——因为 `TileEntityConfigurableMachineEjectMixin` 只包那一处。这个默认对配方机器成立，对**发电机不成立**：

```
TileEntityGenerator.onUpdateServer()
    if (outputCaches == null) outputCaches.add(BlockEnergyCapabilityCache.create((ServerLevel) level, ...));
    CableUtils.emit(outputCaches, energyContainer, maxOutput);
```

它自己就地建 `BlockCapabilityCache`（构造时 `level.registerCapabilityListener` 注册监听器）并往邻居里 emit。这和 ejector 是**同一类危险**，而 0.3.0 没有任何东西推迟它。黑名单里也没有它。

发现方式：不靠读代码猜，而是把**已安装的全部 7 个 Mekanism 系 jar**（Mekanism、MekanismGenerators、MekanismTools、mekanism_extras、mekanismsun、mekmm、mekenergistics、compactmekanismmachines…）里 327 个 `TileEntityMekanism` 后代全部提出来，逐个 `javap -c` 扫 `onUpdateServer` / `onUpdateClient` / `tickServer` 的**字节码**，匹配能力缓存、emit、`setBlock`、`gameEvent`、实体扫描、tick、`WorldUtils`、多方块宿主等模式。结果：327 个里有 **9 个**在 0.3.0 策略下会被搬走，而它们自己就伸手进世界——`TileEntityGenerator` 一族 7 个，加上 `TileEntityResistiveHeater`、`TileEntityFuelwoodHeater`（`simulate()` → `simulateAdjacent()` → `getAdjacent(side)`，同样建 `BlockCapabilityCache` 读邻居热容）。

### 修法：把"能不能搬"和"mixIn 覆盖了什么"绑成一个条件

不再维护一份"哪些类危险"的清单（清单永远追不上模组更新），而是改成**形状规则**：一条链只有**包含 `TileEntityConfigurableMachine`** 才可能被搬。

```java
static boolean chainAllowed(List<String> superChain) {
    boolean deferredShape = false, mekanismBase = false;
    for (String name : superChain) {
        if (name == null || !name.startsWith("mekanism.") || DENIED.contains(name)) return false;
        deferredShape |= "mekanism.common.tile.prefab.TileEntityConfigurableMachine".equals(name);
        mekanismBase  |= "mekanism.common.tile.base.TileEntityMekanism".equals(name);
    }
    return deferredShape && mekanismBase;
}
```

这一步把安全性从"清单要对"变成**构造性**的：能被搬的类，其继承的 tick 里唯一伸手进世界的那句调用点，必然就是被 `@WrapOperation` 包住的那句。发电机族、激光族、多方块族、QIO 族现在都是被**形状**挡掉的，不是被名字挡掉的——将来 Mekanism 新增一整个机器族，只要它不在这个形状里，默认就是安全的。

黑名单因此从 17 项缩到 **2 项**，只留形状内部确有的两个例外：

- `TileEntityQuantumEntangloporter`：`InventoryFrequency.handleEject` 遍历同频率**所有**量子传送器并推入它们的邻居，且自身 tick 也跑 `simulate()` 读邻居热容。
- `TileEntityFormulaicAssemblicator`：`moveItemsToGrid` / `organizeStock` 通过配方 API 读世界并写合成网格。

### 覆盖范围

已安装的 327 个后代里，**38 个**通过：

```
TileEntityEnrichmentChamber / Crusher / EnergizedSmelter / OsmiumCompressor /
PurificationChamber / ChemicalInjectionChamber / MetallurgicInfuser / Combiner /
PaintingMachine / PigmentExtractor / PrecisionSawmill / PressurizedReactionChamber /
AntiprotonicNucleosynthesizer / ChemicalCrystallizer / ChemicalDissolutionChamber /
ChemicalOxidizer / NutritionalLiquifier / ChemicalInfuser / ChemicalWasher /
ElectrolyticSeparator / IsotopicCentrifuge / PigmentMixer / RotaryCondensentrator /
SolarNeutronActivator / Oredictionificator
+ 6 个工厂（Factory / ItemToItem / ItemStackToItemStack / ItemStackChemicalToItemStack /
  Sawing / Combining）
+ EnergyCube / ChemicalTank
+ 6 个抽象基类（ConfigurableMachine / RecipeMachine / ProgressMachine /
  ElectricMachine / AdvancedElectricMachine / Factory）
```

也就是**一台 Mekanism 基地里真正吃 CPU 的全部**。被挡掉的 289 个里绝大多数 tick 是空的或只改一个字段（多方块外壳、涡轮线圈、反应堆组件、储罐、发电机、激光），拒绝它们不损失可测量的性能。

### 测试

`MekOffloadPolicyTest` 重写为 8 个用例，**每一条超类链都是从上文那 7 个 jar 里读出来的真实链**，不是手写的。这一条是必需的：上一版测试里写着 `TileEntityResistiveHeater` 继承 `TileEntityConfigurableMachine`——听起来合理、测试通过，而真实的它直接继承 `TileEntityMekanism`，也就是说那条用例在测一个不存在的类。现在 `offloadablePopulationIsExactlyTheseChains` 把 38 条链全部钉死，`worldReachingFamiliesAreRefusedByTheShapeRule` 把 9 个洞逐条钉死。

核心测试 30 项、附属测试 8 项全部通过。附属的 `neoforge.mods.toml` 把核心依赖从 `[0.2.1,)` 收紧到 `[0.3.0,)`（`deferWorldWrite` 是 0.3.0 才有的 API，否则运行期 `NoSuchMethodError`）。

### 还没验证的

还是同一句话：多核收益的**幅度**要等实测。可以确定的是被搬迁的机器数量从 ~7 台/tick 变成"38 个类"，`phases[...]` 的 `deferred` 计数可以直接看出每 tick 有多少次喷出被推迟到服务器线程。

## [0.3.0] - 2026-09-19

**把 ejector gate 换成真正的拆分，多核从 0.1% 变成有意义的量。** 0.2.22 用"会不会喷出邻居"来决定整台机器搬不搬，结论是安全性与收益对立：真实基地里绝大多数机器都配了输出面，所以只有 7 台/tick 被搬走。这次不再拒绝这些机器，而是**把它们的一整个 tick 拆成两半**。

### 反汇编得到的 tick 结构

`TileEntityConfigurableMachine.onUpdateServer` 字节码只有两条语句：

```
0: invokespecial TileEntityMekanism.onUpdateServer:()Z   // 配方 / 能量 / 槽位
5: getfield      ejectorComponent
9: invokevirtual TileComponentEjector.tickServer:()V     // 推入邻居
```

第一半昂贵且只碰机器自己的状态 → 归 worker；第二半建 `BlockCapabilityCache` 并调用邻居能力接口 → 归服务器线程。全 Mekanism 里 `tickServer()` 只有这一处调用点，所以一个 mixin 覆盖全部机器（电动机器、工厂、化学机器、能量立方、化学储罐），不需要按子类写。

### 改动

- **核心新增 `MCTRuntime.deferWorldWrite(Runnable)`**：把一次世界写交给服务器线程的**每 tick 批量队列**（`WriteCoalescer`），不走交互 FIFO。满基地喷出的机器对服务器线程的开销是**每 tick 一个任务**，不是每台机器一个任务。这是"计算线程算完主动通知服务器线程"的那个原语；`scheduleWorldInteraction` 保留给需要与玩家交互排定顺序的写。
- **附属新增 `TileEntityConfigurableMachineEjectMixin`**：`@WrapOperation` 包住上面那个调用点。在 worker 上就把这次推入 `deferWorldWrite` 掉，在服务器线程上原样调用。延迟的推入重新进入 ticker 持有的**同一个 per-BE 锁**，所以不会与下一 tick 的配方计算重叠；最多晚一 tick，而物品喷出本来就是 10 tick 一次的延迟，且推的是"当下槽位里的东西"而不是上一 tick 的差值，因此不可见。
- **`MekOffloadPolicy` 重写为超类链判定 + 黑名单**。之前两版都错在**按具体类名做子串匹配**：Mekanism 用配方给机器命名（`TileEntityEnrichmentChamber`、`TileEntityCrusher`、`TileEntityItemToItemFactory`），所以 `"tileentityelectricmachine"` 这类**基类**名字一个都匹配不上——审计做得再对，匹配器把它全丢了，这才是 `computeTicks=7` 的真正原因。现在从具体类沿 `getSuperclass()` 往上走，链上每一个类都必须在 `mekanism.` 包内、且不在黑名单里，走到 `TileEntityMekanism` 才通过。链上命中一个黑名单类就否掉整族（`TileEntityBasicLaser` 一行否掉激光三兄弟，`TileEntityMultiblock` 一行否掉锅炉/动态储罐/感应矩阵/SPS/热蒸发五族）。
- **删除 `EjectorProbe`**（连同它的反射缓存）。它存在的唯一理由是"拒绝会喷出的机器"，而这个策略已经不存在了。
- **黑名单 17 项**，每项都是读源码定的：邻居能力缓存 + emit（Bin / FluidTank / 放射性废料桶 / 物流分拣器 / 数字采矿机 / 电动泵）、`InventoryFrequency.handleEject` 遍历同频率全部量子传送器（量子传送器）、传送本身（传送器）、AABB 实体扫描（充能板、`TileEntityBasicLaser`）、放流体方块（注液器）、广播 sculk 事件（震源振动器）、发区块 ticket（维度稳定器）、写合成网格（配方组装机）、读写玩家容器正在编辑的物品（改装台）、驱动 QIO 网络（`TileEntityQIOComponent`）。

### 测试

`MekOffloadPolicyTest` 8 个用例，把每条审计过的超类链写死并断言判定结果：`TileEntityEnrichmentChamber` / `TileEntityCrusher` 显式列出（就是旧匹配器丢掉的那个回归）、激光族与多方块族由基类否决、QIO 族由 `TileEntityQIOComponent` 否决、黑名单大小钉在 17、外来包与未知链 fail-closed。核心测试 30 项、附属测试 8 项全部通过。

### 还没验证的

多核收益的**幅度**要等实测。可以确定的是被搬迁的机器数量从 ~7 台/tick 变成"除 17 个黑名单类以外的全部 Mekanism 方块实体"，`phases[...]` 新增 `deferred` 计数可以直接看出每 tick 有多少次喷出被推迟到服务器线程。

## [0.2.22] - 2026-09-19

附属 threadtearer-mek：**找到 0.2.20 漏掉的那条路径**。0.2.20 修的两处（删 `isSameThread` 谎报、附属包前缀限制）确实在 jar 里，但卡死又回来了——因为还有第三条通道没堵。

- **漏掉的路径**：每个白名单机器的 `onUpdateServer` 都会链式调用 `TileEntityConfigurableMachine.onUpdateServer` → `ejectorComponent.tickServer()`。而 `TileComponentEjector.tickServer()` 会创建绑定 `ServerLevel` 的 `BlockCapabilityCache` 并调用**邻居的能力接口**。邻居若是 ME 网络方块，这次调用就从计算线程进了 AE2，破坏它的 `TickHandler` 队列（服务器线程同时在 drain 同一个 `ArrayDeque`：`isEmpty()` 说 false、`poll()` 返回 null，AE2 的 catch-and-retry 于是打了 **62113 条 NPE**、日志涨到 111MB、服务器线程卡在写日志上 13-68 秒）。这也解释了 0.2.20"成功"的偶然性：取决于基地里有没有配置了输出面的机器、以及它旁边是不是 ME 方块。
- **修法**：新增 `EjectorProbe`——运行时读 `ejectorComponent.configInfo`，若任一传输类型配了输出面（`ConfigInfo.isEjecting()`）则该机器留在服务器线程；没有输出面的机器照常多核。反汇编确认 `isEjecting()` 只看配置（`configInfo.isEjecting() && canEject.test(type)`），是只读判断，可在任意线程安全调用。
- **fail-closed**：反射读不到就当作"会喷出"→ 留服务器线程。判断错成"是"只损失性能，错成"否"会毁世界。
- **kill switch**：`-Dthreadtearer.mek.offload=false` 让所有 Mekanism tick 回到服务器线程，用来二分确认卡死是否与附属有关。

## [0.2.22] - 2026-09-19

- **ejector gate 验证有效**：AE2 的 `Queue.poll` NPE 从 **62113 条降到 7 条**，服务器线程不再卡在写日志上，`Can't keep up` 从多次降到 2 次（均为区块加载期的 `getChunkBlocking`，属正常世界加载）。附属启动日志确认 36 个类通过审计、按"是否喷出邻居"逐个判定。
- **并行度指标格式修正**：`parallel` 从 2 位小数改 3 位，并新增 `µs/task`。原格式在真实数据下显示 `0.00x` 无法判读；现在能看出关键信息——**7 任务/tick × 7µs = 0.05ms/tick**，即多核确实在跑但工作量可忽略。

### 当前多核收益的诚实评估

ejector gate 排掉了所有"配了输出面"的机器，而在真实基地里这恰恰是大多数（机器需要把产物推出去）。剩下能搬迁的是没有输出面的机器，实测约 7 台/tick、每任务 7µs，合计 0.05ms/tick，相对 50ms 的 tick 预算占比 0.1%。**安全性与收益目前是对立的**：要拿到多核收益，必须让"喷出"这条路径也变得线程安全（把 `ejectorComponent.tickServer()` 整体搬到服务器线程执行、只把配方/能量计算留在 worker），这是下一步的工作。

## [0.2.21] - 2026-09-19

**可证的多核指标。** 之前的 `computeActive`（`ThreadPoolExecutor.getActiveCount()`）是个坏指标：它在任务极短时采样经常读到 0，看起来像"没用多核"，实际只是没抓到瞬间。新增三个无法作假的数字，随 monitor 行输出：

```
compute[cpu=520ms tasks=13100 workers=14 parallel=9.80x busiest=9%]
```

- `cpu` — 计算任务消耗的 CPU 时间总和（每任务 `nanoTime` 差累加）
- `tasks` — 本区间完成的任务数
- `workers` — **实际执行过任务的 worker 数**（按线程名分组统计，不是池大小）
- `parallel` — `cpu / 墙钟时间`。这是并行度因子：≈1 表示"CPU 时间 ≈ 墙钟时间"，即单核串行；≈9.8 表示平均 9.8 个核同时在跑
- `busiest` — 最忙的那个 worker 承担的任务占比。接近 `100/workers` 表示负载均衡；接近 100% 表示实际还是单线程

统计由 `ComputePool.drainStats()` 提供，只在写日志行时才 drain（热路径零开销）。同时顺手记录每个 worker 的任务计数，负载不均时能直接看出来。

## [0.2.20] - 2026-09-19

**修复 62k 次 AE2 NPE + 60 秒级卡死。** 看门狗（0.2.12 起）抓到了现场：`AE2:S` 打了 62113 条 `Queue.poll() is null`，服务器线程卡在 `TickHandler.processQueue` 上。

- **根因 1（核心）**：`BlockableEventLoopMixin` 让 compute 线程对 `MinecraftServer.isSameThread()` 谎报 `true`（当初为 GregTech 配方 IO 加的）。后果是所有用 `isSameThread()` 判断"我是否在服务端线程"的模组，都会在**计算线程上直接跑服务端专用路径**。AE2 的 `TickHandler` 队列因此被两个线程同时 drain：`isEmpty()` 返回 false、`poll()` 返回 null，而 AE2 的 catch 块是"记日志后 `goto` 回到循环头"，于是无限循环打日志——62113 条 NPE、111MB 日志、单 tick 卡 13-68 秒。GregTech 支持在基线里已经不存在，这个谎报没有任何受益人，直接删除。
- **根因 2（附属）**：`MekOffloadPolicy` 只按类名匹配，而 Mekanism 附属（如 `mekenergistics` 的 `MeMekanismMachineBlockEntity extends TileEntityConfigurableMachine`）继承 Mekanism 基类、类名却在自己的包里，于是被误判为可搬迁。它的 tick 会碰 AE2 网络 → 触发上面那条路径。现在要求**精确的 `mekanism.` 包前缀**：只有 Mekanism 本体可能被搬迁，所有附属（`com.beipuo.mekenergistics`、`com.jerry.mekextras`、`mekmm`、`compactmekanismmachines`）自动默认拒绝。这不是名字启发式，是精确的包归属判断。
- 附属测试补 `MeMekanismMachineBlockEntity` / `MeFactoryAeMachine` 两条断言。

## [0.2.19] - 2026-09-19

**修复严重卡死**（看门狗记录到 tick 167 卡了 68 秒、1364 个 tick 落后）。AE2 自己没有出 bug——是我们的 `runOnServerThread` 路径造成的。

- **根因**：计算线程的世界写以前走 `MinecraftServer.execute()` 直达 server thread 队列。AE2 的 `TickHandler` 在 `ServerTickEvent.Post` 里执行 `ILevelRunnable` 队列，runnable 内部调世界写 → 我们的 mixin 拦截 → 再次 `s.execute()` 排回同一队列 → **server thread 在等自己之前排进去的 task 取出来才能返回**。当某个 runnable 抛 NPE（任何原因）时，队列污染+阻塞使 modernfix 的 `waitUntilNextTick` 一直 park 到下一 tick，下一 tick 又 NPE，雪崩到 68 秒。
- **修复**：`runOnServerThread` 改为统一走 `WriteCoalescer`（不再直接 `s.execute`），把所有计算线程世界写合并到 tick 边界**一次** drain。AE2 runnable 在 drain 期间不再自调度——drain 一次性跑完整个批后返回，下一 tick 可正常开始。同时新加公共 API `queueComputeWorldWrite` 让附属在 Post 回调里也能走同一路径。
- 源码里所有 `s.execute` 调用只剩一处：WriteCoalescer.flush 的单次 drain（tick 边界一次性调度），其余全部改成 `writeCoalescer.enqueue`。

## [0.2.18] - 2026-09-19

附属 threadtearer-mek：逐个审计 16 个原先被拒绝的类，**7 个可以安全地搬到计算线程**（之前是一刀切）。每一个判断都基于反汇编 tick 体调用表：

| 类 | 之前 | 现在 | 为什么 |
|---|---|---|---|
| `TileEntityTeleporter` | 拒绝 | **白名单** | `canTeleport` 只读 frequency，`cleanTeleportCache`/`resetBounds` 是本地状态；实际传送由传送接口异步完成 |
| `TileEntityChargepad` | 拒绝 | **白名单** | `Level.getEntitiesOfClass` 是只读 AABB 快照，结果本 tick 消费，1 tick 延迟无害 |
| `TileEntityBin` | 拒绝 | **白名单** | `HandlerTransitRequest.addItem` 只是入队，真正的物品移动由 handler 异步执行 |
| `TileEntityFluidTank` | 拒绝 | **白名单** | 只读 config + 计算 scale，无世界写 |
| `TileEntityRadioactiveWasteBarrel` | 拒绝 | **白名单** | 只调用 `shrinkStack` 操作自己的气罐 |
| `TileEntityQuantumEntangloporter` | 拒绝 | **白名单** | 热模拟 + frequency 状态；`Frequency` 内部已加锁 |
| `TileEntitySecurityDesk` | 拒绝 | **白名单** | `SecurityFrequency.setOverridden` 由 `Frequency` 内部加锁 |

剩下 9 类仍然默认拒绝：`LogisticalSorter` / `DigitalMiner` / `ElectricPump` / `BasicLaser` 持 `BlockCapabilityCache` over `ServerLevel`（缓存按区块事件失效，离线程读会读到陈旧或缺失条目，导致物品推到错的邻居）；`Multiblock` 走 `MultiblockManager.handleDirtyMultiblock`（与结构成形检查竞态）；`DimensionalStabilizer` 发区块票据；`ModificationStation` 读玩家手持 `ItemStack.getItem`（与 GUI swap 竞态）；`FormulaicAssemblicator` 往合成格移动物品；`QIOComponent` 驱动 QIO 网络导入/导出。

测试已同步扩充：`auditedMekClassesAreOffloaded` 新增 7 个白名单断言；`riskyMekClassesStayOnTheServerThread` 减少为 9 个拒绝断言。

## [0.2.17] - 2026-09-19

附属 threadtearer-mek 切到**白名单 + 默认拒绝**。前一版的黑名单 + 默认放行让未审计的 Mekanism 附属类（如 `TileEntityExtraBin`）静默搬到计算线程——`TileEntityExtraBin extends TileEntityMekanism`，名字不含 `tileentitybin` 黑名单条目，所以 `mayOffload` 返回 true。它的 tick 体持 `BlockCapabilityCache` over `ServerLevel` 并往相邻容器推物品，在计算线程上跑会写坏世界状态。

- **白名单（29 条，逐一反汇编确认只触碰自己的槽位/能量/配方缓存）**：`TileEntityMekanism` / `TileEntityElectricMachine` / `TileEntityAdvancedElectricMachine` / `TileEntityFactory` / `TileEntityLaserAmplifier` / `TileEntityAntiprotonicNucleosynthesizer` / `TileEntityChemicalCrystallizer` / `TileEntityChemicalDissolutionChamber` / `TileEntityChemicalInfuser` / `TileEntityChemicalOxidizer` / `TileEntityChemicalWasher` / `TileEntityCombiner` / `TileEntityElectrolyticSeparator` / `TileEntityFluidicPlenisher` / `TileEntityFuelwoodHeater` / `TileEntityIsotopicCentrifuge` / `TileEntityMetallurgicInfuser` / `TileEntityNutritionalLiquifier` / `TileEntityOredictionificator` / `TileEntityPaintingMachine` / `TileEntityPigmentExtractor` / `TileEntityPigmentMixer` / `TileEntityPrecisionSawmill` / `TileEntityPressurizedReactionChamber` / `TileEntityResistiveHeater` / `TileEntityRotaryCondensentrator` / `TileEntitySeismicVibrator` / `TileEntitySolarNeutronActivator` / `TileEntitySuperheatingElement`。
- **明确拒绝（tick 体里有能力缓存 / 实体查询 / 跨维度 / 多方块状态 / 改玩家物品）**：`TileEntityTeleporter`、`LogisticalSorter`、`Chargepad`、`Bin`、`FluidTank`、`RadioactiveWasteBarrel`、`DigitalMiner`、`ElectricPump`、`BasicLaser`、`Multiblock`、`QIOComponent`、`SecurityDesk`、`ModificationStation`、`QuantumEntangloporter`、`DimensionalStabilizer`、`FormulaicAssemblicator`。
- **未知类**（Mekanism 附属或未来加的 BE）一律留服务器线程——这是默认拒绝的意义。
- `MekOffloadPolicyTest` 把每条规则钉住；写错一个名字（如之前把 `Solar` 拼成 `Sol`）就测试失败，避免了"默写错一个字、几个 BE 在计算线程静默写坏状态"。

## [0.2.16] - 2026-09-19

实测 `computeTicks=42`（搬迁已生效，`Can't keep up` 只剩启动期 2 次，交互无延迟）。这版削掉热路径开销，并让附属从"逐基类打补丁"改为"单点全覆盖"。

- **去掉 `steal` 的每次调用计时**：`steal` 在 100 tick 内被调用 **3,351,155 次**（约 33k/tick，区块加载期每个 mod 的光照/区块/方块更新都会经过）。`StealHotPathCostTest` 量化了插桩本身的开销：**55.6 ns/次**（未插桩路径 0.1 ns），按 33k 次/tick 计就是 **1.84 ms/tick**，比它守护的工作还贵。`PhaseTimings` 现在只统计真正做事的分支（apply / flush / 邻居更新）。
- **`isOnServerThread()` 改为缓存线程引用**：原来是 `MCTRuntimeImpl.get()` + `server()` + `isServerThread()` 三次 volatile 读加一次多态调用，现在一次 volatile 读加一次 `currentThread()` 比较。服务器线程在 `onServerStarting` 时发布到 `InteractionRelocator.cacheServerThread`。
- **门控顺序调整**：服务器线程快速返回提到 `MCThreadConfig` 读取之前，`ON_INTERACTION` 检查排到它后面——最热的路径先短路。

### 附属 threadtearer-mek

- **单点全覆盖**：改为 `@WrapOperation` 包住 `TileEntityMekanism.tickServer` 里那一次 `onUpdateServer()` 虚调用。Mekanism 有 **48 个类覆写 `onUpdateServer`**，之前只挂了 3 个基类（漏掉整个工厂族、储能立方、化学罐、QIO、多方块等）。现在一个钩子覆盖全部，且 `tickServer` 里其余部分（frequency/upgrade/chunkloader 组件、`setActive` 方块状态、比较器、更新包）仍留在服务器线程——正是想要的切分：配方/能量计算搬走，世界记账不搬。
- **`MekOffloadPolicy` 安全边界**：逐类反汇编 tick 体后列出必须留在服务器线程的类（实体查询、绑定 `ServerLevel` 的 `BlockCapabilityCache`、区块加载/票据、传送、跨维度频率传输、多方块结构状态）。`MekOffloadPolicyTest` 把每条规则钉住——规则若被误删，测试失败而不是游戏在计算线程上写坏状态。
- 需要 MixinExtras（`@WrapOperation`）；NeoForge 21.1 自带 `mixinextras-neoforge:0.5.3`，附属无需额外依赖。

## [0.2.15] - 2026-09-19

**多核优化此前从未生效** —— 实测 `computeTicks=0`（addon 已装、mixin 已确认应用）。

- **根因**：`stealTick(Object blockEntity, Runnable)` 用 `callerName()` 门控，而 `callerName()` 拿到的是**目标类 + mixin handler 方法名**（如 `TileEntityElectricMachine.handler$zdh000$mek$relocateOnUpdateServer`），这种名字永远过不了 `isBlockEntityTick`。附属入口现在**完全不看名字**：调用方已经声明"这是方块实体 tick"，再做猜测只会出错。
- **反方向的误分类一并修掉**：`Level.blockEntityChanged` / `Level.setBlockEntity` / `Level.removeBlockEntity` / `Level.addFreshBlockEntities` 含 "blockentity" 却被当成 tick 送进计算池（此前 `computeTicks≈98/tick` 的来源）。`isBlockEntityTick` 现在排除 `Level.*` / `LevelChunk.*` / `ServerLevel.*`。
- **新增 `TickClassificationTest`**：钉住分类规则，包括"mixin handler 帧不可按名字分类"这一条——将来若有人想给它加名字门控，测试会直接失败，而不是静默停止搬迁。
- 测试依赖补 `org.spongepowered:mixin`（`InteractionRelocator` 的方法签名引用 `CallbackInfo`，否则单测加载类就报 `NoClassDefFoundError`）。

### 附属 threadtearer-mek

- **删除每 tick 搬迁上限**（原 12 个/tick）。所有 Mekanism 基础机器 tick 全部交给计算池，机器数量 N 决定并行度而不是一个固定切片。
- 限制改由两处承担：① 计算池队列超过 64 时 `stealTick` 退化为原版（背压）；② 计算线程的世界写在 tick 边界合批，服务器线程每 tick 只处理一批，与机器数量无关。

## [0.2.14] - 2026-09-19

看门狗（0.2.12 引入的外部采样线程）抓到了真正的开销来源：**服务器线程自身在 `InteractionRelocator.steal` 里**。跨所有 stall dump，服务器线程栈上共 64 帧属于 threadtearer，其中 `stealImpl` 第 116 行（`callerName()` 的 StackWalker）出现 6 次、第 145 行（`stealTickNamed`）1 次、第 167 行（旧的 fast path 又调一次 `callerName`）2 次。

- **`steal` 首行加服务器线程快速返回**：`!ON_COMPUTE && !ON_TICK && APPLY_DEPTH==0 && isOnServerThread()` 直接 `return false`（不取消原方法，vanilla 就在当前线程执行——正是调用方想要的）。放在 `callerName()` 之前，于是热路径完全跳过 StackWalker（实测 1.7-2.2 µs/次）以及后面约 50 次字符串分类操作。此前**每个 mod 的每一次世界写**都在付这个开销：区块加载时每 tick 有数千次光照/方块更新，叠加起来就是 tick 里的可观测耗时。
- **顺带修掉一个误分类**：`Level.blockEntityChanged`、`Level.setBlockEntity` 这类名字包含 "blockentity"，会被 `isBlockEntityTick` 命中并送进计算池当成方块实体 tick 处理。服务器线程上的这类调用现在直接走原版路径。
- **看门狗报告节流**：单次长卡顿原本每 500ms 就烧掉一份报告预算（12 份用尽后整场游戏再无输出）。改为每 3 秒最多一份、上限 40 份，长时间会话也能持续采样。

## [0.2.13] - 2026-09-19

- **延迟 apply 的区块卸载守卫**：看门狗抓到服务器线程停在 `ServerChunkCache.getChunkBlocking`（栈底是 `Level.getBlockState` ← `SignalGetter.getDirectSignal` ← EnderIO 的 `updateRedstonePower`）。根因是延迟写入在**之后的 tick** 执行，那时目标区块可能已卸载，vanilla 于是同步从磁盘加载区块——单次数百毫秒到数秒。`setBlock` / `removeBlock` / `destroyBlock` / `removeBlockEntity` / `blockEntityChanged` 以及计算线程侧的状态读取现在都先做非阻塞的 `isLoaded` 检查，未加载就跳过：丢一个延迟方块变更远好于卡住 tick 循环。
- 去掉 fast path 里重复的 `callerName()` 调用。

## [0.2.12] - 2026-09-19

- **`TickWatchdog`：从外部采样的停顿检测**。0.2.11 的检测器在 tick 末尾从服务器线程自己采样自己，栈永远是检测器那几帧（自证循环），看不到任何阻塞点。改为独立守护线程每 500ms 检查 tick 是否推进，卡住就 dump 服务器线程栈 + 所有 `MCT-*` worker 栈（互相等锁时两边栈同时出现）。

## [0.2.11] - 2026-09-19

诊断轮：这一版的目标是**让数据看得见**，同时修掉读代码时发现的两个真实缺陷。

- **查明 monitor 为何从不输出**：`log_deduplicator` 这个第三方 mod 开着 `fuzzy_matching = true` 且把数字归一化，monitor 行每 100 tick 只差几个数字，被判为重复，INFO 阈值 20 次之后整行被吞掉。改为 `WARN` + 数据接入聊天指令 `/threadtearer monitor status`（chat 不被去重）。之前"加了测量但看不到"就是这个原因。
- **修 housekeeping 静默死亡**：`ThreadPoolExecutor` 默认吞掉任务抛出的 Throwable，`writeCoalescer.flush()` 一抛异常，后面的 monitor/统计全部不再执行且无任何日志。现在 flush 与 housekeeping 各自 try/catch + 限流报错（`MCThread.Lifecycle`）。
- **加服务器线程停顿检测**：单 tick 超过 1s 时 dump 服务器线程栈（`MCThread.Stall`，最多 20 次）。日志事后无法区分"整体慢"和"卡在某个调用"，这个能一次定位。
- **删除 MekSafety 快照与 per-BE 锁**：`beforeTick` 在服务器线程上 `lock.lock()` 阻塞，而锁的持有者是计算线程（`afterTick` 的反射 diff），服务器线程因此可能卡死——与实测的 15 秒单次停顿吻合。快照只用于诊断漂移，收益远小于风险，整包删除。
- **修 MekTickThrottle 计数器膨胀**：`incrementAndGet() <= MAX` 让每次被拒的调用也自增，计数器冲到 ~98 后一旦漏掉一次重置就永久失效（实测 `computeTicks=9824/100ticks` ≈ 98/tick，远超上限 12 即为证据）。改为到达上限后不再自增，fail-closed 退化为原版。
- **计算池背压**：`ComputePool.queueDepth()` 超过 64 时停止偷取 tick，交给原版执行。队列无限增长时每个排队任务都对着越来越旧的世界状态跑，同时新任务继续涌入，形成失控反馈环。

## [0.2.9] - 2026-09-19

- **计算线程轻量写路径（按实测数据修正方向）**：实测 `setBlocks=809real/40noop`（重复率仅 4.7%，no-op 短路帮不上忙），瓶颈在每次 setBlock 的 fan-out（~1.5ms：每观看玩家一个 `ClientboundBlockUpdatePacket`、6 方向立即 `neighborChanged`（邻居是 MEK 机器时每次红石重查）、比较器/形状级联）。现在计算线程/交互线程的 `Level.setBlock` 走轻量路径：
  - **立即**：`setBlock(pos, state, 0, recursionLeft)`——只做区块 section 写入 + 方块实体替换（`LevelChunk.setBlockState` 与 flags 无关的部分），与 vanilla worldgen 语义一致；
  - **客户端同步**：`ServerChunkSource.blockChanged(pos)` 标记 section 脏，vanilla `ChunkHolder` 每 tick 每区块自动发**一个**批量 `ClientboundSectionBlocksUpdatePacket`（替代每写×观看玩家数的单块包）；
  - **邻居更新**：进 `NeighborUpdateBatch` 去重（每位置每 tick 一次），tick 边界服务器线程统一 `updateNeighborsAt` + 比较器更新。机器邻居延迟 ≤1 tick 重查红石，与机器语义相容；
  - 服务器线程的原版/玩家路径完全不动，保持立即更新语义。预期计算线程 setBlock 的服务器开销从 ~1.5ms/次 降到 ~0.1-0.2ms/次。

## [0.2.8] - 2026-09-19

- setBlock no-op 短路计数器：`real/no-op` 两个 AtomicLong 接入 monitor 日志（`setBlocks=N/M (real/no-op)`），用于量化 MEK 写重复率。计数器从 `LevelMixin` 移到独立类 `monitor/SetBlockCounters`（mixin 类禁止非 private static 成员，直接放 mixin 会在应用阶段 `InvalidMixinException` 崩溃）。

## [0.2.7] - 2026-09-19

- **Level.setBlock 无变化短路**：当 `state == oldState`（方块已经是目标状态）时，setBlock mixin 直接 `setReturnValue(false)` 返回，跳过整个 apply 链（区块 section 写入、邻居通知、tile entity 唤醒、客户端包 fan-out）。MEK ejector / bounding block / comparator 等每 tick 把同一状态写一遍的代码路径直接被短路，**服务器线程实际工作量降一个数量级**。这是解决 586 tick 积压 / 29 秒延迟的关键。

## [0.2.6] - 2026-09-19

- **计算线程世界写合并（WriteCoalescer）**：`MCTRuntimeImpl.runOnServerThread` 把跨计算线程的多次 `s.execute` 调用合并到一个 server task，于 `ServerTickEvent.Post` 触发 flush。一次 MEK BE tick 触发的所有 setBlock/ejector 包从 N 个 server 任务变成 1 个，服务器线程队列从上百次降到个位数；服务器线程能跑出 20 TPS。
- 设计哲学落实：计算线程算完主动把 apply 请求塞入 coalescer 缓冲，**服务器线程不再被计算线程每个 setBlock 阻塞**——服务器线程一次 tick 内可批量处理一整 tick 攒下来的所有请求。

## [0.2.5] - 2026-09-19

- **计算线程世界写跳过 interaction FIFO**：MEK / Jade 等计算线程调用的 `setBlock` / `addFresh` 等世界写直接 `runOnServerThread` 排进服务器线程队列，不再经 interaction FIFO 中转。FIFO 是单线程瓶颈，MEK 每游戏 tick 上百次世界写排队会拖慢整个 FIFO（玩家包也排在其中）。直传后多核收益保留：MEK 计算仍跑在 15 个 compute worker 上并行，计算 worker 不再因 FIFO hop 空闲等待；写回仍由服务器线程单线程串行（物理不可并行）。玩家包走 fast path 仍然 inline。

## [0.2.4] - 2026-09-19

- **默认开启交互分离**：`MCThreadConfig.offloadPlayerUseItem` 字段初始化为 `true`（与 spec 默认一致），不再依赖 `ModConfigEvent` 触发；config event 没跑或没读到文件时也保持开启。
- **修复顶级玩家包延迟**：`InteractionRelocator.steal` 新增快路径——当 `APPLY_DEPTH=0` 且当前线程是服务器线程时（玩家包处理、服务器控制台），apply 直接 inline 在服务器线程执行，**不再绕 interaction FIFO**。原 interaction FIFO 是单线程，被计算线程的世界写排队填满后会严重阻塞玩家包；快路径让玩家包恢复原生响应速度。计算线程的世界写仍走 FIFO 保证顺序。
- `MCTRuntimeImpl.runOnServerThread` 改为 public API：附属可直接调用把世界写塞回服务器线程（不再走 interaction FIFO）。

## [0.2.3] - 2026-09-19

- 修复 `Level.sendBlockUpdated` 在 `Level` 类本身没有具体方法体（仅在 `ClientLevel`/`ServerLevel` 上有实现，mappings 行 326 的 sendBlockUpdated 是 `setBlockAndUpdate` 的 inline call）所导致的 mixin 准备阶段 NPE。该包 fan-out 已经由 `setBlockAndUpdate` 注入覆盖（内部链式调用随整体转到服务器线程），单独注入已被移除。

## [0.2.2] - 2026-09-19

- **核心 mod 收口世界交互路由**：`Level.setBlockAndUpdate` 与 `Level.sendBlockUpdated` 加入 `LevelMixin`，从计算线程/交互线程调用会自动经交互 FIFO 转到服务器线程，与玩家交互保持顺序一致。`setBlockAndUpdate` 在 MC 1.21.1 返回 `boolean`（setBlock 与 sendBlockUpdated 结果的 OR），注入签名已对齐官方映射。附属 mod 今后不再需要写自己的 Level 拦截 mixin，直接调 `InteractionRelocator.steal/stealAndCancel/stealWorldApply` 即可。

## [0.2.1] - 2026-09-17

- **新增附属 API：`MCT.runtime().scheduleWorldInteraction(name, apply)`**——附属在提交的 tick 计算（计算线程）内把世界交互交给交互线程：FIFO 与玩家交互保序，最终在服务器线程 apply，返回 `CompletableFuture<OffloadOutcome>` 观察结果（切勿在服务器线程 join）。服务器线程上调用则内联执行（原版语义）。
- `OffloadOutcome` 从 experiment 包迁入 `api` 包（公开 API 的一部分）。
- `InteractionRelocator.stealWorldApply(name, apply)`：Mixin 式便捷入口——计算/交互线程上调用返回 true 并转入管线（调用方跳过内联写）；服务器线程返回 false（调用方按原版内联执行）。`runApply` 同步开放为公开方法。
- 管线新增 2 个单测（计算线程提交保序 + apply 失败传播）；gametest 新增 compute→worldInteraction 端到端用例；`/threadtearer selftest` 覆盖新接口。

## [0.2.0] - 2026-09-17

- **回到纯基线**：移除全部第三方 mod 相关代码（AE2 / Mekanism / SFM / XNet / Integrated Dynamics / Soul Surge / RFTools / GTCEu / ExtendedAE 等 mixin 与并发集合补丁），不再自动搬迁任何原版方块实体 tick。
- 仅保留两大能力：
  1. **交互线程分离**——玩家放置/使用/攻击/菜单/背包等交互经快照在 MCT-Interaction 线程计算、服务器线程 apply（`experiments.offloadPlayerUseItem` 开关）。
  2. **计算线程提交接口**——`MCT.runtime().submitCompute / scheduleInteraction / optimistic / Transaction`，供后续按"线程撕裂者"架构单独编写的插件 mod 搬迁 tick 计算使用；方块实体互斥锁 `InteractionRelocator.runLockedBlockEntityTick` 一并保留为公开 API。
- 保留原版交互路径 mixin（useItemOn / attack / clicked / 各类容器与 Level 写入钩子）与 profiler / replay / bench / monitor 工具链。
- 单元测试改为域无关的 FakeSession 管线测试；gametest 保留 compute / FIFO / optimistic / 交互 mixin 覆盖。

## Unreleased

- 从 1.20.1 Forge 迁到 **Minecraft 1.21.1 / NeoForge 21.1**：工程目录 `1.21.1-neoforge/`。
- 显示名改为 **线程撕裂者**（英文 Thread Tearer）；modId 仍为 `mcthread`。
- 仓库按版本拆分：`1.20.1-forge/` 与 `1.21.1-neoforge/`。

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
