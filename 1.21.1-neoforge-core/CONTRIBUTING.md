# 协同开发指南

仓库按 **Minecraft 版本 + 加载器** 拆分。当前实现在分支 `1.21.1-neoforge`、目录 `1.21.1-neoforge/`。架构说明见仓库根 [README.md](../README.md)。

## 环境准备

- **工程目录**：所有 Gradle 命令在 `1.21.1-neoforge/` 下执行，不要在仓库根目录跑 `gradlew`；
- **JDK 21**：模组编译目标（`java.toolchain.languageVersion = 21`）；通过 `gradle.properties` 的 `org.gradle.java.installations.paths` 指定（示例：`D:/java21`）；
- **Gradle 8.14.5**：使用本目录 Wrapper（`gradlew.bat`），无需手动安装；
- **网络说明（CN 网络）**：
  - Gradle 发行版已切换到腾讯云镜像（`gradle/wrapper/gradle-wrapper.properties`）；
  - Maven Central 走阿里云镜像（`build.gradle` 的 repositories）；
  - NeoForged Maven 偶发不可达且 Gradle 不会自动回退：`mixin` 与 `gson` 已 vendor 到 `libs/` 并用文件依赖引用。新增依赖时优先尝试 Maven Central/阿里云；若被 neoforged 仓库阻塞，按 `libs/` vendor 模式处理并在注释中说明原因。

## 开发流程

1. 从版本分支拉取最新（1.21.1 为 `1.21.1-neoforge`），再创建功能分支：`feature/<主题>`；
2. 实现并补充测试（改动只放进对应版本目录）；
3. 在 `1.21.1-neoforge/` 本地验证：`gradlew test --no-configuration-cache` 与 `gradlew build` 必须全绿；涉及游戏内行为的改动运行 `gradlew runGameTestServer`；
4. 提交（Conventional Commits 风格，中文或英文均可，建议英文）：
   - `feat(scope): ...` / `fix(scope): ...` / `docs: ...` / `test: ...` / `refactor: ...`
   - 示例：`feat(profiler): add per-mod estimated tick time`
5. 推送并创建 PR，描述改动、测试结果与 A/B 数据（如有）。

## 编码约定

### 线程纪律（最重要）

- 服务器线程是 Live State 的唯一所有者；
- `MCTRuntime.optimistic()` 返回的 `CompletableFuture` **禁止在服务器线程 `join()`**（最后阶段会调度回服务器线程，会死锁）——用 `whenComplete` 回调组合；
- `ComputeTask` 必须是纯函数：不得访问/修改 Live State、不得有隐藏副作用（Constraint-001）；
- `InteractionExecutor` 任务只处理可延后、可批处理的工作（IO、序列化、批量通知），或实验管线中的**纯快照判定**；不得修改 Live State。判定完成后由交互线程 **emit** 结果到服务器队列，主线程不得等待该结果。

### 优化项规则

- 每项优化必须有独立配置开关，默认保守（高风险默认关）；
- 开启前必须通过 A/B 门槛：同一 `bench` 场景 Tick 平均耗时提升 ≥5% 且无行为差异（Rule-008）；
- 任何行为差异：先关开关、再查证据，回滚优先。

### Mixin 规则

- 目标优先选择 NeoForge/Forge 通用类（保证对所有模组生效）；
- Forge 自有类目标使用 `remap = false`（其方法描述符在 dev/prod 一致，已通过 javap 验证）；
- `mcthread.mixins.json` 保持 `defaultRequire = 1`，注入点最小化；
- 新 Mixin 必须能解释清楚失效/回滚路径（如能力缓存的 `invalidateCaps/reviveCaps` 清空；交互卸载实验关闭 `experiments.offloadPlayerUseItem`）。

### API 边界

- `mcthread.api` 包不得依赖 Minecraft/实现类，供第三方模组编译接入；
- 第三方接入统一走 `MCT.runtime()`，未安装 线程撕裂者 时返回 NOOP。

### 测试要求

- 纯 Java 逻辑必须带 JUnit 用例（放 `src/test/java`，不依赖游戏环境）；
- 服务器内行为用 GameTest（`src/main/java/.../gametest/`）；
- 提交前必须 `gradlew test build` 全绿；
- 改动功能时同步更新 README 的指令集/配置表与对应 docs。

## 发布

- 版本号遵循语义化（`gradle.properties` 的 `mod_version`）；
- 发布前执行 [测试方案](docs/03-testing-plan.md) 的 L5（soak）与 L6（兼容矩阵）；
- 更新 [CHANGELOG.md](CHANGELOG.md)。
