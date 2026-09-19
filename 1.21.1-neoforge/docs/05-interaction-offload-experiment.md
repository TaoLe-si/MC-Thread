# 实验：把原版交互方法整段搬到交互线程

> 状态：spike（默认开）
> 对应方案：可行性分析中的「方案 A：交互线程统一化」

## 1. 要验证的假设

其它模组把 Mixin 打在原版交互方法上（`useItemOn`、`clicked`、`serverTick`、`dispenseFrom`…）。如果本模组在 HEAD cancel 再用自己的规则表重写，那些 Mixin **不会执行**，模组适配失败。

本实验改为：

> **A 发起请求（不放置）→ 交互线程计算 → 交互线程向主线程发 apply 请求 → 主线程放置 → 主线程再发更新请求 → 交互线程计算 → 主线程更新**
>
> Live State（`getBlockState` / `getBlockEntity` / `setBlock` / 实体交互）只在主线程 apply 阶段发生。其它模组打在原版方法上的 Mixin 随 apply 在主线程执行。

快照规则表（`TillRules` / `FurnaceRules` / …）仍用于 L1 单测和 `pipeline.submit`。运行时热路径把入口变成请求，而不是把原版方法体搬到交互线程去写世界。

## 2. 管线

```text
[A / Server]     发起请求，立刻返回（不放置、不 getBlockEntity）
        ▼
[Interaction]    计算
        ▼
[A / Server]     收到 apply 请求：放置 / 打开 / 实体交互（Live State 只在这里读写）
        ▼
[A / Server]     放置后把邻居更新再发成请求
        ▼
[Interaction]    计算更新
        ▼
[A / Server]     收到更新请求：updateNeighborsAt
        ▼
结束（owner 不等待）
```

`getBlockState` / `getBlockEntity` / 世界交互 / 实体交互都走这条环，不在交互线程碰 Live State。主线程 **不 join**。返回值是推测值（`useItemOn` → `SUCCESS`）。

交互线程不再打印请求/计算/apply 日志。

已纳入请求环的入口（开关打开后；`handlePingRequest` / 存档 IO / Netty `Connection.tick` / 看门狗 / 移动与 tick 除外）：

- **玩家放置 / 使用**：`handleUseItemOn` / `useItemOn` / `ItemStack.useOn` / `BlockStateBase.use` — 主线程不放置，交互线程计算后再由主线程 apply
- **邻居更新**：`Level.updateNeighborsAt` 在放置 apply 之后作为第二条请求
- **破坏 / 实体交互 / 菜单点击**：同样走请求 → 计算 → 主线程 apply
- **世界 tick**：玩家 / 实体 / 区块 / DistanceManager 留在主线程
- **方块实体 tick**：计算线程跑 ticker；tick 内的 `setBlock` / `removeBlock` / `addFreshEntity` / `setItem` 等写入发给交互线程，再由主线程 apply

主线程 **不 join**。返回值是推测值（`useItemOn` → `SUCCESS`）。

## 3. 通过标准

| # | 标准 | 覆盖 |
| --- | --- | --- |
| 1 | 快照路径：判定在交互线程，写入在 owner 线程 | L1 `hoeOnDirtAppliesFarmlandOnOwnerThread` / `FurnaceOffloadTest` |
| 2 | 请求路径：计算在交互线程，写入在 owner 线程；submit 不等待 | L1 `relocateVanillaComputesOnInteractionAppliesOnOwnerWithoutWaiting` |
| 3 | Mixin 抢走 `useItemOn` 且 `applied` 增加 | L2 `offloadMixinInterceptsUseItemOn` |
| 4 | 关闭开关后零行为变化 | `experiments.offloadPlayerUseItem=false` |
| 5 | 快照过期拒绝 | L1 + L2 `offloadTillRejectsStaleBlock` |
| 6 | 熔炉 / 箱子 / 漏斗 / 投掷器快照适配器 | L1 对应 `*OffloadTest` + L2 `offload*` |

## 4. 已知边界

1. **所有权**。Live State 只在主线程 apply。交互线程只计算。主线程不 join。
2. **其它模组 Mixin**。入口 HEAD 在主线程 cancel 并发请求；方法体在主线程 apply 时再跑，其它模组的 Mixin 跟 apply 走。
3. **推测返回值**。主线程立刻返回 `SUCCESS`，真实放置/打开晚一个请求往返。
4. **不进请求环**。移动包、ping、存档 IO、Netty `Connection.tick`、看门狗。模拟 tick 在计算线程；tick 内的世界写入走交互请求环。
5. **Forge 事件**。在 apply 阶段于主线程 `post`，监听器也在主线程。
6. **Amdahl**。请求往返本身有排队延迟；收益取决于主线程是否因此不再同步等待这些调用栈。

## 5. 怎么跑

```powershell
.\gradlew.bat test                 # L1
.\gradlew.bat runGameTestServer    # L2
```

游戏内（权限 2）：

```
/threadtearer experiment status
/threadtearer experiment on     # 放置/使用走请求→计算→主线程 apply
/threadtearer experiment off    # 立即回退原版
```

## 6. 结论

运行时热路径是 **请求环**：A 不放置，交互线程计算，主线程 apply，邻居更新再走一圈。`getBlockEntity` 只在主线程 apply 里发生，所以箱子/AE2 能看到方块实体。

开关默认开。
