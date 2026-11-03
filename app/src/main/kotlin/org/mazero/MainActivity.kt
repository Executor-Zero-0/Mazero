package org.mazero

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mazero.screen.MainMenuScreen
import org.mazero.screen.StartScreen
import org.mazero.screen.LevelSelectScreen
import org.mazero.screen.LevelStartScreen
import org.mazero.screen.LevelCompleteScreen
import org.mazero.screen.MazeGameScreen
import org.mazero.screen.HelpBrowserScreen
import org.mazero.screen.StoryListScreen
import org.mazero.screen.StoryViewScreen
import org.mazero.screen.AccountScreen
import org.mazero.screen.SettingsPanelScreen
import org.mazero.screen.MapEditorScreen

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 关键 1：内容延伸到系统栏下方
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        supportActionBar?.hide()

        // ★ 关键 2：隐藏系统栏
        hideSystemBars()

        // 创建 GameView
        gameView = GameView(this)
        gameView.registerScreen(GameState.START, StartScreen)
        gameView.registerScreen(GameState.MAIN_MENU, MainMenuScreen)
        gameView.registerScreen(GameState.LEVEL_SELECT, LevelSelectScreen)   // ← 新增
        gameView.registerScreen(GameState.LEVEL_START, LevelStartScreen)
        gameView.registerScreen(GameState.GAME, MazeGameScreen)
        gameView.registerScreen(GameState.LEVEL_COMPLETE, LevelCompleteScreen)
        gameView.registerScreen(GameState.HELP, HelpBrowserScreen)
        gameView.registerScreen(GameState.STORY_LIST, StoryListScreen)
        gameView.registerScreen(GameState.STORY_VIEW, StoryViewScreen)
        gameView.registerScreen(GameState.ACCOUNT, AccountScreen)
        gameView.registerScreen(GameState.SETTINGS_PANEL, SettingsPanelScreen)
        gameView.registerScreen(GameState.MAP_EDITOR, MapEditorScreen)
        gameView.gameState = GameState.START
        setContentView(gameView)

        // ★ 对应原 C onCreate：创建并启动背景音乐（assets/audio/yumu.mp3，循环播放）
        BgmPlayer.init(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

    }

    /**
     * 沉浸式全屏：隐藏状态栏 + 导航栏
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 用户从边缘滑动时临时显示，松手自动隐藏
     */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** 从通知栏拉下、切后台回来等，重新隐藏 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun handleBack() {
        when (gameView.gameState) {
            GameState.START -> {
                finish()
            }
            GameState.LEVEL_START -> {
                gameView.gameState = GameState.LEVEL_SELECT
            }
            GameState.LEVEL_COMPLETE -> {
                gameView.gameState = GameState.LEVEL_SELECT
            }
            GameState.GAME -> {
                // B：进入关卡后，返回彻底禁掉（不回 LEVEL_SELECT），仅消费事件
                Log.i(TAG, "back GAME -> 已禁用（关卡内不允许返回）")
            }
            GameState.MAIN_MENU -> {
                gameView.gameState = GameState.START
            }
            GameState.SETTINGS_PANEL -> {
                SettingsPanelScreen.hide()
            }
            GameState.MAP_EDITOR -> {
                if (!MapEditorScreen.handleBackKey()) {
                    gameView.gameState = GameState.MAIN_MENU
                }
            }
            else -> {
                gameView.gameState = GameState.MAIN_MENU
            }
        }
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
        hideSystemBars()   // 每次回前台再隐藏一次
        BgmPlayer.start()  // 对应原 C onResume: mediaPlayerStart()
        // ★ 修复：切后台时 pauseTimer 把 isLevelPaused 置 true，回来必须恢复计时
        if (gameView.gameState == GameState.GAME) {
            org.mazero.MazeModules.byType(org.mazero.Global.mazeType)?.startTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        // 对应原 C onPause：切后台时暂停并保存当前关卡进度
        if (gameView.gameState == GameState.GAME) {
            val module = org.mazero.MazeModules.byType(org.mazero.Global.mazeType)
            module?.pauseTimer()
            module?.saveState()
        }
        gameView.pause()
        BgmPlayer.pause()  // 对应原 C onPause: mediaPlayerPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        gameView.destroy()
        BgmPlayer.release()  // 对应原 C onDestroy: mediaPlayerStop() + deleteMediaPlayer()
    }
}