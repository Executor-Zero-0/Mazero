package org.mazero

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * 背景音乐播放器
 * 对应原 C 工程 main.c 中的全局 bgmPlayer（JNI MediaPlayer 封装）
 *
 * 原 C 逻辑（main.c）：
 *   onCreate : createMediaPlayer() -> mediaPlayerSetSourceFromAssert("audio/yumu.mp3")
 *              -> mediaPlayerSetLooping(true) -> mediaPlayerPrepare()
 *              -> mediaPlayerStart() -> mediaPlayerSetVolume(vol, vol)
 *   onPause  : mediaPlayerPause()
 *   onResume : mediaPlayerStart()
 *   onDestroy: mediaPlayerStop() + deleteMediaPlayer()
 *   设置面板 : apply_audio() 按 musicSwitchState / musicVolume 调用 mediaPlayerSetVolume()
 *
 * Kotlin 复刻使用 android.media.MediaPlayer 播放 assets/audio/yumu.mp3，
 * 通过 AssetFileDescriptor 直接设置数据源，无需 FileProvider。
 */
object BgmPlayer {

    private const val TAG = "BgmPlayer"
    private const val ASSET_PATH = "audio/yumu.mp3"

    /** 与 settings_panel.c 的 SAVE_FILE 等价：音频设置存档 */
    private const val PREFS_NAME = "mazero_settings"
    private const val KEY_MUSIC_SWITCH = "music_switch"
    private const val KEY_MUSIC_VOLUME = "music_volume"

    @Volatile
    private var player: MediaPlayer? = null

    /**
     * 对应 onCreate 中创建并启动 BGM。重复调用安全（已存在则仅刷新音量）。
     */
    fun init(context: Context) {
        if (player != null) {
            applyVolume()
            return
        }

        // 等价于 settings_panel_load()：先把已保存的音频设置同步到 Global
        loadSavedSettings(context)

        try {
            val mp = MediaPlayer()
            // 对应 mediaPlayerSetSourceFromAssert(bgmPlayer, "audio/yumu.mp3")
            context.assets.openFd(ASSET_PATH).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.isLooping = true   // mediaPlayerSetLooping(bgmPlayer, JNI_TRUE)
            mp.setVolume(0f, 0f)  // 先静音，避免 prepare 期间爆音
            mp.prepare()          // mediaPlayerPrepare(bgmPlayer)
            mp.start()            // mediaPlayerStart(bgmPlayer)
            player = mp
            applyVolume()         // mediaPlayerSetVolume(vol, vol)
            Log.i(TAG, "BGM started: $ASSET_PATH")
        } catch (e: Exception) {
            // 对应 C 中数据源设置失败：deleteMediaPlayer() 并置 NULL，游戏继续无声运行
            Log.e(TAG, "Failed to load BGM: $ASSET_PATH", e)
            player = null
        }
    }

    /** 对应 onResume：mediaPlayerStart() */
    fun start() {
        val mp = player ?: return
        try {
            if (!mp.isPlaying) mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "BGM start failed", e)
        }
    }

    /** 对应 onPause：mediaPlayerPause() */
    fun pause() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (e: Exception) {
            Log.e(TAG, "BGM pause failed", e)
        }
    }

    /** 对应 apply_audio()：按 Global.musicEnabled / Global.musicVolume 设置 MediaPlayer 音量 */
    fun applyVolume() {
        val mp = player ?: return
        val vol = if (Global.musicEnabled) Global.musicVolume else 0f
        try {
            mp.setVolume(vol, vol)
        } catch (e: Exception) {
            Log.e(TAG, "BGM setVolume failed", e)
        }
    }

    /** 对应 onDestroy：mediaPlayerStop() + deleteMediaPlayer() */
    fun release() {
        val mp = player
        player = null
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
            } catch (e: Exception) {
                Log.e(TAG, "BGM stop failed", e)
            }
            try {
                mp.release()
            } catch (e: Exception) {
                Log.e(TAG, "BGM release failed", e)
            }
        }
    }

    /** 对应 settings_panel_load()：从 SharedPreferences 读取音乐开关与音量 */
    private fun loadSavedSettings(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Global.musicEnabled = prefs.getBoolean(KEY_MUSIC_SWITCH, true)
            Global.musicVolume = prefs.getFloat(KEY_MUSIC_VOLUME, 0.5f)
        } catch (e: Exception) {
            Global.musicEnabled = true
            Global.musicVolume = 0.5f
        }
    }
}
