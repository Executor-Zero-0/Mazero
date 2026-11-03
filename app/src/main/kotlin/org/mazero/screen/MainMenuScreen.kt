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
import org.mazero.MazeType
import org.mazero.MazeToast
import org.mazero.TouchAction
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import org.mazero.Global

/**
 * 主菜单（完整版）
 * 对应原 C 工程 main_menu.c
 */
object MainMenuScreen : GameScreen {

    private const val TAG = "MainMenuScreen"
    private const val PREFS_NAME = "mazero_prefs"
    private const val KEY_CENTER_INDEX = "main_menu_center_index"

    private const val STARDUST_COUNT = 80
    private const val TOTAL_BUTTONS = 5
    private const val SNAP_SPEED = 0.12f

    /** 按钮资源（按 CGMG / XXHD / ZLXZ / QXZS / HCMX 顺序） */
    private val btnAssetNames = arrayOf(
        "background_image/cgmg.png",   // 常规迷宫
        "background_image/xxhd.png",   // 线性滑道
        "background_image/zlxz.png",   // 重力行者
        "background_image/qxzs.png",   // 气象之森
        "background_image/hcmx.png"    // 虹彩门限
    )

    /** 按钮对应的迷宫类型（沿用原常量名） */
    private val btnMazeTypes = intArrayOf(
        MazeType.CGMG,
        MazeType.XXHD,
        MazeType.ZLXZ,
        MazeType.QXZS,
        MazeType.HCMX
    )

    // ==================== 资源 ====================

    private val btnBitmaps = arrayOfNulls<Bitmap>(TOTAL_BUTTONS)
    private var bgMenuBitmap: Bitmap? = null
    private var allocateBitmap: Bitmap? = null
    private var fileBitmap: Bitmap? = null
    private var helpBitmap: Bitmap? = null
    private var editBitmap: Bitmap? = null
    private var messageBitmap: Bitmap? = null
    private var accountBitmap: Bitmap? = null
    private var avatarOriginal: Bitmap? = null
    private var avatarCropped: Bitmap? = null

    // 头像梯形尺寸缓存
    private var lastTrapW = 0f
    private var lastTrapH = 0f
    private var lastScreenW = 0
    private var lastScreenH = 0

    // ==================== 布局 ====================

    private var btnW = 0f
    private var btnH = 0f
    private var btnY = 0f
    private var btnSpacing = 0f
    private val baseX = FloatArray(TOTAL_BUTTONS)
    private var totalWidth = 0f
    private var cyclePeriod = 0f

    private var scrollOffset = 0f
    private var targetScrollOffset = 0f
    private var restoreDone = false
    private var savedCenterIndex = -1

    // ==================== 触摸状态 ====================

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchX = 0f
    private var isDragging = false
    private var touchMoved = false

    // ==================== 顶部图标位置 ====================

    private var iconSize = 0f
    private val iconPadding = 20f
    private val iconSpacing = 20f
    private var allocateX = 0f; private var allocateY = 0f
    private var fileX = 0f; private var fileY = 0f
    private var helpX = 0f; private var helpY = 0f
    private var editX = 0f; private var editY = 0f

    // ==================== 底部三角 / 账户 / 消息 ====================

    private var triangleSize = 0f
    private var accountX = 0f; private var accountY = 0f
    private var messageX = 0f; private var messageY = 0f

    // ==================== 时钟 ====================

    private var clockX = 0f; private var clockY = 0f

    /** 时钟文本缓存：秒变化时才重新生成，避免每帧分配对象造成 GC 卡顿 */
    private var clockStr = ""
    private var lastClockSecond = -1

    // ==================== 头像梯形 ====================

    private var trapX = 0f; private var trapY = 0f
    private var trapW = 0f; private var trapH = 0f

    // ==================== 星尘 ====================

    private class Stardust {
        var x = 0f; var y = 0f
        var speed = 0f
        var alpha = 0
        var radius = 0
    }

    private val stars = Array(STARDUST_COUNT) { Stardust() }
    private var starsInitialized = false

    // ==================== 画笔 ====================

    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
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

    // ==================== 生命周期 ====================

    override fun init() {
        Log.i(TAG, "init")
        lastTrapW = 0f; lastTrapH = 0f; lastScreenW = 0; lastScreenH = 0
        for (i in 0 until TOTAL_BUTTONS) {
            btnBitmaps[i] = loadBitmap(btnAssetNames[i])
        }
        bgMenuBitmap = loadBitmap("background_image/zcdhm.jpg")
        allocateBitmap = loadBitmap("button_icon/allocate.png")
        fileBitmap = loadBitmap("button_icon/file.png")
        helpBitmap = loadBitmap("button_icon/help.png")
        editBitmap = loadBitmap("button_icon/edit.png")
        messageBitmap = loadBitmap("button_icon/message.png")
        accountBitmap = loadBitmap("button_icon/account.png")
        avatarOriginal = loadBitmap("background_image/avatar.png")

        val prefs = ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        savedCenterIndex = prefs?.getInt(KEY_CENTER_INDEX, -1) ?: -1
        restoreDone = false

        Log.i(TAG, "init done, bg=${bgMenuBitmap != null}, avatar=${avatarOriginal != null}")
    }

    override fun update(deltaTime: Float) {
        if (!starsInitialized || sw == 0 || sh == 0) return

        // 星尘下落
        for (s in stars) {
            s.y += s.speed * deltaTime * 60f
            if (s.y > sh) {
                s.y = 0f
                s.x = Random.nextInt(sw + 1).toFloat()
                s.radius = 1 + Random.nextInt(3)
                s.alpha = 100 + Random.nextInt(156)
            }
        }

        // 吸附
        val diff = targetScrollOffset - scrollOffset
        if (abs(diff) > 0.5f) {
            scrollOffset += diff * SNAP_SPEED
        } else {
            scrollOffset = targetScrollOffset
        }
    }

    private fun initStardust() {
        if (sw == 0 || sh == 0) return
        val rnd = Random(System.currentTimeMillis())
        for (s in stars) {
            s.x = rnd.nextInt(sw + 1).toFloat()
            s.y = rnd.nextInt(sh + 1).toFloat()
            s.speed = 0.06f + rnd.nextInt(5) / 100f
            s.alpha = 100 + rnd.nextInt(156)
            s.radius = 1 + rnd.nextInt(3)
        }
        starsInitialized = true
    }

    // ==================== 绘制 ====================

    override fun draw(canvas: Canvas) {
        if (sw == 0 || sh == 0) return
        if (!starsInitialized) initStardust()

        // 1. 背景
        val bg = bgMenuBitmap
        if (bg != null) {
            srcRect.set(0, 0, bg.width, bg.height)
            dstRectF.set(0f, 0f, sw.toFloat(), sh.toFloat())
            canvas.drawBitmap(bg, srcRect, dstRectF, bitmapPaint)
        } else {
            canvas.drawColor(Color.rgb(50, 50, 80))
        }

        // 2. 星尘
        if (starsInitialized) {
            for (s in stars) {
                fillPaint.color = Color.argb(s.alpha, 255, 255, 255)
                canvas.drawCircle(s.x, s.y, s.radius.toFloat(), fillPaint)
            }
        }

        // 3. 旋转木马
        layoutButtons()
        drawCarousel(canvas)

        // 4. 标题
        drawTitle(canvas)

        // 5. 顶部图标
        layoutTopIcons()
        drawTopIcons(canvas)

        // 6. 时钟
        drawClock(canvas)

        // 7. 消息图标
        drawMessageIcon(canvas)

        // 8. 左下三角 + 账户
        drawBottomLeft(canvas)

        // 9. 右上头像区
        layoutAvatar()
        drawAvatar(canvas)
    }

    private fun layoutButtons() {
        btnW = sw / 6f
        if (btnW < 80f) btnW = 80f

        val b0 = btnBitmaps[0]
        btnH = if (b0 != null) btnW * b0.height / b0.width.toFloat() else btnW

        btnSpacing = btnW * 0.25f
        totalWidth = TOTAL_BUTTONS * btnW + (TOTAL_BUTTONS - 1) * btnSpacing
        cyclePeriod = totalWidth + btnSpacing

        val startX = (sw - totalWidth) / 2f
        for (i in 0 until TOTAL_BUTTONS) {
            baseX[i] = startX + i * (btnW + btnSpacing)
        }
        btnY = sh * 0.28f

        if (!restoreDone && savedCenterIndex in 0 until TOTAL_BUTTONS) {
            val halfScreen = sw / 2f
            val target = baseX[savedCenterIndex] + btnW / 2f - halfScreen
            scrollOffset = target
            targetScrollOffset = target
        }
        restoreDone = true
    }

    private fun drawCarousel(canvas: Canvas) {
        val halfScreen = sw / 2f
        val halfBtn = btnW / 2f

        var cycleOffset = scrollOffset % cyclePeriod
        if (cycleOffset < 0) cycleOffset += cyclePeriod

        var firstLeft = baseX[0] - cycleOffset
        while (firstLeft + cyclePeriod <= 0f) firstLeft += cyclePeriod
        while (firstLeft > 0f) firstLeft -= cyclePeriod

        for (cycle in -1..2) {
            val offsetX = firstLeft + cycle * cyclePeriod
            for (i in 0 until TOTAL_BUTTONS) {
                val bmp = btnBitmaps[i] ?: continue
                val left = offsetX + (baseX[i] - baseX[0])
                val centerX = left + halfBtn
                if (centerX + halfBtn < 0f || centerX - halfBtn > sw) continue

                val dist = abs(centerX - halfScreen)
                var scale = 1f
                var alpha = 1f

                if (dist > halfBtn) {
                    val range = halfScreen - halfBtn
                    if (range > 0f) {
                        var t = (dist - halfBtn) / range
                        if (t > 1f) t = 1f
                        scale = 1f - t * 0.5f
                        alpha = 1f - t
                        if (alpha < 0f) alpha = 0f
                        if (scale < 0.3f) scale = 0.3f
                    }
                }

                val drawW = btnW * scale
                val drawH = btnH * scale
                val drawX = centerX - drawW / 2f
                val drawY = btnY + (btnH - drawH) / 2f

                bitmapPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                srcRect.set(0, 0, bmp.width, bmp.height)
                dstRectF.set(drawX, drawY, drawX + drawW, drawY + drawH)
                canvas.drawBitmap(bmp, srcRect, dstRectF, bitmapPaint)
            }
        }
        bitmapPaint.alpha = 255
    }

    private fun drawTitle(canvas: Canvas) {
        boldTextPaint.textSize = sh * 0.065f
        boldTextPaint.color = Color.WHITE
        val title = "选择迷宫类型"
        val titleW = boldTextPaint.measureText(title)
        canvas.drawText(title, (sw - titleW) / 2f, sh * 0.175f, boldTextPaint)
    }

    private fun layoutTopIcons() {
        iconSize = sw * 0.06f
        allocateX = iconPadding
        allocateY = iconPadding
        fileX = allocateX + iconSize + iconSpacing
        fileY = iconPadding
        helpX = fileX + iconSize + iconSpacing
        helpY = iconPadding
        editX = helpX + iconSize + iconSpacing
        editY = iconPadding
    }

    private fun drawTopIcons(canvas: Canvas) {
        drawIconAt(canvas, allocateBitmap, allocateX, allocateY, 1.0f)
        drawIconAt(canvas, fileBitmap, fileX, fileY, 0.7f)
        drawIconAt(canvas, helpBitmap, helpX, helpY, 0.8f)
        drawIconAt(canvas, editBitmap, editX, editY, 0.8f)
    }

    private fun drawIconAt(canvas: Canvas, bmp: Bitmap?, left: Float, top: Float, scaleFactor: Float) {
        if (bmp == null) return
        val maxDim = maxOf(bmp.width, bmp.height)
        val scale = (iconSize * scaleFactor) / maxDim
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val offsetX = left + (iconSize - drawW) / 2f
        val offsetY = top + (iconSize - drawH) / 2f
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRectF.set(offsetX, offsetY, offsetX + drawW, offsetY + drawH)
        canvas.drawBitmap(bmp, srcRect, dstRectF, bitmapPaint)
    }

    private fun drawClock(canvas: Canvas) {
        // 秒变化时才重新生成文本，其余帧直接复用，减少每帧分配对象的 GC 卡顿
        val cal = Calendar.getInstance()
        val s = cal.get(Calendar.SECOND)
        if (s != lastClockSecond) {
            lastClockSecond = s
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            clockStr = String.format("%02d:%02d:%02d", h, m, s)
        }

        boldTextPaint.textSize = sh * 0.055f
        boldTextPaint.color = Color.WHITE
        val tw = boldTextPaint.measureText(clockStr)
        clockX = (sw - tw) / 2f
        clockY = iconPadding + iconSize * 0.5f
        canvas.drawText(clockStr, clockX, clockY, boldTextPaint)
    }

    private fun drawMessageIcon(canvas: Canvas) {
        messageX = sw - iconPadding - iconSize
        messageY = sh - iconPadding - iconSize
        drawIconAt(canvas, messageBitmap, messageX, messageY, 0.8f)
    }

    private fun drawBottomLeft(canvas: Canvas) {
        triangleSize = iconSize * 1.8f

        fillPaint.color = Color.rgb(180, 180, 180)
        val path = Path()
        path.moveTo(0f, sh.toFloat())
        path.lineTo(0f, sh - triangleSize)
        path.lineTo(triangleSize, sh.toFloat())
        path.close()
        canvas.drawPath(path, fillPaint)

        accountX = triangleSize * 0.11f
        accountY = sh - triangleSize * 0.5f
        drawIconAt(canvas, accountBitmap, accountX, accountY, 0.8f)
    }

    private fun layoutAvatar() {
        trapW = iconSize * 3.8f
        trapH = iconSize * 1.15f
        trapX = sw - trapW
        trapY = 0f
    }

    private fun drawAvatar(canvas: Canvas) {
        // 外层梯形
        fillPaint.color = Color.rgb(140, 140, 140)
        val path = Path()
        val topLeft = trapX
        val topRight = trapX + trapW
        val bottomLeft = trapX + trapW * 0.13f
        val bottomRight = trapX + trapW
        path.moveTo(topLeft, trapY)
        path.lineTo(topRight, trapY)
        path.lineTo(bottomRight, trapY + trapH)
        path.lineTo(bottomLeft, trapY + trapH)
        path.close()
        canvas.drawPath(path, fillPaint)

        // 生成内嵌头像（尺寸变化时）
        if (abs(trapW - lastTrapW) > 0.5f || abs(trapH - lastTrapH) > 0.5f ||
            sw != lastScreenW || sh != lastScreenH) {
            generateCroppedAvatar()
            lastTrapW = trapW
            lastTrapH = trapH
            lastScreenW = sw
            lastScreenH = sh
        }

        val cropped = avatarCropped
        if (cropped != null) {
            val left = sw - trapW / 3f
            val top = 0f
            srcRect.set(0, 0, cropped.width, cropped.height)
            dstRectF.set(left, top, left + cropped.width, top + cropped.height)
            canvas.drawBitmap(cropped, srcRect, dstRectF, bitmapPaint)
        }

        // 名字 + 等级
        val lineYRatio = 0.70f
        val nameFontScale = 0.45f
        val nameOffsetY = -trapH * 0.06f
        val levelFontScale = 0.24f
        val levelOffsetY = trapH * 0.3f
        val levelOffsetX = 18f

        var lineY = trapH * lineYRatio
        if (lineY < 0f) lineY = 0f
        if (lineY > trapH) lineY = trapH

        val leftX = trapX + trapW * 0.13f * (lineY / trapH)
        var rightX = sw - trapW / 3f + (trapW / 12f) * (lineY / (trapH * 5f / 6f))
        if (rightX > sw) rightX = sw.toFloat()
        if (rightX <= leftX) rightX = leftX + 1f

        strokePaint.color = Color.rgb(140, 140, 140)
        strokePaint.strokeWidth = 2f
        canvas.drawLine(leftX, lineY, rightX, lineY, strokePaint)

        val nameText = "安娜莉丝"
        var nameFontSize = trapH * nameFontScale
        if (nameFontSize < 14f) nameFontSize = 14f
        if (nameFontSize > 48f) nameFontSize = 48f

        boldTextPaint.textSize = nameFontSize
        boldTextPaint.color = Color.WHITE
        val nameW = boldTextPaint.measureText(nameText)
        val centerX = (leftX + rightX) / 2f
        val nameX = centerX - nameW / 2f
        var nameY = lineY + nameOffsetY
        if (nameY < 0f) nameY = nameFontSize
        if (nameY > sh) nameY = sh - nameFontSize * 0.2f
        canvas.drawText(nameText, nameX, nameY, boldTextPaint)

        val levelText = "Lv.10"
        var levelFontSize = trapH * levelFontScale
        if (levelFontSize < 12f) levelFontSize = 12f
        if (levelFontSize > 36f) levelFontSize = 36f

        boldTextPaint.textSize = levelFontSize
        canvas.drawText(levelText, leftX + levelOffsetX, lineY + levelOffsetY, boldTextPaint)
    }

    /**
     * 生成内嵌头像：用 Canvas.clipPath 做梯形裁剪
     * 替代原 C 版的 Bitmap_lockPixels 像素级操作
     */
    private fun generateCroppedAvatar() {
        val src = avatarOriginal
        if (src == null) {
            avatarCropped?.recycle()
            avatarCropped = null
            return
        }

        val bmpW = (trapW / 3f).toInt()
        val bmpH = (trapH * 5f / 6f).toInt()
        if (bmpW <= 0 || bmpH <= 0) return

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 梯形裁剪（局部坐标）：
        //   左上 (0,0)
        //   右上 (bmpW,0)
        //   右下 (bmpW,bmpH)
        //   左下 (bmpW*0.25,bmpH)
        val path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(bmpW.toFloat(), 0f)
        path.lineTo(bmpW.toFloat(), bmpH.toFloat())
        path.lineTo(bmpW * 0.25f, bmpH.toFloat())
        path.close()
        canvas.clipPath(path)

        // 居中铺满（取最大缩放）
        val scaleX = bmpW.toFloat() / src.width
        val scaleY = bmpH.toFloat() / src.height
        val scale = maxOf(scaleX, scaleY)
        val drawW = src.width * scale
        val drawH = src.height * scale
        val dx = (bmpW - drawW) / 2f
        val dy = (bmpH - drawH) / 2f

        srcRect.set(0, 0, src.width, src.height)
        dstRectF.set(dx, dy, dx + drawW, dy + drawH)
        canvas.drawBitmap(src, srcRect, dstRectF, bitmapPaint)

        avatarCropped?.recycle()
        avatarCropped = bmp
    }

    // ==================== 触摸 ====================

    override fun touch(action: Int, x: Float, y: Float) {
        // 界面切换防误触由 GameView 统一处理（gameState 变化后 200ms 内丢弃触摸）
        when (action) {
            TouchAction.DOWN -> {
                touchDownX = x
                touchDownY = y
                lastTouchX = x
                isDragging = false
                touchMoved = false

                if (isTouchOnIcon(x, y, allocateX, allocateY, iconSize)) {
                    SettingsPanelScreen.show()
                    GameView.instance?.gameState = GameState.SETTINGS_PANEL
                    return
                }
                if (isTouchOnIcon(x, y, fileX, fileY, iconSize)) {
                    GameView.instance?.gameState = GameState.STORY_LIST
                    return
                }
                if (isTouchOnIcon(x, y, helpX, helpY, iconSize)) {
                    GameView.instance?.gameState = GameState.HELP
                    return
                }
                if (isTouchOnIcon(x, y, editX, editY, iconSize)) {
                    GameView.instance?.gameState = GameState.MAP_EDITOR
                    return
                }
                if (isTouchOnIcon(x, y, messageX, messageY, iconSize)) {
                    return
                }
                if (isTouchOnIcon(x, y, accountX, accountY, iconSize)) {
                    GameView.instance?.gameState = GameState.ACCOUNT
                    return
                }
            }
            TouchAction.MOVE -> {
                val dx = x - touchDownX
                val dy = y - touchDownY
                if (abs(dx) > 10f || abs(dy) > 10f) {
                    isDragging = true
                    touchMoved = true
                }
                if (isDragging) {
                    val deltaX = x - lastTouchX
                    scrollOffset -= deltaX
                    targetScrollOffset = scrollOffset
                    lastTouchX = x
                }
            }
            TouchAction.UP -> {
                if (!touchMoved) {
                    handleCarouselClick(x, y)
                } else {
                    snapToNearest()
                }
                isDragging = false
                touchMoved = false
            }
        }
    }

    private fun isTouchOnIcon(x: Float, y: Float, left: Float, top: Float, size: Float): Boolean {
        return x >= left && x <= left + size && y >= top && y <= top + size
    }

    private fun handleCarouselClick(x: Float, y: Float) {
        var cycleOffset = scrollOffset % cyclePeriod
        if (cycleOffset < 0) cycleOffset += cyclePeriod
        var firstLeft = baseX[0] - cycleOffset
        while (firstLeft + cyclePeriod <= 0f) firstLeft += cyclePeriod
        while (firstLeft > 0f) firstLeft -= cyclePeriod

        for (cycle in -1..2) {
            val offsetX = firstLeft + cycle * cyclePeriod
            for (i in 0 until TOTAL_BUTTONS) {
                val left = offsetX + (baseX[i] - baseX[0])
                val right = left + btnW
                if (x >= left && x <= right && y >= btnY && y <= btnY + btnH) {
                    val type = btnMazeTypes[i]
                    // 对应原 C main_menu.c：QXZS / HCMX 尚未实现，提示后停留在主菜单
                    if (type == MazeType.QXZS || type == MazeType.HCMX) {
                        MazeToast.show("该迷宫类型开发中，敬请期待")
                        return
                    }
                    Global.mazeType = type
                    GameView.instance?.gameState = GameState.LEVEL_SELECT
                    return
                }
            }
        }
    }

    private fun snapToNearest() {
        val halfScreen = sw / 2f
        var bestTarget = targetScrollOffset
        var minDist = Float.MAX_VALUE

        for (i in 0 until TOTAL_BUTTONS) {
            val center = baseX[i] + btnW / 2f - scrollOffset
            val diff = center - halfScreen
            val m = (-diff / cyclePeriod).roundToInt()
            val candidateCenter = center + m * cyclePeriod
            val dist = abs(candidateCenter - halfScreen)
            if (dist < minDist) {
                minDist = dist
                bestTarget = baseX[i] + btnW / 2f - halfScreen + m * cyclePeriod
            }
        }
        targetScrollOffset = bestTarget

        var cycleOffset = bestTarget % cyclePeriod
        if (cycleOffset < 0) cycleOffset += cyclePeriod
        val firstLeft = baseX[0] - cycleOffset

        var centerIdx = 0
        var minIdxDist = Float.MAX_VALUE
        for (i in 0 until TOTAL_BUTTONS) {
            for (cycle in -1..2) {
                val left = firstLeft + cycle * cyclePeriod + (baseX[i] - baseX[0])
                val centerX = left + btnW / 2f
                val dist = abs(centerX - halfScreen)
                if (dist < minIdxDist) {
                    minIdxDist = dist
                    centerIdx = i
                }
            }
        }

        ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_CENTER_INDEX, centerIdx)?.apply()
    }

    override fun destroy() {
        for (i in 0 until TOTAL_BUTTONS) {
            btnBitmaps[i]?.recycle()
            btnBitmaps[i] = null
        }
        bgMenuBitmap?.recycle(); bgMenuBitmap = null
        allocateBitmap?.recycle(); allocateBitmap = null
        fileBitmap?.recycle(); fileBitmap = null
        helpBitmap?.recycle(); helpBitmap = null
        editBitmap?.recycle(); editBitmap = null
        messageBitmap?.recycle(); messageBitmap = null
        accountBitmap?.recycle(); accountBitmap = null
        avatarOriginal?.recycle(); avatarOriginal = null
        avatarCropped?.recycle(); avatarCropped = null
    }
}
