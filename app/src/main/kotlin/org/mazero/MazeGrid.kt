package org.mazero

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * 网格迷宫游戏（CGMG 常规迷宫 + XXHD 线性滑道）
 * 对应旧 C 工程 maze_game.c / game_xxhd.c
 *
 * 两者逻辑几乎一致，差异仅两处：
 *   1. 地图字符语义（wall/floor 相反）
 *   2. 移动方式：CGMG 单步移动；XXHD 沿方向连续滑行至障碍
 */
class MazeGridGame(
    override val code: String,
    override val displayName: String,
    private val semantics: MapSemantics,
    private val continuous: Boolean
) : MazeModule {

    companion object {
        private const val CELL_SIZE_BASE = 48.0f
        private const val MAX_UNDO = 100
        private const val DOUBLE_TAP_MS = 300L
        private const val DOUBLE_TAP_RADIUS = 50f
        private const val TWO_FINGER_COOLDOWN_MS = 200L
        private const val MOVE_THRESHOLD = 20f
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 3.0f
    }

    // ---- 地图 ----
    private var mapWidth = 0
    private var mapHeight = 0
    private var cells: Array<IntArray> = Array(0) { IntArray(0) }
    private var startX = -1
    private var startY = -1
    private var goalX = -1
    private var goalY = -1
    @Volatile private var playerX = 0
    @Volatile private var playerY = 0

    private val meta = ArrayList<String>()

    // ---- 视口 ----
    @Volatile private var viewX = 0f
    @Volatile private var viewY = 0f
    @Volatile private var scale = 1.0f
    @Volatile private var cellSize = CELL_SIZE_BASE
    private var rectLeft = 0f
    private var rectTop = 0f
    private var rectRight = 0f
    private var rectBottom = 0f

    // ---- 双指 ----
    private val pointerIds = intArrayOf(-1, -1)
    private var isTwoFinger = false
    private var oldDist = 0f
    private var oldScale = 1f
    private var oldViewX = 0f
    private var oldViewY = 0f
    private var pivotX = 0f
    private var pivotY = 0f

    // ---- 单指 ----
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isMoving = false

    // ---- 双击返回 ----
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapInGameArea = false

    /** 双指手势结束（抬起一指）后短暂屏蔽，避免残指被判成双击返回或单指移动 */
    private var twoFingerEndTime = 0L

    // ---- 进度 ----
    @Volatile private var level = 1
    @Volatile
    private var isFullscreen = false
    @Volatile private var levelStartTime = 0L
    @Volatile private var levelPausedTime = 0L
    @Volatile private var levelTimeSeconds = 0
    @Volatile private var isLevelPaused = false
    @Volatile private var stepCount = 0
    @Volatile private var timerStarted = false

    private var firstComplete = false
    private var firstCompleteTime = 0
    private var bestTimeVal = 0
    private var bestStepsVal = 0

    private val undoX = IntArray(MAX_UNDO)
    private val undoY = IntArray(MAX_UNDO)
    private val undoStep = IntArray(MAX_UNDO)
    private var undoIndex = 0
    private var undoCount = 0

    private var showSettingsPanel = false
    private var doubleTapResetEnabled = false

    private var fullscreenBtnX = 0f; private var fullscreenBtnY = 0f; private var fullscreenBtnSize = 0f
    private var settingsBtnX = 0f; private var settingsBtnY = 0f; private var settingsBtnSize = 0f
    private var undoBtnX = 0f; private var undoBtnY = 0f; private var undoBtnSize = 0f
    private var resetBtnX = 0f; private var resetBtnY = 0f; private var resetBtnSize = 0f

    private var showResetDialog = false
    private var resetConfirmType = 0

    private var btnSize = 0f
    private var margin = 0f
    private var topY = 0f

    private var isTouchingResetBtn = false
    private var resetClickValid = false
    private var lastResetUpTime = 0L

    // ---- 画笔 ----
    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }

    /** 地图格专用画笔：关闭抗锯齿，整像素落点，与旧 C 的整数 drawRect 一致（更快且无缝） */
    private val mapPaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val bitmapPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val srcRect = Rect()
    private val dstRectF = RectF()

    private val sw get() = GameView.instance?.screenW ?: 0
    private val sh get() = GameView.instance?.screenH ?: 0
    private fun now() = System.currentTimeMillis()

    private fun gameState() = GameView.instance?.gameState ?: -1
    private fun setGameState(s: Int) { GameView.instance?.gameState = s }

    // ==================== MazeModule ====================

    override fun metaOf(i: Int): String? = meta.getOrNull(i)
    override val metaCount: Int get() = meta.size
    override fun isFirstComplete() = firstComplete
    override fun bestTime() = bestTimeVal
    override fun bestSteps() = bestStepsVal
    override fun firstCompleteTime() = firstCompleteTime

    override fun enterLevel(level: Int) {
        if (!firstEnterToast) {
            firstEnterToast = true
            MazeToast.show("两指缩放移动地图，单指操控；双击边缘返回")
        }
        this.level = level
        Global.currentLevel = level

        val m = MazeMapLoader.load(code, level, semantics)
        if (m == null) {
            MazeToast.show("地图加载失败")
            setGameState(GameState.LEVEL_SELECT)
            loadOk = false
            return
        }
        loadOk = true
        mapWidth = m.width
        mapHeight = m.height
        cells = m.cells
        startX = m.startX; startY = m.startY
        goalX = m.goalX; goalY = m.goalY
        meta.clear(); meta.addAll(m.meta)
        playerX = startX; playerY = startY

        loadRecord(level)

        val mm = sw * 0.05f
        rectLeft = mm; rectTop = mm
        rectRight = sw - mm; rectBottom = sh - mm

        scale = 1.0f
        cellSize = CELL_SIZE_BASE * scale
        initViewport()

        resetTwoFinger()
        isMoving = false
        lastTapTime = 0

        loadGameState()

        cellSize = CELL_SIZE_BASE * scale
        clampViewport()
        if (viewX == 0f && viewY == 0f && scale == 1.0f) initViewport()

        isFullscreen = false
        undoCount = 0; undoIndex = 0
        showSettingsPanel = false
        showResetDialog = false
        isTouchingResetBtn = false
        resetClickValid = false
    }

    private var firstEnterToast = false

    /** 地图是否加载成功（供开始界面判断） */
    override var loadOk = false
        private set

    // ==================== 存档 ====================

    private fun loadRecord(level: Int) {
        val r = MazeProgress.loadRecord(code, level)
        firstComplete = r[0] != 0
        firstCompleteTime = r[1]
        bestTimeVal = r[2]
        bestStepsVal = r[3]
    }

    private fun saveRecord() {
        MazeProgress.saveRecord(
            code, level,
            if (firstComplete) 1 else 0,
            firstCompleteTime, bestTimeVal, bestStepsVal
        )
    }

    private fun saveGameState() {
        MazeProgress.saveGameState(
            code, level, playerX, playerY, stepCount,
            levelStartTime, levelPausedTime, levelTimeSeconds,
            if (timerStarted) 1 else 0, if (isLevelPaused) 1 else 0,
            viewX, viewY, scale, 0f, 1
        )
    }

    private fun loadGameState() {
        val s = MazeProgress.loadGameState(code, level)
        if (s == null) {
            playerX = startX; playerY = startY
            stepCount = 0
            levelStartTime = now()
            levelPausedTime = 0
            levelTimeSeconds = 0
            timerStarted = false
            isLevelPaused = false
            viewX = 0f; viewY = 0f; scale = 1.0f
        } else {
            playerX = s[0].toInt(); playerY = s[1].toInt()
            stepCount = s[2].toInt()
            levelStartTime = s[3]
            levelPausedTime = s[4]
            levelTimeSeconds = s[5].toInt()
            timerStarted = s[6] != 0L
            isLevelPaused = s[7] != 0L
            viewX = Float.fromBits(s[8].toInt())
            viewY = Float.fromBits(s[9].toInt())
            scale = Float.fromBits(s[10].toInt())
        }
    }

    private fun deleteGameState() = MazeProgress.deleteGameState(code, level)

    override fun saveState() = saveGameState()

    override fun pauseTimer() {
        if (timerStarted && !isLevelPaused) {
            levelPausedTime += now() - levelStartTime
            isLevelPaused = true
        }
    }

    override fun startTimer() {
        if (!timerStarted) {
            timerStarted = true
            levelStartTime = now()
            levelPausedTime = 0
            isLevelPaused = false
        } else if (isLevelPaused) {
            levelStartTime = now()
            isLevelPaused = false
        }
    }

    private fun saveAndReturn() {
        pauseTimer()
        saveGameState()
        MazeToast.show("进度已保存")
        showSettingsPanel = false
        setGameState(GameState.LEVEL_SELECT)
    }

    // ==================== 视口 ====================

    private fun clampViewport() {
        val mapTotalW = mapWidth * cellSize
        val mapTotalH = mapHeight * cellSize
        val centerX = (rectLeft + rectRight) / 2f
        val centerY = (rectTop + rectBottom) / 2f

        val minX = centerX - mapTotalW
        val maxX = centerX
        if (viewX < minX) viewX = minX
        if (viewX > maxX) viewX = maxX

        val minY = centerY - mapTotalH
        val maxY = centerY
        if (viewY < minY) viewY = minY
        if (viewY > maxY) viewY = maxY
    }

    private fun initViewport() {
        val mapTotalW = mapWidth * cellSize
        val mapTotalH = mapHeight * cellSize
        val viewW = rectRight - rectLeft
        val viewH = rectBottom - rectTop
        viewX = rectLeft + (viewW - mapTotalW) / 2f
        viewY = rectTop + (viewH - mapTotalH) / 2f
        clampViewport()
    }

    // ==================== 撤销 / 重置 ====================

    private fun saveUndoState() {
        if (undoIndex < undoCount) { undoCount = undoIndex }
        if (undoCount < MAX_UNDO) {
            undoX[undoCount] = playerX
            undoY[undoCount] = playerY
            undoStep[undoCount] = stepCount
            undoCount++
            undoIndex = undoCount
        } else {
            for (i in 1 until MAX_UNDO) {
                undoX[i - 1] = undoX[i]
                undoY[i - 1] = undoY[i]
                undoStep[i - 1] = undoStep[i]
            }
            undoX[MAX_UNDO - 1] = playerX
            undoY[MAX_UNDO - 1] = playerY
            undoStep[MAX_UNDO - 1] = stepCount
            undoCount = MAX_UNDO
            undoIndex = MAX_UNDO
        }
    }

    private fun resetLevelInternal(resetTime: Int) {
        playerX = startX; playerY = startY
        stepCount = 0
        if (resetTime != 0) {
            levelStartTime = now()
            levelPausedTime = 0
            levelTimeSeconds = 0
            // ★ 修复：归零但不启动，等玩家在关卡介绍点「登录」时再 startTimer()
            timerStarted = false
            isLevelPaused = false
            deleteGameState()
        } else {
            saveGameState()
        }
        undoCount = 0; undoIndex = 0
        showResetDialog = false
    }

    override fun resetLevelFull() = resetLevelInternal(1)

    private fun resetLevel() = resetLevelInternal(0)

    private fun undoStep() {
        if (undoCount > 0 && undoIndex > 0) {
            undoIndex--
            playerX = undoX[undoIndex]
            playerY = undoY[undoIndex]
            stepCount = undoStep[undoIndex]
        } else {
            MazeToast.show("没有可回撤的步骤")
        }
    }

    // ==================== 移动 ====================

    private fun movePlayerStep(dx: Int, dy: Int) {
        val nx = playerX + dx
        val ny = playerY + dy
        if (nx < 0 || nx >= mapWidth || ny < 0 || ny >= mapHeight) return
        val v = cells[ny][nx]
        if (v == 0 || (nx == goalX && ny == goalY)) {
            saveUndoState()
            playerX = nx; playerY = ny
            stepCount++
            checkGoal()
        }
    }

    private fun movePlayerContinuous(dx: Int, dy: Int) {
        var nx = playerX
        var ny = playerY
        while (true) {
            val tx = nx + dx
            val ty = ny + dy
            if (tx < 0 || tx >= mapWidth || ty < 0 || ty >= mapHeight) break
            if (cells[ty][tx] == 1) break
            nx = tx; ny = ty
        }
        if (nx != playerX || ny != playerY) {
            saveUndoState()
            playerX = nx; playerY = ny
            stepCount++
            checkGoal()
        }
    }

    private fun movePlayer(dx: Int, dy: Int) {
        if (continuous) movePlayerContinuous(dx, dy) else movePlayerStep(dx, dy)
    }

    private fun checkGoal() {
        if (playerX == goalX && playerY == goalY) {
            // ★ 修复：用绝对时间差重算，避免读到上一帧旧值（最多差 1 秒）
            if (timerStarted && !isLevelPaused) {
                levelTimeSeconds = ((now() - levelStartTime) / 1000 + levelPausedTime / 1000).toInt()
            }
            val time = levelTimeSeconds
            if (!firstComplete) {
                firstComplete = true
                firstCompleteTime = time
                bestTimeVal = time
                bestStepsVal = stepCount
                saveRecord()
                deleteGameState()
            } else {
                if (time < bestTimeVal || bestTimeVal == 0) bestTimeVal = time
                if (stepCount < bestStepsVal || bestStepsVal == 0) bestStepsVal = stepCount
                saveRecord()
            }
            Global.setCompleteData(time, stepCount)
            setGameState(GameState.LEVEL_COMPLETE)
        }
    }

    // ==================== 销毁 ====================

    override fun destroyModule() {
        saveRecord()
        if (timerStarted) saveGameState()
        resetTwoFinger()
    }

    // ==================== 地图绘制 ====================

    private fun drawMap(canvas: Canvas, fullscreen: Boolean) {
        val l = if (fullscreen) 0f else rectLeft
        val t = if (fullscreen) 0f else rectTop
        val r = if (fullscreen) sw.toFloat() else rectRight
        val b = if (fullscreen) sh.toFloat() else rectBottom

        for (y in 0 until mapHeight) {
            val topF = viewY + y * cellSize
            val botF = viewY + (y + 1) * cellSize
            if (ceil(botF) < t || floor(topF) > b) continue
            for (x in 0 until mapWidth) {
                val leftF = viewX + x * cellSize
                val rightF = viewX + (x + 1) * cellSize
                if (ceil(rightF) < l || floor(leftF) > r) continue
                if (ceil(botF) < t || floor(topF) > b) continue

                val isWall = cells[y][x] == 1
                if (semantics == MapSemantics.CGMG) {
                    mapPaint.color = if (isWall) Color.BLACK else Color.WHITE
                } else {
                    mapPaint.color = if (isWall) Color.rgb(255, 255, 255) else Color.BLACK
                }
                // 旧 C 用 floor/ceil 落到整像素；这里再把右边/下边多画 1px，
                // 让相邻格互相叠压，彻底填掉亚像素坐标留下的缝隙
                canvas.drawRect(
                    floor(leftF), floor(topF),
                    ceil(rightF) + 1f, ceil(botF) + 1f,
                    mapPaint
                )
            }
        }

        val gcx = viewX + goalX * cellSize + cellSize / 2f
        val gcy = viewY + goalY * cellSize + cellSize / 2f
        fillPaint.color = Color.rgb(0, 200, 0)
        canvas.drawCircle(gcx, gcy, cellSize * 0.3f, fillPaint)

        val pcx = viewX + playerX * cellSize + cellSize / 2f
        val pcy = viewY + playerY * cellSize + cellSize / 2f
        fillPaint.color = Color.rgb(0, 120, 255)
        canvas.drawCircle(pcx, pcy, cellSize * 0.35f, fillPaint)
    }

    override fun drawFullscreen(canvas: Canvas) {
        if (!loadOk) return
        val oldL = rectLeft; val oldT = rectTop; val oldR = rectRight; val oldB = rectBottom
        rectLeft = 0f; rectTop = 0f
        rectRight = sw.toFloat(); rectBottom = sh.toFloat()
        clampViewport()
        canvas.drawColor(Color.WHITE)
        drawMap(canvas, true)
        rectLeft = oldL; rectTop = oldT; rectRight = oldR; rectBottom = oldB
    }

    override fun update(dt: Float) {
        // ★ 修复：全屏时 drawUi 不执行，计时必须每帧推进
        if (timerStarted && !isLevelPaused) {
            levelTimeSeconds = ((now() - levelStartTime) / 1000 + levelPausedTime / 1000).toInt()
        }
    }

    override fun drawGame(canvas: Canvas) {
        if (!loadOk) return
        clampViewport()

        if (isFullscreen) {
            val oldL = rectLeft; val oldT = rectTop; val oldR = rectRight; val oldB = rectBottom
            rectLeft = 0f; rectTop = 0f
            rectRight = sw.toFloat(); rectBottom = sh.toFloat()
            canvas.drawColor(Color.WHITE)
            drawMap(canvas, true)
            rectLeft = oldL; rectTop = oldT; rectRight = oldR; rectBottom = oldB
            return
        }

        canvas.drawColor(Color.rgb(173, 216, 230))
        fillPaint.color = Color.argb(100, 0, 0, 0)
        canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, fillPaint)

        canvas.save()
        canvas.clipRect(rectLeft, rectTop, rectRight, rectBottom)
        drawMap(canvas, false)
        canvas.restore()

        drawUi(canvas)
    }

    // ==================== UI ====================

    private fun drawBitmapFit(canvas: Canvas, bmp: Bitmap, x: Float, y: Float, size: Float) {
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRectF.set(x, y, x + size, y + size)
        canvas.drawBitmap(bmp, srcRect, dstRectF, bitmapPaint)
    }

    private fun drawUi(canvas: Canvas) {
        if (isFullscreen) return

        btnSize = sw * 0.06f
        margin = sw * 0.01f
        topY = margin

        settingsBtnSize = btnSize
        settingsBtnX = margin
        settingsBtnY = topY * 0.08f
        MazeIcons.settings()?.let {
            val drawSize = btnSize * 0.5f
            val offset = (btnSize - drawSize) / 2f
            drawBitmapFit(canvas, it, settingsBtnX + offset, settingsBtnY + offset, drawSize)
        }

        undoBtnSize = btnSize * 1.3f
        undoBtnX = settingsBtnX + btnSize + margin
        undoBtnY = topY - btnSize * 0.30f
        MazeIcons.undo()?.let { drawBitmapFit(canvas, it, undoBtnX, undoBtnY, undoBtnSize) }

        resetBtnSize = btnSize * 1.2f
        resetBtnX = undoBtnX + undoBtnSize + margin
        resetBtnY = topY - btnSize * 0.25f
        MazeIcons.reset()?.let { drawBitmapFit(canvas, it, resetBtnX, resetBtnY, resetBtnSize) }

        fullscreenBtnSize = btnSize
        fullscreenBtnX = sw - margin - btnSize
        fullscreenBtnY = topY
        MazeIcons.fullscreen()?.let { drawBitmapFit(canvas, it, fullscreenBtnX, fullscreenBtnY, btnSize) }

        if (timerStarted && !isLevelPaused) {
            val elapsed = (now() - levelStartTime) / 1000 + levelPausedTime / 1000
            levelTimeSeconds = elapsed.toInt()
        }
        if (timerStarted) {
            val minutes = levelTimeSeconds / 60
            val seconds = levelTimeSeconds % 60
            val timeStr = String.format("%02d:%02d", minutes, seconds)
            textPaint.textSize = btnSize * 0.6f
            textPaint.color = Color.WHITE
            val timeW = textPaint.measureText(timeStr)
            canvas.drawText(timeStr, fullscreenBtnX - margin - timeW, topY + btnSize * 0.7f, textPaint)
        }

        if (showResetDialog) {
            fillPaint.color = Color.argb(180, 0, 0, 0)
            canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fillPaint)

            val dialogW = sw * 0.5f
            val dialogH = sh * 0.2f
            val dialogX = (sw - dialogW) / 2f
            val dialogY = (sh - dialogH) / 2f

            fillPaint.color = Color.rgb(60, 60, 70)
            canvas.drawRoundRect(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, dialogH * 0.1f, dialogH * 0.1f, fillPaint)

            textPaint.textSize = dialogH * 0.2f
            textPaint.color = Color.WHITE
            val msg = if (resetConfirmType == 1) "确定要重置位置和时间吗？" else "确定要重置位置吗？"
            val msgW = textPaint.measureText(msg)
            canvas.drawText(msg, dialogX + (dialogW - msgW) / 2f, dialogY + dialogH * 0.35f, textPaint)

            val btnW2 = dialogW * 0.18f
            val btnH2 = dialogH * 0.3f
            val btnY2 = dialogY + dialogH * 0.55f
            val btnSpacing2 = dialogW * 0.1f
            val totalBtnW = btnW2 * 2 + btnSpacing2
            val btnStartX2 = dialogX + (dialogW - totalBtnW) / 2f

            fillPaint.color = Color.argb(200, 0, 180, 0)
            canvas.drawRoundRect(btnStartX2, btnY2, btnStartX2 + btnW2, btnY2 + btnH2, btnH2 * 0.2f, btnH2 * 0.2f, fillPaint)
            fillPaint.color = Color.argb(200, 80, 80, 80)
            canvas.drawRoundRect(btnStartX2 + btnW2 + btnSpacing2, btnY2, btnStartX2 + btnW2 + btnSpacing2 + btnW2, btnY2 + btnH2, btnH2 * 0.2f, btnH2 * 0.2f, fillPaint)

            textPaint.textSize = btnH2 * 0.5f
            textPaint.color = Color.WHITE
            canvas.drawText("确认", btnStartX2 + btnW2 * 0.25f, btnY2 + btnH2 * 0.7f, textPaint)
            canvas.drawText("取消", btnStartX2 + btnW2 + btnSpacing2 + btnW2 * 0.25f, btnY2 + btnH2 * 0.7f, textPaint)
        }

        if (showSettingsPanel) {
            val panelW = sw * 0.35f
            val panelH = sh * 0.3f
            val panelX = margin
            val panelY = topY + btnSize + margin

            fillPaint.color = Color.argb(220, 50, 50, 60)
            canvas.drawRoundRect(panelX, panelY, panelX + panelW, panelY + panelH, panelH * 0.05f, panelH * 0.05f, fillPaint)

            textPaint.textSize = panelH * 0.12f
            textPaint.color = Color.WHITE
            canvas.drawText("双击重置键", panelX + panelW * 0.05f, panelY + panelH * 0.25f, textPaint)

            val switchX = panelX + panelW * 0.7f
            val switchY = panelY + panelH * 0.12f
            val switchW = panelW * 0.2f
            val switchH = panelH * 0.2f
            fillPaint.color = if (doubleTapResetEnabled) Color.rgb(100, 200, 100) else Color.rgb(150, 150, 150)
            canvas.drawRoundRect(switchX, switchY, switchX + switchW, switchY + switchH, switchH * 0.5f, switchH * 0.5f, fillPaint)

            val thumbX = if (doubleTapResetEnabled) switchX + switchW - switchH else switchX
            fillPaint.color = Color.WHITE
            canvas.drawCircle(thumbX + switchH / 2f, switchY + switchH / 2f, switchH * 0.4f, fillPaint)

            val saveBtnW = panelW * 0.4f
            val saveBtnH = panelH * 0.2f
            val saveBtnX = panelX + (panelW - saveBtnW) / 2f
            val saveBtnY = panelY + panelH * 0.55f
            fillPaint.color = Color.argb(200, 60, 120, 200)
            canvas.drawRoundRect(saveBtnX, saveBtnY, saveBtnX + saveBtnW, saveBtnY + saveBtnH, saveBtnH * 0.2f, saveBtnH * 0.2f, fillPaint)

            textPaint.textSize = saveBtnH * 0.5f
            textPaint.color = Color.WHITE
            val saveTextW = textPaint.measureText("保存并返回")
            canvas.drawText("保存并返回", saveBtnX + (saveBtnW - saveTextW) / 2f, saveBtnY + saveBtnH * 0.7f, textPaint)
        }
    }

    // ==================== 多点触控辅助 ====================

    private fun addPointer(id: Int) {
        if (pointerIds[0] == -1) {
            pointerIds[0] = id
        } else if (pointerIds[1] == -1 && pointerIds[0] != id) {
            pointerIds[1] = id
            isTwoFinger = true
        }
    }

    private fun removePointer(id: Int) {
        val wasTwoFinger = isTwoFinger
        if (pointerIds[0] == id) {
            pointerIds[0] = pointerIds[1]
            pointerIds[1] = -1
        } else if (pointerIds[1] == id) {
            pointerIds[1] = -1
        }
        if (pointerIds[1] == -1) {
            isTwoFinger = false
            if (wasTwoFinger) {
                twoFingerEndTime = now()
            }
        }
    }

    private fun resetTwoFinger() {
        pointerIds[0] = -1; pointerIds[1] = -1
        isTwoFinger = false
        oldDist = 0f
    }

    private fun twoFingerCoords(pointers: Map<Int, Pair<Float, Float>>): FloatArray? {
        val p1 = pointers[pointerIds[0]] ?: return null
        val p2 = pointers[pointerIds[1]] ?: return null
        return floatArrayOf(p1.first, p1.second, p2.first, p2.second)
    }

    private fun updateTwoFingerStart(pointers: Map<Int, Pair<Float, Float>>) {
        val c = twoFingerCoords(pointers) ?: return
        oldDist = hypot(c[0] - c[2], c[1] - c[3])
        oldScale = scale
        oldViewX = viewX
        oldViewY = viewY
        pivotX = (c[0] + c[2]) / 2f
        pivotY = (c[1] + c[3]) / 2f
    }

    private fun updateTwoFingerMove(pointers: Map<Int, Pair<Float, Float>>) {
        val c = twoFingerCoords(pointers) ?: return
        val curCenterX = (c[0] + c[2]) / 2f
        val curCenterY = (c[1] + c[3]) / 2f

        val newDist = hypot(c[0] - c[2], c[1] - c[3])
        if (oldDist <= 0f) return
        val ratio = newDist / oldDist
        var newScale = oldScale * ratio
        if (newScale < MIN_SCALE) newScale = MIN_SCALE
        if (newScale > MAX_SCALE) newScale = MAX_SCALE

        // 锚点 = 双指按下瞬间的中点对应的背景世界坐标（格子坐标）
        // 中心点始终相对背景静止，且通过平移始终对齐到当前两指中点
        val anchorX = (pivotX - oldViewX) / (CELL_SIZE_BASE * oldScale)
        val anchorY = (pivotY - oldViewY) / (CELL_SIZE_BASE * oldScale)

        // 缩放与平移统一：让锚点落在当前两指中点，围绕锚点缩放
        scale = newScale
        cellSize = CELL_SIZE_BASE * scale
        viewX = curCenterX - anchorX * cellSize
        viewY = curCenterY - anchorY * cellSize
        clampViewport()
    }

    // ==================== 触摸 ====================

    override fun touch(action: Int, x: Float, y: Float, pointers: Map<Int, Pair<Float, Float>>) {
        if (!loadOk) return
        val masked = action and TouchAction.MASK
        val index = (action and TouchAction.POINTER_INDEX_MASK) shr TouchAction.POINTER_INDEX_SHIFT
        val id = pointers.keys.elementAtOrNull(index) ?: -1

        /** 双指结束冷却：抬起一指后 200ms 内的按下不参与双击返回与单指滑动 */
        if ((masked == TouchAction.DOWN || masked == TouchAction.POINTER_DOWN) &&
            now() - twoFingerEndTime < TWO_FINGER_COOLDOWN_MS
        ) {
            return
        }

        if (isFullscreen) {
            handleFullscreenTouch(masked, x, y, id, pointers)
            return
        }

        when (masked) {
            TouchAction.DOWN, TouchAction.POINTER_DOWN -> {
                if (id != -1) addPointer(id)
                if (isTwoFinger) {
                    isMoving = false
                    isTouchingResetBtn = false
                    resetClickValid = false
                    updateTwoFingerStart(pointers)
                } else {
                    if (handleUiDown(x, y)) return
                    touchStartX = x; touchStartY = y
                    isMoving = true
                    val n = now()
                    if (n - lastTapTime < DOUBLE_TAP_MS &&
                        abs(x - lastTapX) < DOUBLE_TAP_RADIUS && abs(y - lastTapY) < DOUBLE_TAP_RADIUS
                    ) {
                        if (!lastTapInGameArea) {
                            pauseTimer(); saveGameState()
                            setGameState(GameState.LEVEL_SELECT)
                            lastTapTime = 0
                            return
                        }
                    }
                    lastTapTime = n
                    lastTapX = x; lastTapY = y
                    lastTapInGameArea = (x >= rectLeft && x <= rectRight && y >= rectTop && y <= rectBottom)
                }
            }

            TouchAction.MOVE -> {
                if (isTouchingResetBtn && firstComplete && resetClickValid) {
                    if (now() - lastTapTime > 800) {
                        isTouchingResetBtn = false
                        resetClickValid = false
                        resetConfirmType = 1
                        showResetDialog = true
                        return
                    }
                }
                if (isTwoFinger) updateTwoFingerMove(pointers)
            }

            TouchAction.UP, TouchAction.POINTER_UP -> {
                if (id != -1) removePointer(id)
                handleResetUp(x, y)
                if (!isTwoFinger && isMoving && now() - twoFingerEndTime >= TWO_FINGER_COOLDOWN_MS) {
                    val dx = x - touchStartX
                    val dy = y - touchStartY
                    if (abs(dx) > MOVE_THRESHOLD || abs(dy) > MOVE_THRESHOLD) {
                        if (abs(dx) > abs(dy)) movePlayer(if (dx > 0) 1 else -1, 0)
                        else movePlayer(0, if (dy > 0) 1 else -1)
                    }
                    isMoving = false
                }
            }
        }
    }

    /** 处理非全屏 UI 点击；返回 true 表示已消费 */
    private fun handleUiDown(x: Float, y: Float): Boolean {
        if (showResetDialog) {
            val dialogW = sw * 0.5f
            val dialogH = sh * 0.2f
            val dialogX = (sw - dialogW) / 2f
            val dialogY = (sh - dialogH) / 2f
            val btnW2 = dialogW * 0.18f
            val btnH2 = dialogH * 0.3f
            val btnY2 = dialogY + dialogH * 0.55f
            val btnSpacing2 = dialogW * 0.1f
            val totalBtnW = btnW2 * 2 + btnSpacing2
            val btnStartX2 = dialogX + (dialogW - totalBtnW) / 2f

            if (x >= btnStartX2 && x <= btnStartX2 + btnW2 && y >= btnY2 && y <= btnY2 + btnH2) {
                if (resetConfirmType == 1) {
                    resetLevelFull()
                    // ★ 修复：resetLevelFull 不再自动启动计时，游戏内重置确认后需手动启动
                    startTimer()
                } else {
                    resetLevel()
                }
                showResetDialog = false
                return true
            }
            if (x >= btnStartX2 + btnW2 + btnSpacing2 && x <= btnStartX2 + btnW2 + btnSpacing2 + btnW2 &&
                y >= btnY2 && y <= btnY2 + btnH2
            ) {
                showResetDialog = false
                return true
            }
            if (x < dialogX || x > dialogX + dialogW || y < dialogY || y > dialogY + dialogH) {
                showResetDialog = false
            }
            return true
        }

        if (showSettingsPanel) {
            val panelW = sw * 0.35f
            val panelH = sh * 0.3f
            val panelX = margin
            val panelY = topY + btnSize + margin

            val switchX = panelX + panelW * 0.7f
            val switchY = panelY + panelH * 0.12f
            val switchW = panelW * 0.2f
            val switchH = panelH * 0.2f
            if (x >= switchX && x <= switchX + switchW && y >= switchY && y <= switchY + switchH) {
                doubleTapResetEnabled = !doubleTapResetEnabled
                MazeToast.show(if (doubleTapResetEnabled) "双击重置键已开启" else "双击重置键已关闭")
                return true
            }
            val saveBtnW = panelW * 0.4f
            val saveBtnH = panelH * 0.2f
            val saveBtnX = panelX + (panelW - saveBtnW) / 2f
            val saveBtnY = panelY + panelH * 0.55f
            if (x >= saveBtnX && x <= saveBtnX + saveBtnW && y >= saveBtnY && y <= saveBtnY + saveBtnH) {
                saveAndReturn()
                return true
            }
            if (x < panelX || x > panelX + panelW || y < panelY || y > panelY + panelH) {
                showSettingsPanel = false
            }
            return true
        }

        if (x >= fullscreenBtnX && x <= fullscreenBtnX + fullscreenBtnSize &&
            y >= fullscreenBtnY && y <= fullscreenBtnY + fullscreenBtnSize
        ) {
            isFullscreen = true
            return true
        }
        if (x >= settingsBtnX && x <= settingsBtnX + settingsBtnSize &&
            y >= settingsBtnY && y <= settingsBtnY + settingsBtnSize
        ) {
            showSettingsPanel = !showSettingsPanel
            return true
        }

        val undoHalfSize = undoBtnSize / 2f
        val undoCenterX = undoBtnX + undoBtnSize / 2f
        val undoCenterY = undoBtnY + undoBtnSize / 2f
        if (x >= undoCenterX - undoHalfSize && x <= undoCenterX + undoHalfSize &&
            y >= undoCenterY - undoHalfSize && y <= undoCenterY + undoHalfSize
        ) {
            undoStep()
            return true
        }

        val halfSize = btnSize / 2f
        val resetCenterX = resetBtnX + resetBtnSize / 2f
        val resetCenterY = resetBtnY + resetBtnSize / 2f
        if (x >= resetCenterX - halfSize && x <= resetCenterX + halfSize &&
            y >= resetCenterY - halfSize && y <= resetCenterY + halfSize
        ) {
            isTouchingResetBtn = true
            resetClickValid = true
            if (firstComplete) lastTapTime = now()
            return true
        }
        return false
    }

    private fun handleResetUp(x: Float, y: Float) {
        if (!isTouchingResetBtn || !resetClickValid) return
        val halfSize = btnSize / 2f
        val resetCenterX = resetBtnX + resetBtnSize / 2f
        val resetCenterY = resetBtnY + resetBtnSize / 2f
        val stillInBtn = (x >= resetCenterX - halfSize && x <= resetCenterX + halfSize &&
            y >= resetCenterY - halfSize && y <= resetCenterY + halfSize)
        if (stillInBtn && !showResetDialog) {
            if (doubleTapResetEnabled) {
                val n = now()
                if (n - lastResetUpTime < 500) {
                    resetLevel()
                    MazeToast.show("已重置")
                    lastResetUpTime = 0
                } else {
                    lastResetUpTime = n
                }
            } else {
                resetConfirmType = 0
                showResetDialog = true
            }
        }
        isTouchingResetBtn = false
        resetClickValid = false
    }

    private fun handleFullscreenTouch(masked: Int, x: Float, y: Float, id: Int, pointers: Map<Int, Pair<Float, Float>>) {
        if (masked == TouchAction.DOWN || masked == TouchAction.POINTER_DOWN) {
            if (id != -1) addPointer(id)
            if (isTwoFinger) {
                // 进入双指：立即禁用移动（C），允许缩放/拖动背景
                isMoving = false
                updateTwoFingerStart(pointers)
            } else if (masked == TouchAction.DOWN) {
                val n = now()
                if (n - lastTapTime < DOUBLE_TAP_MS &&
                    abs(x - lastTapX) < DOUBLE_TAP_RADIUS && abs(y - lastTapY) < DOUBLE_TAP_RADIUS
                ) {
                    isFullscreen = false
                    lastTapTime = 0
                    return
                }
                lastTapTime = n
                lastTapX = x; lastTapY = y
                touchStartX = x; touchStartY = y
                isMoving = true
            }
        }
        if (masked == TouchAction.MOVE && isTwoFinger) {
            updateTwoFingerMove(pointers)
        }
        if (masked == TouchAction.UP && !isTwoFinger && isMoving && now() - twoFingerEndTime >= TWO_FINGER_COOLDOWN_MS) {
            val dx = x - touchStartX
            val dy = y - touchStartY
            if (abs(dx) > MOVE_THRESHOLD || abs(dy) > MOVE_THRESHOLD) {
                if (abs(dx) > abs(dy)) movePlayer(if (dx > 0) 1 else -1, 0)
                else movePlayer(0, if (dy > 0) 1 else -1)
            }
            isMoving = false
        }
        if (masked == TouchAction.UP || masked == TouchAction.POINTER_UP) {
            if (id != -1) removePointer(id)
        }
    }
}
