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
import org.mazero.TouchAction
import kotlin.math.abs
import kotlin.random.Random

object StartScreen : GameScreen {

    private const val TAG = "StartScreen"
    private const val PARTICLE_COUNT = 80
    private const val PARTICLE_RADIUS = 3f
    private const val EXTRA_SPACE = 200f

    private enum class AnimState { FADE_IN, IDLE, FADE_OUT }

    private var animState = AnimState.FADE_IN
    private var animStartTime = 0L
    private var fadeAlpha = 0f
    private var scaleFactor = 1f
    private var textVisible = false
    private var textAlpha = 0f

    private var flickerAlpha = 0.5f
    private var flickerDir = 1
    private var lastFlickerTime = 0L

    private val titleText = "𝔐𝔞𝔷𝔢𝔯𝔬"
    private val startText = "点击屏幕进入游戏"

    // 资源
    private var bgBitmap: Bitmap? = null
    private var announceBitmap: Bitmap? = null
    private var changelogBitmap: Bitmap? = null

    // 公告按钮位置
    private var announceX = 0f
    private var announceY = 0f
    private var announceW = 0f
    private var announceH = 0f
    private val announcePadding = 20f

    // 粒子
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var alpha: Int, var alphaDir: Int)
    private val particles = Array(PARTICLE_COUNT) { Particle(0f, 0f, 0f, 0f, 0, 1) }
    private var particlesInitialized = false
    private var lastParticleUpdate = 0L

    // 对话框
    private var showDialog = false
    private var dialogLeft = 0f
    private var dialogTop = 0f
    private var dialogRight = 0f
    private var dialogBottom = 0f
    private var contentLeft = 0f
    private var contentTop = 0f
    private var contentRight = 0f
    private var contentBottom = 0f
    private var contentOffset = 0f
    private var touchStartY = 0f
    private var isScrolling = false
    private var announcementText = ""

    // changelog 预览
    private var showChangelog = false
    private var changelogContent = ""
    private var changelogScrollOffset = 0f
    private var changelogTouchStartY = 0f
    private var changelogIsScrolling = false

    // 双击检测
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // changelog 按钮位置（对话框内）
    private var lastTotalHeight = 0f
    private var lastBtnX = 0f
    private var lastBtnY = 0f
    private var lastBtnSize = 0f

    private var welcomeShown = false

    // Paint 复用
    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val boldTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val srcRect = Rect()
    private val dstRectF = RectF()

    private val sw: Int get() = GameView.instance?.screenW ?: 0
    private val sh: Int get() = GameView.instance?.screenH ?: 0

    private fun now(): Long = System.currentTimeMillis()

    private fun loadBitmap(path: String): Bitmap? {
        val ctx = GameView.instance?.context ?: return null
        return try {
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap failed: $path", e)
            null
        }
    }

    private fun loadTextAsset(path: String): String {
        val ctx = GameView.instance?.context ?: return ""
        return try {
            ctx.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
                .removePrefix("\uFEFF")
        } catch (e: Exception) {
            Log.e(TAG, "loadTextAsset failed: $path", e)
            ""
        }
    }

    // ==================== 生命周期 ====================

    override fun init() {
        announceW = 0f; announceH = 0f; announceX = 0f; announceY = 0f
        dialogLeft = 0f; dialogTop = 0f; dialogRight = 0f; dialogBottom = 0f
        contentLeft = 0f; contentTop = 0f; contentRight = 0f; contentBottom = 0f
        bgBitmap = loadBitmap("background_image/qshm.jpg")
        announceBitmap = loadBitmap("button_icon/announcement.png")
        changelogBitmap = loadBitmap("button_icon/changelog.png")

        announcementText = loadTextAsset("announcement.txt").ifEmpty { "暂无公告\n请稍后查看" }
        changelogContent = loadTextAsset("changelog.txt").ifEmpty { "无法加载更新日志" }

        animStartTime = now()
        animState = AnimState.FADE_IN
        fadeAlpha = 0f
        scaleFactor = 1f
        textVisible = false
        textAlpha = 0f
        lastFlickerTime = now()
        showChangelog = false
        changelogScrollOffset = 0f
        lastTapTime = 0L
        showDialog = false
        contentOffset = 0f
        welcomeShown = false

        Log.i(TAG, "init done, bg=${bgBitmap != null}, announce=${announceBitmap != null}")
    }

    override fun update(deltaTime: Float) {
        val t = now()

        if (animState == AnimState.IDLE && textVisible) {
            if (t - lastFlickerTime > 30) {
                lastFlickerTime = t
                flickerAlpha += flickerDir * 0.01f
                if (flickerAlpha >= 0.9f) { flickerAlpha = 0.9f; flickerDir = -1 }
                else if (flickerAlpha <= 0.2f) { flickerAlpha = 0.2f; flickerDir = 1 }
            }
        }

        if (particlesInitialized && t - lastParticleUpdate > 50) {
            lastParticleUpdate = t
            updateParticles()
        }

        updateAnimation()
    }

    private fun updateAnimation() {
        val t = now()
        when (animState) {
            AnimState.FADE_IN -> {
                val elapsed = (t - animStartTime) / 1000f
                if (elapsed >= 2.0f) {
                    fadeAlpha = 1f
                    animState = AnimState.IDLE
                    animStartTime = t
                    textVisible = false
                    textAlpha = 0f
                } else {
                    fadeAlpha = elapsed / 2f
                }
            }
            AnimState.IDLE -> {
                if (!textVisible) {
                    val elapsed = (t - animStartTime) / 1000f
                    if (elapsed >= 0.5f) { textAlpha = 1f; textVisible = true }
                    else { textAlpha = elapsed / 0.5f }
                }
            }
            AnimState.FADE_OUT -> {
                val elapsed = (t - animStartTime) / 1000f
                if (elapsed >= 1.3f) {
                    val gv = GameView.instance
                    if (gv != null) {
                        gv.globalWhiteAlpha = 1.0f
                        gv.gameState = GameState.MAIN_MENU
                    }
                    if (!welcomeShown) {
                        welcomeShown = true
                    }
                    animState = AnimState.FADE_IN
                } else {
                    val p = elapsed / 1.5f
                    scaleFactor = 1f + p
                    fadeAlpha = 1f - p
                }
            }
        }
    }

    private fun initParticles() {
        if (sw == 0 || sh == 0) return
        val rnd = Random(System.currentTimeMillis())
        for (i in 0 until PARTICLE_COUNT) {
            particles[i].x = rnd.nextInt(sw + 1).toFloat()
            particles[i].y = rnd.nextInt(sh + 1).toFloat()
            particles[i].vx = (rnd.nextInt(100) - 50) * 0.01f
            particles[i].vy = (rnd.nextInt(100) - 50) * 0.01f
            particles[i].alpha = rnd.nextInt(256)
            particles[i].alphaDir = if (rnd.nextBoolean()) 1 else -1
        }
        particlesInitialized = true
    }

    private fun updateParticles() {
        for (p in particles) {
            p.x += p.vx
            p.y += p.vy
            if (p.x < 0) p.x = sw.toFloat() else if (p.x > sw) p.x = 0f
            if (p.y < 0) p.y = sh.toFloat() else if (p.y > sh) p.y = 0f
            p.alpha += p.alphaDir * 3
            if (p.alpha >= 255) { p.alpha = 255; p.alphaDir = -1 }
            else if (p.alpha <= 50) { p.alpha = 50; p.alphaDir = 1 }
        }
    }

    private fun initAnnounce() {
        val ab = announceBitmap ?: return
        if (sw == 0) return
        val targetSize = sw * 0.08f
        announceW = targetSize
        announceH = targetSize * ab.height / ab.width.toFloat()
        announceX = sw - announceW - announcePadding
        announceY = announcePadding
    }

    private fun initDialogRect() {
        dialogLeft = sw / 4f
        dialogTop = sh / 4f
        dialogRight = sw * 3f / 4f
        dialogBottom = sh * 3f / 4f
    }

    // ==================== 绘制 ====================

    override fun draw(canvas: Canvas) {
        if (sw == 0 || sh == 0) return

        if (!particlesInitialized) initParticles()
        if (announceW == 0f && announceBitmap != null) {
            initAnnounce()
            initDialogRect()
        }

        if (showChangelog) {
            drawChangelogView(canvas)
            return
        }

        canvas.save()

        if (animState == AnimState.FADE_OUT) {
            val cx = sw / 2f
            val cy = sh / 2f
            canvas.translate(cx, cy)
            canvas.scale(scaleFactor, scaleFactor)
            canvas.translate(-cx, -cy)
        }

        // 背景
        val bg = bgBitmap
        if (bg != null) {
            srcRect.set(0, 0, bg.width, bg.height)
            dstRectF.set(0f, 0f, sw.toFloat(), sh.toFloat())
            canvas.drawBitmap(bg, srcRect, dstRectF, null)
        } else {
            canvas.drawColor(Color.BLACK)
        }

        // 粒子
        if (particlesInitialized) {
            for (p in particles) {
                fillPaint.color = Color.argb(p.alpha, 255, 255, 255)
                canvas.drawCircle(p.x, p.y, PARTICLE_RADIUS, fillPaint)
            }
        }

        // 标题
        boldTextPaint.textSize = sh * 0.40f
        boldTextPaint.color = Color.WHITE
        boldTextPaint.typeface = Typeface.DEFAULT
        val titleW = boldTextPaint.measureText(titleText)
        canvas.drawText(titleText, (sw - titleW) / 2f, sh * 0.50f, boldTextPaint)

        // 闪烁文字
        if (animState != AnimState.FADE_IN && (animState != AnimState.IDLE || textVisible)) {
            val curAlpha = if (animState == AnimState.IDLE) (textAlpha * flickerAlpha) else flickerAlpha
            boldTextPaint.textSize = sh * 0.06f
            boldTextPaint.color = Color.argb((curAlpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            boldTextPaint.typeface = Typeface.DEFAULT_BOLD
            val w = boldTextPaint.measureText(startText)
            canvas.drawText(startText, (sw - w) / 2f, sh * 0.85f, boldTextPaint)
        }

        // 公告按钮
        val ab = announceBitmap
        if (animState != AnimState.FADE_OUT && ab != null && fadeAlpha >= 0.99f) {
            srcRect.set(0, 0, ab.width, ab.height)
            dstRectF.set(announceX, announceY, announceX + announceW, announceY + announceH)
            canvas.drawBitmap(ab, srcRect, dstRectF, null)
        }

        // 对话框
        if (animState != AnimState.FADE_OUT && showDialog) {
            drawDialog(canvas)
        }

        canvas.restore()

        // 淡入 / 淡出白蒙
        if ((animState == AnimState.FADE_IN || animState == AnimState.FADE_OUT) && fadeAlpha < 1f) {
            fillPaint.color = Color.argb(((1f - fadeAlpha) * 255).toInt(), 255, 255, 255)
            canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fillPaint)
        }
    }

    private fun drawDialog(canvas: Canvas) {
        fillPaint.color = Color.argb(80, 0, 0, 0)
        canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fillPaint)

        fillPaint.color = Color.rgb(100, 100, 100)
        canvas.drawRoundRect(dialogLeft, dialogTop, dialogRight, dialogBottom, 20f, 20f, fillPaint)

        val titleHeight = sh * 0.1f
        fillPaint.color = Color.rgb(80, 80, 80)
        canvas.drawRoundRect(dialogLeft, dialogTop, dialogRight, dialogTop + titleHeight, 20f, 20f, fillPaint)

        boldTextPaint.textSize = titleHeight * 0.5f
        boldTextPaint.color = Color.WHITE
        boldTextPaint.typeface = Typeface.DEFAULT_BOLD
        val titleStr = "公告"
        val tw = boldTextPaint.measureText(titleStr)
        canvas.drawText(titleStr, dialogLeft + (dialogRight - dialogLeft - tw) / 2f,
            dialogTop + titleHeight * 0.65f, boldTextPaint)

        contentLeft = dialogLeft + 20f
        contentTop = dialogTop + titleHeight + 10f
        contentRight = dialogRight - 20f
        contentBottom = dialogBottom - 20f
        val contentW = contentRight - contentLeft
        val contentH = contentBottom - contentTop

        var fontSize = contentH * 0.08f
        if (fontSize < 16f) fontSize = 16f
        if (fontSize > 36f) fontSize = 36f

        val lines = wrapText(announcementText, fontSize, contentW - 20f)
        val lineHeight = fontSize * 1.2f
        val totalHeight = lines.size * lineHeight + EXTRA_SPACE
        var maxOffset = totalHeight - contentH
        if (maxOffset < 0f) maxOffset = 0f
        if (contentOffset < 0f) contentOffset = 0f
        if (contentOffset > maxOffset) contentOffset = maxOffset

        canvas.save()
        canvas.clipRect(contentLeft, contentTop, contentRight, contentBottom)

        fillPaint.color = Color.rgb(140, 140, 140)
        canvas.drawRect(contentLeft, contentTop, contentRight, contentBottom, fillPaint)

        textPaint.textSize = fontSize
        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.DEFAULT
        val startY = contentTop - contentOffset + lineHeight
        for (i in lines.indices) {
            val y = startY + i * lineHeight
            if (y >= contentTop && y <= contentBottom + lineHeight) {
                canvas.drawText(lines[i], contentLeft + 10f, y, textPaint)
            }
        }

        val btnSize = sh * 0.08f
        val padding = 20f
        val btnX = (contentRight - contentLeft) - btnSize - padding
        val btnY = totalHeight - btnSize - padding
        val drawX = contentLeft + btnX
        val drawY = contentTop + btnY - contentOffset

        lastTotalHeight = totalHeight
        lastBtnX = btnX
        lastBtnY = btnY
        lastBtnSize = btnSize

        if (drawY + btnSize > contentTop && drawY < contentBottom) {
            val cb = changelogBitmap
            if (cb != null) {
                srcRect.set(0, 0, cb.width, cb.height)
                dstRectF.set(drawX, drawY, drawX + btnSize, drawY + btnSize)
                canvas.drawBitmap(cb, srcRect, dstRectF, null)
            } else {
                fillPaint.color = Color.argb(200, 80, 80, 80)
                canvas.drawRoundRect(drawX, drawY, drawX + btnSize, drawY + btnSize, 10f, 10f, fillPaint)
                textPaint.textSize = btnSize * 0.3f
                textPaint.color = Color.WHITE
                canvas.drawText("更新日志", drawX + 5f, drawY + btnSize * 0.6f, textPaint)
            }
        }

        canvas.restore()
    }

    private fun drawChangelogView(canvas: Canvas) {
        canvas.drawColor(Color.rgb(50, 50, 50))

        val titleBarHeight = sh * 0.1f
        fillPaint.color = Color.rgb(30, 30, 30)
        canvas.drawRect(0f, 0f, sw.toFloat(), titleBarHeight, fillPaint)

        boldTextPaint.textSize = sh * 0.05f
        boldTextPaint.color = Color.WHITE
        boldTextPaint.typeface = Typeface.DEFAULT_BOLD
        val title = "更新日志"
        val tw = boldTextPaint.measureText(title)
        canvas.drawText(title, (sw - tw) / 2f, titleBarHeight * 0.7f, boldTextPaint)

        val cLeft = sw * 0.05f
        val cTop = titleBarHeight + 10f
        val cRight = sw - cLeft
        val cBottom = sh - 10f
        val cH = cBottom - cTop
        var fontSize = cH * 0.04f
        if (fontSize < 18f) fontSize = 18f
        if (fontSize > 36f) fontSize = 36f

        textPaint.textSize = fontSize
        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.DEFAULT

        val lineHeight = fontSize * 1.4f
        val lines = changelogContent.split("\n")
        val totalHeight = lines.size * lineHeight
        val viewHeight = cBottom - cTop
        var maxOffset = totalHeight - viewHeight
        if (maxOffset < 0f) maxOffset = 0f
        if (changelogScrollOffset < 0f) changelogScrollOffset = 0f
        if (changelogScrollOffset > maxOffset) changelogScrollOffset = maxOffset

        canvas.save()
        canvas.clipRect(cLeft, cTop, cRight, cBottom)
        var y = cTop - changelogScrollOffset + lineHeight
        for (line in lines) {
            if (y >= cTop && y <= cBottom + lineHeight) {
                canvas.drawText(line, cLeft + 10f, y, textPaint)
            }
            y += lineHeight
        }
        canvas.restore()
    }

    // ==================== 触摸 ====================

    override fun touch(action: Int, x: Float, y: Float) {
        if (showChangelog) { handleChangelogTouch(action, x, y); return }
        if (showDialog) { handleDialogTouch(action, x, y); return }

        if (action == TouchAction.DOWN) {
            val ab = announceBitmap
            if (ab != null &&
                x >= announceX && x <= announceX + announceW &&
                y >= announceY && y <= announceY + announceH) {
                showDialog = true
                contentOffset = 0f
                return
            }
            if (animState == AnimState.IDLE && textVisible) {
                animState = AnimState.FADE_OUT
                animStartTime = now()
                scaleFactor = 1f
                fadeAlpha = 1f
            }
        }
    }

    private fun handleDialogTouch(action: Int, x: Float, y: Float) {
        when (action) {
            TouchAction.DOWN -> {
                if (isPointOnChangelogBtn(x, y)) {
                    showChangelog = true
                    changelogScrollOffset = 0f
                    return
                }
                if (x < dialogLeft || x > dialogRight || y < dialogTop || y > dialogBottom) {
                    showDialog = false
                    return
                }
                if (x >= contentLeft && x <= contentRight && y >= contentTop && y <= contentBottom) {
                    touchStartY = y
                    isScrolling = true
                }
            }
            TouchAction.MOVE -> {
                if (isScrolling) {
                    val dy = y - touchStartY
                    val contentH = contentBottom - contentTop
                    val contentW = contentRight - contentLeft
                    var fontSize = contentH * 0.08f
                    if (fontSize < 16f) fontSize = 16f
                    if (fontSize > 36f) fontSize = 36f
                    val lines = wrapText(announcementText, fontSize, contentW - 20f)
                    val lineHeight = fontSize * 1.2f
                    val totalHeight = lines.size * lineHeight + EXTRA_SPACE
                    var maxOffset = totalHeight - contentH
                    if (maxOffset < 0f) maxOffset = 0f
                    contentOffset -= dy
                    if (contentOffset < 0f) contentOffset = 0f
                    if (contentOffset > maxOffset) contentOffset = maxOffset
                    touchStartY = y
                }
            }
            TouchAction.UP -> { isScrolling = false }
        }
    }

    private fun handleChangelogTouch(action: Int, x: Float, y: Float) {
        when (action) {
            TouchAction.DOWN -> {
                val t = now()
                if (t - lastTapTime < 300 && abs(x - lastTapX) < 50 && abs(y - lastTapY) < 50) {
                    showChangelog = false
                    lastTapTime = 0L
                    return
                }
                lastTapTime = t
                lastTapX = x
                lastTapY = y
                changelogTouchStartY = y
                changelogIsScrolling = true
            }
            TouchAction.MOVE -> {
                if (changelogIsScrolling) {
                    val dy = y - changelogTouchStartY
                    changelogScrollOffset -= dy
                    changelogTouchStartY = y
                    var fontSize = (sh - sh * 0.1f) * 0.04f
                    if (fontSize < 18f) fontSize = 18f
                    if (fontSize > 36f) fontSize = 36f
                    val lineHeight = fontSize * 1.4f
                    val totalHeight = changelogContent.split("\n").size * lineHeight
                    val viewHeight = sh - sh * 0.1f - 20f
                    var maxOffset = totalHeight - viewHeight
                    if (maxOffset < 0f) maxOffset = 0f
                    if (changelogScrollOffset < 0f) changelogScrollOffset = 0f
                    if (changelogScrollOffset > maxOffset) changelogScrollOffset = maxOffset
                }
            }
            TouchAction.UP -> { changelogIsScrolling = false }
        }
    }

    private fun isPointOnChangelogBtn(x: Float, y: Float): Boolean {
        if (lastBtnSize == 0f) return false
        val contentH = contentBottom - contentTop
        var maxOffset = lastTotalHeight - contentH
        if (maxOffset < 0f) maxOffset = 0f
        if (contentOffset < maxOffset - 5f) return false
        val drawX = contentLeft + lastBtnX
        val drawY = contentTop + lastBtnY - contentOffset
        return x >= drawX && x <= drawX + lastBtnSize &&
               y >= drawY && y <= drawY + lastBtnSize
    }

    // ==================== 工具 ====================

    private fun wrapText(text: String, fontSize: Float, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        if (text.isEmpty()) return result
        textPaint.textSize = fontSize
        for (rawLine in text.split("\n")) {
            var line = rawLine
            while (line.isNotEmpty()) {
                var end = line.length
                while (end > 0 && textPaint.measureText(line.substring(0, end)) > maxWidth) end--
                if (end == 0) end = 1
                result.add(line.substring(0, end))
                line = line.substring(end)
            }
        }
        return result
    }

    override fun destroy() {
        bgBitmap?.recycle(); bgBitmap = null
        announceBitmap?.recycle(); announceBitmap = null
        changelogBitmap?.recycle(); changelogBitmap = null
        particlesInitialized = false
    }
}