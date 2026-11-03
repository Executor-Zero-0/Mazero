package org.mazero

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/** 迷宫地图数据 */
class MazeMap {
    var width = 0
    var height = 0
    var cells: Array<IntArray> = Array(0) { IntArray(0) }
    var startX = -1
    var startY = -1
    var goalX = -1
    var goalY = -1
    val meta = ArrayList<String>()

    fun inBounds(x: Int, y: Int): Boolean =
        x >= 0 && x < width && y >= 0 && y < height

    fun cell(x: Int, y: Int): Int =
        if (inBounds(x, y)) cells[y][x] else -1
}

/** 不同游戏对地图字符的语义 */
enum class MapSemantics {
    /** 常规迷宫：'1'=墙(1)，'0'=通路(0)，起点/终点存 0 */
    CGMG,

    /** 线性滑道：'0'=墙(1)，'1'=通路(0)，起点/终点存 0 */
    XXHD,

    /** 重力行者：'1'=墙(0)，'0'=通路(1)，起点/终点存 1 */
    ZLXZ
}

/**
 * 地图与元数据加载器
 * 旧 C 中三个游戏各自实现了一份 load_map，解析规则完全一致：
 *   - 地图数据位于两个 U+2061 标记之间
 *   - 元数据由成对的 U+200B 标记分隔
 *   - 网格字符 '0'~'9'，其中 6=起点、9=终点
 * 差异仅在于字符到通行格的映射，由 semantics 决定
 */
object MazeMapLoader {
    private const val TAG = "MazeMapLoader"
    private const val MAP_MARKER = '\u2061'
    private const val META_MARKER = '\u200B'

    fun readAssetText(path: String): String? {
        val ctx = GameView.instance?.context ?: return null
        return try {
            ctx.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.e(TAG, "readAssetText failed: $path", e)
            null
        }
    }

    fun load(code: String, level: Int, semantics: MapSemantics): MazeMap? {
        val text = readAssetText("map/${code}_$level.txt") ?: return null
        return parse(text, semantics)
    }

    fun parse(text: String, semantics: MapSemantics): MazeMap? {
        val first = text.indexOf(MAP_MARKER)
        val mapData: String
        val remaining: String
        if (first >= 0) {
            val second = text.indexOf(MAP_MARKER, first + 1)
            if (second > first) {
                mapData = text.substring(first + 1, second)
                remaining = text.substring(0, first) + text.substring(second + 1)
            } else {
                mapData = text
                remaining = ""
            }
        } else {
            mapData = text
            remaining = ""
        }

        if (mapData.isEmpty()) {
            Log.e(TAG, "地图数据为空")
            return null
        }

        val result = MazeMap()

        // 解析元数据：成对 U+200B 之间的内容
        var idx = 0
        var lastPos = -1
        var pair = 0
        while (idx < remaining.length && result.meta.size < 8) {
            val p = remaining.indexOf(META_MARKER, idx)
            if (p < 0) break
            if (pair == 0) {
                lastPos = p + 1
                pair = 1
            } else {
                if (lastPos in 0 until p) {
                    val block = remaining.substring(lastPos, p).trim()
                    if (block.isNotEmpty() && block.length < 511) {
                        result.meta.add(block)
                    }
                }
                pair = 0
                lastPos = -1
            }
            idx = p + 1
        }

        // 解析网格
        val rows = ArrayList<IntArray>()
        var cur = IntArray(mapData.length)
        var col = 0
        var maxCol = 0
        var row = 0
        var i = 0
        while (i < mapData.length && (mapData[i] == '\n' || mapData[i] == '\r')) i++
        while (i < mapData.length) {
            val c = mapData[i]
            if (c == '\n' || c == '\r') {
                if (col > 0) {
                    if (col > maxCol) maxCol = col
                    rows.add(cur.copyOf(col))
                    row++
                    col = 0
                    cur = IntArray(mapData.length)
                }
                while (i + 1 < mapData.length && (mapData[i + 1] == '\n' || mapData[i + 1] == '\r')) i++
            } else if (c in '0'..'9') {
                val v = c - '0'
                var stored = 0
                if (v == 6) {
                    result.startX = col
                    result.startY = row
                    stored = when (semantics) {
                        MapSemantics.CGMG -> 0
                        MapSemantics.XXHD -> 0
                        MapSemantics.ZLXZ -> 1
                    }
                } else if (v == 9) {
                    result.goalX = col
                    result.goalY = row
                    stored = when (semantics) {
                        MapSemantics.CGMG -> 0
                        MapSemantics.XXHD -> 0
                        MapSemantics.ZLXZ -> 1
                    }
                } else {
                    stored = when (semantics) {
                        MapSemantics.CGMG -> v
                        MapSemantics.XXHD -> if (v == 0) 1 else 0
                        MapSemantics.ZLXZ -> if (v == 0) 1 else 0
                    }
                }
                cur[col] = stored
                col++
            }
            i++
        }
        if (col > 0) {
            if (col > maxCol) maxCol = col
            rows.add(cur.copyOf(col))
            row++
        }

        result.width = maxCol
        result.height = row

        if (result.startX < 0 || result.goalX < 0) {
            Log.e(TAG, "地图缺少起点(6)或终点(9)")
            return null
        }

        // 对应旧 C：某行长度不足 mapWidth 时，缺失列以字符 '1' 补齐
        // 按各自 semantics 映射后：CGMG -> 1（墙）；XXHD / ZLXZ -> 0（可通行）
        val pad = when (semantics) {
            MapSemantics.CGMG -> 1
            MapSemantics.XXHD -> 0
            MapSemantics.ZLXZ -> 0
        }
        val cells = Array(result.height) { IntArray(result.width) }
        for (y in 0 until result.height) {
            val src = rows.getOrNull(y) ?: continue
            for (x in 0 until result.width) {
                cells[y][x] = if (x < src.size) src[x] else pad
            }
        }
        result.cells = cells
        return result
    }
}

/**
 * 进度与关卡存档
 * 旧 C 使用 /sdcard/.mazero/ 下的 *.dat 二进制读写，Android 下改为 SharedPreferences
 */
object MazeProgress {
    private const val PREFS = "mazero_progress"
    private fun prefs() =
        GameView.instance?.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadRecord(code: String, level: Int): IntArray {
        val p = prefs() ?: return IntArray(4)
        val k = "${code}_$level"
        return intArrayOf(
            p.getInt("${k}_fc", 0),
            p.getInt("${k}_fct", 0),
            p.getInt("${k}_bt", 0),
            p.getInt("${k}_bs", 0)
        )
    }

    fun saveRecord(code: String, level: Int, fc: Int, fct: Int, bt: Int, bs: Int) {
        val p = prefs() ?: return
        val k = "${code}_$level"
        p.edit()
            .putInt("${k}_fc", fc)
            .putInt("${k}_fct", fct)
            .putInt("${k}_bt", bt)
            .putInt("${k}_bs", bs)
            .apply()
    }

    /** [playerX, playerY, stepCount, levelStartTime, levelPausedTime, levelTimeSeconds, timerStarted, isLevelPaused, viewX, viewY, scale, rotation, playerState] */
    fun loadGameState(code: String, level: Int): LongArray? {
        val p = prefs() ?: return null
        val k = "${code}_${level}_state"
        if (!p.getBoolean("${k}_exists", false)) return null
        return longArrayOf(
            p.getLong("${k}_px", 0L),
            p.getLong("${k}_py", 0L),
            p.getLong("${k}_sc", 0L),
            p.getLong("${k}_lst", 0L),
            p.getLong("${k}_lpt", 0L),
            p.getLong("${k}_lts", 0L),
            p.getLong("${k}_ts", 0L),
            p.getLong("${k}_lp", 0L),
            p.getLong("${k}_vx", 0L),
            p.getLong("${k}_vy", 0L),
            p.getLong("${k}_vs", 0L),
            p.getLong("${k}_vr", 0L),
            p.getLong("${k}_ps", 0L)
        )
    }

    fun saveGameState(
        code: String, level: Int,
        playerX: Int, playerY: Int, stepCount: Int,
        levelStartTime: Long, levelPausedTime: Long, levelTimeSeconds: Int,
        timerStarted: Int, isLevelPaused: Int,
        viewX: Float, viewY: Float, scale: Float, rotation: Float, playerState: Int
    ) {
        val p = prefs() ?: return
        val k = "${code}_${level}_state"
        p.edit()
            .putBoolean("${k}_exists", true)
            .putLong("${k}_px", playerX.toLong())
            .putLong("${k}_py", playerY.toLong())
            .putLong("${k}_sc", stepCount.toLong())
            .putLong("${k}_lst", levelStartTime)
            .putLong("${k}_lpt", levelPausedTime)
            .putLong("${k}_lts", levelTimeSeconds.toLong())
            .putLong("${k}_ts", timerStarted.toLong())
            .putLong("${k}_lp", isLevelPaused.toLong())
            .putLong("${k}_vx", viewX.toRawBits().toLong())
            .putLong("${k}_vy", viewY.toRawBits().toLong())
            .putLong("${k}_vs", scale.toRawBits().toLong())
            .putLong("${k}_vr", rotation.toRawBits().toLong())
            .putLong("${k}_ps", playerState.toLong())
            .apply()
    }

    fun deleteGameState(code: String, level: Int) {
        val p = prefs() ?: return
        p.edit().remove("${code}_${level}_state_exists").apply()
    }
}

/** Toast 提示（替代旧 C 的 showToastText） */
object MazeToast {
    private val handler = Handler(Looper.getMainLooper())
    fun show(msg: String) {
        val ctx = GameView.instance?.context ?: return
        handler.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}

/** 游戏内顶部按钮图标（对应旧 C decodeBitmapFromAssets 的静态缓存） */
object MazeIcons {
    private var settings: Bitmap? = null
    private var undo: Bitmap? = null
    private var reset: Bitmap? = null
    private var fullscreen: Bitmap? = null

    private fun load(path: String): Bitmap? = try {
        GameView.instance?.context?.assets?.open(path)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Log.e("MazeIcons", "load failed: $path", e)
        null
    }

    fun settings(): Bitmap? { if (settings == null) settings = load("button_icon/allocate.png"); return settings }
    fun undo(): Bitmap? { if (undo == null) undo = load("button_icon/withdraw.png"); return undo }
    fun reset(): Bitmap? { if (reset == null) reset = load("button_icon/reset.png"); return reset }
    fun fullscreen(): Bitmap? { if (fullscreen == null) fullscreen = load("button_icon/full_screen.png"); return fullscreen }
    fun player(): Bitmap? = load("button_icon/p1.png")
}

/**
 * 迷宫模块统一接口
 * 三个游戏（cgmg/xxhd/zlxz）各自实现
 */
interface MazeModule {
    val code: String
    val displayName: String
    fun metaOf(i: Int): String?
    val metaCount: Int
    fun isFirstComplete(): Boolean
    fun bestTime(): Int
    fun bestSteps(): Int
    fun firstCompleteTime(): Int

    val loadOk: Boolean

    fun enterLevel(level: Int)
    fun startTimer()
    fun drawFullscreen(canvas: Canvas)
    fun drawGame(canvas: Canvas)
    fun update(dt: Float)
    fun touch(action: Int, x: Float, y: Float, pointers: Map<Int, Pair<Float, Float>>)
    fun pauseTimer()
    fun saveState()
    fun resetLevelFull()
    fun destroyModule()
}

/**
 * 开始界面 / 通关界面的共享渲染
 * 对应 maze_game.c 的 cgmg_draw_start_screen / cgmg_draw_complete_screen
 * （xxhd/zlxz 的布局公式完全一致，仅数据来源不同）
 */
object MazeFlow {
    private val textPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val btnBgPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val btnTextPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val blurPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private fun sw() = (GameView.instance?.screenW ?: 0).toFloat()
    private fun sh() = (GameView.instance?.screenH ?: 0).toFloat()

    fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h${m}m${s}s"
            m > 0 -> "${m}m${s}s"
            else -> "${s}s"
        }
    }

    private fun drawWrappedText(canvas: Canvas, text: String, x: Float, y0: Float, fontSize: Float) {
        if (text.isEmpty()) return
        textPaint.textSize = fontSize
        var y = y0
        val lineHeight = fontSize * 1.3f
        for (raw in text.split('\n')) {
            val line = raw.trimStart(' ', '\t')
            if (line.isNotEmpty()) canvas.drawText(line, x, y, textPaint)
            y += lineHeight
        }
    }

    private fun rewardText(base: String?, markComplete: Boolean): String {
        if (base == null) return "--"
        return if (markComplete) base.replace("\u25A2", "\u25A8") else base
    }

    /** 开始界面，返回 12 个按钮坐标 [back, unlock, start] 各 4 */
    fun drawStartScreen(canvas: Canvas, module: MazeModule): FloatArray {
        val w = sw(); val h = sh()
        val buttons = FloatArray(12)
        canvas.save(); canvas.clipRect(0f, 0f, w, h)
        module.drawFullscreen(canvas)
        canvas.restore()

        blurPaint.color = Color.argb(160, 240, 240, 240)
        canvas.drawRect(0f, 0f, w, h * 0.22f, blurPaint)
        canvas.drawRect(0f, h * 0.78f, w, h, blurPaint)
        blurPaint.color = Color.argb(220, 150, 150, 150)
        canvas.drawRect(0f, h * 0.22f, w, h * 0.78f, blurPaint)

        val topLimit = h * 0.30f
        val bottomLimit = h * 1.00f
        val leftLimit = w * 0.25f
        val rightLimit = w * 0.75f
        val usableWidth = rightLimit - leftLimit
        val usableHeight = bottomLimit - topLimit
        val rowHeight = usableHeight / 11.0f
        var fontSize = rowHeight * 0.5f
        if (fontSize < 14f) fontSize = 14f
        if (fontSize > 48f) fontSize = 48f
        val smallFontSize = fontSize * 0.6f
        val largeFontSize = fontSize * 1.2f

        val title = module.metaOf(0)?.ifEmpty { null } ?: "常规迷宫"
        val spec = module.metaOf(1)
        val difficulty = module.metaOf(2)
        val acquire = module.metaOf(3)
        val firstCompleteStr = module.metaOf(4)
        val time = module.metaOf(5)
        val goal = module.metaOf(6)
        val loadInfo = module.metaOf(7)
        val rewardDisplay = rewardText(firstCompleteStr, module.isFirstComplete())

        var y = topLimit + rowHeight * 0.1f
        textPaint.textSize = largeFontSize
        textPaint.color = Color.rgb(255, 255, 255)
        canvas.drawText(title, leftLimit + (usableWidth - textPaint.measureText(title)) / 2f, y, textPaint)
        y += rowHeight

        textPaint.textSize = fontSize
        textPaint.color = Color.rgb(250, 250, 250)
        canvas.drawText("规格：" + (spec ?: "--"), leftLimit, y, textPaint)
        val diffLabel = "难度：" + (difficulty ?: "--")
        canvas.drawText(diffLabel, rightLimit - textPaint.measureText(diffLabel), y, textPaint)
        y += rowHeight

        textPaint.color = Color.rgb(250, 250, 250)
        canvas.drawText("获取：" + (acquire ?: "--"), leftLimit, y, textPaint)
        val rewardLabel = "奖励：$rewardDisplay"
        canvas.drawText(rewardLabel, rightLimit - textPaint.measureText(rewardLabel), y, textPaint)
        y += rowHeight

        canvas.drawText("荐时：" + (time ?: "--"), leftLimit, y, textPaint)
        val goalLabel = "目标：" + (goal ?: "--")
        canvas.drawText(goalLabel, rightLimit - textPaint.measureText(goalLabel), y, textPaint)
        y += rowHeight

        textPaint.color = Color.rgb(240, 240, 240)
        drawWrappedText(canvas, "可能的器官负荷：" + (loadInfo ?: "--"), leftLimit, y, smallFontSize)
        val lineCount = 1 + (if (!loadInfo.isNullOrEmpty()) loadInfo.split('\n').size else 0)
        y += rowHeight * 0.2f + (if (lineCount > 1) rowHeight * 0.5f else 0f)
        y += rowHeight * 0.3f

        val btnSpacing = usableWidth * 0.08f
        val btnW = maxOf((usableWidth - btnSpacing * 2) / 3f, 40f)
        val btnH = rowHeight * 0.65f
        val btnY = y

        buttons[0] = leftLimit; buttons[1] = btnY; buttons[2] = btnW; buttons[3] = btnH
        buttons[4] = leftLimit + btnW + btnSpacing; buttons[5] = btnY; buttons[6] = btnW; buttons[7] = btnH
        buttons[8] = leftLimit + (btnW + btnSpacing) * 2f; buttons[9] = btnY; buttons[10] = btnW; buttons[11] = btnH

        btnBgPaint.color = Color.argb(200, 100, 100, 100)
        canvas.drawRoundRect(buttons[0], buttons[1], buttons[0] + btnW, buttons[1] + btnH, btnH * 0.2f, btnH * 0.2f, btnBgPaint)
        btnBgPaint.color = Color.argb(200, 180, 150, 50)
        canvas.drawRoundRect(buttons[4], buttons[5], buttons[4] + btnW, buttons[5] + btnH, btnH * 0.2f, btnH * 0.2f, btnBgPaint)
        btnBgPaint.color = Color.argb(200, 0, 180, 0)
        canvas.drawRoundRect(buttons[8], buttons[9], buttons[8] + btnW, buttons[9] + btnH, btnH * 0.2f, btnH * 0.2f, btnBgPaint)

        btnTextPaint.textSize = fontSize * 0.5f
        val labels = arrayOf("返离", "获取", "登录")
        for (k in 0 until 3) {
            val lw = btnTextPaint.measureText(labels[k])
            canvas.drawText(labels[k], buttons[k * 4] + (buttons[k * 4 + 2] - lw) / 2f, buttons[k * 4 + 1] + buttons[k * 4 + 3] * 0.7f, btnTextPaint)
        }
        return buttons
    }

    /** 通关界面，返回 12 个按钮坐标 [back, export, reset] 各 4 */
    fun drawCompleteScreen(canvas: Canvas, module: MazeModule): FloatArray {
        val w = sw(); val h = sh()
        val buttons = FloatArray(12)
        canvas.save(); canvas.clipRect(0f, 0f, w, h)
        module.drawFullscreen(canvas)
        canvas.restore()

        blurPaint.color = Color.argb(160, 240, 240, 240)
        canvas.drawRect(0f, 0f, w * 2f / 9f, h, blurPaint)
        canvas.drawRect(w * 7f / 9f, 0f, w, h, blurPaint)
        blurPaint.color = Color.argb(220, 150, 150, 150)
        canvas.drawRect(w * 2f / 9f, 0f, w * 7f / 9f, h, blurPaint)

        val topLimit = h * 0.175f
        val bottomLimit = h * 1.0f
        val leftLimit = w * 0.235f
        val rightLimit = w * 0.765f
        val usableWidth = rightLimit - leftLimit
        val usableHeight = bottomLimit - topLimit
        val rowHeight = usableHeight / 11.0f
        var fontSize = rowHeight * 0.5f
        if (fontSize < 14f) fontSize = 14f
        if (fontSize > 48f) fontSize = 48f
        val smallFontSize = fontSize * 0.6f
        val largeFontSize = fontSize * 1.2f

        val title = module.metaOf(0)?.ifEmpty { null } ?: "常规迷宫"
        val spec = module.metaOf(1)
        val difficulty = module.metaOf(2)
        val acquire = module.metaOf(3)
        val goal = module.metaOf(6)
        val loadInfo = module.metaOf(7)
        val rewardDisplay = rewardText(module.metaOf(4), module.isFirstComplete())
        val bestTimeV = module.bestTime()
        val bestStepsV = module.bestSteps()
        val firstCompleteTimeV = module.firstCompleteTime()

        var y = topLimit + rowHeight * 0.1f
        textPaint.textSize = largeFontSize
        textPaint.color = Color.rgb(255, 255, 255)
        val titleBuf = "已通关 $title"
        canvas.drawText(titleBuf, leftLimit + (usableWidth - textPaint.measureText(titleBuf)) / 2f, y, textPaint)
        y += rowHeight

        textPaint.textSize = fontSize
        textPaint.color = Color.rgb(250, 250, 250)
        canvas.drawText("规格：" + (spec ?: "--"), leftLimit, y, textPaint)
        val diffLabel = "难度：" + (difficulty ?: "--")
        canvas.drawText(diffLabel, rightLimit - textPaint.measureText(diffLabel), y, textPaint)
        y += rowHeight

        canvas.drawText("获取：" + (acquire ?: "--"), leftLimit, y, textPaint)
        val rewardLabel = "奖励：$rewardDisplay"
        canvas.drawText(rewardLabel, rightLimit - textPaint.measureText(rewardLabel), y, textPaint)
        y += rowHeight

        canvas.drawText("荐时：" + (module.metaOf(5)?.ifEmpty { null } ?: "--"), leftLimit, y, textPaint)
        val firstLabel = "首通：" + formatTime(firstCompleteTimeV)
        canvas.drawText(firstLabel, rightLimit - textPaint.measureText(firstLabel), y, textPaint)
        y += rowHeight

        canvas.drawText("本次：" + formatTime(Global.completeTime), leftLimit, y, textPaint)
        val bestLabel = "最短：" + formatTime(bestTimeV)
        canvas.drawText(bestLabel, rightLimit - textPaint.measureText(bestLabel), y, textPaint)
        y += rowHeight

        canvas.drawText("步耗：${Global.completeSteps}步", leftLimit, y, textPaint)
        val stepBest = "最短：${bestStepsV}步"
        canvas.drawText(stepBest, rightLimit - textPaint.measureText(stepBest), y, textPaint)
        y += rowHeight

        canvas.drawText("目标：" + (goal ?: "--"), leftLimit, y, textPaint)
        y += rowHeight

        if (!loadInfo.isNullOrEmpty()) {
            textPaint.color = Color.rgb(240, 240, 240)
            drawWrappedText(canvas, "可能的器官负荷：$loadInfo", leftLimit, y, smallFontSize)
            y += rowHeight * 0.2f + (if (loadInfo.split('\n').size > 1) rowHeight * 0.5f else 0f)
        }

        y += rowHeight * 0.8f
        textPaint.textSize = fontSize * 0.8f
        textPaint.color = Color.rgb(255, 215, 0)
        val codeBuf = "凭证：${Global.completeCode}"
        canvas.drawText(codeBuf, leftLimit + (usableWidth - textPaint.measureText(codeBuf)) / 2f, y, textPaint)
        y += rowHeight * 0.5f

        val btnSpacing = usableWidth * 0.08f
        val btnW = maxOf((usableWidth - btnSpacing * 2) / 3f, 40f)
        val btnH = rowHeight * 0.65f
        val btnY = y

        buttons[0] = leftLimit; buttons[1] = btnY; buttons[2] = btnW; buttons[3] = btnH
        buttons[4] = leftLimit + btnW + btnSpacing; buttons[5] = btnY; buttons[6] = btnW; buttons[7] = btnH
        buttons[8] = leftLimit + (btnW + btnSpacing) * 2f; buttons[9] = btnY; buttons[10] = btnW; buttons[11] = btnH

        btnBgPaint.color = Color.argb(200, 100, 100, 100)
        canvas.drawRoundRect(buttons[0], buttons[1], buttons[0] + btnW, buttons[1] + btnH, btnH * 0.2f, btnH * 0.2f, btnBgPaint)
        btnBgPaint.color = Color.argb(200, 60, 120, 200)
        canvas.drawRoundRect(buttons[4], buttons[5], buttons[4] + btnW, buttons[5] + btnH, btnH * 0.2f, btnH * 0.2f, btnBgPaint)
        btnBgPaint.color = Color.argb(200, 200, 150, 50)
        canvas.drawRoundRect(buttons[8], buttons[9], buttons[8] + btnW, buttons[9] + btnH, btnH * 0.2f, btnH * 0.2f, btnBgPaint)

        btnTextPaint.textSize = fontSize * 0.5f
        val labels = arrayOf("返离", "导出", "重置")
        for (k in 0 until 3) {
            val lw = btnTextPaint.measureText(labels[k])
            canvas.drawText(labels[k], buttons[k * 4] + (buttons[k * 4 + 2] - lw) / 2f, buttons[k * 4 + 1] + buttons[k * 4 + 3] * 0.7f, btnTextPaint)
        }
        return buttons
    }
}
