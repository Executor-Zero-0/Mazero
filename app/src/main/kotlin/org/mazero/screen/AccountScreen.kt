package org.mazero.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import org.mazero.GameScreen
import org.mazero.GameState
import org.mazero.GameView
import org.mazero.TouchAction

/**
 * 账户页面
 * 对应原 C 工程 account.c
 *
 * 布局：
 *   顶部左侧「账户」梯形标签 + 右侧深灰色条
 *   左侧竖线分割
 *   右侧展图（cgmg.png 迷宫封面，保持比例）
 *   左侧文字信息区（头像 + 多行用户信息）
 *
 * 交互：
 *   双击空白处 -> 返回主菜单
 */
object AccountScreen : GameScreen {

    private const val TAG = "AccountScreen"
    private const val DOUBLE_TAP_MS = 300L

    // ==================== 用户数据（预留） ====================
    private const val USER_NAME = "安娜丽丝"
    private const val USER_TITLE = "最可爱的员工"
    private const val USER_LEVEL = "Lv.10"
    private const val USER_THINKING = 20
    private const val USER_PASSED = 0
    private const val USER_OBTAINED = 0
    private const val USER_ASSETS = "8㎅446"
    private const val USER_COLLECT = 0
    private const val USER_ACHIEVE = 0
    private const val USER_BIO = "一只普普通通简简单单可可爱爱的小员工"
    private const val USER_ID = "喵喵喵~"

    // ==================== 资源 ====================
    private var coverBitmap: Bitmap? = null   // 展图（迷宫封面 cgmg.png）
    private var avatarBitmap: Bitmap? = null  // 头像
    private var coverRatio = 1.0f

    // 双击检测
    private var lastTapTime = 0L

    // 画笔
    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val boldTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val srcRect = Rect()
    private val dstRectF = RectF()

    private val sw: Int get() = GameView.instance?.screenW ?: 0
    private val sh: Int get() = GameView.instance?.screenH ?: 0

    private fun ctx(): Context? = GameView.instance?.context

    private fun loadBitmap(path: String): Bitmap? {
        val c = ctx() ?: return null
        return try {
            c.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap failed: $path", e)
            null
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    /** 画梯形（用 Path 填充） */
    private fun drawTrapezoid(canvas: Canvas, color: Int,
                              topLeft: Float, topRight: Float,
                              bottomLeft: Float, bottomRight: Float,
                              yTop: Float, yBottom: Float) {
        if (yBottom <= yTop) return
        val path = Path()
        path.moveTo(topLeft, yTop)
        path.lineTo(topRight, yTop)
        path.lineTo(bottomRight, yBottom)
        path.lineTo(bottomLeft, yBottom)
        path.close()
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    override fun init() {
        if (coverBitmap == null) {
            coverBitmap = loadBitmap("background_image/cgmg.png")
            val bmp = coverBitmap
            coverRatio = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 1.0f
        }
        if (avatarBitmap == null) {
            avatarBitmap = loadBitmap("background_image/avatar.png")
        }
        lastTapTime = 0L
    }

    override fun update(deltaTime: Float) {
        // 无需更新
    }

    override fun draw(canvas: Canvas) {
        if (sw == 0 || sh == 0) return

        // 背景
        canvas.drawColor(Color.rgb(220, 220, 220))

        val trapH = sh * 0.08f
        val trapW = sw * 0.22f
        val grayBarH = trapH * 0.5f
        var margin = sw * 0.035f
        if (margin < 15f) margin = 15f

        val trapLeft = 0f
        val trapTop = 0f
        val trapRight = trapLeft + trapW
        val trapBottom = trapTop + trapH

        // 深灰色条（右上角）
        val barTop = trapTop
        val barBottom = trapTop + grayBarH
        drawTrapezoid(canvas, Color.rgb(80, 80, 80),
            trapRight, sw.toFloat(),
            trapRight - trapW * 0.1f, sw.toFloat(),
            barTop, barBottom)

        // 账户梯形（左上角）
        drawTrapezoid(canvas, Color.rgb(140, 140, 140),
            trapLeft, trapRight,
            trapLeft, trapRight - trapW * 0.2f,
            trapTop, trapBottom)

        // 「账户」标签
        boldTextPaint.textSize = trapH * 0.5f
        boldTextPaint.color = Color.WHITE
        val label = "账户"
        val labelW = boldTextPaint.measureText(label)
        val labelX = trapLeft + (trapW - labelW) / 2f
        val labelY = trapTop + trapH * 0.65f
        canvas.drawText(label, labelX, labelY, boldTextPaint)

        // 左侧竖线
        val leftLineX = margin
        val leftLineTop = trapBottom + margin
        val leftLineBottom = sh - margin
        var lineWidth = sw * 0.004f
        if (lineWidth < 2f) lineWidth = 2f
        fillPaint.color = Color.rgb(100, 100, 100)
        canvas.drawRect(leftLineX - lineWidth / 2f, leftLineTop,
            leftLineX + lineWidth / 2f, leftLineBottom, fillPaint)

        // 右侧展图（保持比例）
        val imageMargin = margin
        var imageTop = trapBottom + imageMargin
        var imageBottom = sh - imageMargin
        var imageHeight = imageBottom - imageTop
        var imageWidth = imageHeight * coverRatio
        var imageRight = sw - imageMargin
        var imageLeft = imageRight - imageWidth
        if (imageLeft < leftLineX + margin * 3) {
            imageWidth = sw - imageMargin - leftLineX - margin * 3
            imageHeight = imageWidth / coverRatio
            imageLeft = leftLineX + margin * 3
            imageRight = imageLeft + imageWidth
            imageTop = trapBottom + (sh - trapBottom - margin - imageHeight) / 2f
            imageBottom = imageTop + imageHeight
        }

        // 展图背景
        fillPaint.color = Color.rgb(200, 200, 200)
        canvas.drawRect(imageLeft - 2f, imageTop - 2f, imageRight + 2f, imageBottom + 2f, fillPaint)

        // 绘制展图（contain 模式）
        val cover = coverBitmap
        if (cover != null) {
            val imgW = cover.width.toFloat()
            val imgH = cover.height.toFloat()
            val rectW = imageRight - imageLeft
            val rectH = imageBottom - imageTop
            val scaleW = rectW / imgW
            val scaleH = rectH / imgH
            val scale = if (scaleW < scaleH) scaleW else scaleH
            val drawW = imgW * scale
            val drawH = imgH * scale
            val drawX = imageLeft + (rectW - drawW) / 2f
            val drawY = imageTop + (rectH - drawH) / 2f
            srcRect.set(0, 0, cover.width, cover.height)
            dstRectF.set(drawX, drawY, drawX + drawW, drawY + drawH)
            canvas.drawBitmap(cover, srcRect, dstRectF, bitmapPaint)
        }

        // 文字显示区
        val textLeft = leftLineX + margin
        val textRight = imageLeft - margin
        val textTop = trapBottom + margin
        val textBottom = sh - margin

        // 字体
        var fontSize = sh * 0.04f
        if (fontSize < 18f) fontSize = 18f
        if (fontSize > 48f) fontSize = 48f
        val lineHeight = fontSize * 1.8f

        textPaint.textSize = fontSize
        textPaint.color = Color.rgb(50, 50, 50)
        textPaint.typeface = Typeface.DEFAULT

        val x = textLeft
        var y = textTop + fontSize

        // 第一行：头像 + 名称 + 称号
        val avatarSize = lineHeight * 1.2f
        val avatarX = x
        val avatarY = y - avatarSize * 0.8f
        val avatar = avatarBitmap
        if (avatar != null) {
            val maxDim = maxOf(avatar.width, avatar.height)
            val scale = avatarSize / maxDim
            val drawW = avatar.width * scale
            val drawH = avatar.height * scale
            srcRect.set(0, 0, avatar.width, avatar.height)
            dstRectF.set(avatarX, avatarY + (avatarSize - drawH) / 2f,
                avatarX + drawW, avatarY + (avatarSize - drawH) / 2f + drawH)
            canvas.drawBitmap(avatar, srcRect, dstRectF, bitmapPaint)
        }

        val nameX = avatarX + avatarSize + 12f
        val nameBuf = "名称：$USER_NAME"
        canvas.drawText(nameBuf, nameX, y, textPaint)

        val titleBuf = "称号：$USER_TITLE"
        val nameW = textPaint.measureText(nameBuf)
        val titleX = nameX + nameW + 90f
        canvas.drawText(titleBuf, titleX, y, textPaint)

        y += lineHeight

        // 第二行：等级、思维力
        canvas.drawText("等级：$USER_LEVEL  思维力：$USER_THINKING", x, y, textPaint)
        y += lineHeight

        // 第三行：通过关卡、获取关卡
        canvas.drawText("通过关卡：$USER_PASSED  获取关卡：$USER_OBTAINED", x, y, textPaint)
        y += lineHeight

        // 第四行：资产
        canvas.drawText("资产：$USER_ASSETS", x, y, textPaint)
        y += lineHeight

        // 第五行：收集、成就
        canvas.drawText("收集：$USER_COLLECT  成就：$USER_ACHIEVE", x, y, textPaint)
        y += lineHeight

        // 第六行：简介
        canvas.drawText("简介：$USER_BIO", x, y, textPaint)
        y += lineHeight

        // 第七行：标识
        canvas.drawText("标识：$USER_ID", x, y, textPaint)
    }

    private var lastTapX = 0f
    private var lastTapY = 0f

    override fun touch(action: Int, x: Float, y: Float) {
        if (action == TouchAction.DOWN) {
            val n = now()
            if (n - lastTapTime < DOUBLE_TAP_MS &&
                kotlin.math.abs(x - lastTapX) < 50f &&
                kotlin.math.abs(y - lastTapY) < 50f
            ) {
                GameView.instance?.gameState = GameState.MAIN_MENU
                lastTapTime = 0L
            } else {
                lastTapTime = n
                lastTapX = x
                lastTapY = y
            }
        }
    }

    override fun destroy() {
        coverBitmap?.recycle(); coverBitmap = null
        avatarBitmap?.recycle(); avatarBitmap = null
    }
}
