package org.mazero.screen

import android.graphics.Canvas
import org.mazero.GameScreen
import org.mazero.GameView
import org.mazero.Global
import org.mazero.MazeModules

/**
 * 游戏进行中界面（GAME）
 * 对应旧 C 中 main.c 的 STATE_GAME 分支
 * 实际绘制／触摸全部转发给当前迷宫模块
 *
 * QXZS / HCMX 无对应模块：主菜单已在入口处拦截并提示，不会进入 GAME；
 * 这里仅保留兜底，取不到模块时回退到常规迷宫，避免黑屏。
 */
object MazeGameScreen : GameScreen {

    private fun currentModule() = MazeModules.byType(Global.mazeType)

    override fun init() {
        val module = currentModule() ?: return
        if (!module.loadOk) {
            module.enterLevel(Global.currentLevel)
            if (module.loadOk) module.startTimer()
        }
    }

    override fun update(deltaTime: Float) {
        currentModule()?.update(deltaTime)
    }

    override fun draw(canvas: Canvas) {
        val view = GameView.instance ?: return
        if (view.screenW == 0 || view.screenH == 0) return
        (currentModule() ?: MazeModules.cgmg).drawGame(canvas)
    }

    override fun touch(action: Int, x: Float, y: Float) {
        currentModule()?.touch(action, x, y, emptyMap())
    }

    override fun touchMulti(action: Int, x: Float, y: Float, pointers: Map<Int, Pair<Float, Float>>) {
        currentModule()?.touch(action, x, y, pointers)
    }

    override fun destroy() {
        currentModule()?.destroyModule()
    }
}
