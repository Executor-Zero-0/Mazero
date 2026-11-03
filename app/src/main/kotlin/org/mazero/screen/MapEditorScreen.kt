package org.mazero.screen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import org.mazero.GameScreen
import org.mazero.GameState
import org.mazero.GameView
import org.mazero.TouchAction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 地图编辑器
 * 对应原 C 工程 map_editor.c
 *
 * 两种模式：
 *   编辑模式（previewMode=false）：面板设置 单元/填充/规格，按钮 载入/导入/创建
 *   预览模式（previewMode=true）：显示网格，点击/拖动编辑，长按弹出菜单，双指缩放
 *
 * 单元类型：
 *   1 单元：单格，点击翻转
 *   3 单元：隔行（奇数行列不可操作），滑动染色
 *   5 单元：四格线（仅特定格子可操作），笔锁直线
 */
object MapEditorScreen : GameScreen {

    private const val TAG = "MapEditorScreen"
    private const val PREFS_NAME = "mazero_editor"

    // ---------- 可调参数 ----------
    private const val PADDING = 40f
    private const val ROW_SPACING = 0.3f
    private const val FONT_SCALE = 0.35f
    private const val ARROW_SCALE = 0.8f
    private const val ARROW_GAP = 8f
    private const val GROUP_SPACING_RATIO = 0.15f
    private const val INPUT_WIDTH_SCALE = 2.2f
    private const val INPUT_HEIGHT_SCALE = 1.2f
    private const val GAP = 10f
    private const val BTN_WIDTH_SCALE = 0.5f
    private const val BTN_HEIGHT_SCALE = 0.5f
    private const val BTN_SPACING = 20f
    private const val BTN_FONT_SCALE = 0.4f

    private const val ARROW1_OFFSET_X = 4f
    private const val Y1_TEXT_OFFSET = 1.0f
    private const val Y1_ARROW_OFFSET = 0.55f
    private const val Y2_TEXT_OFFSET = 0.5f
    private const val Y2_INPUT_OFFSET = -0.5f
    private const val Y3_OFFSET = -0.5f

    private const val DOUBLE_TAP_MS = 300L
    private const val DOUBLE_TAP_RADIUS = 50f
    private const val LONG_PRESS_MS = 600L
    private const val DRAG_THRESHOLD = 10f
    private const val TWO_FINGER_COOLDOWN_MS = 200L

    // ---------- 状态 ----------
    private var unit = 1
    private var fill = 0
    private var mapWidth = 10
    private var mapHeight = 10
    private var previewMode = false

    private var previewMap: Array<IntArray>? = null
    private var previewW = 0
    private var previewH = 0

    // 编辑界面坐标
    private var gLeftArrowX = 0f
    private var gRightArrowX = 0f
    private var gFillLeftArrowX = 0f
    private var gFillRightArrowX = 0f
    private var gBox1X = 0f; private var gBox1Y = 0f; private var gBox1W = 0f; private var gBox1H = 0f
    private var gBox2X = 0f; private var gBox2Y = 0f; private var gBox2W = 0f; private var gBox2H = 0f
    private val gBtnX = FloatArray(3)
    private val gBtnY = FloatArray(3)
    private var gBtnW = 0f; private var gBtnH = 0f
    private var gArrowY = 0f; private var gFillArrowY = 0f; private var gArrowSize = 0f
    private var gUnitValX = 0f; private var gFillValX = 0f

    private var menuCooldownUntil = 0L
    private var isDrawing = false
    private var firstColor = -1
    private var isPressing = false
    private var pressX = 0f; private var pressY = 0f
    private var pressTime = 0L
    private var longPressTriggered = false

    // 双指结束防误触
    private var twoFingerEndTime = 0L

    // 单元3 滑动染色
    private var slideActive = false
    private var slideLockDir = 0
    private var slideStartCol = -1; private var slideStartRow = -1
    private var slideLastCol = -1; private var slideLastRow = -1

    // 单元5 笔锁
    private var drawLockDir = 0
    private var drawStartCol = -1; private var drawStartRow = -1
    private var drawLastCol = -1; private var drawLastRow = -1

    // 双指缩放
    private var isTwoFinger = false
    private var oldDist = 0f; private var oldScale = 1f
    private var oldViewX = 0f; private var oldViewY = 0f
    private var pivotX = 0f; private var pivotY = 0f

    // 视口
    private var viewX = 0f; private var viewY = 0f; private var viewScale = 1f
    private var rectLeft = 0f; private var rectTop = 0f; private var rectRight = 0f; private var rectBottom = 0f
    private var gBaseCellSize = 0f; private var gCellSize = 0f

    // 菜单
    private var menuVisible = false
    private var menuAnimOffset = 0f
    private var menuAnimStart = 0L
    private var menuAnimating = false

    // 撤销
    private const val MAX_FLIP_RECORDS = 1024
    private const val FLIP_UNDO_TIMEOUT = 300L
    private class FlipRecord {
        var time = 0L
        var col = 0
        var row = 0
        var oldColor = 0
        var undone = false
    }
    private val flipRecords = Array(MAX_FLIP_RECORDS) { FlipRecord() }
    private var flipRecordCount = 0
    private var flipRecordHead = 0
    private var flipRecordTail = 0

    // 导入状态
    private const val IMPORT_NONE = 0
    private const val IMPORT_FILE_LIST = 1
    private const val IMPORT_UNIT_SEL = 2
    private var importState = IMPORT_NONE
    private val importFiles = ArrayList<String>()
    private var importSelectedIndex = -1
    private var importScrollOffset = 0
    private var importChosenUnit = 1

    // ---------- 画笔 ----------
    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val strokePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val boldTextPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }

    // ==================== 工具 ====================
    private val sw: Float get() = (GameView.instance?.screenW ?: 0).toFloat()
    private val sh: Float get() = (GameView.instance?.screenH ?: 0).toFloat()

    private fun now(): Long = System.currentTimeMillis()

    private fun showToast(msg: String) {
        val ctx = GameView.instance?.context ?: return
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "showToast error", e)
        }
    }

    // ==================== 撤销 ====================
    private fun recordFlip(col: Int, row: Int, oldColor: Int) {
        cleanupOldRecords()
        val rec = flipRecords[flipRecordTail]
        rec.time = now()
        rec.col = col
        rec.row = row
        rec.oldColor = oldColor
        rec.undone = false
        flipRecordTail = (flipRecordTail + 1) % MAX_FLIP_RECORDS
        if (flipRecordTail == flipRecordHead) {
            flipRecordHead = (flipRecordHead + 1) % MAX_FLIP_RECORDS
        } else {
            flipRecordCount++
        }
    }

    private fun cleanupOldRecords() {
        val n = now()
        while (flipRecordCount > 0) {
            if (n - flipRecords[flipRecordHead].time < FLIP_UNDO_TIMEOUT) break
            flipRecordHead = (flipRecordHead + 1) % MAX_FLIP_RECORDS
            flipRecordCount--
        }
    }

    private fun undoRecentFlips() {
        val map = previewMap ?: return
        val n = now()
        var undoneCount = 0
        var idx = flipRecordHead
        for (i in 0 until flipRecordCount) {
            val rec = flipRecords[idx]
            if (!rec.undone && (n - rec.time) < FLIP_UNDO_TIMEOUT) {
                if (rec.col in 0 until previewW && rec.row in 0 until previewH) {
                    map[rec.row][rec.col] = rec.oldColor
                }
                rec.undone = true
                undoneCount++
            }
            idx = (idx + 1) % MAX_FLIP_RECORDS
        }
        if (undoneCount > 0) saveEditorMap()
    }

    // ==================== 格子可操作判断（单元5） ====================
    private fun isCellOperable(col: Int, row: Int): Boolean {
        if (unit != 5) return true
        return (col % 4 == 0) || (row % 4 == 0) || ((col - row) % 4 == 0) || ((col + row) % 4 == 0)
    }

    // ==================== 方向判定（8扇形 -> 4轴） ====================
    private fun determineDirection(dCol: Int, dRow: Int): Int {
        if (abs(dCol) + abs(dRow) < 1) return 0
        var angle = Math.toDegrees(atan2(dRow.toDouble(), dCol.toDouble()))
        if (angle < 0) angle += 360.0
        return if ((angle < 22.5 || angle >= 337.5) || (angle >= 157.5 && angle < 202.5)) 1
        else if ((angle >= 67.5 && angle < 112.5) || (angle >= 247.5 && angle < 292.5)) 2
        else if ((angle >= 22.5 && angle < 67.5) || (angle >= 202.5 && angle < 247.5)) 3
        else 4
    }

    private fun stepInDir(dir: Int, col: IntArray, row: IntArray, step: Int) {
        when (dir) {
            1 -> col[0] += step
            2 -> row[0] += step
            3 -> { col[0] += step; row[0] += step }
            4 -> { col[0] += step; row[0] -= step }
        }
    }

    // ==================== 视口 ====================
    private fun clampViewportPreview() {
        if (previewMap == null) return
        val mW = previewW * gCellSize
        val mH = previewH * gCellSize
        val cx = (rectLeft + rectRight) / 2f
        val cy = (rectTop + rectBottom) / 2f

        val minX = cx - mW
        val maxX = cx
        if (viewX < minX) viewX = minX
        if (viewX > maxX) viewX = maxX

        val minY = cy - mH
        val maxY = cy
        if (viewY < minY) viewY = minY
        if (viewY > maxY) viewY = maxY
    }

    private fun initViewportPreview() {
        rectLeft = 0f; rectTop = 0f
        rectRight = sw; rectBottom = sh
        if (previewW <= 0 || previewH <= 0) return
        val cw = sw / previewW
        val ch = sh / previewH
        gBaseCellSize = min(cw, ch)
        viewScale = 1f
        gCellSize = gBaseCellSize
        val mW = previewW * gCellSize
        val mH = previewH * gCellSize
        viewX = rectLeft + (sw - mW) / 2f
        viewY = rectTop + (sh - mH) / 2f
        clampViewportPreview()
    }

    private fun screenToMap(sx: Float, sy: Float): IntArray {
        val px = sx - viewX
        val py = sy - viewY
        val col = if (gCellSize > 0f) (px / gCellSize).toInt() else -1
        val row = if (gCellSize > 0f) (py / gCellSize).toInt() else -1
        return intArrayOf(col, row)
    }

    // ==================== 存档 ====================
    private var lastSaveMs = 0L

    private fun saveEditorMap(force: Boolean = false) {
        val map = previewMap ?: return
        val ctx = GameView.instance?.context ?: return
        // 节流：拖动中高频写入合并，关键节点传 force=true 立即落盘
        val n = now()
        if (!force && n - lastSaveMs < 200L) return
        lastSaveMs = n
        try {
            val sb = StringBuilder()
            for (y in 0 until previewH) {
                for (x in 0 until previewW) sb.append(if (map[y][x] != 0) '1' else '0')
                if (y < previewH - 1) sb.append('\n')
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("unit", unit)
                .putInt("w", previewW)
                .putInt("h", previewH)
                .putString("map", sb.toString())
                .putFloat("viewX", viewX)
                .putFloat("viewY", viewY)
                .putFloat("viewScale", viewScale)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveEditorMap error", e)
        }
    }

    private fun loadEditorMap(): Boolean {
        val ctx = GameView.instance?.context ?: return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mapStr = prefs.getString("map", null) ?: return false
        val w = prefs.getInt("w", 0)
        val h = prefs.getInt("h", 0)
        if (w <= 0 || h <= 0 || mapStr.isEmpty()) return false
        val rows = mapStr.split("\n")
        if (rows.size < h) return false
        previewMap = Array(h) { y ->
            val rowStr = rows.getOrNull(y) ?: ""
            IntArray(w) { x -> if (x < rowStr.length && rowStr[x] == '1') 1 else 0 }
        }
        previewW = w
        previewH = h
        unit = prefs.getInt("unit", 1)
        previewMode = true
        rectLeft = 0f; rectTop = 0f; rectRight = sw; rectBottom = sh
        val cw = sw / w
        val ch = sh / h
        gBaseCellSize = min(cw, ch)
        val vs = prefs.getFloat("viewScale", 0f)
        if (vs > 0f) {
            viewScale = vs
            gCellSize = gBaseCellSize * viewScale
            viewX = prefs.getFloat("viewX", 0f)
            viewY = prefs.getFloat("viewY", 0f)
            clampViewportPreview()
        } else {
            initViewportPreview()
        }
        return true
    }

    // ==================== 生成 / 导出 ====================
    private fun generateMap() {
        val rw: Int
        val rh: Int
        if (unit == 1) {
            rw = mapWidth; rh = mapHeight
        } else if (unit == 3) {
            rw = mapWidth * 3 - (mapWidth - 1); rh = mapHeight * 3 - (mapHeight - 1)
        } else {
            rw = mapWidth * 5 - (mapWidth - 1); rh = mapHeight * 5 - (mapHeight - 1)
        }
        previewMap = Array(rh) { IntArray(rw) { fill } }
        previewW = rw
        previewH = rh
        slideActive = false; slideLockDir = 0
        slideLastCol = -1; slideLastRow = -1
        drawLockDir = 0
        drawStartCol = -1; drawStartRow = -1
        drawLastCol = -1; drawLastRow = -1
        flipRecordCount = 0; flipRecordHead = 0; flipRecordTail = 0
        for (r in flipRecords) r.undone = false
        previewMode = true
        initViewportPreview()
        saveEditorMap()
    }

    /** 导出目录：应用私有 filesDir/mazero */
    private fun exportDir(): File {
        val ctx = GameView.instance?.context
        val base = ctx?.filesDir ?: File("/data/local/tmp")
        val dir = File(base, "mazero")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun exportMap() {
        val map = previewMap
        if (map == null) { showToast("无地图"); return }
        try {
            val dir = exportDir()
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val fn = "map_" + fmt.format(Date()) + ".txt"
            val f = File(dir, fn)
            val sb = StringBuilder()
            for (y in 0 until previewH) {
                for (x in 0 until previewW) sb.append(if (map[y][x] != 0) '1' else '0')
                sb.append('\n')
            }
            f.writeText(sb.toString())
            showToast("已导出到 " + f.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "exportMap error", e)
            showToast("导出失败")
        }
    }

    // ==================== 导入 ====================
    private fun initImport() {
        importFiles.clear()
        importScrollOffset = 0
        importSelectedIndex = -1
        importState = IMPORT_FILE_LIST
        try {
            val dir = exportDir()
            val list = dir.listFiles()
            if (list != null) {
                for (f in list) {
                    if (f.isFile && f.name.lowercase().endsWith(".txt")) {
                        importFiles.add(f.absolutePath)
                        if (importFiles.size >= 256) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initImport error", e)
        }
        if (importFiles.isEmpty()) {
            showToast("没有可导入的地图文件")
            importState = IMPORT_NONE
        }
    }

    /** 解析并加载地图文件，unitType 为指定单元类型 */
    private fun loadMapFromFile(path: String, unitType: Int) {
        try {
            val f = File(path)
            if (!f.exists()) { showToast("无法打开文件"); return }
            val lines = f.readLines()
            if (lines.isEmpty()) { showToast("文件为空"); return }
            val first = lines[0].trimEnd('\r', '\n')
            val width = first.length
            if (width == 0) { showToast("文件格式错误"); return }
            val height = lines.size
            val map = Array(height) { y ->
                val ln = lines.getOrNull(y)?.trimEnd('\r', '\n') ?: ""
                IntArray(width) { x ->
                    if (x < ln.length && (ln[x] == '1')) 1 else 0
                }
            }
            previewMap = map
            previewW = width
            previewH = height
            unit = unitType
            fill = 0
            slideActive = false; slideLockDir = 0
            slideLastCol = -1; slideLastRow = -1
            drawLockDir = 0
            drawStartCol = -1; drawStartRow = -1
            drawLastCol = -1; drawLastRow = -1
            flipRecordCount = 0; flipRecordHead = 0; flipRecordTail = 0
            for (r in flipRecords) r.undone = false
            previewMode = true
            initViewportPreview()
            saveEditorMap()
            importState = IMPORT_NONE
        } catch (e: Exception) {
            Log.e(TAG, "loadMapFromFile error", e)
            showToast("无法打开文件")
        }
    }

    // ==================== 辅助线 ====================
    private fun drawAuxiliary(canvas: Canvas) {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1f
        strokePaint.color = Color.argb(150, 200, 200, 200)

        var startCol = ((rectLeft - viewX) / gCellSize).toInt() - 1
        var endCol = ((rectRight - viewX) / gCellSize).toInt() + 2
        var startRow = ((rectTop - viewY) / gCellSize).toInt() - 1
        var endRow = ((rectBottom - viewY) / gCellSize).toInt() + 2
        if (startCol < 0) startCol = 0
        if (endCol > previewW) endCol = previewW
        if (startRow < 0) startRow = 0
        if (endRow > previewH) endRow = previewH

        if (unit == 3) {
            var x = startCol
            while (x <= endCol) {
                if (x % 2 == 0) {
                    val lx = viewX + x * gCellSize + gCellSize / 2f
                    if (lx in rectLeft..rectRight) canvas.drawLine(lx, rectTop, lx, rectBottom, strokePaint)
                }
                x++
            }
            var y = startRow
            while (y <= endRow) {
                if (y % 2 == 0) {
                    val ly = viewY + y * gCellSize + gCellSize / 2f
                    if (ly in rectTop..rectBottom) canvas.drawLine(rectLeft, ly, rectRight, ly, strokePaint)
                }
                y++
            }
        } else if (unit == 5) {
            var y = startRow
            while (y <= endRow) {
                if (y % 4 == 0) {
                    val ly = viewY + y * gCellSize + gCellSize / 2f
                    canvas.drawLine(rectLeft, ly, rectRight, ly, strokePaint)
                }
                y++
            }
            var x = startCol
            while (x <= endCol) {
                if (x % 4 == 0) {
                    val lx = viewX + x * gCellSize + gCellSize / 2f
                    canvas.drawLine(lx, rectTop, lx, rectBottom, strokePaint)
                }
                x++
            }
            val step = 4
            var yy = (startRow / step) * step
            while (yy <= endRow) {
                var xx = (startCol / step) * step
                while (xx <= endCol) {
                    val cx = viewX + xx * gCellSize + gCellSize / 2f
                    val cy = viewY + yy * gCellSize + gCellSize / 2f
                    val cx2 = viewX + (xx + step) * gCellSize + gCellSize / 2f
                    val cy2 = viewY + (yy + step) * gCellSize + gCellSize / 2f
                    if (xx + step <= previewW && yy + step <= previewH) {
                        canvas.drawLine(cx, cy, cx2, cy2, strokePaint)
                        canvas.drawLine(cx2, cy, cx, cy2, strokePaint)
                    }
                    xx += step
                }
                yy += step
            }
        }
    }

    // ==================== 绘制编辑界面 ====================
    private fun drawEditor(canvas: Canvas) {
        if (importState != IMPORT_NONE) {
            drawImportUI(canvas)
            return
        }

        canvas.drawColor(Color.rgb(160, 160, 160))

        val panelLeft = PADDING
        val panelTop = PADDING
        val panelRight = sw - PADDING
        val panelBottom = sh - PADDING
        val panelW = panelRight - panelLeft
        val panelH = panelBottom - panelTop

        fillPaint.color = Color.rgb(200, 200, 200)
        canvas.drawRoundRect(panelLeft, panelTop, panelRight, panelBottom, 20f, 20f, fillPaint)

        val rowCount = 3
        val rowHeight = panelH / (rowCount + ROW_SPACING)
        var fontSize = rowHeight * FONT_SCALE
        if (fontSize < 16f) fontSize = 16f
        if (fontSize > 36f) fontSize = 36f

        textPaint.textSize = fontSize
        textPaint.color = Color.rgb(50, 50, 50)
        textPaint.typeface = Typeface.DEFAULT_BOLD

        val yBase = panelTop + (panelH - rowHeight * rowCount) / 2f
        val halfRow = rowHeight * 0.5f
        val y1Text = yBase + halfRow * Y1_TEXT_OFFSET * 2f
        val y1Arrow = yBase + halfRow * Y1_ARROW_OFFSET * 2f

        val unitStr = unit.toString()
        val unitLabelW = textPaint.measureText("单元：")
        val unitValW = textPaint.measureText(unitStr)
        gArrowSize = fontSize * ARROW_SCALE
        val arrowGap = ARROW_GAP
        val unitGroupW = unitLabelW + arrowGap + gArrowSize + arrowGap + unitValW + arrowGap + gArrowSize

        val fillStr = fill.toString()
        val fillLabelW = textPaint.measureText("填充：")
        val fillValW = textPaint.measureText(fillStr)
        val fillGroupW = fillLabelW + arrowGap + gArrowSize + arrowGap + fillValW + arrowGap + gArrowSize

        val groupSpacing = panelW * GROUP_SPACING_RATIO
        val totalFirstRowW = unitGroupW + groupSpacing + fillGroupW
        val startX1 = panelLeft + (panelW - totalFirstRowW) / 2f
        val unitGroupX = startX1
        val fillGroupX = startX1 + unitGroupW + groupSpacing

        val unitLabelX = unitGroupX
        val unitLabelY = y1Text + fontSize * 0.6f
        canvas.drawText("单元：", unitLabelX, unitLabelY, textPaint)

        val arrowY = y1Arrow + (rowHeight - gArrowSize) / 2f
        gArrowY = arrowY
        val unitValX = unitLabelX + unitLabelW + arrowGap + gArrowSize + arrowGap
        gUnitValX = unitValX
        val leftArrowX = unitLabelX + unitLabelW + arrowGap + ARROW1_OFFSET_X
        val rightArrowX = unitValX + unitValW + arrowGap + ARROW1_OFFSET_X
        gLeftArrowX = leftArrowX; gRightArrowX = rightArrowX

        textPaint.textSize = fontSize
        textPaint.color = Color.rgb(80, 80, 80)
        canvas.drawText("<", leftArrowX, arrowY + gArrowSize * 0.8f, textPaint)
        textPaint.color = Color.rgb(50, 50, 50)
        canvas.drawText(unitStr, unitValX, unitLabelY, textPaint)
        textPaint.color = Color.rgb(80, 80, 80)
        canvas.drawText(">", rightArrowX, arrowY + gArrowSize * 0.8f, textPaint)

        val fillDisabled = (unit == 3 || unit == 5)
        if (fillDisabled) fill = 0
        val fillLabelX = fillGroupX
        val fillLabelY = y1Text + fontSize * 0.6f
        textPaint.color = if (fillDisabled) Color.rgb(180, 180, 180) else Color.rgb(50, 50, 50)
        canvas.drawText("填充：", fillLabelX, fillLabelY, textPaint)

        val fillValX = fillLabelX + fillLabelW + arrowGap + gArrowSize + arrowGap
        gFillValX = fillValX
        val fillLeftArrowX = fillLabelX + fillLabelW + arrowGap + ARROW1_OFFSET_X
        val fillRightArrowX = fillValX + fillValW + arrowGap + ARROW1_OFFSET_X
        gFillLeftArrowX = fillLeftArrowX; gFillRightArrowX = fillRightArrowX
        val fillArrowY = y1Arrow + (rowHeight - gArrowSize) / 2f
        gFillArrowY = fillArrowY

        textPaint.color = if (fillDisabled) Color.rgb(180, 180, 180) else Color.rgb(80, 80, 80)
        canvas.drawText("<", fillLeftArrowX, fillArrowY + gArrowSize * 0.8f, textPaint)
        textPaint.color = if (fillDisabled) Color.rgb(180, 180, 180) else Color.rgb(50, 50, 50)
        canvas.drawText(fillStr, fillValX, fillLabelY, textPaint)
        textPaint.color = if (fillDisabled) Color.rgb(180, 180, 180) else Color.rgb(80, 80, 80)
        canvas.drawText(">", fillRightArrowX, fillArrowY + gArrowSize * 0.8f, textPaint)

        val y2 = yBase + rowHeight + halfRow * Y2_TEXT_OFFSET * 2f
        val wStr = mapWidth.toString()
        val hStr = mapHeight.toString()
        val specLabelW = textPaint.measureText("规格：")
        val inputWidth = fontSize * INPUT_WIDTH_SCALE
        val inputHeight = fontSize * INPUT_HEIGHT_SCALE
        val multW = textPaint.measureText("×")
        val unitSuffixW = textPaint.measureText("单元")
        val gap = GAP
        val specGroupW = specLabelW + gap + inputWidth + gap + multW + gap + inputWidth + gap + unitSuffixW
        val startX2 = panelLeft + (panelW - specGroupW) / 2f

        val specLabelX = startX2
        val specLabelY = y2 + fontSize * 0.6f
        textPaint.color = Color.rgb(50, 50, 50)
        canvas.drawText("规格：", specLabelX, specLabelY, textPaint)

        val inputY = y2 + (rowHeight - inputHeight) / 2f + halfRow * Y2_INPUT_OFFSET * 2f
        val box1X = specLabelX + specLabelW + gap
        val box2X = box1X + inputWidth + gap + multW + gap
        gBox1X = box1X; gBox1Y = inputY; gBox1W = inputWidth; gBox1H = inputHeight

        fillPaint.color = Color.WHITE
        canvas.drawRect(box1X, inputY, box1X + inputWidth, inputY + inputHeight, fillPaint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 2f
        strokePaint.color = Color.rgb(100, 100, 100)
        canvas.drawRect(box1X, inputY, box1X + inputWidth, inputY + inputHeight, strokePaint)

        textPaint.textSize = fontSize * 0.7f
        textPaint.color = Color.BLACK
        val wStrW = textPaint.measureText(wStr)
        canvas.drawText(wStr, box1X + (inputWidth - wStrW) / 2f, inputY + inputHeight * 0.7f, textPaint)

        val multX = box1X + inputWidth + gap
        textPaint.textSize = fontSize
        textPaint.color = Color.rgb(50, 50, 50)
        canvas.drawText("×", multX, specLabelY, textPaint)

        gBox2X = box2X; gBox2Y = inputY; gBox2W = inputWidth; gBox2H = inputHeight
        fillPaint.color = Color.WHITE
        canvas.drawRect(box2X, inputY, box2X + inputWidth, inputY + inputHeight, fillPaint)
        strokePaint.strokeWidth = 2f
        strokePaint.color = Color.rgb(100, 100, 100)
        canvas.drawRect(box2X, inputY, box2X + inputWidth, inputY + inputHeight, strokePaint)

        textPaint.textSize = fontSize * 0.7f
        textPaint.color = Color.BLACK
        val hStrW = textPaint.measureText(hStr)
        canvas.drawText(hStr, box2X + (inputWidth - hStrW) / 2f, inputY + inputHeight * 0.7f, textPaint)

        val unitSuffixX = box2X + inputWidth + gap
        textPaint.textSize = fontSize
        textPaint.color = Color.rgb(50, 50, 50)
        canvas.drawText("单元", unitSuffixX, specLabelY, textPaint)

        val y3 = yBase + rowHeight * 2 + halfRow * Y3_OFFSET * 2f
        val btnWidth = (panelW - 4 * BTN_SPACING) / 3f * BTN_WIDTH_SCALE
        val btnHeight = rowHeight * 0.7f * BTN_HEIGHT_SCALE
        val btnSpacing = BTN_SPACING
        val totalBtnW = btnWidth * 3 + btnSpacing * 2
        val btnStartX = panelLeft + (panelW - totalBtnW) / 2f

        val btnLabels = arrayOf("载入", "导入", "创建")
        textPaint.textSize = fontSize * BTN_FONT_SCALE
        textPaint.typeface = Typeface.DEFAULT_BOLD
        gBtnW = btnWidth; gBtnH = btnHeight
        for (i in 0 until 3) {
            val bx = btnStartX + i * (btnWidth + btnSpacing)
            val by = y3 + (rowHeight - btnHeight) / 2f
            gBtnX[i] = bx; gBtnY[i] = by
            fillPaint.color = Color.argb(200, 100, 120, 140)
            canvas.drawRoundRect(bx, by, bx + btnWidth, by + btnHeight, btnHeight * 0.2f, btnHeight * 0.2f, fillPaint)
            textPaint.color = Color.WHITE
            val labelW = textPaint.measureText(btnLabels[i])
            canvas.drawText(btnLabels[i], bx + (btnWidth - labelW) / 2f, by + btnHeight * 0.7f, textPaint)
        }
    }

    // ==================== 绘制导入界面 ====================
    private fun drawImportUI(canvas: Canvas) {
        canvas.drawColor(Color.rgb(40, 40, 40))

        boldTextPaint.textSize = 36f
        boldTextPaint.color = Color.WHITE

        if (importState == IMPORT_FILE_LIST) {
            canvas.drawText("选择地图文件", 20f, 60f, boldTextPaint)
            textPaint.textSize = 28f
            textPaint.color = Color.rgb(220, 220, 220)

            val listTop = 100f
            val listBottom = sh - 20f
            val itemHeight = 45f

            val totalContentHeight = importFiles.size * itemHeight
            var maxScroll = (totalContentHeight - (listBottom - listTop)).toInt()
            if (maxScroll < 0) maxScroll = 0
            if (importScrollOffset < 0) importScrollOffset = 0
            if (importScrollOffset > maxScroll) importScrollOffset = maxScroll

            val startIdx = importScrollOffset / itemHeight.toInt()
            val remainder = importScrollOffset % itemHeight.toInt()
            var drawY = listTop - remainder

            var i = startIdx
            while (i < importFiles.size && drawY < listBottom) {
                val fullPath = importFiles[i]
                val slash = fullPath.lastIndexOf('/')
                val fileName = if (slash >= 0) fullPath.substring(slash + 1) else fullPath
                if (i == importSelectedIndex) {
                    fillPaint.color = Color.argb(100, 100, 150, 200)
                    canvas.drawRect(10f, drawY, sw - 10f, drawY + itemHeight - 2f, fillPaint)
                }
                canvas.drawText(fileName, 30f, drawY + itemHeight * 0.7f, textPaint)
                drawY += itemHeight
                i++
            }

            fillPaint.color = Color.rgb(120, 120, 120)
            canvas.drawRoundRect(sw / 2f - 60f, sh - 60f, sw / 2f + 60f, sh - 20f, 10f, 10f, fillPaint)
            boldTextPaint.textSize = 30f
            boldTextPaint.color = Color.WHITE
            canvas.drawText("取消", sw / 2f - 40f, sh - 32f, boldTextPaint)

            if (importSelectedIndex in 0 until importFiles.size) {
                fillPaint.color = Color.rgb(50, 150, 50)
                canvas.drawRoundRect(sw - 130f, sh - 60f, sw - 20f, sh - 20f, 10f, 10f, fillPaint)
                textPaint.textSize = 24f
                textPaint.color = Color.WHITE
                canvas.drawText("选择类型", sw - 120f, sh - 32f, textPaint)
            }
        } else if (importState == IMPORT_UNIT_SEL) {
            canvas.drawText("选择单元类型", 20f, 60f, boldTextPaint)

            val unitNames = arrayOf("1单元 (普通)", "3单元 (隔行)", "5单元 (四格线)")
            var btnY = 150f
            val btnH = 70f
            textPaint.textSize = 28f
            textPaint.typeface = Typeface.DEFAULT_BOLD
            for (i in 0 until 3) {
                fillPaint.color = Color.argb(200, 70, 130, 180)
                canvas.drawRoundRect(50f, btnY, sw - 50f, btnY + btnH, 15f, 15f, fillPaint)
                textPaint.color = Color.WHITE
                val tw = textPaint.measureText(unitNames[i])
                canvas.drawText(unitNames[i], (sw - tw) / 2f, btnY + btnH * 0.65f, textPaint)
                btnY += btnH + 20f
            }

            fillPaint.color = Color.rgb(120, 120, 120)
            canvas.drawRoundRect(sw / 2f - 60f, sh - 70f, sw / 2f + 60f, sh - 30f, 10f, 10f, fillPaint)
            textPaint.color = Color.WHITE
            val cw = textPaint.measureText("取消")
            canvas.drawText("取消", sw / 2f - cw / 2f, sh - 42f, textPaint)
        }
    }

    // ==================== 绘制预览地图 ====================
    private fun drawPreviewMap(canvas: Canvas) {
        canvas.drawColor(Color.rgb(180, 180, 180))
        val map = previewMap ?: return
        rectLeft = 0f; rectTop = 0f; rectRight = sw; rectBottom = sh
        if (previewW <= 0 || previewH <= 0) return
        val cw = sw / previewW
        val ch = sh / previewH
        gBaseCellSize = min(cw, ch)
        gCellSize = gBaseCellSize * viewScale
        clampViewportPreview()

        fillPaint.style = Paint.Style.FILL
        var sc = ((rectLeft - viewX) / gCellSize).toInt() - 1
        var ec = ((rectRight - viewX) / gCellSize).toInt() + 2
        var sr = ((rectTop - viewY) / gCellSize).toInt() - 1
        var er = ((rectBottom - viewY) / gCellSize).toInt() + 2
        if (sc < 0) sc = 0
        if (ec > previewW) ec = previewW
        if (sr < 0) sr = 0
        if (er > previewH) er = previewH

        for (y in sr until er) {
            for (x in sc until ec) {
                val l = viewX + x * gCellSize - 0.5f
                val t = viewY + y * gCellSize - 0.5f
                val r = l + gCellSize + 1.0f
                val b = t + gCellSize + 1.0f
                if (r < rectLeft || l > rectRight || b < rectTop || t > rectBottom) continue
                val v = map[y][x]
                if (unit == 5 && !isCellOperable(x, y)) {
                    fillPaint.color = Color.WHITE
                } else {
                    fillPaint.color = if (v == 0) Color.WHITE else Color.BLACK
                }
                canvas.drawRect(l, t, r, b, fillPaint)
            }
        }

        if (unit == 1) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = 1f
            strokePaint.color = Color.rgb(128, 128, 128)
            var x = sc
            while (x <= ec) {
                val lx = viewX + x * gCellSize
                if (lx in rectLeft..rectRight) canvas.drawLine(lx, rectTop, lx, rectBottom, strokePaint)
                x++
            }
            var y = sr
            while (y <= er) {
                val ly = viewY + y * gCellSize
                if (ly in rectTop..rectBottom) canvas.drawLine(rectLeft, ly, rectRight, ly, strokePaint)
                y++
            }
        }
        if (unit == 3 || unit == 5) drawAuxiliary(canvas)

        if (menuVisible || menuAnimating) {
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.argb(120, 0, 0, 0)
            canvas.drawRect(0f, 0f, sw, sh, fillPaint)

            val mw = sw / 4f
            val ml = -mw + menuAnimOffset * mw
            fillPaint.color = Color.rgb(200, 200, 200)
            canvas.drawRect(ml, 0f, ml + mw, sh, fillPaint)

            val sepY = 30f
            fillPaint.color = Color.rgb(150, 150, 150)
            canvas.drawRect(ml + 10f, sepY, ml + mw - 10f, sepY + 2f, fillPaint)

            val btnY = sepY + 20f
            val btnH = 50f
            val btnW = mw * 0.7f
            val btnX = (mw - btnW) / 2f
            val labels = arrayOf("保存并退出", "导出")
            textPaint.textSize = 24f
            textPaint.typeface = Typeface.DEFAULT_BOLD
            for (i in 0 until 2) {
                val by = btnY + i * (btnH + 10f)
                fillPaint.color = Color.argb(200, 100, 120, 140)
                canvas.drawRoundRect(ml + btnX, by, ml + btnX + btnW, by + btnH, btnH * 0.2f, btnH * 0.2f, fillPaint)
                textPaint.color = Color.WHITE
                val lw = textPaint.measureText(labels[i])
                canvas.drawText(labels[i], ml + btnX + (btnW - lw) / 2f, by + btnH * 0.7f, textPaint)
            }
        }
    }

    // ==================== 触摸入口 ====================
    // 编辑模式双击返回
    private var lastTapTimeEditor = 0L
    private var lastTapXEditor = 0f
    private var lastTapYEditor = 0f
    // 预览模式双击返回地图外
    private var lastTapPreview = 0L
    private var lastTapXPreview = 0f
    private var lastTapYPreview = 0f

    override fun touch(action: Int, x: Float, y: Float) {
        handleTouch(action, x, y, null)
    }

    override fun touchMulti(action: Int, x: Float, y: Float, pointers: Map<Int, Pair<Float, Float>>) {
        handleTouch(action, x, y, pointers)
    }

    private fun handleTouch(action: Int, x: Float, y: Float, pointers: Map<Int, Pair<Float, Float>>?) {
        val masked = action and TouchAction.MASK
        if (now() < menuCooldownUntil) return
        if (!previewMode && importState != IMPORT_NONE) {
            importTouchHandler(masked, x, y)
            return
        }
        if (!previewMode) {
            handleEditorTouch(masked, x, y)
            return
        }
        handlePreviewTouch(masked, x, y, pointers)
    }

    // ==================== 输入对话框 ====================
    private fun showInputDialog(title: String, init: String, onOk: (String) -> Unit) {
        val ctx = GameView.instance?.context ?: return
        try {
            val et = EditText(ctx)
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setText(init)
            et.setSelection(init.length)
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定") { _, _ -> onOk(et.text.toString()) }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showInputDialog error", e)
        }
    }

    // ==================== 编辑模式触摸 ====================
    private fun handleEditorTouch(action: Int, x: Float, y: Float) {
        if (action != TouchAction.DOWN) return

        val padding = PADDING
        val rowCount = 3f
        val rowHeight = (sh - 2 * padding) / (rowCount + ROW_SPACING)
        var fontSize = rowHeight * FONT_SCALE
        if (fontSize < 16f) fontSize = 16f
        if (fontSize > 36f) fontSize = 36f
        val yBase = padding + ((sh - 2 * padding) - rowHeight * rowCount) / 2f
        val halfRow = rowHeight * 0.5f
        val arrowSize = fontSize * ARROW_SCALE
        val y1Arrow = yBase + halfRow * Y1_ARROW_OFFSET * 2f
        val arrowY = y1Arrow + (rowHeight - arrowSize) / 2f
        val fillArrowY = arrowY

        // 单元左箭头
        if (x >= gLeftArrowX - 10 && x <= gLeftArrowX + arrowSize + 10 &&
            y >= arrowY - 10 && y <= arrowY + arrowSize + 10) {
            unit = when (unit) { 1 -> 5; 5 -> 3; else -> 1 }
            if ((unit == 3 || unit == 5) && fill != 0) fill = 0
            return
        }
        // 单元右箭头
        if (x >= gRightArrowX - 10 && x <= gRightArrowX + arrowSize + 10 &&
            y >= arrowY - 10 && y <= arrowY + arrowSize + 10) {
            unit = when (unit) { 1 -> 3; 3 -> 5; else -> 1 }
            if ((unit == 3 || unit == 5) && fill != 0) fill = 0
            return
        }
        // 填充箭头（仅单元1）
        if (unit == 1) {
            if (x >= gFillLeftArrowX - 10 && x <= gFillLeftArrowX + arrowSize + 10 &&
                y >= fillArrowY - 10 && y <= fillArrowY + arrowSize + 10) {
                fill = if (fill == 0) 1 else 0
                return
            }
            if (x >= gFillRightArrowX - 10 && x <= gFillRightArrowX + arrowSize + 10 &&
                y >= fillArrowY - 10 && y <= fillArrowY + arrowSize + 10) {
                fill = if (fill == 0) 1 else 0
                return
            }
        }
        // 宽度输入框
        if (x >= gBox1X && x <= gBox1X + gBox1W && y >= gBox1Y && y <= gBox1Y + gBox1H) {
            showInputDialog("输入长（列数）", mapWidth.toString()) { v ->
                val n = v.toIntOrNull() ?: 0
                if (n > 0) mapWidth = n
            }
            return
        }
        // 高度输入框
        if (x >= gBox2X && x <= gBox2X + gBox2W && y >= gBox2Y && y <= gBox2Y + gBox2H) {
            showInputDialog("输入宽（行数）", mapHeight.toString()) { v ->
                val n = v.toIntOrNull() ?: 0
                if (n > 0) mapHeight = n
            }
            return
        }
        // 三个按钮
        for (i in 0 until 3) {
            if (x >= gBtnX[i] && x <= gBtnX[i] + gBtnW && y >= gBtnY[i] && y <= gBtnY[i] + gBtnH) {
                when (i) {
                    0 -> {
                        if (loadEditorMap()) { /* 已切到预览模式 */ } else showToast("没有已保存的地图")
                    }
                    1 -> initImport()
                    2 -> {
                        if (mapWidth < 1 || mapHeight < 1) { showToast("规格必须为正整数"); return }
                        val maxW: Int; val maxH: Int
                        if (unit == 1) { maxW = 1000; maxH = 1000 }
                        else if (unit == 3) { maxW = 333; maxH = 333 }
                        else { maxW = 200; maxH = 200 }
                        if (mapWidth > maxW || mapHeight > maxH) {
                            showToast("规格超出限制，最大 ${maxW}x${maxH}")
                            return
                        }
                        generateMap()
                    }
                }
                return
            }
        }
        // 双击空白返回主菜单
        val n = now()
        if (n - lastTapTimeEditor < DOUBLE_TAP_MS &&
            abs(x - lastTapXEditor) < DOUBLE_TAP_RADIUS &&
            abs(y - lastTapYEditor) < DOUBLE_TAP_RADIUS) {
            GameView.instance?.gameState = GameState.MAIN_MENU
            lastTapTimeEditor = 0L
            return
        }
        lastTapTimeEditor = n
        lastTapXEditor = x
        lastTapYEditor = y
    }

    // ==================== 导入界面触摸 ====================
    private var importTouchStartY = 0f

    private fun importTouchHandler(action: Int, x: Float, y: Float) {
        if (importState == IMPORT_FILE_LIST) {
            if (action == TouchAction.DOWN) { importTouchStartY = y }
            else if (action == TouchAction.MOVE) {
                val dy = y - importTouchStartY
                importTouchStartY = y
                if (abs(dy) > 1f) {
                    importScrollOffset -= dy.toInt()
                    val totalH = importFiles.size * 45
                    var maxScroll = (totalH - (sh - 60f - 100f)).toInt()
                    if (maxScroll < 0) maxScroll = 0
                    if (importScrollOffset < 0) importScrollOffset = 0
                    if (importScrollOffset > maxScroll) importScrollOffset = maxScroll
                }
                return
            }
        }
        if (action != TouchAction.DOWN) return

        if (importState == IMPORT_FILE_LIST) {
            val listTop = 100f
            val listBottom = sh - 60f
            val itemHeight = 45

            // 取消
            if (y >= sh - 60 && y <= sh - 20 && x >= sw / 2f - 60 && x <= sw / 2f + 60) {
                importState = IMPORT_NONE
                return
            }
            // 选择类型
            if (importSelectedIndex in 0 until importFiles.size &&
                y >= sh - 60 && y <= sh - 20 && x >= sw - 130 && x <= sw - 20) {
                importChosenUnit = 1
                importState = IMPORT_UNIT_SEL
                return
            }
            // 文件列表项
            if (y >= listTop && y <= listBottom) {
                val idx = (importScrollOffset + (y - listTop).toInt()) / itemHeight
                if (idx in 0 until importFiles.size) importSelectedIndex = idx
            }
        } else if (importState == IMPORT_UNIT_SEL) {
            var btnY = 150f
            val btnH = 70f
            val unitVals = intArrayOf(1, 3, 5)
            for (i in 0 until 3) {
                if (y >= btnY && y <= btnY + btnH && x >= 50 && x <= sw - 50) {
                    if (importSelectedIndex in 0 until importFiles.size) {
                        loadMapFromFile(importFiles[importSelectedIndex], unitVals[i])
                    }
                    return
                }
                btnY += btnH + 20f
            }
            // 取消
            if (y >= sh - 70 && y <= sh - 30 && x >= sw / 2f - 60 && x <= sw / 2f + 60) {
                importState = IMPORT_FILE_LIST
                importSelectedIndex = -1
            }
        }
    }

    // ==================== 预览模式触摸 ====================
    private fun handlePreviewTouch(action: Int, x: Float, y: Float, pointers: Map<Int, Pair<Float, Float>>?) {
        val ptrList = pointers?.values?.toList() ?: emptyList()
        val n = now()

        // 菜单处理
        if (menuVisible || menuAnimating) {
            if (action == TouchAction.DOWN || action == TouchAction.POINTER_DOWN) {
                val mw = sw / 4f
                val ml = -mw + menuAnimOffset * mw
                if (x < ml || x > ml + mw) {
                    menuVisible = false
                    menuAnimating = true
                    menuAnimOffset = 1f
                    menuAnimStart = n
                    menuCooldownUntil = n + 400L
                    return
                }
                val sepY = 30f
                val btnY = sepY + 20f
                val btnH = 50f
                val btnW2 = mw * 0.7f
                val btnX = (mw - btnW2) / 2f
                for (i in 0 until 2) {
                    val by = btnY + i * (btnH + 10f)
                    if (x >= ml + btnX && x <= ml + btnX + btnW2 && y >= by && y <= by + btnH) {
                        if (i == 0) {
                            saveEditorMap(true)
                            previewMode = false
                            menuVisible = false
                            menuAnimating = false
                        } else {
                            exportMap()
                            menuVisible = false
                            menuAnimating = true
                            menuAnimOffset = 1f
                            menuAnimStart = n
                        }
                        return
                    }
                }
            }
            return
        }

        // -------- 双指缩放 --------
        if (action == TouchAction.DOWN || action == TouchAction.POINTER_DOWN) {
            if (ptrList.size >= 2 && !isTwoFinger) {
                isTwoFinger = true
                undoRecentFlips()
                val p1 = ptrList[0]
                val p2 = ptrList[1]
                val dx = p1.first - p2.first
                val dy = p1.second - p2.second
                oldDist = sqrt(dx * dx + dy * dy)
                oldScale = viewScale
                oldViewX = viewX
                oldViewY = viewY
                pivotX = (p1.first + p2.first) / 2f
                pivotY = (p1.second + p2.second) / 2f
                slideActive = false
                drawLockDir = 0
                return
            }
        }
        if (action == TouchAction.MOVE && isTwoFinger) {
            if (ptrList.size >= 2) {
                val p1 = ptrList[0]
                val p2 = ptrList[1]
                val dx = p1.first - p2.first
                val dy = p1.second - p2.second
                val nd = sqrt(dx * dx + dy * dy)
                if (oldDist == 0f) oldDist = nd
                val ratio = nd / oldDist
                var ns = oldScale * ratio
                if (ns < 0.2f) ns = 0.2f
                if (ns > 3.0f) ns = 3.0f
                val oldCS = gBaseCellSize * oldScale
                if (oldCS > 0f) {
                    val cmx = (pivotX - oldViewX) / oldCS
                    val cmy = (pivotY - oldViewY) / oldCS
                    viewScale = ns
                    gCellSize = gBaseCellSize * viewScale
                    viewX = pivotX - cmx * gCellSize
                    viewY = pivotY - cmy * gCellSize
                    val cPX = (p1.first + p2.first) / 2f
                    val cPY = (p1.second + p2.second) / 2f
                    viewX += (cPX - pivotX)
                    viewY += (cPY - pivotY)
                    clampViewportPreview()
                }
            } else {
                // 只剩一指，结束双指缩放（进入冷却防误触）
                isTwoFinger = false
                twoFingerEndTime = n
            }
            return
        }
        if (action == TouchAction.POINTER_UP || action == TouchAction.UP) {
            if (isTwoFinger && ptrList.size <= 1) {
                isTwoFinger = false
                twoFingerEndTime = n
            }
        }

        // 全局双指结束冷却屏蔽
        if (n - twoFingerEndTime < TWO_FINGER_COOLDOWN_MS) return

        // 双击返回地图外
        if (action == TouchAction.DOWN) {
            if (n - lastTapPreview < DOUBLE_TAP_MS &&
                abs(x - lastTapXPreview) < DOUBLE_TAP_RADIUS &&
                abs(y - lastTapYPreview) < DOUBLE_TAP_RADIUS) {
                val cr = screenToMap(x, y)
                if (cr[0] < 0 || cr[0] >= previewW || cr[1] < 0 || cr[1] >= previewH) {
                    saveEditorMap()
                    previewMode = false
                    menuVisible = false
                    menuAnimating = false
                    lastTapPreview = 0L
                    return
                }
            }
            lastTapPreview = n
            lastTapXPreview = x
            lastTapYPreview = y
        }

        // ========== 单元 1 ==========
        if (unit == 1) {
            when (action) {
                TouchAction.DOWN -> {
                    val cr = screenToMap(x, y)
                    val c = cr[0]; val r = cr[1]
                    if (c in 0 until previewW && r in 0 until previewH) {
                        val old = previewMap!![r][c]
                        previewMap!![r][c] = if (previewMap!![r][c] == 0) 1 else 0
                        firstColor = previewMap!![r][c]
                        recordFlip(c, r, old)
                        saveEditorMap()
                    } else firstColor = -1
                    pressX = x; pressY = y
                    pressTime = n
                    isPressing = true
                    isDrawing = false
                    longPressTriggered = false
                }
                TouchAction.MOVE -> {
                    if (isPressing && !longPressTriggered) {
                        val dx = x - pressX; val dy = y - pressY
                        if (abs(dx) < DRAG_THRESHOLD && abs(dy) < DRAG_THRESHOLD) {
                            if (n - pressTime > LONG_PRESS_MS) {
                                longPressTriggered = true
                                isPressing = false
                                menuVisible = true
                                menuAnimating = true
                                menuAnimOffset = 0f
                                menuAnimStart = n
                            }
                        } else {
                            isPressing = false
                            isDrawing = true
                            val cr = screenToMap(x, y)
                            val c = cr[0]; val r = cr[1]
                            if (c in 0 until previewW && r in 0 until previewH) {
                                if (firstColor == -1) firstColor = previewMap!![r][c]
                                if (previewMap!![r][c] != firstColor) {
                                    recordFlip(c, r, previewMap!![r][c])
                                    previewMap!![r][c] = firstColor
                                    saveEditorMap()
                                }
                            }
                        }
                    } else if (isDrawing) {
                        val cr = screenToMap(x, y)
                        val c = cr[0]; val r = cr[1]
                        if (c in 0 until previewW && r in 0 until previewH && firstColor != -1) {
                            if (previewMap!![r][c] != firstColor) {
                                recordFlip(c, r, previewMap!![r][c])
                                previewMap!![r][c] = firstColor
                                saveEditorMap()
                            }
                        }
                    }
                }
                TouchAction.UP, TouchAction.CANCEL -> {
                    isDrawing = false
                    isPressing = false
                    longPressTriggered = false
                    saveEditorMap(true)
                }
            }
        }
        // ========== 单元 3 ==========
        else if (unit == 3) {
            when (action) {
                TouchAction.DOWN -> {
                    val cr = screenToMap(x, y)
                    val c = cr[0]; val r = cr[1]
                    if (c in 0 until previewW && r in 0 until previewH && !(c % 2 != 0 && r % 2 != 0)) {
                        slideActive = true
                        slideLockDir = 0
                        slideStartCol = c; slideStartRow = r
                        slideLastCol = c; slideLastRow = r
                        val old = previewMap!![r][c]
                        previewMap!![r][c] = if (previewMap!![r][c] == 0) 1 else 0
                        firstColor = previewMap!![r][c]
                        recordFlip(c, r, old)
                        saveEditorMap()
                    } else {
                        slideActive = false
                        firstColor = -1
                    }
                    pressX = x; pressY = y
                    pressTime = n
                    isPressing = true
                    longPressTriggered = false
                }
                TouchAction.MOVE -> {
                    if (isPressing && !longPressTriggered) {
                        if (abs(x - pressX) < DRAG_THRESHOLD && abs(y - pressY) < DRAG_THRESHOLD) {
                            if (n - pressTime > LONG_PRESS_MS) {
                                longPressTriggered = true
                                isPressing = false
                                menuVisible = true
                                menuAnimating = true
                                menuAnimOffset = 0f
                                menuAnimStart = n
                            }
                        } else isPressing = false
                    }
                    if (slideActive && !longPressTriggered) {
                        val cr = screenToMap(x, y)
                        val c = cr[0]; val r = cr[1]
                        if (c in 0 until previewW && r in 0 until previewH && !(c % 2 != 0 && r % 2 != 0)) {
                            if (slideLockDir == 0) {
                                val dCol = c - slideStartCol
                                val dRow = r - slideStartRow
                                val dir = determineDirection(dCol, dRow)
                                if (dir != 0) {
                                    slideLockDir = dir
                                    val stepC = if (c > slideLastCol) 1 else -1
                                    val stepR = if (r > slideLastRow) 1 else -1
                                    if (slideLockDir == 1) {
                                        var col = slideLastCol
                                        while (col != c) {
                                            col += stepC
                                            if (col in 0 until previewW && !(col % 2 != 0 && slideLastRow % 2 != 0)) {
                                                if (previewMap!![slideLastRow][col] != firstColor) {
                                                    recordFlip(col, slideLastRow, previewMap!![slideLastRow][col])
                                                    previewMap!![slideLastRow][col] = firstColor
                                                }
                                            }
                                        }
                                    } else if (slideLockDir == 2) {
                                        var row = slideLastRow
                                        while (row != r) {
                                            row += stepR
                                            if (row in 0 until previewH && !(slideLastCol % 2 != 0 && row % 2 != 0)) {
                                                if (previewMap!![row][slideLastCol] != firstColor) {
                                                    recordFlip(slideLastCol, row, previewMap!![row][slideLastCol])
                                                    previewMap!![row][slideLastCol] = firstColor
                                                }
                                            }
                                        }
                                    }
                                    slideLastCol = c
                                    slideLastRow = r
                                    saveEditorMap()
                                }
                            } else {
                                if (slideLockDir == 1) {
                                    if (r == slideLastRow && c != slideLastCol) {
                                        val step = if (c > slideLastCol) 1 else -1
                                        var col = slideLastCol
                                        while (col != c) {
                                            col += step
                                            if (col in 0 until previewW && !(col % 2 != 0 && r % 2 != 0)) {
                                                if (previewMap!![r][col] != firstColor) {
                                                    recordFlip(col, r, previewMap!![r][col])
                                                    previewMap!![r][col] = firstColor
                                                }
                                            }
                                        }
                                        slideLastCol = c
                                        saveEditorMap()
                                    }
                                } else if (slideLockDir == 2) {
                                    if (c == slideLastCol && r != slideLastRow) {
                                        val step = if (r > slideLastRow) 1 else -1
                                        var row = slideLastRow
                                        while (row != r) {
                                            row += step
                                            if (row in 0 until previewH && !(c % 2 != 0 && row % 2 != 0)) {
                                                if (previewMap!![row][c] != firstColor) {
                                                    recordFlip(c, row, previewMap!![row][c])
                                                    previewMap!![row][c] = firstColor
                                                }
                                            }
                                        }
                                        slideLastRow = r
                                        saveEditorMap()
                                    }
                                }
                            }
                        }
                    }
                }
                TouchAction.UP, TouchAction.CANCEL -> {
                    slideActive = false
                    slideLockDir = 0
                    isPressing = false
                    longPressTriggered = false
                    saveEditorMap(true)
                }
            }
        }
        // ========== 单元 5 ==========
        else if (unit == 5) {
            when (action) {
                TouchAction.DOWN -> {
                    val cr = screenToMap(x, y)
                    val c = cr[0]; val r = cr[1]
                    if (c in 0 until previewW && r in 0 until previewH && isCellOperable(c, r)) {
                        val old = previewMap!![r][c]
                        previewMap!![r][c] = if (previewMap!![r][c] == 0) 1 else 0
                        firstColor = previewMap!![r][c]
                        recordFlip(c, r, old)
                        saveEditorMap()
                        drawStartCol = c; drawStartRow = r
                        drawLastCol = c; drawLastRow = r
                        drawLockDir = 0
                    } else {
                        firstColor = -1
                        drawStartCol = -1; drawStartRow = -1
                    }
                    pressX = x; pressY = y
                    pressTime = n
                    isPressing = true
                    longPressTriggered = false
                }
                TouchAction.MOVE -> {
                    if (isPressing && !longPressTriggered) {
                        if (abs(x - pressX) < DRAG_THRESHOLD && abs(y - pressY) < DRAG_THRESHOLD) {
                            if (n - pressTime > LONG_PRESS_MS) {
                                longPressTriggered = true
                                isPressing = false
                                menuVisible = true
                                menuAnimating = true
                                menuAnimOffset = 0f
                                menuAnimStart = n
                            }
                        } else if (firstColor != -1 && drawStartCol != -1) {
                            val cr = screenToMap(x, y)
                            val c = cr[0]; val r = cr[1]
                            if (c in 0 until previewW && r in 0 until previewH && isCellOperable(c, r)) {
                                if (drawLockDir == 0) {
                                    val dCol = c - drawStartCol
                                    val dRow = r - drawStartRow
                                    val dir = determineDirection(dCol, dRow)
                                    if (dir != 0) {
                                        drawLockDir = dir
                                        drawStraightMark(c, r)
                                    }
                                } else {
                                    drawStraightMark(c, r)
                                }
                            }
                        }
                    }
                }
                TouchAction.UP, TouchAction.CANCEL -> {
                    isPressing = false
                    longPressTriggered = false
                    drawLockDir = 0
                    drawStartCol = -1; drawStartRow = -1
                    saveEditorMap(true)
                }
            }
        }
    }

    /** 单元5 沿锁定方向直线染色到目标位置 */
    private fun drawStraightMark(c: Int, r: Int) {
        var step = 1
        when (drawLockDir) {
            1 -> step = if (c > drawLastCol) 1 else -1
            2 -> step = if (r > drawLastRow) 1 else -1
            3 -> step = if (c > drawLastCol) 1 else -1
            4 -> step = if (c > drawLastCol) 1 else -1
        }
        val cc = intArrayOf(drawLastCol)
        val rr = intArrayOf(drawLastRow)
        while (true) {
            stepInDir(drawLockDir, cc, rr, step)
            val curC = cc[0]
            val curR = rr[0]
            if (curC < 0 || curC >= previewW || curR < 0 || curR >= previewH) break
            if (!isCellOperable(curC, curR)) break
            if ((drawLockDir == 1 && (if (step > 0) curC > c else curC < c)) ||
                (drawLockDir == 2 && (if (step > 0) curR > r else curR < r)) ||
                (drawLockDir == 3 && (if (step > 0) curC > c else curC < c)) ||
                (drawLockDir == 4 && (if (step > 0) curC > c else curC < c))) break
            if (previewMap!![curR][curC] != firstColor) {
                recordFlip(curC, curR, previewMap!![curR][curC])
                previewMap!![curR][curC] = firstColor
            }
            drawLastCol = curC
            drawLastRow = curR
        }
        saveEditorMap()
    }

    // ==================== 生命周期 ====================
    override fun init() {
        unit = 1; fill = 0; mapWidth = 10; mapHeight = 10; previewMode = false
        previewMap = null; previewW = 0; previewH = 0
        viewX = 0f; viewY = 0f; viewScale = 1f; gBaseCellSize = 0f; gCellSize = 0f
        rectLeft = 0f; rectTop = 0f; rectRight = sw; rectBottom = sh
        menuVisible = false; menuAnimOffset = 0f; menuAnimating = false
        isDrawing = false; isPressing = false; longPressTriggered = false
        isTwoFinger = false; oldDist = 0f; oldScale = 1f
        slideActive = false; slideLockDir = 0; slideLastCol = -1; slideLastRow = -1
        drawLockDir = 0; drawStartCol = -1; drawStartRow = -1
        drawLastCol = -1; drawLastRow = -1; twoFingerEndTime = 0L
        flipRecordCount = 0; flipRecordHead = 0; flipRecordTail = 0
        for (r in flipRecords) r.undone = false
        importState = IMPORT_NONE; importFiles.clear(); importSelectedIndex = -1
        lastTapTimeEditor = 0L; lastTapPreview = 0L
    }

    override fun update(deltaTime: Float) {
        if (menuAnimating) {
            val n = now()
            val elapsed = (n - menuAnimStart) / 200f
            if (elapsed >= 1f) {
                menuAnimating = false
                menuAnimOffset = if (menuVisible) 1f else 0f
            } else {
                menuAnimOffset = if (menuVisible) elapsed else 1f - elapsed
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (sw == 0f || sh == 0f) return
        if (previewMode) drawPreviewMap(canvas) else drawEditor(canvas)
    }

    override fun destroy() {
        previewMap = null
    }

    /**
     * 处理系统返回键。
     * 返回 true 表示已消费（预览模式 -> 编辑模式，或导入界面 -> 上一步）；
     * 返回 false 表示交由 MainActivity 处理（回主菜单）。
     */
    fun handleBackKey(): Boolean {
        if (!previewMode && importState != IMPORT_NONE) {
            if (importState == IMPORT_UNIT_SEL) {
                importState = IMPORT_FILE_LIST
                importSelectedIndex = -1
            } else {
                importState = IMPORT_NONE
            }
            return true
        }
        if (previewMode) {
            if (menuVisible || menuAnimating) {
                menuVisible = false
                menuAnimating = true
                menuAnimOffset = 1f
                menuAnimStart = now()
            } else {
                saveEditorMap(true)
                previewMode = false
            }
            return true
        }
        return false
    }
}
