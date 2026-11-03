package org.mazero.screen

import android.graphics.Canvas
import org.mazero.AcquireActions
import org.mazero.GameScreen
import org.mazero.GameState
import org.mazero.GameView
import org.mazero.Global
import org.mazero.MazeFlow
import org.mazero.MazeModules
import org.mazero.TouchAction

/**
 * 关卡开始界面（LEVEL_START）
 * 对应旧 C 中 main.c 的 STATE_LEVEL_START 分支 + *_draw_start_screen
 *
 * 负责：按当前迷宫类型／关卡初始化对应模块，并绘制元数据与三个按钮
 */
object LevelStartScreen : GameScreen {


    private var buttons: FloatArray? = null
    private var lastMazeType = -1
    private var lastLevel = -1

    override fun init() {
        lastMazeType = -1
        lastLevel = -1
        buttons = null
    }

    override fun update(deltaTime: Float) {}

    override fun draw(canvas: Canvas) {
        val view = GameView.instance ?: return
        if (view.screenW == 0 || view.screenH == 0) return

        val type = Global.currentMazeType
        val module = MazeModules.byType(type) ?: MazeModules.cgmg

        if (lastMazeType != type || lastLevel != Global.currentLevel) {
            lastMazeType = type
            lastLevel = Global.currentLevel
            module.enterLevel(Global.currentLevel)
            if (view.gameState == GameState.LEVEL_SELECT) return
        }

        if (!module.loadOk) {
            view.gameState = GameState.LEVEL_SELECT
            return
        }

        buttons = MazeFlow.drawStartScreen(canvas, module)
    }

    override fun touch(action: Int, x: Float, y: Float) {
        if (action != TouchAction.DOWN) return
        val view = GameView.instance ?: return
        val b = buttons ?: return
        val type = Global.currentMazeType
        val module = MazeModules.byType(type) ?: MazeModules.cgmg

        if (hit(b, 0, x, y)) {
            view.gameState = GameState.LEVEL_SELECT
            return
        }
        if (hit(b, 1, x, y)) {
            AcquireActions.copyAcquireInfo()
            return
        }
        if (hit(b, 2, x, y)) {
            Global.mazeType = type
            view.gameState = GameState.GAME
            module.startTimer()
        }
    }

    /** i 为按钮槽位号（0=返离，1=获取，2=登录），每个槽位占 4 个 float */
    private fun hit(b: FloatArray, i: Int, x: Float, y: Float): Boolean {
        val o = i * 4
        val bx = b[o]; val by = b[o + 1]
        val bw = b[o + 2]; val bh = b[o + 3]
        return x >= bx && x <= bx + bw && y >= by && y <= by + bh
    }

    override fun destroy() {
        buttons = null
    }
}
