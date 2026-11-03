package org.mazero

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 所有游戏界面的统一接口
 * 对应原 C 工程各模块的 init / draw / touch / update / destroy
 */
interface GameScreen {
    fun init()
    fun update(deltaTime: Float)
    fun draw(canvas: Canvas)
    fun touch(action: Int, x: Float, y: Float)
    fun destroy()

    /** 多指触摸：pointers 为 pointerId -> (x,y) 映射。默认实现退化为单指 touch() */
    fun touchMulti(action: Int, x: Float, y: Float, pointers: Map<Int, Pair<Float, Float>>) {
        touch(action, x, y)
    }
}

/**
 * 主视图：承载整个游戏循环
 * 对应原 C 工程 main.c 中的 onCreate / onDraw / onTouchEvent / onLoopCall
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, Runnable {

    companion object {
        private const val TAG = "GameView"
        private const val TARGET_FPS = 60
        private const val FRAME_TIME_MS = 1000L / TARGET_FPS

        /** 界面切换后的防误触时间窗口（毫秒），集中处理，各界面不再自行实现 */
        private const val TOUCH_PROTECT_MS = 200L

        /** 全局单例，方便其他模块读取屏幕尺寸 */
        @Volatile
        var instance: GameView? = null
            private set
    }

    // ==================== 全局状态 ====================

    /** 当前游戏状态，对应原 C 的 gameState */
    @Volatile
    private var _gameState: Int = GameState.START
    var gameState: Int
        get() = _gameState
        set(value) {
            if (_gameState != value) {
                Log.i(TAG, "gameState: $_gameState -> $value")
                _gameState = value
                lastStateChangeTime = System.currentTimeMillis()
                // 状态切换时，把当前仍在进行的手势剩余事件（含触发切换的那次 UP）
                // 全部吞掉，避免落到新界面被当成一次点击（如主菜单轮播/图标）
                suppressUntilUp = true
            }
        }

    /** 上一次界面切换的时间戳，用于统一防误触 */
    @Volatile
    private var lastStateChangeTime: Long = System.currentTimeMillis()

    /** 防误触期间是否正在丢弃一整个手势（直到 UP / CANCEL） */
    private var suppressUntilUp = false

    /** 全局白闪过渡透明度，0.0~1.0，对应原 C 的 globalWhiteAlpha */
    @Volatile
    var globalWhiteAlpha: Float = 0f

    /** 屏幕宽高，由 surfaceChanged 设置 */
    @Volatile
    var screenW: Int = 0
        private set

    @Volatile
    var screenH: Int = 0
        private set

    /** 屏幕密度 */
    var density: Float = 1f
        private set

    // ==================== 界面注册表 ====================

    private val screens = HashMap<Int, GameScreen>()
    private val initializedScreens = HashSet<Int>()

    /**
     * 注册一个界面到指定的 gameState
     * 例如：registerScreen(GameState.START, StartScreen)
     */
    fun registerScreen(state: Int, screen: GameScreen) {
        screens[state] = screen
    }

    // ==================== 渲染循环 ====================

    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    private var lastFrameTime: Long = 0L

    private val whitePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        lastStateChangeTime = System.currentTimeMillis()
        instance = this
    }

    // ==================== SurfaceHolder.Callback ====================

    override fun surfaceCreated(holder: SurfaceHolder) {
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width
        screenH = height
        density = resources.displayMetrics.density
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    // ==================== 主循环 ====================

    private fun startLoop() {
        if (running) return
        running = true
        renderThread = Thread(this, "GameRenderThread").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        try {
            renderThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        renderThread = null
    }

    override fun run() {
        lastFrameTime = System.currentTimeMillis()
        while (running) {
            val frameStart = System.currentTimeMillis()

            // 1. 更新
            val deltaTime = (frameStart - lastFrameTime) / 1000f
            lastFrameTime = frameStart
            updateInternal(deltaTime)

            // 2. 绘制
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    synchronized(holder) {
                        drawInternal(canvas)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "draw error", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "unlockCanvasAndPost error", e)
                    }
                }
            }

            // 3. 帧率控制
            val elapsed = System.currentTimeMillis() - frameStart
            val sleepTime = FRAME_TIME_MS - elapsed
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun updateInternal(deltaTime: Float) {
        // 白闪衰减
        if (globalWhiteAlpha > 0f) {
            globalWhiteAlpha -= 0.03f
            if (globalWhiteAlpha < 0f) globalWhiteAlpha = 0f
        }

        if (screenW == 0 || screenH == 0) return

        // 分发 update
        val screen = screens[gameState] ?: return
        ensureInitialized(gameState, screen)
        try {
            screen.update(deltaTime)
        } catch (e: Exception) {
            Log.e(TAG, "screen update error state=$gameState", e)
        }
    }

    private fun drawInternal(canvas: Canvas) {
        if (screenW == 0 || screenH == 0) return

        // 清屏
        canvas.drawColor(Color.BLACK)

        // 分发 draw
        val screen = screens[gameState]
        if (screen != null) {
            ensureInitialized(gameState, screen)
            try {
                screen.draw(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "screen draw error state=$gameState", e)
            }
        }

        // 叠加全局白闪过渡层
        if (globalWhiteAlpha > 0f) {
            val alpha = (globalWhiteAlpha * 255f).toInt().coerceIn(0, 255)
            whitePaint.alpha = alpha
            canvas.drawRect(
                0f, 0f,
                screenW.toFloat(), screenH.toFloat(),
                whitePaint
            )
        }
    }

    /** 第一次访问某界面时调用其 init()，确保屏幕尺寸已就绪 */
    private fun ensureInitialized(state: Int, screen: GameScreen) {
        if (initializedScreens.add(state)) {
            try {
                screen.init()
            } catch (e: Exception) {
                Log.e(TAG, "screen init error state=$state", e)
            }
        }
    }

    // ==================== 触摸事件 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val masked = event.actionMasked

        // 统一界面切换防误触：状态切换后 200ms 内的首次按下，以及该手势的后续事件全部丢弃
        if (suppressUntilUp) {
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                suppressUntilUp = false
            }
            return true
        }
        if (masked == MotionEvent.ACTION_DOWN &&
            System.currentTimeMillis() - lastStateChangeTime < TOUCH_PROTECT_MS
        ) {
            suppressUntilUp = true
            return true
        }

        val actionIndex = event.actionIndex
        val x: Float
        val y: Float
        if (masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_POINTER_UP) {
            x = event.getX(actionIndex)
            y = event.getY(actionIndex)
        } else {
            x = event.x
            y = event.y
        }

        val baseAction = when (masked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            else -> return true
        }

        // 把 actionIndex 编码进高位，供多指模块解析“当前是哪根手指”
        // 缺此编码时 index 恒为 0，第二根手指无法登记，双指缩放/平移失效
        val action = if (masked == MotionEvent.ACTION_POINTER_DOWN ||
            masked == MotionEvent.ACTION_POINTER_UP
        ) {
            baseAction or (event.actionIndex shl TouchAction.POINTER_INDEX_SHIFT)
        } else {
            baseAction
        }

        val screen = screens[gameState]
        if (screen != null) {
            try {
                // 收集所有活动指针（pointerId -> (x,y)），供多指手势（如地图编辑器双指缩放）使用
                // 使用 LinkedHashMap：迭代顺序即 pointer index 顺序，供模块用 index 解析 pointerId
                val pointers = LinkedHashMap<Int, Pair<Float, Float>>()
                for (i in 0 until event.pointerCount) {
                    pointers[event.getPointerId(i)] = Pair(event.getX(i), event.getY(i))
                }
                screen.touchMulti(action, x, y, pointers)
            } catch (e: Exception) {
                Log.e(TAG, "screen touch error state=$gameState", e)
            }
        }
        return true
    }

    // ==================== 生命周期 ====================

    fun resume() {
        // SurfaceView 循环已在 surfaceCreated 中启动
        // BGM 恢复由 MainActivity.onResume() -> BgmPlayer.start() 负责，对应旧 C 的 onResume
        // 后续如有计时器需要恢复，可在此补充
    }

    fun pause() {
        // BGM 暂停由 MainActivity.onPause() -> BgmPlayer.pause() 负责，对应旧 C 的 onPause
        // 后续如有计时器保存，可在此补充
    }

    fun destroy() {
        stopLoop()
        for ((state, screen) in screens) {
            try {
                screen.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "screen destroy error state=$state", e)
            }
        }
        screens.clear()
        initializedScreens.clear()
        if (instance === this) {
            instance = null
        }
    }
}
