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
 * 故事浏览器
 * 对应原 C 工程 story_browser.c
 *
 * 两个子界面：
 *   STORY_LIST  - 档案列表（StoryListScreen）
 *   STORY_VIEW  - 内容查看（StoryViewScreen）
 *
 * 交互：
 *   列表：双击文件打开，双击空白返回主菜单，拖动列表滚动
 *   查看：双击返回列表，拖动内容滚动
 */
object StoryBrowserScreen {

    private const val TAG = "StoryBrowserScreen"

    /** 列表 / 查看共享的数据 */
    object StoryData {
        val storyFiles = ArrayList<String>()
        val storyDisplay = ArrayList<String>()
        var currentFile = ""
        var rawContent: String? = null
    }

    /** 文本换行工具（按像素宽度） */
    fun wrapText(text: String, fontSize: Float, maxWidth: Float, paint: Paint): ArrayList<String> {
        val result = ArrayList<String>()
        if (text.isEmpty()) return result
        paint.textSize = fontSize
        for (rawLine in text.split("\n")) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) { result.add(""); continue }
            var start = 0
            var accW = 0f
            var i = 0
            while (i < line.length) {
                val charW = paint.measureText(line, i, i + 1)
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

    fun loadTextAsset(path: String): String {
        val ctx = GameView.instance?.context ?: return ""
        return try {
            ctx.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
                .removePrefix("\uFEFF")
        } catch (e: Exception) {
            Log.e(TAG, "loadTextAsset failed: $path", e)
            ""
        }
    }

    fun loadFileList() {
        StoryData.storyFiles.clear()
        StoryData.storyDisplay.clear()
        val data = loadTextAsset("plot/a_catalogue.txt")
        if (data.isEmpty()) {
            Log.e(TAG, "plot/a_catalogue.txt not found")
            return
        }
        for (rawLine in data.split("\n")) {
            var line = rawLine.trimEnd('\r').trim()
            if (line.isEmpty()) continue
            val pipe = line.indexOf('|')
            if (pipe >= 0) {
                StoryData.storyFiles.add(line.substring(0, pipe))
                StoryData.storyDisplay.add(line.substring(pipe + 1))
            }
        }
    }

    fun loadStoryContent(filename: String): Boolean {
        val path = "plot/$filename"
        val data = loadTextAsset(path)
        if (data.isEmpty()) return false
        StoryData.rawContent = data
        return true
    }

    /** 根据文件名找显示名 */
    fun displayNameFor(file: String): String {
        for (i in StoryData.storyFiles.indices) {
            if (StoryData.storyFiles[i] == file) return StoryData.storyDisplay[i]
        }
        return file
    }
}

/**
 * 档案列表界面
 * 对应原 C 的 draw_file_list
 */
object StoryListScreen : GameScreen {

    private const val TAG = "StoryListScreen"
    private const val DOUBLE_TAP_MS = 300L
    private const val DOUBLE_TAP_RADIUS = 50f

    private var listScrollOffset = 0f
    private var listTouchStartY = 0f
    private var listIsScrolling = false

    private var itemHeight = 0f
    private var listTotalHeight = 0f
    private var listViewTop = 0f
    private var listViewBottom = 0f
    private var listLeftStart = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapInItem = false
    private var firstEnter = true

    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val boldTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val sw: Int get() = GameView.instance?.screenW ?: 0
    private val sh: Int get() = GameView.instance?.screenH ?: 0

    private fun now(): Long = System.currentTimeMillis()

    override fun init() {
        StoryBrowserScreen.loadFileList()
        StoryBrowserScreen.StoryData.rawContent = null
        StoryBrowserScreen.StoryData.currentFile = ""
        listScrollOffset = 0f
        listIsScrolling = false
        lastTapTime = 0L
        firstEnter = true
    }

    override fun update(deltaTime: Float) {}

    private fun isPointInListItem(x: Float, y: Float): Int {
        if (y < listViewTop || y > listViewBottom) return -1
        if (x < listLeftStart) return -1
        for (i in StoryBrowserScreen.StoryData.storyFiles.indices) {
            val itemY = listViewTop + i * itemHeight - listScrollOffset
            if (y >= itemY && y <= itemY + itemHeight) return i
        }
        return -1
    }

    override fun draw(canvas: Canvas) {
        if (sw == 0 || sh == 0) return

        if (firstEnter) {
            firstEnter = false
        }

        // 背景
        fillPaint.color = Color.rgb(110, 110, 110)
        canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fillPaint)

        // 标题栏
        val titleBarHeight = sh * 0.12f
        fillPaint.color = Color.rgb(80, 80, 80)
        canvas.drawRect(0f, 0f, sw.toFloat(), titleBarHeight, fillPaint)

        boldTextPaint.textSize = sh * 0.07f
        boldTextPaint.color = Color.WHITE
        val title = "档案列表"
        val titleW = boldTextPaint.measureText(title)
        canvas.drawText(title, (sw - titleW) / 2f, titleBarHeight * 0.7f, boldTextPaint)

        listViewTop = titleBarHeight + 2f
        listViewBottom = sh.toFloat()
        val viewHeight = listViewBottom - listViewTop
        itemHeight = sh * 0.09f
        listTotalHeight = StoryBrowserScreen.StoryData.storyFiles.size * itemHeight
        var maxOffset = listTotalHeight - viewHeight
        if (maxOffset < 0f) maxOffset = 0f
        if (listScrollOffset < 0f) listScrollOffset = 0f
        if (listScrollOffset > maxOffset) listScrollOffset = maxOffset

        listLeftStart = sw / 7.0f

        canvas.save()
        canvas.clipRect(listLeftStart, listViewTop, sw.toFloat(), listViewBottom)

        fillPaint.color = Color.rgb(140, 140, 140)
        textPaint.textSize = itemHeight * 0.4f
        textPaint.color = Color.WHITE
        val textLeft = listLeftStart + 10f

        for (i in StoryBrowserScreen.StoryData.storyFiles.indices) {
            val y = listViewTop + i * itemHeight - listScrollOffset
            if (y + itemHeight < listViewTop || y > listViewBottom) continue
            canvas.drawRect(listLeftStart, y, sw.toFloat(), y + itemHeight, fillPaint)
            canvas.drawText(StoryBrowserScreen.StoryData.storyDisplay[i], textLeft, y + itemHeight * 0.65f, textPaint)
        }
        canvas.restore()
    }

    override fun touch(action: Int, x: Float, y: Float) {
        val n = now()

        if (action == TouchAction.DOWN) {
            val itemIndex = isPointInListItem(x, y)
            val hitItem = itemIndex >= 0

            // 双击检测
            if (n - lastTapTime < DOUBLE_TAP_MS &&
                abs(x - lastTapX) < DOUBLE_TAP_RADIUS &&
                abs(y - lastTapY) < DOUBLE_TAP_RADIUS) {
                if (hitItem && lastTapInItem) {
                    StoryBrowserScreen.StoryData.currentFile = StoryBrowserScreen.StoryData.storyFiles[itemIndex]
                    if (StoryBrowserScreen.loadStoryContent(StoryBrowserScreen.StoryData.currentFile)) {
                        GameView.instance?.gameState = GameState.STORY_VIEW
                    } else {
                        Log.e(TAG, "无法读取文件")
                    }
                } else {
                    GameView.instance?.gameState = GameState.MAIN_MENU
                }
                lastTapTime = 0L
                lastTapInItem = false
                return
            }

            lastTapTime = n
            lastTapX = x
            lastTapY = y
            lastTapInItem = hitItem
            listTouchStartY = y
            listIsScrolling = false
        } else if (action == TouchAction.MOVE) {
            if (!listIsScrolling) {
                listIsScrolling = true
                listTouchStartY = y
            } else {
                val dy = y - listTouchStartY
                listScrollOffset -= dy
                listTouchStartY = y
                val viewHeight = listViewBottom - listViewTop
                var maxOffset = listTotalHeight - viewHeight
                if (maxOffset < 0f) maxOffset = 0f
                if (listScrollOffset < 0f) listScrollOffset = 0f
                if (listScrollOffset > maxOffset) listScrollOffset = maxOffset
            }
        } else if (action == TouchAction.UP || action == TouchAction.CANCEL) {
            listIsScrolling = false
        }
    }

    override fun destroy() {}
}

/**
 * 内容查看界面
 * 对应原 C 的 draw_content_view
 */
object StoryViewScreen : GameScreen {

    private const val TAG = "StoryViewScreen"
    private const val DOUBLE_TAP_MS = 300L
    private const val DOUBLE_TAP_RADIUS = 50f

    private var contentScrollOffset = 0f
    private var contentTouchStartY = 0f
    private var contentIsScrolling = false
    private var lastWrapRaw: String? = null
    private var lastWrapFontSize = 0f
    private var lastWrapWidth = 0f

    private val contentLines = ArrayList<String>()
    private var lineHeight = 0f
    private var totalHeight = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val boldTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val sw: Int get() = GameView.instance?.screenW ?: 0
    private val sh: Int get() = GameView.instance?.screenH ?: 0

    private fun now(): Long = System.currentTimeMillis()

    override fun init() {
        contentScrollOffset = 0f
        contentIsScrolling = false
        lastTapTime = 0L
    }

    override fun update(deltaTime: Float) {}

    override fun draw(canvas: Canvas) {
        if (sw == 0 || sh == 0) return

        // 整体背景浅灰色
        fillPaint.color = Color.rgb(140, 140, 140)
        canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fillPaint)

        // 标题栏
        val titleBarHeight = sh * 0.12f
        fillPaint.color = Color.rgb(80, 80, 80)
        canvas.drawRect(0f, 0f, sw.toFloat(), titleBarHeight, fillPaint)

        val displayName = StoryBrowserScreen.displayNameFor(StoryBrowserScreen.StoryData.currentFile)
        boldTextPaint.textSize = sh * 0.06f
        boldTextPaint.color = Color.WHITE
        val titleW = boldTextPaint.measureText(displayName)
        canvas.drawText(displayName, (sw - titleW) / 2f, titleBarHeight * 0.7f, boldTextPaint)

        // 内容区域
        val contentLeft = sw * 0.05f
        val contentTop = titleBarHeight
        val contentRight = sw - contentLeft
        val contentBottom = sh.toFloat()
        val contentWidth = contentRight - contentLeft
        val contentHeight = contentBottom - contentTop

        var fontSize = contentHeight * 0.05f
        if (fontSize < 20f) fontSize = 20f
        if (fontSize > 40f) fontSize = 40f

        val raw = StoryBrowserScreen.StoryData.rawContent ?: return
        if (raw != lastWrapRaw || fontSize != lastWrapFontSize || contentWidth != lastWrapWidth) {
            lastWrapRaw = raw; lastWrapFontSize = fontSize; lastWrapWidth = contentWidth
            contentLines.clear()
            contentLines.addAll(StoryBrowserScreen.wrapText(raw, fontSize, contentWidth - 20f, textPaint))
            lineHeight = fontSize * 1.2f
            totalHeight = contentLines.size * lineHeight
        }

        // 滚动范围
        val viewHeight = contentHeight
        val extraOffset = viewHeight / 3.0f
        var maxOffset = totalHeight - viewHeight + extraOffset
        if (maxOffset < 0f) maxOffset = 0f
        if (totalHeight <= viewHeight) {
            maxOffset = 0f
            contentScrollOffset = 0f
        } else {
            if (contentScrollOffset < 0f) contentScrollOffset = 0f
            if (contentScrollOffset > maxOffset) contentScrollOffset = maxOffset
        }

        canvas.save()
        canvas.clipRect(contentLeft, contentTop, contentRight, contentBottom)

        textPaint.textSize = fontSize
        textPaint.color = Color.WHITE
        val x = contentLeft + 10f
        val startY = contentTop - contentScrollOffset + lineHeight
        for (i in contentLines.indices) {
            val y = startY + i * lineHeight
            if (y >= contentTop && y <= contentBottom + lineHeight) {
                canvas.drawText(contentLines[i], x, y, textPaint)
            }
        }
        canvas.restore()
    }

    override fun touch(action: Int, x: Float, y: Float) {
        val n = now()

        if (action == TouchAction.DOWN) {
            if (n - lastTapTime < DOUBLE_TAP_MS &&
                abs(x - lastTapX) < DOUBLE_TAP_RADIUS &&
                abs(y - lastTapY) < DOUBLE_TAP_RADIUS) {
                GameView.instance?.gameState = GameState.STORY_LIST
                lastTapTime = 0L
                return
            }
            lastTapTime = n
            lastTapX = x
            lastTapY = y
            contentTouchStartY = y
            contentIsScrolling = false
        } else if (action == TouchAction.MOVE) {
            if (!contentIsScrolling) {
                contentIsScrolling = true
                contentTouchStartY = y
            } else {
                val dy = y - contentTouchStartY
                contentScrollOffset -= dy
                contentTouchStartY = y
                val viewHeight = sh - (sh * 0.12f)
                val extraOffset = viewHeight / 3.0f
                var maxOffset = totalHeight - viewHeight + extraOffset
                if (maxOffset < 0f) maxOffset = 0f
                if (totalHeight <= viewHeight) maxOffset = 0f
                if (contentScrollOffset < 0f) contentScrollOffset = 0f
                if (contentScrollOffset > maxOffset) contentScrollOffset = maxOffset
            }
        } else if (action == TouchAction.UP || action == TouchAction.CANCEL) {
            contentIsScrolling = false
        }
    }

    override fun destroy() {}
}
