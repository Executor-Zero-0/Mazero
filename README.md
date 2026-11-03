# Mazero

> 一款用 Kotlin 从零重写的 Android 迷宫游戏 —— 由原 C / NDK 工程完整迁移，无 JNI，纯 JVM 实现。

Mazero 是一款在方寸屏幕上“走迷宫”的休闲益智游戏。玩家需操控角色从起点走到终点，沿途可回撤、重置、缩放视角，享受沉浸式游玩体验。游戏内置三种风格迥异的玩法，通关后会生成独一无二的可分享凭证码。

## ✨ 核心特色

- **零 NDK**：原 C 工程逻辑全部用 Kotlin 重写，便于阅读、调试与二次开发。
- **三种迷宫玩法**：
  - **常规迷宫 (CGMG)**：经典单步走格。
  - **线性滑道 (XXHD)**：一次滑动，沿方向一路滑到障碍。
  - **重力行者 (ZLXZ)**：8 朝向规则表驱动，视角随角色旋转。
- **自绘渲染**：基于 `SurfaceView` + `Canvas` 的 60 fps 渲染循环，无第三方 UI 依赖。
- **多指手势**：支持双指缩放、平移，ZLXZ 额外支持双指旋转。
- **丰富功能**：内置地图编辑器、通关凭证分享、背景音乐播放及无障碍友好设计。

## 🎮 玩法简述

| 手势 | 操作 |
|---|---|
| 单指滑动 | 朝滑动方向移动 |
| 双指缩放 / 拖动 | 缩放 / 平移地图 |
| 顶部右侧 / 左侧图标 | 全屏切换 / 设置、撤销、重置 |

*注：三种迷宫对地图数据（`0` 或 `1`）的墙体语义定义不同，具体请见游戏内帮助。*

## 🚀 构建与运行

### 环境要求
- Android Studio Ladybug 及以上
- JDK 17 | Gradle 8.x | Kotlin 2.x

### 一键构建

git clone https://github.com/Executor-Zero-0/Mazero.git
cd Mazero
./gradlew assembleDebug

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
