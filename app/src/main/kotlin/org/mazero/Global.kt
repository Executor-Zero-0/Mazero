package org.mazero

import kotlin.random.Random

/**
 * 全局状态
 * 对应原 C 工程 main.c 中的全局变量
 */
object Global {

    // ==================== 当前选择状态 ====================

    /** 当前迷宫类型，对应原 C 的 mazeType */
    @Volatile
    var mazeType: Int = MazeType.CGMG

    /** 当前关卡编号（从 1 开始），对应原 C 的 g_currentLevel */
    @Volatile
    var currentLevel: Int = 1

    /** 进入开始界面的迷宫类型，对应原 C 的 g_currentMazeType */
    @Volatile
    var currentMazeType: Int = MazeType.XXHD

    /** 开始界面元数据是否已加载，对应原 C 的 g_levelMetaLoaded */
    @Volatile
    var levelMetaLoaded: Boolean = false

    // ==================== 音频设置 ====================
    // 对应原 C 的 musicSwitchState / musicVolume

    /** 背景音乐开关，对应原 C 的 musicSwitchState */
    @Volatile
    var musicEnabled: Boolean = true

    /** 背景音乐音量（0.0~1.0），对应原 C 的 musicVolume */
    @Volatile
    var musicVolume: Float = 0.5f

    // ==================== 通关数据 ====================
    // 对应原 C 的 g_completeTime / g_completeSteps / g_completeCode / g_isComplete

    @Volatile
    var completeTime: Int = 0

    @Volatile
    var completeSteps: Int = 0

    @Volatile
    var completeCode: String = "ABCD-EFGH-IJKL-MNOP"

    @Volatile
    var isComplete: Boolean = false

    /**
     * 对应原 C 的 set_complete_data(time, steps)
     * 生成一个随机凭证码，格式 XXXX-XXXX-XXXX-XXXX
     */
    fun setCompleteData(time: Int, steps: Int) {
        completeTime = time
        completeSteps = steps
        completeCode = String.format(
            "%04X-%04X-%04X-%04X",
            Random.nextInt(0, 65536),
            Random.nextInt(0, 65536),
            Random.nextInt(0, 65536),
            Random.nextInt(0, 65536)
        )
        isComplete = true
    }

    // ==================== 迷宫类型映射 ====================

    /**
     * 迷宫类型 -> 中文名
     * 常规迷宫 / 线性滑道 / 重力行者 / 气象之森 / 虹彩门限
     * 对应原 C common.h：QXZS=气象之森，HCMX=虹彩门限
     */
    fun mazeTypeName(type: Int): String = when (type) {
        MazeType.CGMG -> "常规迷宫"
        MazeType.XXHD -> "线性滑道"
        MazeType.ZLXZ -> "重力行者"
        MazeType.QXZS -> "气象之森"
        MazeType.HCMX -> "虹彩门限"
        else -> "未知迷宫"
    }

    /**
     * 迷宫类型 -> 代码缩写
     * 用于存档路径、地图文件名（map/{code}_{level}.txt）
     */
    fun mazeTypeCode(type: Int): String = when (type) {
        MazeType.CGMG -> "cgmg"
        MazeType.XXHD -> "xxhd"
        MazeType.ZLXZ -> "zlxz"
        MazeType.QXZS -> "qxzs"
        MazeType.HCMX -> "hcmx"
        else -> "cgmg"
    }
}