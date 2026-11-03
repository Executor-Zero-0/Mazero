package org.mazero

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 重力行者（ZLXZ）
 * 对应旧 C 工程 game_zlxz.c
 *
 * 特色：
 *   1. 玩家有 8 个朝向状态（playerState 1~8，对应 0°/45°/…/315°）
 *   2. 移动由 8×6 规则表驱动，规则含前置条件格检查
 *   3. 视口可旋转 + 缩放（双指），并有跟随模式
 *   4. 地图为斜向线条构成的图形（map==0 处画线）
 */
class MazeZlxzGame : MazeModule {

    companion object {
        private const val TAG = "MazeZlxzGame"
        private const val CELL_SIZE_BASE = 48.0f
        private const val MAX_UNDO = 100
        private const val DOUBLE_TAP_MS = 300L
        private const val DOUBLE_TAP_RADIUS = 50f
        private const val TWO_FINGER_COOLDOWN_MS = 200L
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 3.0f
        private const val FOLLOW_SPEED = 0.08f
        private const val ROTATION_FOLLOW_SPEED = 0.12f
        private const val FOLLOW_BTN_SIZE_RATIO = 0.08f
        private const val FOLLOW_BTN_MARGIN = 20.0f

        private const val MOVE_RIGHT = 0
        private const val MOVE_UP_RIGHT = 1
        private const val MOVE_DOWN_RIGHT = 2
        private const val MOVE_LEFT = 3
        private const val MOVE_UP_LEFT = 4
        private const val MOVE_DOWN_LEFT = 5

        private val STATE_TARGET_ROTATIONS = floatArrayOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
        private val STATE_OFFSET_X = floatArrayOf(0.00f, 0.15f, -0.20f, 0.15f, 0.00f, -0.15f, 0.20f, -0.15f)
        private val STATE_OFFSET_Y = floatArrayOf(-0.20f, 0.15f, 0.00f, -0.15f, 0.20f, -0.15f, 0.00f, 0.15f)

        private class Cond(val dx: Int, val dy: Int, val expected: Int)
        private class Rule(val dx: Int, val dy: Int, val newState: Int, val conds: Array<Cond>)

        /** 规则表 RULES[state-1][dir] */
        private val RULES: Array<Array<Rule>> = arrayOf(
            arrayOf(
                Rule(4, 0, 1, arrayOf(Cond(4, 1, 0), Cond(2, 0, 1), Cond(3, 0, 1))),
                Rule(3, -2, 2, arrayOf(Cond(2, 0, 1), Cond(3, 0, 0))),
                Rule(5, 2, 8, arrayOf(Cond(3, 2, 0))),
                Rule(-4, 0, 1, arrayOf(Cond(-4, 1, 0), Cond(-2, 0, 1), Cond(-3, 0, 1))),
                Rule(-3, -2, 8, arrayOf(Cond(-2, 0, 1), Cond(-3, 0, 0))),
                Rule(-5, 2, 2, arrayOf(Cond(-3, 2, 0)))
            ),
            arrayOf(
                Rule(4, -4, 2, arrayOf(Cond(5, -3, 0), Cond(2, -2, 1), Cond(3, -3, 1))),
                Rule(2, -3, 3, arrayOf(Cond(2, -2, 1), Cond(3, -3, 0))),
                Rule(5, -2, 1, arrayOf(Cond(4, -1, 0))),
                Rule(-4, 4, 2, arrayOf(Cond(-3, 5, 0), Cond(-2, 2, 1), Cond(-3, 3, 1))),
                Rule(-3, 2, 1, arrayOf(Cond(-2, 2, 1), Cond(-3, 3, 0))),
                Rule(-2, 5, 3, arrayOf(Cond(-1, 4, 0)))
            ),
            arrayOf(
                Rule(0, -4, 3, arrayOf(Cond(1, -4, 0), Cond(0, -2, 1), Cond(0, -3, 1))),
                Rule(-2, -3, 4, arrayOf(Cond(0, -2, 1), Cond(0, -3, 0))),
                Rule(2, -5, 2, arrayOf(Cond(2, -3, 0))),
                Rule(0, 4, 3, arrayOf(Cond(1, 4, 0), Cond(0, 2, 1), Cond(0, 3, 1))),
                Rule(-2, 3, 2, arrayOf(Cond(0, 2, 1), Cond(0, 3, 0))),
                Rule(2, 5, 4, arrayOf(Cond(2, 3, 0)))
            ),
            arrayOf(
                Rule(-4, -4, 4, arrayOf(Cond(-3, -5, 0), Cond(-2, -2, 1), Cond(-3, -3, 1))),
                Rule(-3, -2, 5, arrayOf(Cond(-2, -2, 1), Cond(-3, -3, 0))),
                Rule(-2, -5, 3, arrayOf(Cond(-1, -4, 0))),
                Rule(4, 4, 4, arrayOf(Cond(5, 3, 0), Cond(2, 2, 1), Cond(3, 3, 1))),
                Rule(2, 3, 3, arrayOf(Cond(2, 2, 1), Cond(3, 3, 0))),
                Rule(5, 2, 5, arrayOf(Cond(4, 1, 0)))
            ),
            arrayOf(
                Rule(-4, 0, 5, arrayOf(Cond(-4, -1, 0), Cond(-2, 0, 1), Cond(-3, 0, 1))),
                Rule(-3, 2, 6, arrayOf(Cond(-2, 0, 1), Cond(-3, 0, 0))),
                Rule(-5, -2, 4, arrayOf(Cond(-3, -2, 0))),
                Rule(4, 0, 5, arrayOf(Cond(4, -1, 0), Cond(2, 0, 1), Cond(3, 0, 1))),
                Rule(3, 2, 4, arrayOf(Cond(2, 0, 1), Cond(3, 0, 0))),
                Rule(5, -2, 6, arrayOf(Cond(3, -2, 0)))
            ),
            arrayOf(
                Rule(-4, 4, 6, arrayOf(Cond(-5, 3, 0), Cond(-2, 2, 1), Cond(-3, 3, 1))),
                Rule(-2, 3, 7, arrayOf(Cond(-2, 2, 1), Cond(-3, 3, 0))),
                Rule(-5, 2, 5, arrayOf(Cond(-4, 1, 0))),
                Rule(4, -4, 6, arrayOf(Cond(3, -5, 0), Cond(2, -2, 1), Cond(3, -3, 1))),
                Rule(3, -2, 5, arrayOf(Cond(2, -2, 1), Cond(3, -3, 0))),
                Rule(2, -5, 7, arrayOf(Cond(1, -4, 0)))
            ),
            arrayOf(
                Rule(0, 4, 7, arrayOf(Cond(-1, 4, 0), Cond(0, 2, 1), Cond(0, 3, 1))),
                Rule(2, 3, 8, arrayOf(Cond(0, 2, 1), Cond(0, 3, 0))),
                Rule(-2, 5, 6, arrayOf(Cond(-2, 3, 0))),
                Rule(0, -4, 7, arrayOf(Cond(-1, -4, 0), Cond(0, -2, 1), Cond(0, -3, 1))),
                Rule(2, -3, 6, arrayOf(Cond(0, -2, 1), Cond(0, -3, 0))),
                Rule(-2, -5, 8, arrayOf(Cond(-2, -3, 0)))
            ),
            arrayOf(
                Rule(4, 4, 8, arrayOf(Cond(3, 5, 0), Cond(2, 2, 1), Cond(3, 3, 1))),
                Rule(3, 2, 1, arrayOf(Cond(2, 2, 1), Cond(3, 3, 0))),
                Rule(2, 5, 7, arrayOf(Cond(1, 4, 0))),
                Rule(-4, -4, 8, arrayOf(Cond(-5, -3, 0), Cond(-2, -2, 1), Cond(-3, -3, 1))),
                Rule(-2, -3, 7, arrayOf(Cond(-2, -2, 1), Cond(-3, -3, 0))),
                Rule(-5, -2, 1, arrayOf(Cond(-4, -1, 0)))
            )
        )
    }

    override val code = "zlxz"
    override val displayName = "重力行者"

    private var mapWidth = 0
    private var mapHeight = 0
    private var cells: Array<IntArray> = Array(0) { IntArray(0) }
    private var startX = -1; private var startY = -1
    private var goalX = -1; private var goalY = -1
    @Volatile private var playerX = 0; @Volatile private var playerY = 0
    @Volatile private var playerState = 1

    private val meta = ArrayList<String>()
    override var loadOk = false
        private set

    @Volatile private var viewX = 0f
    @Volatile private var viewY = 0f
    @Volatile private var viewScale = 1f
    @Volatile private var viewRotation = 0f
    @Volatile private var cellSize = CELL_SIZE_BASE
    private var viewRectLeft = 0f
    private var viewRectTop = 0f
    private var viewRectRight = 0f
    private var viewRectBottom = 0f

    private val pointerIds = intArrayOf(-1, -1)
    private var isTwoFinger = false
    private var oldDist = 0f
    private var oldScale = 1f
    private var oldViewX = 0f
    private var oldViewY = 0f
    private var oldRotation = 0f
    private var pivotX = 0f
    private var pivotY = 0f
    private var lastAngle = 0f
    private var lastRotation = 0f

    // ============ 双指手势绝对模式基准 ============
    /** 手势开始时的视口状态 */
    private var gestureStartViewX = 0f
    private var gestureStartViewY = 0f
    private var gestureStartScale = 1f
    private var gestureStartRotation = 0f
    /** 手势开始时两指状态（屏幕坐标） */
    private var gestureStartFocusX = 0f
    private var gestureStartFocusY = 0f
    private var gestureStartDist = 0f
    private var gestureStartAngle = 0f
    /** 手势开始时焦点对应的世界坐标（固定不动的锚点） */
    private var gestureWorldFocusX = 0f
    private var gestureWorldFocusY = 0f
    // ======================================================

    private var isMoving = false
    private var touchStartX = 0f
    private var touchStartY = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapInGameArea = false

    /** 双指手势结束（抬起一指）后短暂屏蔽，避免残指被判成双击返回或单指滑动 */
    private var twoFingerEndTime = 0L

    private var followMode = true
    private var userInteracting = false
    private var followBtnLeft = 0f; private var followBtnTop = 0f
    private var followBtnRight = 0f; private var followBtnBottom = 0f

    private var playerBitmap: Bitmap? = null

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
    private val undoState = IntArray(MAX_UNDO)
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

    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val linePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.BLACK }
    private val textPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val bitmapPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val srcRect = Rect()
    private val dstRectF = RectF()

    private val sw get() = GameView.instance?.screenW ?: 0
    private val sh get() = GameView.instance?.screenH ?: 0
    private fun now() = System.currentTimeMillis()

    override fun metaOf(i: Int): String? = meta.getOrNull(i)
    override val metaCount: Int get() = meta.size
    override fun isFirstComplete() = firstComplete
    override fun bestTime() = bestTimeVal
    override fun bestSteps() = bestStepsVal
    override fun firstCompleteTime() = firstCompleteTime

    override fun enterLevel(level: Int) {
        if (sw <= 0 || sh <= 0) {
            Log.e(TAG, "屏幕尺寸未初始化")
            GameView.instance?.gameState = GameState.LEVEL_SELECT
            loadOk = false
            return
        }
        this.level = level
        Global.currentLevel = level

        val m = MazeMapLoader.load(code, level, MapSemantics.ZLXZ)
        if (m == null) {
            MazeToast.show("地图加载失败")
            GameView.instance?.gameState = GameState.LEVEL_SELECT
            loadOk = false
            return
        }
        loadOk = true
        mapWidth = m.width; mapHeight = m.height
        cells = m.cells
        startX = m.startX; startY = m.startY
        goalX = m.goalX; goalY = m.goalY
        meta.clear(); meta.addAll(m.meta)
        playerX = startX; playerY = startY; playerState = 1

        if (playerBitmap == null) playerBitmap = MazeIcons.player()

        loadRecord(level)

        val mm = sw * 0.05f
        viewRectLeft = mm; viewRectTop = mm
        viewRectRight = sw - mm; viewRectBottom = sh - mm

        viewScale = 1.0f
        viewRotation = 0.0f
        // ★ 修复：cellSize 固定为 CELL_SIZE_BASE，视觉缩放完全交给 canvas.scale(viewScale)
        cellSize = CELL_SIZE_BASE
        initViewport()

        resetTwoFinger()
        lastTapTime = 0
        playerState = 1
        isMoving = false
        followMode = true

        val btnSizeTmp = sw * FOLLOW_BTN_SIZE_RATIO
        followBtnLeft = FOLLOW_BTN_MARGIN
        followBtnTop = (sh - btnSizeTmp) / 2f
        followBtnRight = followBtnLeft + btnSizeTmp
        followBtnBottom = followBtnTop + btnSizeTmp

        loadGameState()

        // ★ 修复：cellSize 固定
        cellSize = CELL_SIZE_BASE
        clampViewport()

        isFullscreen = false
        undoCount = 0; undoIndex = 0
        showSettingsPanel = false
        showResetDialog = false
        isTouchingResetBtn = false
        resetClickValid = false
    }

    private fun loadRecord(level: Int) {
        val r = MazeProgress.loadRecord(code, level)
        firstComplete = r[0] != 0
        firstCompleteTime = r[1]
        bestTimeVal = r[2]
        bestStepsVal = r[3]
    }

    private fun saveRecord() {
        MazeProgress.saveRecord(code, level, if (firstComplete) 1 else 0, firstCompleteTime, bestTimeVal, bestStepsVal)
    }

    private fun saveGameState() {
        MazeProgress.saveGameState(
            code, level, playerX, playerY, stepCount,
            levelStartTime, levelPausedTime, levelTimeSeconds,
            if (timerStarted) 1 else 0, if (isLevelPaused) 1 else 0,
            viewX, viewY, viewScale, viewRotation, playerState
        )
    }

    private fun loadGameState() {
        val s = MazeProgress.loadGameState(code, level)
        if (s == null) {
            playerX = startX; playerY = startY
            stepCount = 0
            levelStartTime = now(); levelPausedTime = 0; levelTimeSeconds = 0
            timerStarted = false; isLevelPaused = false
            viewX = 0f; viewY = 0f; viewScale = 1.0f; viewRotation = 0.0f
            playerState = 1
        } else {
            playerX = s[0].toInt(); playerY = s[1].toInt()
            stepCount = s[2].toInt()
            levelStartTime = s[3]; levelPausedTime = s[4]
            levelTimeSeconds = s[5].toInt()
            timerStarted = s[6] != 0L
            isLevelPaused = s[7] != 0L
            viewX = Float.fromBits(s[8].toInt())
            viewY = Float.fromBits(s[9].toInt())
            viewScale = Float.fromBits(s[10].toInt())
            viewRotation = Float.fromBits(s[11].toInt())
            playerState = if (s[12] == 0L) 1 else s[12].toInt()
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
            timerStarted = true; levelStartTime = now(); levelPausedTime = 0; isLevelPaused = false
        } else if (isLevelPaused) {
            levelStartTime = now(); isLevelPaused = false
        }
    }

    private fun getMapValue(x: Int, y: Int): Int =
        if (x < 0 || x >= mapWidth || y < 0 || y >= mapHeight) -1 else cells[y][x]

    private fun clampViewport() {
        val mapTotalW = mapWidth * cellSize
        val mapTotalH = mapHeight * cellSize

        if (viewX < 0f) viewX = 0f
        if (viewX > mapTotalW) viewX = mapTotalW
        if (viewY < 0f) viewY = 0f
        if (viewY > mapTotalH) viewY = mapTotalH
    }

    private fun initViewport() {
        viewX = mapWidth * cellSize / 2f
        viewY = mapHeight * cellSize / 2f
        clampViewport()
    }

    private fun getSwipeDirection(startX: Float, startY: Float, endX: Float, endY: Float): Int {
        val dx = endX - startX
        val dy = endY - startY
        if (abs(dx) < 20f && abs(dy) < 20f) return -1
        var angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        return when {
            angle >= 337.5f || angle < 22.5f -> MOVE_RIGHT
            angle >= 22.5f && angle < 67.5f -> MOVE_UP_RIGHT
            angle >= 67.5f && angle < 112.5f -> -1
            angle >= 112.5f && angle < 157.5f -> MOVE_UP_LEFT
            angle >= 157.5f && angle < 202.5f -> MOVE_LEFT
            angle >= 202.5f && angle < 247.5f -> MOVE_DOWN_LEFT
            angle >= 247.5f && angle < 292.5f -> -1
            angle >= 292.5f && angle < 337.5f -> MOVE_DOWN_RIGHT
            else -> -1
        }
    }

    private fun checkConditions(rule: Rule, px: Int, py: Int): Boolean {
        for (c in rule.conds) {
            if (getMapValue(px + c.dx, py + c.dy) != c.expected) return false
        }
        val v = getMapValue(px + rule.dx, py + rule.dy)
        if (v == -1 || v == 0) return false
        return true
    }

    private fun performMove(dir: Int) {
        val idx = (playerState - 1) % 8
        if (dir < 0 || dir >= 6) return
        val rule = RULES[idx][dir]
        if (!checkConditions(rule, playerX, playerY)) return

        if (undoIndex < undoCount) { undoCount = undoIndex }
        if (undoCount < MAX_UNDO) {
            undoX[undoCount] = playerX; undoY[undoCount] = playerY
            undoState[undoCount] = playerState; undoStep[undoCount] = stepCount
            undoCount++; undoIndex = undoCount
        } else {
            for (i in 1 until MAX_UNDO) {
                undoX[i - 1] = undoX[i]; undoY[i - 1] = undoY[i]
                undoState[i - 1] = undoState[i]; undoStep[i - 1] = undoStep[i]
            }
            undoX[MAX_UNDO - 1] = playerX; undoY[MAX_UNDO - 1] = playerY
            undoState[MAX_UNDO - 1] = playerState; undoStep[MAX_UNDO - 1] = stepCount
            undoCount = MAX_UNDO; undoIndex = MAX_UNDO
        }

        playerX += rule.dx
        playerY += rule.dy
        playerState = rule.newState
        stepCount++

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
            GameView.instance?.gameState = GameState.LEVEL_COMPLETE
        }
    }

    private fun resetLevelInternal(resetTime: Int) {
        playerX = startX; playerY = startY; playerState = 1
        stepCount = 0
        if (resetTime != 0) {
            levelStartTime = now(); levelPausedTime = 0; levelTimeSeconds = 0
            // ★ 修复：归零但不启动，等玩家在关卡介绍点「登录」时再 startTimer()
            timerStarted = false; isLevelPaused = false
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
            playerState = undoState[undoIndex]
            stepCount = undoStep[undoIndex]
        } else {
            MazeToast.show("没有可回撤的步骤")
        }
    }

    override fun destroyModule() {
        saveRecord()
        if (timerStarted) saveGameState()
        resetTwoFinger()
    }

    // ==================== 双指 / 旋转 ====================

    private fun resetTwoFinger() {
        pointerIds[0] = -1; pointerIds[1] = -1
        isTwoFinger = false
        oldDist = 0f
        oldScale = viewScale
        oldViewX = viewX; oldViewY = viewY
        oldRotation = viewRotation
        lastAngle = 0f
        lastRotation = viewRotation
        userInteracting = false
    }

    private fun addPointer(id: Int) {
        if (pointerIds[0] == -1) {
            pointerIds[0] = id
        } else if (pointerIds[1] == -1 && pointerIds[0] != id) {
            pointerIds[1] = id
            isTwoFinger = true
            userInteracting = true
        }
    }

    private fun removePointer(id: Int) {
        val wasTwoFinger = isTwoFinger
        if (pointerIds[0] == id) {
            pointerIds[0] = pointerIds[1]; pointerIds[1] = -1
        } else if (pointerIds[1] == id) {
            pointerIds[1] = -1
        }
        if (pointerIds[1] == -1) {
            isTwoFinger = false
            userInteracting = false
            if (wasTwoFinger) {
                twoFingerEndTime = now()
                clampViewport()
            }
        }
    }

    private fun twoFingerCoords(pointers: Map<Int, Pair<Float, Float>>): FloatArray? {
        val p1 = pointers[pointerIds[0]] ?: return null
        val p2 = pointers[pointerIds[1]] ?: return null
        return floatArrayOf(p1.first, p1.second, p2.first, p2.second)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /** 当前渲染使用的视口中心（需与 drawMapWithTransform 保持一致） */
    private fun viewportCenterX(): Float =
        if (isFullscreen) sw / 2f else (viewRectLeft + viewRectRight) / 2f

    private fun viewportCenterY(): Float =
        if (isFullscreen) sh / 2f else (viewRectTop + viewRectBottom) / 2f

    private fun twoFingerStart(pointers: Map<Int, Pair<Float, Float>>) {
        val c = twoFingerCoords(pointers) ?: return

        gestureStartViewX = viewX
        gestureStartViewY = viewY
        gestureStartScale = viewScale
        gestureStartRotation = viewRotation

        gestureStartFocusX = (c[0] + c[2]) / 2f
        gestureStartFocusY = (c[1] + c[3]) / 2f
        gestureStartDist = distance(c[0], c[1], c[2], c[3])
        gestureStartAngle = atan2(c[3] - c[1], c[2] - c[0])

        // 世界→屏幕：M = T(center)·R(rot)·S(scale)·T(-view)
        // 屏幕→世界：W = view + (1/scale)·R(-rot)·(F_screen - center)
        // 由于 cellSize 固定，world 像素与 viewScale 解耦，此逆变换严格成立。
        val cx = viewportCenterX()
        val cy = viewportCenterY()
        val dx = gestureStartFocusX - cx
        val dy = gestureStartFocusY - cy
        val thetaRad = Math.toRadians(-gestureStartRotation.toDouble())
        val cosA = cos(thetaRad).toFloat()
        val sinA = sin(thetaRad).toFloat()
        val rx = dx * cosA - dy * sinA
        val ry = dx * sinA + dy * cosA
        gestureWorldFocusX = gestureStartViewX + rx / gestureStartScale
        gestureWorldFocusY = gestureStartViewY + ry / gestureStartScale

        oldDist = gestureStartDist
        oldScale = gestureStartScale
        oldViewX = gestureStartViewX
        oldViewY = gestureStartViewY
        oldRotation = gestureStartRotation
        pivotX = gestureStartFocusX
        pivotY = gestureStartFocusY
        lastAngle = gestureStartAngle
        lastRotation = gestureStartRotation
    }

    private fun twoFingerMove(pointers: Map<Int, Pair<Float, Float>>) {
        val c = twoFingerCoords(pointers) ?: return
        if (gestureStartDist < 1f) return

        val curFocusX = (c[0] + c[2]) / 2f
        val curFocusY = (c[1] + c[3]) / 2f
        val curDist = distance(c[0], c[1], c[2], c[3])
        val curAngle = atan2(c[3] - c[1], c[2] - c[0])

        // 缩放（相对于手势起点）
        var newScale = gestureStartScale * (curDist / gestureStartDist)
        if (newScale < MIN_SCALE) newScale = MIN_SCALE
        if (newScale > MAX_SCALE) newScale = MAX_SCALE

        // 旋转（相对于手势起点）
        var angleDelta = curAngle - gestureStartAngle
        if (angleDelta > Math.PI.toFloat()) angleDelta -= (2 * Math.PI).toFloat()
        if (angleDelta < -Math.PI.toFloat()) angleDelta += (2 * Math.PI).toFloat()
        val newRotation = gestureStartRotation + Math.toDegrees(angleDelta.toDouble()).toFloat()

        // 平移：让 gestureWorldFocus 精确映射到当前手指中心
        // F_cur = center + S(newScale)·R(newRot)·(W - view)
        // view = W - (1/newScale)·R(-newRot)·(F_cur - center)
        val cx = viewportCenterX()
        val cy = viewportCenterY()
        val dx = curFocusX - cx
        val dy = curFocusY - cy
        val thetaRad = Math.toRadians(-newRotation.toDouble())
        val cosA = cos(thetaRad).toFloat()
        val sinA = sin(thetaRad).toFloat()
        val rx = dx * cosA - dy * sinA
        val ry = dx * sinA + dy * cosA

        viewScale = newScale
        viewRotation = newRotation
        viewX = gestureWorldFocusX - rx / viewScale
        viewY = gestureWorldFocusY - ry / viewScale
        // ★ 修复：cellSize 固定，不再随 viewScale 变化
        cellSize = CELL_SIZE_BASE

        clampViewport()

        lastAngle = curAngle
        lastRotation = viewRotation
        oldDist = curDist
        oldScale = viewScale
        oldViewX = viewX
        oldViewY = viewY
        oldRotation = viewRotation
    }

    // ==================== 地图绘制 ====================

    private fun drawMap(canvas: Canvas) {
        val mapTotalW = mapWidth * cellSize
        val mapTotalH = mapHeight * cellSize

        fillPaint.color = Color.WHITE
        canvas.drawRect(-cellSize, -cellSize, mapTotalW + cellSize, mapTotalH + cellSize, fillPaint)

        // 横竖线
        var n = 0
        while (4 * n < mapWidth) {
            var s = 0
            while (4 * s < mapHeight) {
                val x = 4 * n; val y = 4 * s
                if (x < mapWidth && y < mapHeight && cells[y][x] == 0) {
                    if (x + 4 < mapWidth) {
                        val endX = x + 4; val midX = x + 2
                        if (midX < mapWidth && cells[y][endX] == 0 && cells[y][midX] == 0) {
                            val x1 = (x + 0.5f) * cellSize
                            val y1 = (y + 0.5f) * cellSize
                            val x2 = (endX + 0.5f) * cellSize
                            canvas.drawLine(x1, y1, x2, y1, linePaint)
                        }
                    }
                    if (y + 4 < mapHeight) {
                        val endY = y + 4; val midY = y + 2
                        if (midY < mapHeight && cells[endY][x] == 0 && cells[midY][x] == 0) {
                            val x1 = (x + 0.5f) * cellSize
                            val y1 = (y + 0.5f) * cellSize
                            val y2 = (endY + 0.5f) * cellSize
                            canvas.drawLine(x1, y1, x1, y2, linePaint)
                        }
                    }
                }
                s++
            }
            n++
        }

        // 斜线
        var w = 0
        while (4 * w + 2 < mapWidth) {
            var m = 0
            while (4 * m + 2 < mapHeight) {
                val cx = 4 * w + 2; val cy = 4 * m + 2
                if (cx < mapWidth && cy < mapHeight && cells[cy][cx] == 0) {
                    var c1x = cx - 1; var c1y = cy + 1
                    var c2x = cx + 1; var c2y = cy - 1
                    if (c1x >= 0 && c1x < mapWidth && c1y >= 0 && c1y < mapHeight &&
                        c2x >= 0 && c2x < mapWidth && c2y >= 0 && c2y < mapHeight &&
                        cells[c1y][c1x] == 0 && cells[c2y][c2x] == 0
                    ) {
                        val e1x = cx - 2; val e1y = cy + 2
                        val e2x = cx + 2; val e2y = cy - 2
                        if (e1x >= 0 && e1x < mapWidth && e1y >= 0 && e1y < mapHeight &&
                            e2x >= 0 && e2x < mapWidth && e2y >= 0 && e2y < mapHeight &&
                            cells[e1y][e1x] == 0 && cells[e2y][e2x] == 0
                        ) {
                            canvas.drawLine(
                                (e1x + 0.5f) * cellSize, (e1y + 0.5f) * cellSize,
                                (e2x + 0.5f) * cellSize, (e2y + 0.5f) * cellSize, linePaint
                            )
                        }
                    }
                    c1x = cx - 1; c1y = cy - 1
                    c2x = cx + 1; c2y = cy + 1
                    if (c1x >= 0 && c1x < mapWidth && c1y >= 0 && c1y < mapHeight &&
                        c2x >= 0 && c2x < mapWidth && c2y >= 0 && c2y < mapHeight &&
                        cells[c1y][c1x] == 0 && cells[c2y][c2x] == 0
                    ) {
                        val e1x = cx - 2; val e1y = cy - 2
                        val e2x = cx + 2; val e2y = cy + 2
                        if (e1x >= 0 && e1x < mapWidth && e1y >= 0 && e1y < mapHeight &&
                            e2x >= 0 && e2x < mapWidth && e2y >= 0 && e2y < mapHeight &&
                            cells[e1y][e1x] == 0 && cells[e2y][e2x] == 0
                        ) {
                            canvas.drawLine(
                                (e1x + 0.5f) * cellSize, (e1y + 0.5f) * cellSize,
                                (e2x + 0.5f) * cellSize, (e2y + 0.5f) * cellSize, linePaint
                            )
                        }
                    }
                }
                m++
            }
            w++
        }

        fillPaint.color = Color.rgb(0, 200, 0)
        canvas.drawCircle((goalX + 0.5f) * cellSize, (goalY + 0.5f) * cellSize, cellSize * 0.3f, fillPaint)

        val offsetX = STATE_OFFSET_X[playerState - 1] * cellSize
        val offsetY = STATE_OFFSET_Y[playerState - 1] * cellSize
        val px = (playerX + 0.5f) * cellSize + offsetX
        val py = (playerY + 0.5f) * cellSize + offsetY

        val bmp = playerBitmap
        if (bmp == null) {
            fillPaint.color = Color.RED
            canvas.drawCircle(px, py, cellSize * 0.3f, fillPaint)
        } else {
            val targetSize = cellSize * 1.2f
            val sc = targetSize / maxOf(bmp.width, bmp.height).toFloat()
            val drawW = bmp.width * sc
            val drawH = bmp.height * sc
            val angle = -(playerState - 1) * 45f
            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(angle)
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRectF.set(-drawW / 2f, 0f, drawW / 2f, drawH)
            canvas.drawBitmap(bmp, srcRect, dstRectF, bitmapPaint)
            canvas.restore()
        }
    }

    private fun drawMapWithTransform(canvas: Canvas, fullscreen: Boolean) {
        val l = if (fullscreen) 0f else viewRectLeft
        val t = if (fullscreen) 0f else viewRectTop
        val r = if (fullscreen) sw.toFloat() else viewRectRight
        val b = if (fullscreen) sh.toFloat() else viewRectBottom
        val centerX = (l + r) / 2f
        val centerY = (t + b) / 2f
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(viewRotation)
        // ★ cellSize 固定，这里 scale(viewScale) 是唯一的缩放来源
        canvas.scale(viewScale, viewScale, 0f, 0f)
        canvas.translate(-viewX, -viewY)
        drawMap(canvas)
        canvas.restore()
    }

    override fun drawFullscreen(canvas: Canvas) {
        if (!loadOk) return
        val oldL = viewRectLeft; val oldT = viewRectTop; val oldR = viewRectRight; val oldB = viewRectBottom
        viewRectLeft = 0f; viewRectTop = 0f
        viewRectRight = sw.toFloat(); viewRectBottom = sh.toFloat()
        clampViewport()
        canvas.drawColor(Color.WHITE)
        drawMapWithTransform(canvas, true)
        viewRectLeft = oldL; viewRectTop = oldT; viewRectRight = oldR; viewRectBottom = oldB
    }

    private fun applyFollow(dt: Float) {
        if (!followMode || userInteracting) return
        val frameScale = (dt * 60f).coerceIn(0.1f, 3f)
        var targetBase = STATE_TARGET_ROTATIONS[playerState - 1]
        while (targetBase < 0f) targetBase += 360f
        while (targetBase >= 360f) targetBase -= 360f

        var normView = viewRotation
        while (normView < 0f) normView += 360f
        while (normView >= 360f) normView -= 360f

        var diff = targetBase - normView
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        viewRotation += diff * (ROTATION_FOLLOW_SPEED * frameScale).coerceAtMost(1f)

        val targetViewX = (playerX + 0.5f) * cellSize
        val targetViewY = (playerY + 0.5f) * cellSize
        viewX += (targetViewX - viewX) * (FOLLOW_SPEED * frameScale).coerceAtMost(1f)
        viewY += (targetViewY - viewY) * (FOLLOW_SPEED * frameScale).coerceAtMost(1f)
        clampViewport()
    }

    override fun update(dt: Float) {
        applyFollow(dt)
        // ★ 修复：全屏时 drawUi 不执行，计时必须每帧推进
        if (timerStarted && !isLevelPaused) {
            levelTimeSeconds = ((now() - levelStartTime) / 1000 + levelPausedTime / 1000).toInt()
        }
    }

    override fun drawGame(canvas: Canvas) {
        if (!loadOk) return
        clampViewport()

        if (isFullscreen) {
            val oldL = viewRectLeft; val oldT = viewRectTop; val oldR = viewRectRight; val oldB = viewRectBottom
            viewRectLeft = 0f; viewRectTop = 0f
            viewRectRight = sw.toFloat(); viewRectBottom = sh.toFloat()
            canvas.drawColor(Color.WHITE)
            drawMapWithTransform(canvas, true)
            viewRectLeft = oldL; viewRectTop = oldT; viewRectRight = oldR; viewRectBottom = oldB
            return
        }

        canvas.drawColor(Color.rgb(173, 216, 230))
        fillPaint.color = Color.argb(100, 0, 0, 0)
        canvas.drawRect(viewRectLeft, viewRectTop, viewRectRight, viewRectBottom, fillPaint)

        canvas.save()
        canvas.clipRect(viewRectLeft, viewRectTop, viewRectRight, viewRectBottom)
        drawMapWithTransform(canvas, false)
        canvas.restore()

        fillPaint.color = if (followMode) Color.argb(200, 0, 180, 0) else Color.argb(200, 100, 100, 100)
        canvas.drawRoundRect(
            followBtnLeft, followBtnTop, followBtnRight, followBtnBottom,
            (followBtnRight - followBtnLeft) * 0.2f,
            (followBtnBottom - followBtnTop) * 0.2f, fillPaint
        )
        textPaint.textSize = (followBtnBottom - followBtnTop) * 0.6f
        textPaint.color = Color.WHITE
        val label = "\u271B"
        val tw = textPaint.measureText(label)
        canvas.drawText(
            label,
            followBtnLeft + (followBtnRight - followBtnLeft - tw) / 2f,
            followBtnTop + (followBtnBottom - followBtnTop) * 0.7f,
            textPaint
        )

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
            val timeStr = String.format("%02d:%02d", levelTimeSeconds / 60, levelTimeSeconds % 60)
            textPaint.textSize = btnSize * 0.6f
            textPaint.color = Color.WHITE
            val timeW = textPaint.measureText(timeStr)
            canvas.drawText(timeStr, fullscreenBtnX - margin - timeW, topY + btnSize * 0.7f, textPaint)
        }

        if (showResetDialog) {
            fillPaint.color = Color.argb(180, 0, 0, 0)
            canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), fillPaint)
            val dialogW = sw * 0.5f; val dialogH = sh * 0.2f
            val dialogX = (sw - dialogW) / 2f; val dialogY = (sh - dialogH) / 2f
            fillPaint.color = Color.rgb(60, 60, 70)
            canvas.drawRoundRect(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, dialogH * 0.1f, dialogH * 0.1f, fillPaint)
            textPaint.textSize = dialogH * 0.2f
            textPaint.color = Color.WHITE
            val msg = if (resetConfirmType == 1) "确定要重置位置和时间吗？" else "确定要重置位置吗？"
            canvas.drawText(msg, dialogX + (dialogW - textPaint.measureText(msg)) / 2f, dialogY + dialogH * 0.35f, textPaint)
            val btnW2 = dialogW * 0.18f; val btnH2 = dialogH * 0.3f
            val btnY2 = dialogY + dialogH * 0.55f; val btnSpacing2 = dialogW * 0.1f
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
            val panelW = sw * 0.35f; val panelH = sh * 0.3f
            val panelX = margin; val panelY = topY + btnSize + margin
            fillPaint.color = Color.argb(220, 50, 50, 60)
            canvas.drawRoundRect(panelX, panelY, panelX + panelW, panelY + panelH, panelH * 0.05f, panelH * 0.05f, fillPaint)
            textPaint.textSize = panelH * 0.12f
            textPaint.color = Color.WHITE
            canvas.drawText("双击重置键", panelX + panelW * 0.05f, panelY + panelH * 0.25f, textPaint)
            val switchX = panelX + panelW * 0.7f; val switchY = panelY + panelH * 0.12f
            val switchW = panelW * 0.2f; val switchH = panelH * 0.2f
            fillPaint.color = if (doubleTapResetEnabled) Color.rgb(100, 200, 100) else Color.rgb(150, 150, 150)
            canvas.drawRoundRect(switchX, switchY, switchX + switchW, switchY + switchH, switchH * 0.5f, switchH * 0.5f, fillPaint)
            val thumbX = if (doubleTapResetEnabled) switchX + switchW - switchH else switchX
            fillPaint.color = Color.WHITE
            canvas.drawCircle(thumbX + switchH / 2f, switchY + switchH / 2f, switchH * 0.4f, fillPaint)
            val saveBtnW = panelW * 0.4f; val saveBtnH = panelH * 0.2f
            val saveBtnX = panelX + (panelW - saveBtnW) / 2f; val saveBtnY = panelY + panelH * 0.55f
            fillPaint.color = Color.argb(200, 60, 120, 200)
            canvas.drawRoundRect(saveBtnX, saveBtnY, saveBtnX + saveBtnW, saveBtnY + saveBtnH, saveBtnH * 0.2f, saveBtnH * 0.2f, fillPaint)
            textPaint.textSize = saveBtnH * 0.5f
            textPaint.color = Color.WHITE
            val saveTextW = textPaint.measureText("保存并返回")
            canvas.drawText("保存并返回", saveBtnX + (saveBtnW - saveTextW) / 2f, saveBtnY + saveBtnH * 0.7f, textPaint)
        }
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
                if (!isTwoFinger) {
                    if (x >= followBtnLeft && x <= followBtnRight && y >= followBtnTop && y <= followBtnBottom) {
                        followMode = !followMode
                        return
                    }
                    if (handleUiDown(x, y)) return
                    touchStartX = x; touchStartY = y
                    isMoving = true
                    val n = now()
                    if (n - lastTapTime < DOUBLE_TAP_MS &&
                        abs(x - lastTapX) < DOUBLE_TAP_RADIUS && abs(y - lastTapY) < DOUBLE_TAP_RADIUS
                    ) {
                        if (!lastTapInGameArea) {
                            pauseTimer(); saveGameState()
                            GameView.instance?.gameState = GameState.LEVEL_SELECT
                            lastTapTime = 0
                            return
                        }
                    }
                    lastTapTime = n
                    lastTapX = x; lastTapY = y
                    lastTapInGameArea = (x >= viewRectLeft && x <= viewRectRight && y >= viewRectTop && y <= viewRectBottom)
                } else {
                    isMoving = false
                    isTouchingResetBtn = false
                    resetClickValid = false
                    twoFingerStart(pointers)
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
                if (isTwoFinger) twoFingerMove(pointers)
            }

            TouchAction.UP, TouchAction.POINTER_UP -> {
                if (id != -1) removePointer(id)
                handleResetUp(x, y)
                if (!isTwoFinger && isMoving && now() - twoFingerEndTime >= TWO_FINGER_COOLDOWN_MS) {
                    val dir = getSwipeDirection(touchStartX, touchStartY, x, y)
                    if (dir >= 0) performMove(dir)
                    isMoving = false
                }
            }
        }
    }

    private fun handleUiDown(x: Float, y: Float): Boolean {
        if (showResetDialog) {
            val dialogW = sw * 0.5f; val dialogH = sh * 0.2f
            val dialogX = (sw - dialogW) / 2f; val dialogY = (sh - dialogH) / 2f
            val btnW2 = dialogW * 0.18f; val btnH2 = dialogH * 0.3f
            val btnY2 = dialogY + dialogH * 0.55f; val btnSpacing2 = dialogW * 0.1f
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
            if (x >= btnStartX2 + btnW2 + btnSpacing2 && x <= btnStartX2 + btnW2 + btnSpacing2 + btnW2 && y >= btnY2 && y <= btnY2 + btnH2) {
                showResetDialog = false
                return true
            }
            if (x < dialogX || x > dialogX + dialogW || y < dialogY || y > dialogY + dialogH) {
                showResetDialog = false
            }
            return true
        }

        if (showSettingsPanel) {
            val panelW = sw * 0.35f; val panelH = sh * 0.3f
            val panelX = margin; val panelY = topY + btnSize + margin
            val switchX = panelX + panelW * 0.7f; val switchY = panelY + panelH * 0.12f
            val switchW = panelW * 0.2f; val switchH = panelH * 0.2f
            if (x >= switchX && x <= switchX + switchW && y >= switchY && y <= switchY + switchH) {
                doubleTapResetEnabled = !doubleTapResetEnabled
                MazeToast.show(if (doubleTapResetEnabled) "双击重置键已开启" else "双击重置键已关闭")
                return true
            }
            val saveBtnW = panelW * 0.4f; val saveBtnH = panelH * 0.2f
            val saveBtnX = panelX + (panelW - saveBtnW) / 2f; val saveBtnY = panelY + panelH * 0.55f
            if (x >= saveBtnX && x <= saveBtnX + saveBtnW && y >= saveBtnY && y <= saveBtnY + saveBtnH) {
                pauseTimer(); saveGameState()
                MazeToast.show("进度已保存")
                showSettingsPanel = false
                GameView.instance?.gameState = GameState.LEVEL_SELECT
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
        if ((masked == TouchAction.DOWN || masked == TouchAction.POINTER_DOWN) &&
            now() - twoFingerEndTime < TWO_FINGER_COOLDOWN_MS
        ) {
            return
        }
        if (masked == TouchAction.DOWN || masked == TouchAction.POINTER_DOWN) {
            if (id != -1) addPointer(id)
            if (isTwoFinger) {
                isMoving = false
                twoFingerStart(pointers)
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
            twoFingerMove(pointers)
        }
        if (masked == TouchAction.UP && !isTwoFinger && isMoving &&
            now() - twoFingerEndTime >= TWO_FINGER_COOLDOWN_MS) {
            val dir = getSwipeDirection(touchStartX, touchStartY, x, y)
            if (dir >= 0) performMove(dir)
            isMoving = false
        }
        if (masked == TouchAction.UP || masked == TouchAction.POINTER_UP) {
            if (id != -1) removePointer(id)
        }
    }
}