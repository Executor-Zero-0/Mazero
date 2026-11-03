package org.mazero.screen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import org.mazero.GameScreen
import org.mazero.GameState
import org.mazero.GameView
import org.mazero.TouchAction
import kotlin.math.abs

/**
 * 帮助浏览器
 * 对应原 C 工程 help_browser.c
 *
 * 布局：
 *   顶部标题栏「帮助」
 *   左侧列表（文件目录 a_catalogue.txt，格式：文件名|显示名）
 *   右侧内容区（选中主题的文本）
 *
 * 交互：
 *   单击左侧列表项 -> 选中并加载右侧内容
 *   拖动列表 / 内容 -> 滚动
 *   双击空白处 -> 返回主菜单
 */
object HelpBrowserScreen : GameScreen {

    private const val TAG = "HelpBrowserScreen"
    private const val LEFT_RATIO = 0.175f
    private const val DOUBLE_TAP_MS = 300L
    private const val DOUBLE_TAP_RADIUS = 50f

    // ==================== 数据 ====================

    private val helpFiles = ArrayList<String>()
    private val helpDisplay = ArrayList<String>()

    private var selectedIndex = -1
    private var currentContent: String? = null
    private var lastWrapContent: String? = null
    private var lastWrapFontSize = 0f
    private var lastWrapWidth = 0f
    private val contentLines = ArrayList<String>()
    private var contentLineHeight = 0f
    private var contentTotalHeight = 0f

    // 滚动偏移
    private var listScrollOffset = 0f
    private var contentScrollOffset = 0f

    // 触摸状态
    private var listIsScrolling = false
    private var listTouchStartY = 0f
    private var contentIsScrolling = false
    private var contentTouchStartY = 0f

    // 布局矩形
    private var listLeft = 0f; private var listRight = 0f
    private var listTop = 0f; private var listBottom = 0f
    private var contentLeft = 0f; private var contentRight = 0f
    private var contentTop = 0f; private var contentBottom = 0f
    private var titleBarHeight = 0f

    // 双击检测
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapInLeft = false

    // 画笔
    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val boldTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val sw: Int get() = GameView.instance?.screenW ?: 0
    private val sh: Int get() = GameView.instance?.screenH ?: 0

    private fun now(): Long = System.currentTimeMillis()

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

    // ==================== 加载列表 ====================

    private fun loadFileList() {
        helpFiles.clear()
        helpDisplay.clear()

        val data = loadTextAsset("help/a_catalogue.txt")
        if (data.isEmpty()) {
            Log.e(TAG, "help/a_catalogue.txt not found")
            return
        }

        for (rawLine in data.split("\n")) {
            var line = rawLine.trimEnd('\r')
            line = line.trim()
            if (line.isEmpty()) continue
            val pipe = line.indexOf('|')
            if (pipe >= 0) {
                val file = line.substring(0, pipe)
                val display = line.substring(pipe + 1)
                helpFiles.add(file)
                helpDisplay.add(display)
            }
        }
    }

    // ==================== 加载内容 ====================

    private fun loadContent(filename: String) {
        val path = "help/$filename"
        var content = loadTextAsset(path)
        if (content.isEmpty()) {
            Log.e(TAG, "无法加载帮助文件: $path")
            content = "无法加载该帮助主题"
        }
        currentContent = content
        contentScrollOffset = 0f
    }

    // ==================== 文本换行 ====================

    /** 按像素宽度换行（对应原 C 的 wrap_text_ex） */
    private fun wrapText(text: String, fontSize: Float, maxWidth: Float): ArrayList<String> {
        val result = ArrayList<String>()
        if (text.isEmpty()) return result
        textPaint.textSize = fontSize
        for (rawLine in text.split("\n")) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) { result.add(""); continue }
            var start = 0
            var accW = 0f
            var i = 0
            while (i < line.length) {
                val charW = textPaint.measureText(line, i, i + 1)
                if (accW + charW > maxWidth && i > start) {
                    result.add(line.substring(start, i))
                    start = i
                    accW = 0f
                }
                accW += charW
                i++
            }
            if (start < line.length) result.add(line.substring(start))
        }
        return result
    }

    private fun prepareContentLines(maxWidth: Float, fontSize: Float) {
        val content = currentContent ?: return
        if (content == lastWrapContent && fontSize == lastWrapFontSize && maxWidth == lastWrapWidth
            && contentLines.isNotEmpty()) return
        lastWrapContent = content; lastWrapFontSize = fontSize; lastWrapWidth = maxWidth
        contentLines.clear()
        contentLines.addAll(wrapText(content, fontSize, maxWidth - 20f))
        contentLineHeight = fontSize * 1.2f
        contentTotalHeight = contentLines.size * contentLineHeight
    }

    // ==================== 生命周期 ====================

    override fun init() {
        loadFileList()
        selectedIndex = -1
        listScrollOffset = 0f
        contentScrollOffset = 0f
        currentContent = null
        contentLines.clear()
        contentLineHeight = 0f
        contentTotalHeight = 0f
        lastTapTime = 0L
        if (sw > 0 && sh > 0) {
            titleBarHeight = sh * 0.12f
            listLeft = 0f
            listTop = titleBarHeight
            listRight = sw * LEFT_RATIO
            listBottom = sh.toFloat()
            contentLeft = listRight + 1f
            contentTop = titleBarHeight
            contentRight = sw.toFloat()
            contentBottom = sh.toFloat()
        }
        if (helpFiles.isNotEmpty()) {
            selectedIndex = 0
            loadContent(helpFiles[0])
        }
    }

    override fun update(deltaTime: Float) {
        // 无需更新
    }

    // ==================== 绘制 ====================

    override fun draw(canvas: Canvas) {
        if (sw == 0 || sh == 0) return

        // 全屏背景（浅灰色）
        canvas.drawColor(Color.rgb(200, 200, 200))

        // 标题栏
        titleBarHeight = sh * 0.12f
        fillPaint.color = Color.rgb(80, 80, 80)
        canvas.drawRect(0f, 0f, sw.toFloat(), titleBarHeight, fillPaint)

        boldTextPaint.textSize = sh * 0.07f
        boldTextPaint.color = Color.WHITE
        val title = "帮助"
        val titleW = boldTextPaint.measureText(title)
        val titleX = (sw - titleW) / 2f
        val titleY = titleBarHeight * 0.7f
        canvas.drawText(title, titleX, titleY, boldTextPaint)

        // 分栏区域
        val leftRatio = LEFT_RATIO
        listLeft = 0f
        listTop = titleBarHeight
        listRight = sw * leftRatio
        listBottom = sh.toFloat()

        contentLeft = listRight + 1f
        contentTop = titleBarHeight
        contentRight = sw.toFloat()
        contentBottom = sh.toFloat()

        // 分隔线
        fillPaint.color = Color.rgb(150, 150, 150)
        canvas.drawRect(listRight, listTop, listRight + 1f, listBottom, fillPaint)

        // ----- 左侧列表 -----
        fillPaint.color = Color.rgb(110, 110, 110)
        canvas.drawRect(listLeft, listTop, listRight, listBottom, fillPaint)

        var itemHeight = sh * 0.08f
        if (itemHeight < 40f) itemHeight = 40f
        val listTotalHeight = helpFiles.size * itemHeight
        val viewHeight = listBottom - listTop
        var maxListOffset = listTotalHeight - viewHeight
        if (maxListOffset < 0f) maxListOffset = 0f
        if (listScrollOffset < 0f) listScrollOffset = 0f
        if (listScrollOffset > maxListOffset) listScrollOffset = maxListOffset

        canvas.save()
        canvas.clipRect(listLeft, listTop, listRight, listBottom)

        textPaint.textSize = itemHeight * 0.4f
        textPaint.color = Color.WHITE

        for (i in helpFiles.indices) {
            val y = listTop + i * itemHeight - listScrollOffset
            if (y + itemHeight < listTop || y > listBottom) continue
            if (i == selectedIndex) {
                fillPaint.color = Color.rgb(80, 120, 180)
            } else {
                fillPaint.color = Color.rgb(130, 130, 130)
            }
            canvas.drawRect(listLeft, y, listRight, y + itemHeight, fillPaint)
            val textX = listLeft + 10f
            val textY = y + itemHeight * 0.65f
            canvas.drawText(helpDisplay[i], textX, textY, textPaint)
        }
        canvas.restore()

        // ----- 右侧内容 -----
        fillPaint.color = Color.rgb(200, 200, 200)
        canvas.drawRect(contentLeft, contentTop, contentRight, contentBottom, fillPaint)

        if (selectedIndex >= 0 && currentContent != null) {
            val contentWidth = contentRight - contentLeft
            val contentHeight = contentBottom - contentTop
            var fontSize = contentHeight * 0.05f
            if (fontSize < 20f) fontSize = 20f
            if (fontSize > 40f) fontSize = 40f

            prepareContentLines(contentWidth, fontSize)

            val viewH = contentHeight
            val totalH = contentTotalHeight
            var maxContentOffset = totalH - viewH
            if (maxContentOffset < 0f) maxContentOffset = 0f
            if (contentScrollOffset < 0f) contentScrollOffset = 0f
            if (contentScrollOffset > maxContentOffset) contentScrollOffset = maxContentOffset

            canvas.save()
            canvas.clipRect(contentLeft, contentTop, contentRight, contentBottom)

            textPaint.textSize = fontSize
            textPaint.color = Color.BLACK
            val x = contentLeft + 10f
            val startY = contentTop - contentScrollOffset + contentLineHeight
            for (i in contentLines.indices) {
                val y = startY + i * contentLineHeight
                if (y >= contentTop && y <= contentBottom + contentLineHeight) {
                    canvas.drawText(contentLines[i], x, y, textPaint)
                }
            }
            canvas.restore()
        } else {
            textPaint.textSize = sh * 0.06f
            textPaint.color = Color.rgb(100, 100, 100)
            val hint = "请选择一个帮助主题"
            val w = textPaint.measureText(hint)
            val x = contentLeft + (contentRight - contentLeft - w) / 2f
            val y = contentTop + (contentBottom - contentTop) / 2f + textPaint.textSize * 0.4f
            canvas.drawText(hint, x, y, textPaint)
        }
    }

    // ==================== 触摸 ====================

    override fun touch(action: Int, x: Float, y: Float) {
        val n = now()

        if (action == TouchAction.DOWN) {
            val inLeft = x >= listLeft && x <= listRight && y >= listTop && y <= listBottom

            // 双击检测：如果上次点击时间间隔短且位置接近
            if (n - lastTapTime < DOUBLE_TAP_MS &&
                abs(x - lastTapX) < DOUBLE_TAP_RADIUS &&
                abs(y - lastTapY) < DOUBLE_TAP_RADIUS) {
                // 双击：如果不是在左侧区域，返回主菜单
                if (!inLeft) {
                    GameView.instance?.gameState = GameState.MAIN_MENU
                    lastTapTime = 0L // 防止连续触发
                    return
                }
                // 如果在左侧区域双击，忽略
                lastTapTime = 0L
                return
            }

            // 记录本次点击
            lastTapTime = n
            lastTapX = x
            lastTapY = y
            lastTapInLeft = inLeft

            // 开始滚动
            if (inLeft) {
                listIsScrolling = true
                listTouchStartY = y
            } else if (x >= contentLeft && x <= contentRight && y >= contentTop && y <= contentBottom) {
                contentIsScrolling = true
                contentTouchStartY = y
            }
        } else if (action == TouchAction.MOVE) {
            if (listIsScrolling) {
                val dy = y - listTouchStartY
                listScrollOffset -= dy
                listTouchStartY = y
                var itemHeight = sh * 0.08f
                if (itemHeight < 40f) itemHeight = 40f
                val listTotalHeight = helpFiles.size * itemHeight
                val viewHeight = listBottom - listTop
                var maxOffset = listTotalHeight - viewHeight
                if (maxOffset < 0f) maxOffset = 0f
                if (listScrollOffset < 0f) listScrollOffset = 0f
                if (listScrollOffset > maxOffset) listScrollOffset = maxOffset
            }
            if (contentIsScrolling) {
                val dy = y - contentTouchStartY
                contentScrollOffset -= dy
                contentTouchStartY = y
                val viewH = contentBottom - contentTop
                val totalH = contentTotalHeight
                var maxOffset = totalH - viewH
                if (maxOffset < 0f) maxOffset = 0f
                if (contentScrollOffset < 0f) contentScrollOffset = 0f
                if (contentScrollOffset > maxOffset) contentScrollOffset = maxOffset
            }
        } else if (action == TouchAction.UP || action == TouchAction.CANCEL) {
            val moved = abs(x - lastTapX) > 20f || abs(y - lastTapY) > 20f
            listIsScrolling = false
            contentIsScrolling = false

            // 单击：只在未移动且点击在左侧区域时选中列表项
            if (!moved && lastTapInLeft) {
                var itemHeight = sh * 0.08f
                if (itemHeight < 40f) itemHeight = 40f
                val index = ((y - listTop + listScrollOffset) / itemHeight).toInt()
                if (index >= 0 && index < helpFiles.size) {
                    selectedIndex = index
                    loadContent(helpFiles[index])
                    contentScrollOffset = 0f
                }
            }
        }
    }
    override fun destroy() {}
}
