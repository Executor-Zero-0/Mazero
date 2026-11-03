package org.mazero.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import org.mazero.BgmPlayer
import org.mazero.GameScreen
import org.mazero.GameState
import org.mazero.GameView
import org.mazero.Global
import org.mazero.TouchAction

/**
 * 设置面板
 * 对应原 C 工程 settings_panel.c
 *
 * 作为覆盖层叠加在主菜单之上，从左侧滑入/滑出。
 * 包含三个标签页：调配（背景音乐开关+音量滑块）、进度、其他。
 *
 * 交互：
 *   点击面板外部 -> 关闭面板（动画结束后返回主菜单）
 *   点击标签按钮 -> 切换标签页
 *   调配页：点击开关切换背景音乐，拖动滑块调节音量，拖动内容滚动
 *
 * 存储：音乐开关与音量保存到 SharedPreferences（替代旧的 /sdcard/mazero_settings.dat）
 */
object SettingsPanelScreen : GameScreen {


    // ==================== 布局常量 ====================
    private const val PANEL_WIDTH_RATIO = 2.0f / 3.0f
    private const val BUTTON_HEIGHT_ABSOLUTE = 96.0f
    private const val SEPARATOR_ABSOLUTE_Y = 102.0f
    private const val CONTENT_PADDING_LEFT = 20.0f
    private const val CONTENT_PADDING_RIGHT = 20.0f
    private const val SWITCH_WIDTH = 60.0f
    private const val SWITCH_HEIGHT = 30.0f
    private const val SLIDER_HEIGHT = 8.0f
    private const val CONTENT_START_OFFSET = 70.0f
    private const val SWITCH_RELATIVE_Y = -20.0f
    private const val SLIDER_RELATIVE_Y = 60.0f
    private const val TEXT_RELATIVE_Y = 20.0f
    private const val SCROLL_AREA_OFFSET = 8.0f
    private const val SCROLL_TOP_LIMIT = -25.0f
    private const val SCROLL_BOTTOM_LIMIT = 500.0f
    private const val SLIDER_TOUCH_TOLERANCE = 20.0f
    private const val SWITCH_TOUCH_TOLERANCE = 15.0f
    private const val TOUCH_HORIZONTAL_TOLERANCE = 30.0f
    private const val PREFS_NAME = "mazero_settings"
    private const val KEY_MUSIC_SWITCH = "music_switch"
    private const val KEY_MUSIC_VOLUME = "music_volume"

    // ==================== 面板动画 ====================
    private var panelOffset = 0.0f
    private var panelAnimating = false
    private var animStartTime = 0L
    private var panelVisible = false

    // ==================== 标签按钮 ====================
    private var btnTop = 0f
    private var btnBottom = 0f
    private val btnLeft = FloatArray(3)
    private val btnRight = FloatArray(3)
    private var currentTab = 0

    // 每个标签页独立的滚动位置
    private val scrollYByTab = floatArrayOf(-25.0f, -25.0f, -25.0f)

    // 内容滚动临时变量
    private var contentTouchStartY = 0f
    private var contentIsScrolling = false

    // ==================== 音乐开关和音量 ====================
    private var sliderDragging = false

    // 画笔
    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val boldTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val sw: Int get() = GameView.instance?.screenW ?: 0
    private val sh: Int get() = GameView.instance?.screenH ?: 0

    private fun now(): Long = System.currentTimeMillis()
    private fun ctx(): Context? = GameView.instance?.context

    // ==================== 文件读写（SharedPreferences） ====================

    private fun save() {
        ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_MUSIC_SWITCH, Global.musicEnabled)
            ?.putFloat(KEY_MUSIC_VOLUME, Global.musicVolume)
            ?.apply()
    }

    private fun load() {
        val prefs = ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs != null) {
            Global.musicEnabled = prefs.getBoolean(KEY_MUSIC_SWITCH, true)
            Global.musicVolume = prefs.getFloat(KEY_MUSIC_VOLUME, 0.5f)
        } else {
            Global.musicEnabled = true
            Global.musicVolume = 0.5f
        }
    }

    /** 对应原 C settings_panel.c 的 apply_audio()：按开关与音量设置 BGM 音量 */
    private fun applyAudio() {
        BgmPlayer.applyVolume()
    }

    // ==================== 面板动画 ====================

    /** 显示面板（由主菜单设置图标调用） */
    fun show() {
        if (panelVisible) return
        panelVisible = true
        panelAnimating = true
        panelOffset = 0.0f
        animStartTime = now()
        scrollYByTab[0] = -25.0f
        scrollYByTab[1] = -25.0f
        scrollYByTab[2] = -25.0f
    }

    /** 隐藏面板（由返回键调用），动画结束后返回主菜单 */
    fun hide() {
        if (!panelVisible) return
        panelVisible = false
        panelAnimating = true
        panelOffset = 1.0f
        animStartTime = now()
    }

    // ==================== 绘制梯形 ====================

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

    // ==================== 绘制三个按钮 ====================

    private fun drawTopButtons(canvas: Canvas, panelLeft: Float, panelRight: Float, topY: Float, bottomY: Float) {
        val firstTopLen = 300.0f
        val firstBottomLen = 250.0f
        val secondTopLen = 250.0f
        val secondBottomLen = 250.0f
        val thirdTopLen = 250.0f
        val thirdBottomLen = 250.0f
        val slopeOffset = -50.0f

        val topL1 = panelLeft
        val topR1 = topL1 + firstTopLen
        val bottomL1 = topL1
        val bottomR1 = bottomL1 + firstBottomLen

        val bottomL2 = bottomR1
        val bottomR2 = bottomL2 + secondBottomLen
        val topL2 = bottomL2 - slopeOffset
        val topR2 = topL2 + secondTopLen

        val bottomL3 = bottomR2
        val bottomR3 = bottomL3 + thirdBottomLen
        val topL3 = bottomL3 - slopeOffset
        val topR3 = topL3 + thirdTopLen

        btnLeft[0] = topL1 - 10
        btnRight[0] = topR1 + 10
        btnLeft[1] = topL2 - 10
        btnRight[1] = topR2 + 10
        btnLeft[2] = topL3 - 10
        btnRight[2] = topR3 + 10

        val color0 = if (0 == currentTab) Color.rgb(160, 160, 160) else Color.rgb(120, 120, 120)
        drawTrapezoid(canvas, color0, topL1, topR1, bottomL1, bottomR1, topY, bottomY)
        val color1 = if (1 == currentTab) Color.rgb(160, 160, 160) else Color.rgb(120, 120, 120)
        drawTrapezoid(canvas, color1, topL2, topR2, bottomL2, bottomR2, topY, bottomY)
        val color2 = if (2 == currentTab) Color.rgb(160, 160, 160) else Color.rgb(120, 120, 120)
        drawTrapezoid(canvas, color2, topL3, topR3, bottomL3, bottomR3, topY, bottomY)

        // 分隔线
        strokePaint.color = Color.rgb(80, 80, 80)
        canvas.drawLine(topR1, topY, bottomR1, bottomY, strokePaint)
        canvas.drawLine(topR2, topY, bottomR2, bottomY, strokePaint)

        // 标签文字
        val labels = arrayOf("调配", "进度", "其他")
        textPaint.style = Paint.Style.FILL
        textPaint.color = Color.WHITE
        textPaint.textSize = (bottomY - topY) * 0.5f

        val centerX1 = (topL1 + topR1 + bottomL1 + bottomR1) / 4.0f
        val textW1 = textPaint.measureText(labels[0])
        canvas.drawText(labels[0], centerX1 - textW1 / 2, topY + (bottomY - topY) * 0.65f, textPaint)

        val centerX2 = (topL2 + topR2 + bottomL2 + bottomR2) / 4.0f
        val textW2 = textPaint.measureText(labels[1])
        canvas.drawText(labels[1], centerX2 - textW2 / 2, topY + (bottomY - topY) * 0.65f, textPaint)

        val centerX3 = (topL3 + topR3 + bottomL3 + bottomR3) / 4.0f
        val textW3 = textPaint.measureText(labels[2])
        canvas.drawText(labels[2], centerX3 - textW3 / 2, topY + (bottomY - topY) * 0.65f, textPaint)
    }

    // ==================== 绘制内容区域 ====================

    private fun drawContentArea(canvas: Canvas, panelLeft: Float, panelTop: Float, panelRight: Float, panelBottom: Float) {
        val contentLeft = panelLeft
        val contentRight = panelRight

        val btnBottomLocal = panelTop + BUTTON_HEIGHT_ABSOLUTE

        fillPaint.color = Color.rgb(200, 200, 200)
        canvas.drawRect(contentLeft, btnBottomLocal, contentRight, panelBottom, fillPaint)

        // 分隔线
        strokePaint.color = Color.rgb(180, 180, 180)
        canvas.drawLine(contentLeft + CONTENT_PADDING_LEFT, SEPARATOR_ABSOLUTE_Y,
            contentRight - CONTENT_PADDING_RIGHT, SEPARATOR_ABSOLUTE_Y, strokePaint)

        val scrollAreaStartY = SEPARATOR_ABSOLUTE_Y + SCROLL_AREA_OFFSET
        val cutLineY = panelBottom - 5.0f
        val scrollAreaEndY = cutLineY

        canvas.save()
        canvas.clipRect(contentLeft, scrollAreaStartY, contentRight, scrollAreaEndY)

        val baseY = scrollAreaStartY
        val contentScrollY = scrollYByTab[currentTab]

        if (currentTab == 0) {
            // 背景音乐文字
            textPaint.textSize = sh * 0.05f
            textPaint.color = Color.BLACK
            canvas.drawText("背景音乐",
                contentLeft + CONTENT_PADDING_LEFT,
                baseY + TEXT_RELATIVE_Y - contentScrollY,
                textPaint)

            // 开关
            val switchX = contentRight - SWITCH_WIDTH - CONTENT_PADDING_RIGHT
            val switchY = baseY + SWITCH_RELATIVE_Y - contentScrollY
            fillPaint.color = if (Global.musicEnabled) Color.rgb(100, 200, 100) else Color.rgb(150, 150, 150)
            canvas.drawRoundRect(switchX, switchY,
                switchX + SWITCH_WIDTH, switchY + SWITCH_HEIGHT,
                15f, 15f, fillPaint)
            val switchThumbX = if (Global.musicEnabled) switchX + SWITCH_WIDTH - 20 else switchX
            fillPaint.color = Color.WHITE
            canvas.drawCircle(switchThumbX + 10, switchY + SWITCH_HEIGHT / 2, 12f, fillPaint)

            // 音量滑块
            val sliderY = baseY + SLIDER_RELATIVE_Y - contentScrollY
            val sliderL = contentLeft + CONTENT_PADDING_LEFT
            val sliderR = contentRight - CONTENT_PADDING_RIGHT

            fillPaint.color = Color.rgb(120, 120, 120)
            canvas.drawRect(sliderL, sliderY, sliderR, sliderY + SLIDER_HEIGHT, fillPaint)

            val fillW = (sliderR - sliderL) * Global.musicVolume
            fillPaint.color = Color.rgb(70, 130, 200)
            canvas.drawRect(sliderL, sliderY, sliderL + fillW, sliderY + SLIDER_HEIGHT, fillPaint)

            val thumbX = sliderL + (sliderR - sliderL) * Global.musicVolume
            fillPaint.color = Color.WHITE
            canvas.drawCircle(thumbX, sliderY + SLIDER_HEIGHT / 2, 12f, fillPaint)

            // 音量百分比
            val volText = "${(Global.musicVolume * 100).toInt()}%"
            textPaint.textSize = sh * 0.04f
            textPaint.color = Color.BLACK
            canvas.drawText(volText, sliderR - 50, sliderY - 5, textPaint)
        } else if (currentTab == 1) {
            val startY = baseY - contentScrollY
            textPaint.textSize = sh * 0.06f
            textPaint.color = Color.BLACK
            canvas.drawText("进度功能开发中",
                contentLeft + CONTENT_PADDING_LEFT,
                startY + 30, textPaint)
        } else if (currentTab == 2) {
            val startY = baseY - contentScrollY
            textPaint.textSize = sh * 0.06f
            textPaint.color = Color.BLACK
            canvas.drawText("其他功能开发中",
                contentLeft + CONTENT_PADDING_LEFT,
                startY + 30, textPaint)
        }
        canvas.restore()
    }

    // ==================== 生命周期 ====================

    override fun init() {
        load()
        applyAudio()
        // 注意：不要在 init() 里重置 panelVisible / panelOffset ——
        // init() 首次是在 gameState 切到 SETTINGS_PANEL 时才被 GameView 调用，
        // 而 MainMenuScreen.touch() 里会先调 show() 把面板置为"打开并动画滑入"。
        // 若在此把 panelVisible 重置为 false，会覆盖 show() 的意图，导致面板
        // 先全开又反向缩回并跳回主菜单（首次打开时的"快速弹缩"缺陷）。
        // 面板的显隐/动画状态统一交由 show() / hide() 管理即可。
        scrollYByTab[0] = -25.0f
        scrollYByTab[1] = -25.0f
        scrollYByTab[2] = -25.0f
        currentTab = 0
    }

    override fun update(deltaTime: Float) {
        if (!panelAnimating) return
        val n = now()
        val elapsed = (n - animStartTime) / 200.0f
        if (elapsed >= 1.0f) {
            panelAnimating = false
            if (panelVisible) {
                panelOffset = 1.0f
            } else {
                panelOffset = 0.0f
                GameView.instance?.gameState = GameState.MAIN_MENU
            }
        } else {
            if (panelVisible) panelOffset = elapsed
            else panelOffset = 1.0f - elapsed
        }
    }

    override fun draw(canvas: Canvas) {
        if (sw == 0 || sh == 0) return
        if (!panelVisible && !panelAnimating) return

        // 先绘制底层主菜单
        MainMenuScreen.draw(canvas)

        // 半透明遮罩
        fillPaint.color = Color.argb(120, 0, 0, 0)
        canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fillPaint)

        val panelW = sw * PANEL_WIDTH_RATIO
        val panelL = -panelW + panelOffset * panelW
        val panelR = panelL + panelW
        val panelT = 0f
        val panelB = sh.toFloat()

        // 面板背景
        fillPaint.color = Color.rgb(200, 200, 200)
        canvas.drawRect(panelL, panelT, panelR, panelB, fillPaint)

        btnTop = panelT
        btnBottom = panelT + BUTTON_HEIGHT_ABSOLUTE
        drawTopButtons(canvas, panelL, panelR, btnTop, btnBottom)
        drawContentArea(canvas, panelL, btnBottom, panelR, panelB)
    }

    override fun touch(action: Int, x: Float, y: Float) {
        if (!panelVisible && !panelAnimating) return
        if (panelAnimating) return
        val panelW = sw * PANEL_WIDTH_RATIO
        val panelL = -panelW + panelOffset * panelW
        val panelR = panelL + panelW
        val panelTop = 0f
        val btnTopLocal = panelTop
        val btnBottomLocal = panelTop + BUTTON_HEIGHT_ABSOLUTE

        val scrollAreaStartY = SEPARATOR_ABSOLUTE_Y + SCROLL_AREA_OFFSET
        val baseY = scrollAreaStartY

        if (action == TouchAction.DOWN) {
            // 点击面板外部 -> 关闭
            if (x < panelL || x > panelR) {
                hide()
                return
            }
            // 点击标签按钮
            if (y >= btnTopLocal && y <= btnBottomLocal) {
                for (i in 0 until 3) {
                    if (x >= btnLeft[i] && x <= btnRight[i]) {
                        currentTab = i
                        return
                    }
                }
            }
            // 调配页：开关
            if (currentTab == 0) {
                val switchX = panelR - SWITCH_WIDTH - CONTENT_PADDING_RIGHT
                val switchY = baseY + SWITCH_RELATIVE_Y - scrollYByTab[currentTab]
                if (x >= switchX - 20 && x <= switchX + SWITCH_WIDTH + 20 &&
                    y >= switchY - SWITCH_TOUCH_TOLERANCE && y <= switchY + SWITCH_HEIGHT + SWITCH_TOUCH_TOLERANCE) {
                    Global.musicEnabled = !Global.musicEnabled
                    applyAudio()
                    save()
                    return
                }
                // 音量滑块
                val sliderL = panelL + CONTENT_PADDING_LEFT
                val sliderR = panelR - CONTENT_PADDING_RIGHT
                val sliderY = baseY + SLIDER_RELATIVE_Y - scrollYByTab[currentTab]
                if (x >= sliderL - TOUCH_HORIZONTAL_TOLERANCE && x <= sliderR + TOUCH_HORIZONTAL_TOLERANCE &&
                    y >= sliderY - SLIDER_TOUCH_TOLERANCE && y <= sliderY + SLIDER_HEIGHT + SLIDER_TOUCH_TOLERANCE) {
                    sliderDragging = true
                    var newVol = (x - sliderL) / (sliderR - sliderL)
                    if (newVol < 0) newVol = 0f
                    if (newVol > 1) newVol = 1f
                    Global.musicVolume = newVol
                    if (Global.musicEnabled) applyAudio()
                    save()
                    return
                }
            }
            // 内容区域滚动
            if (y >= btnBottomLocal) {
                contentIsScrolling = true
                contentTouchStartY = y
            }
        } else if (action == TouchAction.MOVE && contentIsScrolling) {
            val dy = y - contentTouchStartY
            var newScrollY = scrollYByTab[currentTab] - dy
            if (newScrollY < SCROLL_TOP_LIMIT) newScrollY = SCROLL_TOP_LIMIT
            if (newScrollY > SCROLL_BOTTOM_LIMIT) newScrollY = SCROLL_BOTTOM_LIMIT
            scrollYByTab[currentTab] = newScrollY
            contentTouchStartY = y
        } else if (action == TouchAction.MOVE && sliderDragging) {
            val sliderL = panelL + CONTENT_PADDING_LEFT
            val sliderR = panelR - CONTENT_PADDING_RIGHT
            var newVol = (x - sliderL) / (sliderR - sliderL)
            if (newVol < 0) newVol = 0f
            if (newVol > 1) newVol = 1f
            Global.musicVolume = newVol
            if (Global.musicEnabled) applyAudio()
            save()
        } else if (action == TouchAction.UP || action == TouchAction.CANCEL) {
            contentIsScrolling = false
            sliderDragging = false
        }
    }
    override fun destroy() {}
}
