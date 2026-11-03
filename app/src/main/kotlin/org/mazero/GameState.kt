package org.mazero

/**
 * 游戏全局状态常量
 * 对应原 C 工程 common.h 中的 STATE_* 定义
 */
object GameState {
    const val START = 0           // 启动画面
    const val MAIN_MENU = 1       // 主菜单
    const val LEVEL_SELECT = 2    // 关卡选择
    const val LEVEL_START = 3     // 关卡开始提示
    const val LEVEL_COMPLETE = 4  // 关卡完成
    const val GAME = 5            // 游戏进行中
    const val STORY_LIST = 6      // 故事列表
    const val STORY_VIEW = 7      // 故事查看
    const val SETTINGS_PANEL = 8  // 设置面板
    const val ACCOUNT = 9         // 账户
    const val MAP_EDITOR = 10     // 地图编辑器
    const val HELP = 11           // 帮助
}

/**
 * 触摸动作常量
 * 对应原 C 工程 common.h 中的 ACTION_* 定义
 */
object TouchAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val CANCEL = 3
    const val OUTSIDE = 4
    const val POINTER_DOWN = 5
    const val POINTER_UP = 6

    const val MASK = 0xFF
    const val POINTER_INDEX_MASK = 0xFF00
    const val POINTER_INDEX_SHIFT = 8
}

/**
 * 迷宫类型常量
 * 对应原 C 工程 common.h 中的 MAZE_TYPE_* 定义
 * MAZE_TYPE_CGMG=1 / XXHD=2 / ZLXZ=3 / QXZS=4 / HCMX=5
 * 其中 QXZS、HCMX 在原 C 与当前工程中均尚未实现
 */
object MazeType {
    const val CGMG = 1   // 常规迷宫
    const val XXHD = 2   // 线性滑道
    const val ZLXZ = 3   // 重力行者
    const val QXZS = 4   // 气象之森
    const val HCMX = 5   // 虹彩门限
}

/**
 * 画笔样式常量
 * 对应原 C 工程 common.h 中的 STYLE_* 定义
 */
object PaintStyle {
    const val FILL = 0
    const val STROKE = 1
}

/**
 * 字体类型常量
 * 对应原 C 工程 common.h 中的 TYPEFACE_* 定义
 */
object Typeface {
    const val DEFAULT = 0
    const val DEFAULT_BOLD = 1
    const val SANS_SERIF = 2
}