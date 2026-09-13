# 线程撕裂者 · 1.21.1 NeoForge

英文名 **Thread Tearer**。本目录是 **Minecraft 1.21.1 / NeoForge** 的完整 Gradle 工程。

架构原理（三条线程、BE Tick 如何吃多核、AE2 为何留在主线程）见仓库根目录 **[README.md](../README.md)**。请先阅读那一份。

## 构建

需要 JDK 21。在 **本目录** 执行：

```powershell
.\gradlew.bat build                       # build/libs/mcthread-0.1.0.jar
.\gradlew.bat test
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runGameTestServer
.\gradlew.bat copyToMods -PmodsDir="D:\...\mods"
```

修改 Mixin 或运行时后必须完整重启游戏。Mixin 配置写在 `neoforge.mods.toml` 的 `[[mixins]]`，jar MANIFEST 也带 `MixinConfigs: mcthread.mixins.json`。

## 分支

本工程对应远程分支 `1.21.1-neoforge`。其它 Minecraft 版本会使用各自的目录与分支，不要把 1.20.1 的 Mixin 直接复制到更高版本。
