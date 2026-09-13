# MC Thread · 1.20.1 Forge

本目录是 **Minecraft 1.20.1 / Forge（NeoForge 兼容）** 的完整 Gradle 工程。

架构原理（三条线程、BE Tick 如何吃多核、AE2 为何留在主线程）见仓库根目录 **[README.md](../README.md)**。请先阅读那一份。

## 构建

需要 JDK 17（编译目标）与 JDK 21（ModDevGradle 工具链）。在 **本目录** 执行：

```powershell
.\gradlew.bat build                       # build/libs/mcthread-0.1.0.jar
.\gradlew.bat test
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runGameTestServer
.\gradlew.bat copyToMods -PmodsDir="D:\...\mods"
```

修改 Mixin 或运行时后必须完整重启游戏。官方 Forge 依赖 jar MANIFEST 中的 `MixinConfigs: mcthread.mixins.json`（已由 `build.gradle` 写入）。

## 分支

本工程对应远程分支 `1.20.1-forge`。其它 Minecraft 版本会使用各自的目录与分支，不要把 1.20.1 的 Mixin 直接复制到更高版本。
