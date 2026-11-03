package org.mazero.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import org.mazero.GameScreen
import org.mazero.GameState
import org.mazero.GameView
import org.mazero.Global
import org.mazero.MazeType
import org.mazero.TouchAction
import kotlin.math.abs

/**
 * 关卡选择界面
 * 对应原 C 工程 level_select.c
 *
 * 布局：
 *   cellW = screenW / 10
 *   cellH = cellW
 *   spacing = cellW * 0.2
 *   margin = screenW * 0.06
 *   每格画圆角矩形 + 数字
 *
 * 交互：
 *   双击关卡 -> 检查 map/{code}_{level}.txt 存在 -> 跳 LEVEL_START
 *   双击空白 -> 返回 MAIN_MENU
 */
object LevelSelectScreen : GameScreen {

    private const val TAG = "LevelSelectScreen"
    private const val LEVEL_COUNT = 2
    private const val DOUBLE_TAP_MS = 300L
    private const val DOUBLE_TAP_RADIUS = 50f

    // ==================== 背景图 ====================
    private var bgCgmg: Bitmap? = null
    private var bgXxhd: Bitmap? = null
    private var bgZlxz: Bitmap? = null

    // ==================== 布局 ====================
    private var cellW = 0f
    private var cellH = 0f
    private var spacing = 0f
    private var startX = 0f
    private var startY = 0f
    private val cellPosX = FloatArray(LEVEL_COUNT)
    private val cellPosY = FloatArray(LEVEL_COUNT)

    // ==================== 双击检测 ====================
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapHitButton = -1

    // ==================== 首入提示 ====================
    private var firstEnter = true

    // ==================== 画笔 ====================
    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val boldTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val srcRect = Rect()
    private val dstRectF = RectF()

    private val sw: Int get() = GameView.instance?.screenW ?: 0
    private val sh: Int get() = GameView.instance?.screenH ?: 0

    private fun now() = System.currentTimeMillis()

    private fun loadBitmap(path: String): Bitmap? {
        val ctx = GameView.instance?.context ?: return null
        return try {
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap failed: $path", e)
            null
        }
    }

    /** 检查 assets 中是否存在指定文件 */
    private fun assetExists(path: String): Boolean {
        val ctx = GameView.instance?.context ?: return false
        return try {
            ctx.assets.open(path).use { true }
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 生命周期 ====================

    override fun init() {
        // 对应原 C 的 load_backgrounds()
        bgCgmg = loadBitmap("background_image/cgmgbj.png")
        bgXxhd = loadBitmap("background_image/xxhdbj.png")
        bgZlxz = loadBitmap("background_image/zlxzbj.png")

        if (sw > 0) calculateLayout()
        lastTapTime = 0L
        lastTapHitButton = -1
    }

    override fun update(deltaTime: Float) {
        // 无需更新
    }

    // ==================== 绘制 ====================

    override fun draw(canvas: Canvas) {
        if (sw == 0 || sh == 0) return

        // 首次进入提示（对应原 C 的 showToastText）
        if (firstEnter) {
            firstEnter = false
        }

        // 1. 背景图（根据 mazeType 选择）
        val currentBg = when (Global.mazeType) {
            MazeType.CGMG -> bgCgmg
            MazeType.XXHD -> bgXxhd
            MazeType.ZLXZ -> bgZlxz
            else -> bgCgmg
        }

        if (currentBg != null) {
            srcRect.set(0, 0, currentBg.width, currentBg.height)
            dstRectF.set(0f, 0f, sw.toFloat(), sh.toFloat())
            canvas.drawBitmap(currentBg, srcRect, dstRectF, bitmapPaint)
        } else {
            // 对应原 C：Canvas_drawColor(canvas, ARGB(200, 0, 0, 0))
            canvas.drawColor(Color.argb(200, 0, 0, 0))
        }

        // 2. 计算布局（对应原 C 的 calculate_layout）
        calculateLayout()

        // 3. 绘制关卡格子
        for (i in 0 until LEVEL_COUNT) {
            val left = cellPosX[i]
            val top = cellPosY[i]
            val right = left + cellW
            val bottom = top + cellH

            // 圆角矩形背景（对应原 C 的 ARGB(102, 100, 100, 100)）
            fillPaint.color = Color.argb(102, 100, 100, 100)
            canvas.drawRoundRect(left, top, right, bottom, cellW * 0.1f, cellH * 0.1f, fillPaint)

            // 关卡数字
            val numStr = (i + 1).toString()
            boldTextPaint.textSize = cellH * 0.4f
            boldTextPaint.color = Color.WHITE
            val textW = boldTextPaint.measureText(numStr)
            val textX = left + (cellW - textW) / 2f
            val textY = (top + bottom) / 2f + boldTextPaint.textSize * 0.35f
            canvas.drawText(numStr, textX, textY, boldTextPaint)
        }
    }

    private fun calculateLayout() {
        // 对应原 C 的 calculate_layout()
        cellW = sw / 10f
        cellH = cellW
        spacing = cellW * 0.2f
        val margin = sw * 0.06f
        startX = margin
        startY = margin
        for (i in 0 until LEVEL_COUNT) {
            cellPosX[i] = startX + i * (cellW + spacing)
            cellPosY[i] = startY
        }
    }

    // ==================== 触摸 ====================

    override fun touch(action: Int, x: Float, y: Float) {
        if (action != TouchAction.DOWN) return
        if (cellW <= 0f) return
        // 命中检测（对应原 C 的 hit_button）
        var hitButton = -1
        for (i in 0 until LEVEL_COUNT) {
            if (x >= cellPosX[i] && x <= cellPosX[i] + cellW &&
                y >= cellPosY[i] && y <= cellPosY[i] + cellH
            ) {
                hitButton = i
                break
            }
        }

        val t = now()

        // 双击判定
        if (t - lastTapTime < DOUBLE_TAP_MS &&
            abs(x - lastTapX) < DOUBLE_TAP_RADIUS &&
            abs(y - lastTapY) < DOUBLE_TAP_RADIUS
        ) {
            if (lastTapHitButton >= 0 && hitButton >= 0 && hitButton == lastTapHitButton) {
                // 双击同一个关卡
                val level = hitButton + 1
                val code = Global.mazeTypeCode(Global.mazeType)
                val filename = "map/${code}_${level}.txt"

                if (!assetExists(filename)) {
                    Log.w(TAG, "地图文件不存在: $filename")
                    // 对应原 C 的 showToastText("地图文件不存在，请检查资源")
                    lastTapTime = 0L
                    lastTapHitButton = -1
                    return
                }

                // 文件存在，设置关卡并跳转
                Global.currentLevel = level
                Global.currentMazeType = Global.mazeType
                Global.levelMetaLoaded = false
                GameView.instance?.gameState = GameState.LEVEL_START
                Log.i(TAG, "进入关卡: code=$code, level=$level")
            } else {
                // 双击空白 -> 返回主菜单
                GameView.instance?.gameState = GameState.MAIN_MENU
            }

            lastTapTime = 0L
            lastTapHitButton = -1
            return
        }

        // 记录本次点击
        lastTapTime = t
        lastTapX = x
        lastTapY = y
        lastTapHitButton = hitButton
    }

    // ==================== 销毁 ====================

    override fun destroy() {
        bgCgmg?.recycle(); bgCgmg = null
        bgXxhd?.recycle(); bgXxhd = null
        bgZlxz?.recycle(); bgZlxz = null
    }
}