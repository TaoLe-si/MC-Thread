# MC Thread 安装与使用指南

## 1. 前置条件

- Minecraft **1.20.1** + NeoForge **47.1.x**（推荐 47.1.106；Forge 47.1.3+ 亦可）；
- Java 17+（本模组编译目标 Java 17）。

## 2. 安装到客户端

1. 安装 NeoForge 1.20.1：从 neoforge.net 下载对应安装器，选择 **Install client**；
2. 找到游戏目录的 `mods` 文件夹：
   - 官方启动器：`%APPDATA%\.minecraft\mods`
   - HMCL / PCL2 等：你自己设置的 `.minecraft` 目录下的 `mods`；
3. 复制模组 jar（两种方式任选）：

```powershell
# 手动
Copy-Item D:\MCCode\build\libs\mcthread-0.1.0.jar "D:\你的游戏目录\mods\"

# 或使用 Gradle 任务（自动复制构建产物）
.\gradlew.bat copyToMods -PmodsDir="D:\你的游戏目录\mods"
```

4. 启动游戏，选择 **NeoForge** 配置；主菜单的"模组"列表应显示 **MC Thread**；
5. 单人世界需开启作弊，或在服务器控制台执行命令验证。

## 3. 安装到服务器

1. 安装 NeoForge 1.20.1 服务端（47.1.x），首次启动生成 `eula.txt` 并改为 `eula=true`；
2. 将 `mcthread-0.1.0.jar` 复制到服务端 `mods` 文件夹；
3. 启动服务器，控制台应出现 `[MC Thread]` 开头的日志。

## 4. 命令速查

| 命令 | 作用 |
| --- | --- |
| `/mcthread runtime status` | 运行时总览（线程数、profiler/replay/benchmark 状态、最近 tick 变化、已挂载适配器） |
| `/mcthread monitor status` | 游戏变化监测（最近 tick 的区块/实体变化计数） |
| `/mcthread adapters list` | 适配器清单与可用状态 |
| `/mcthread prof start [intervalMs]` | 开始 Tick 采样（默认 5ms 间隔） |
| `/mcthread prof stop` / `report [json]` | 停止采集 / 输出按模组归因报告 |
| `/mcthread replay start <场景>` / `stop` / `export` | 记录按 tick 的事件流并导出 JSON |
| `/mcthread bench start <ticks>` / `stop` / `report [json]` | 固定窗口基准测试（默认 600 ticks） |
| `/mcthread demo run <recipes> <iterations>` | 运行参考适配器（端到端演示乐观工作流） |
| `/mcthread selftest` | 运行时自检 |

## 5. 日志与数据解读

日志位于 `logs/latest.log`（服务器）或 `logs/debug.log`，按 logger 过滤：

| Logger | 内容 |
| --- | --- |
| `[MC Thread]` | 模组生命周期（加载、服务器启动/停止、适配器挂载） |
| `MCThread.Monitor` | 周期性游戏变化：`tick=... chunkLoads=... chunkUnloads=... entityJoins=... entityLeaves=...` |
| `MCThread.Optimistic` | 乐观工作流重试/预算耗尽（可据此发现"快照频繁过期"的热点） |
| `MCThread.Transaction` | 事务部分失败回滚（Heisenbug 追踪线索） |
| `MCThread.Adapters` | 适配器 attach/detach |

导出文件默认写入 `<服务器目录>/mcthread/`（开发环境为 `run/mcthread/`）：

- `profile-*.json` / `bench-*.json`：按模组采样占比 + 预估每 tick 耗时（`estTickMs`）、avg/max tick、overrun、GC 时间；
- `replay-*.json`：每条记录字段：`tick`、`tps`、`tickMs`、`players`、`chunkLoads`、`chunkUnloads`、`entityJoins`、`entityLeaves`、`overrun`。

## 6. 监测数据怎么用

1. **找尖峰原因**：`bench report` 出现高 `max`/`overrun` 时，对照 replay JSON 看同一 tick 的区块/实体变化计数；
2. **找大户**：`prof report` 的 `~ms/tick` 是每模组对主线程耗时的采样估算，优先处理占比最高的模组/帧；
3. **A/B 验证**：优化前后跑同一 `bench` 场景，Tick 平均耗时提升 ≥5% 才算有效（Rule-008）。
