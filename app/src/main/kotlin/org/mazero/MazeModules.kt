package org.mazero

/**
 * 迷宫模块注册表
 * 把 MazeType 常量映射到对应的模块单例
 */
object MazeModules {
    val cgmg: MazeModule = MazeGridGame(
        code = "cgmg",
        displayName = "常规迷宫",
        semantics = MapSemantics.CGMG,
        continuous = false
    )

    val xxhd: MazeModule = MazeGridGame(
        code = "xxhd",
        displayName = "线性滑道",
        semantics = MapSemantics.XXHD,
        continuous = true
    )

    val zlxz: MazeModule = MazeZlxzGame()

    /** 按 MazeType 取模块（仅支持 cgmg/xxhd/zlxz） */
    fun byType(type: Int): MazeModule? = when (type) {
        MazeType.CGMG -> cgmg
        MazeType.XXHD -> xxhd
        MazeType.ZLXZ -> zlxz
        else -> null
    }
}
