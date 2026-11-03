package org.mazero

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * 通关界面的「导出」行为
 * 对应原 C 工程 maze_game.c 中复制凭证并导出通关记录的系统调用
 *
 * Android 侧使用系统剪贴板写入凭证，并同时调起分享面板，
 * 使凭证既能粘贴（贴到聊天、笔记），也能直接发送。
 */
object ClosureActions {

    private const val TAG = "ClosureActions"

    /** 生成可分享的完整凭证文本 */
    private fun buildClosureText(): String {
        val name = Global.mazeTypeName(Global.currentMazeType)
        val time = MazeFlow.formatTime(Global.completeTime)
        return buildString {
            append("Mazero 通关凭证\n")
            append("迷宫：$name\n")
            append("关卡：第 ${Global.currentLevel} 关\n")
            append("用时：$time\n")
            append("步数：${Global.completeSteps} 步\n")
            append("凭证：${Global.completeCode}")
        }
    }

    /** 将凭证文本写入系统剪贴板，返回是否成功 */
    private fun copyToClipboard(text: String): Boolean {
        val ctx = GameView.instance?.context ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            cm.setPrimaryClip(ClipData.newPlainText("Mazero 通关凭证", text))
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyToClipboard error", e)
            false
        }
    }

    /**
     * 通关界面「导出」按钮：
     *   1. 凭证写入系统剪贴板
     *   2. 弹出系统分享面板，可把凭证发送给他人
     * 两者任一成功即提示导出成功；全部失败时提示失败。
     */
    fun exportClosure() {
        val ctx = GameView.instance?.context ?: return
        val text = buildClosureText()
        val copied = copyToClipboard(text)

        val shared = try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Mazero 通关凭证")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(intent, "分享通关凭证").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "share closure error", e)
            false
        }

        val msg = when {
            copied && shared -> "凭证已复制到剪贴板并调起分享"
            copied -> "凭证已复制到剪贴板"
            shared -> "已调起分享"
            else -> "导出失败，请稍后重试"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
