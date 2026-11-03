package org.mazero

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * 关卡开始界面「获取」按钮的行为
 *
 * 地图元数据里的「获取」字段已包含本关的入手条件与奖励（例如「1㎅(限免) ▨」），
 * 这里将其复制到系统剪贴板，作为可分享／可核对的凭证信息。
 */
object AcquireActions {

    private const val TAG = "AcquireActions"

    /** 将「获取」信息写入系统剪贴板，返回是否成功 */
    private fun copyToClipboard(text: String): Boolean {
        val ctx = GameView.instance?.context ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            cm.setPrimaryClip(ClipData.newPlainText("Mazero 获取信息", text))
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyToClipboard error", e)
            false
        }
    }

    /**
     * 复制本关的「获取」信息到剪贴板。
     * 没有可用信息时只弹出提示。
     */
    fun copyAcquireInfo() {
        val ctx = GameView.instance?.context ?: return
        val type = Global.currentMazeType
        val module = MazeModules.byType(type)
        val acquire = module?.metaOf(3)?.trim().orEmpty()

        if (acquire.isEmpty()) {
            Toast.makeText(ctx, "本关暂无获取信息", Toast.LENGTH_SHORT).show()
            return
        }

        val name = Global.mazeTypeName(type)
        val text = "Mazero 获取信息\n迷宫：$name\n关卡：第 ${Global.currentLevel} 关\n获取：$acquire"

        if (copyToClipboard(text)) {
            Toast.makeText(ctx, "获取信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "复制失败，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }
}
