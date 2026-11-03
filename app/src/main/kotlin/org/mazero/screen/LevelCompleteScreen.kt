package org.mazero.screen

import android.graphics.Canvas
import org.mazero.ClosureActions
import org.mazero.GameScreen
import org.mazero.GameState
import org.mazero.GameView
import org.mazero.Global
import org.mazero.MazeFlow
import org.mazero.MazeModules
import org.mazero.TouchAction

/**
 * 关卡通关界面（LEVEL_COMPLETE）
 * 对应旧 C 中 main.c 的 STATE_LEVEL_COMPLETE 分支 + *_draw_complete_screen
 */
object LevelCompleteScreen : GameScreen {

    private var buttons: FloatArray? = null

    override fun init() {
        buttons = null
    }

    override fun update(deltaTime: Float) {}

    override fun draw(canvas: Canvas) {
        val view = GameView.instance ?: return
        if (view.screenW == 0 || view.screenH == 0) return
        val type = Global.currentMazeType
        val module = MazeModules.byType(type) ?: MazeModules.cgmg
        buttons = MazeFlow.drawCompleteScreen(canvas, module)
    }

    override fun touch(action: Int, x: Float, y: Float) {
        if (action != TouchAction.DOWN) return
        val view = GameView.instance ?: return
        val b = buttons ?: return
        val type = Global.currentMazeType
        val module = MazeModules.byType(type) ?: MazeModules.cgmg

        if (hit(b, 0, x, y)) {
            Global.isComplete = false
            module.resetLevelFull()
            view.gameState = GameState.LEVEL_SELECT
            return
        }
        if (hit(b, 1, x, y)) {
            ClosureActions.exportClosure()
            return
        }
        if (hit(b, 2, x, y)) {
            Global.isComplete = false
            module.resetLevelFull()
            view.gameState = GameState.GAME
            // ★ 修复：resetLevelFull 已不再自动启动计时，重置直接开玩需手动启动
            module.startTimer()
        }
    }

    /** i 为按钮槽位号（0=返离，1=导出，2=重置），每个槽位占 4 个 float */
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
