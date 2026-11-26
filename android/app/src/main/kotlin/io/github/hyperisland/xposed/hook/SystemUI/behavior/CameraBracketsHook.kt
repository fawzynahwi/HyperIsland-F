package io.github.hyperisland.xposed.hook.SystemUI.behavior

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import io.github.hyperisland.utils.CameraBracketsConfig
import io.github.hyperisland.xposed.ConfigManager
import io.github.hyperisland.xposed.diagnostics.profiledHook
import io.github.hyperisland.xposed.hook.BaseHook
import io.github.hyperisland.xposed.hook.StatusBarTextColorHook
import io.github.hyperisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * ColorOS-style brackets "( )" around the camera hole.
 *
 * - Drawn around the island whenever it is showing, tracking its animated bounds.
 * - Optional "always": when no island is showing they stay around the camera cutout.
 *   Idle brackets get burn-in protection: dimmed, and nudged to a new spot every minute.
 * - Colour follows the status bar tint (white on dark, black on light), animated.
 *
 * Rendering: one Drawable added to the `DynamicIslandWindowView.overlay`, so it is drawn on
 * top of the island window, inherits its visibility/lifecycle, and needs no extra window.
 * Independent from the "single island" behaviour; no SystemUI restart needed to toggle.
 */
object CameraBracketsHook : BaseHook() {
    private const val ROOT = "miui.systemui.dynamicisland."
    private const val MAX_WINDOWS = 8
    private val SHIFT_PATTERN = arrayOf(
        0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1,
    )

    @Volatile private var enabled = false
    @Volatile private var always = false
    @Volatile private var burnIn = true

    private val installedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val overlays = WeakHashMap<View, BracketsDrawable>()
    private var tintListenerRegistered = false

    override fun getTag() = "HyperIsland[CameraBrackets]"

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        loadConfig()
        registerTintListener()
        bind(module, param.defaultClassLoader)
        HookUtils.hookDynamicClassLoaders(module, ClassLoader.getSystemClassLoader()) { bind(module, it) }
    }

    override fun onConfigChanged() {
        loadConfig()
        invalidateAll()
    }

    private fun loadConfig() {
        enabled = ConfigManager.getBoolean(CameraBracketsConfig.ENABLED_KEY, false)
        always = ConfigManager.getBoolean(CameraBracketsConfig.ALWAYS_KEY, false)
        burnIn = ConfigManager.getBoolean(CameraBracketsConfig.BURN_IN_KEY, true)
    }

    private fun registerTintListener() {
        if (tintListenerRegistered) return
        tintListenerRegistered = true
        runCatching { StatusBarTextColorHook.addReadableTintListener { invalidateAll() } }
    }

    private fun invalidateAll() {
        val views = synchronized(overlays) { overlays.keys.map { WeakReference(it) } }
        views.forEach { it.get()?.postInvalidateOnAnimation() }
    }

    /** Pre-resolved reflection handles. A missing piece disables only the "wrap island" part. */
    private class Access(loader: ClassLoader) {
        val base: Class<*> = loader.loadClass(ROOT + "window.content.DynamicIslandBaseContentView")
        val real: Class<*> = loader.loadClass(ROOT + "window.content.DynamicIslandContentView")
        val windowClass: Class<*> = loader.loadClass(ROOT + "window.DynamicIslandWindowView")
        private val stateClass = loader.loadClass(ROOT + "event.DynamicIslandState")
        val big: Class<*> = loader.loadClass(stateClass.name + "\$BigIsland")
        val showOnce: Class<*> = loader.loadClass(stateClass.name + "\$ShowOnceBigIsland")
        val small: Class<*> = loader.loadClass(stateClass.name + "\$SmallIsland")
        val getState: Method = base.getMethod("getState")
        val setState: Method = base.getMethod("setState", stateClass)
        val attached: Method = real.getDeclaredMethod("onAttachedToWindow")
        val background: Method? = runCatching { base.getMethod("getBackgroundView") }.getOrNull()
        val bigView: Method? = runCatching { base.getMethod("getBigIslandView") }.getOrNull()
        val smallView: Method? = runCatching { base.getMethod("getSmallIslandView") }.getOrNull()
        private val bgClass: Class<*>? = background?.returnType
        val left: Method? = runCatching { bgClass?.getMethod("getActualLeft") }.getOrNull()
        val top: Method? = runCatching { bgClass?.getMethod("getActualTop") }.getOrNull()
        // Stock names are misleading: these return right / bottom coordinates.
        val right: Method? = runCatching { bgClass?.getMethod("getActualWidth") }.getOrNull()
        val bottom: Method? = runCatching { bgClass?.getMethod("getActualHeight") }.getOrNull()
        val canWrapBig get() = background != null && left != null && top != null && right != null && bottom != null

        fun isBig(view: Any) = getState.invoke(view).let { big.isInstance(it) || showOnce.isInstance(it) }
        fun isSmall(view: Any) = small.isInstance(getState.invoke(view))
    }

    private fun bind(module: XposedModule, loader: ClassLoader) {
        if (!HookUtils.isIslandLoaderReady(loader)) return
        synchronized(installedClasses) {
            val baseClass = runCatching {
                loader.loadClass(ROOT + "window.content.DynamicIslandBaseContentView")
            }.getOrNull() ?: return
            if (baseClass in installedClasses || installedClasses.size >= 16) return
            runCatching {
                val a = Access(loader)
                module.profiledHook("CameraBracketsHook", a.attached).intercept { chain ->
                    val result = chain.proceed()
                    guarded(module, "attach") { (chain.thisObject as? View)?.let { attach(it, a) } }
                    result
                }
                module.profiledHook("CameraBracketsHook", a.setState).intercept { chain ->
                    val result = chain.proceed()
                    guarded(module, "state") {
                        (chain.thisObject as? View)?.let { owner ->
                            if (a.real.isInstance(owner)) {
                                attach(owner, a)
                                findWindow(owner, a)?.let { w ->
                                    synchronized(overlays) { overlays[w] }?.track(owner)
                                    w.postInvalidateOnAnimation()
                                }
                            }
                        }
                    }
                    result
                }
                installedClasses.add(baseClass)
                log(module) { "enabled (wrapIsland=${a.canWrapBig})" }
            }.onFailure { error ->
                logFailureOnce(module, "bind-${System.identityHashCode(baseClass)}") {
                    "camera brackets unsupported on this build: ${error.message}"
                }
            }
        }
    }

    private inline fun guarded(module: XposedModule, key: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            logFailureOnce(module, key) { "$key failed: ${error.message}" }
        }
    }

    private fun findWindow(owner: View, a: Access): ViewGroup? {
        var p = owner.parent
        while (p != null) {
            if (a.windowClass.isInstance(p)) return p as? ViewGroup
            p = (p as? View)?.parent ?: break
        }
        return null
    }

    private fun attach(owner: View, a: Access) {
        val window = findWindow(owner, a) ?: return
        val drawable = synchronized(overlays) {
            overlays[window] ?: run {
                if (overlays.size >= MAX_WINDOWS) return
                BracketsDrawable(window, a).also {
                    overlays[window] = it
                    window.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) = Unit
                        override fun onViewDetachedFromWindow(v: View) {
                            synchronized(overlays) { overlays.remove(v) }?.let { d ->
                                v.overlay.remove(d)
                            }
                            v.removeOnAttachStateChangeListener(this)
                        }
                    })
                    window.overlay.add(it)
                }
            }
        }
        drawable.track(owner)
        drawable.setBounds(0, 0, window.width, window.height)
        window.postInvalidateOnAnimation()
    }

    private class BracketsDrawable(window: View, private val a: Access) : Drawable() {
        private val windowRef = WeakReference(window)
        private val tracked = WeakHashMap<View, Boolean>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val arc = RectF()
        private val cur = RectF()
        private val target = RectF()
        private val tmp = Rect()
        private val loc = IntArray(2)
        private val winLoc = IntArray(2)
        private var haveCur = false
        private var curColor = Color.WHITE
        private var lastFrame = 0L
        private var alphaValue = 255

        fun track(owner: View) { synchronized(tracked) { tracked[owner] = true } }

        override fun onBoundsChange(bounds: Rect) { haveCur = false }

        override fun draw(canvas: Canvas) {
            if (!enabled) { haveCur = false; return }
            val window = windowRef.get() ?: return
            if (!window.isAttachedToWindow) return
            val insets = window.rootWindowInsets
            // Hide with the status bar (fullscreen apps, camera, video…).
            if (insets != null && !insets.isVisible(WindowInsets.Type.statusBars())) return
            val showing = resolveTarget(window, insets)
            val idle = !showing
            if (idle && !always) { haveCur = false; return }
            if (idle) applyBurnInShift(window)

            // Frame-rate independent easing: glides between camera ⇄ island, tracks animations.
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1L, 64L).toFloat()
            lastFrame = now
            val k = 1f - exp(-dt / 45f)
            if (!haveCur) { cur.set(target); curColor = desiredColor(); haveCur = true }
            else {
                cur.left += (target.left - cur.left) * k
                cur.top += (target.top - cur.top) * k
                cur.right += (target.right - cur.right) * k
                cur.bottom += (target.bottom - cur.bottom) * k
                curColor = lerpColor(curColor, desiredColor(), k.coerceAtLeast(0.2f))
            }
            val settling = abs(cur.left - target.left) + abs(cur.top - target.top) +
                abs(cur.right - target.right) + abs(cur.bottom - target.bottom) > 0.5f ||
                curColor != desiredColor()

            val density = window.resources.displayMetrics.density
            val stroke = CameraBracketsConfig.STROKE_DP * density
            val h = cur.height()
            if (h > 0f && cur.width() > 0f) {
                paint.strokeWidth = stroke.coerceAtMost(h * 0.25f)
                paint.color = curColor
                val dim = if (idle && burnIn) CameraBracketsConfig.IDLE_ALPHA else 1f
                paint.alpha = (alphaValue * dim * Color.alpha(curColor) / 255f).roundToInt().coerceIn(0, 255)
                val sweep = CameraBracketsConfig.SWEEP_DEG
                val radius = h / 2f + CameraBracketsConfig.GAP_DP * density + paint.strokeWidth / 2f
                val cy = cur.centerY()
                // Arc centres sit on the shape's left/right half-circle centres so the
                // brackets hug a pill (island) and a circle (camera) alike.
                val inset = h / 2f
                val lcx = cur.left + inset
                val rcx = cur.right - inset
                arc.set(lcx - radius, cy - radius, lcx + radius, cy + radius)
                canvas.drawArc(arc, 180f - sweep / 2f, sweep, false, paint)
                arc.set(rcx - radius, cy - radius, rcx + radius, cy + radius)
                canvas.drawArc(arc, -sweep / 2f, sweep, false, paint)
            }
            if (settling) window.postInvalidateOnAnimation()
            else {
                lastFrame = 0L
                // Idle brackets are static: wake up once per interval to apply the next shift.
                if (idle && always && burnIn) {
                    window.postInvalidateDelayed(CameraBracketsConfig.SHIFT_INTERVAL_MS)
                }
            }
        }

        /** Walks the idle brackets over a small 3x3 grid, one step per interval. */
        private fun applyBurnInShift(window: View) {
            if (!burnIn) return
            val step = ((SystemClock.elapsedRealtime() / CameraBracketsConfig.SHIFT_INTERVAL_MS) % SHIFT_PATTERN.size).toInt()
            val (gx, gy) = SHIFT_PATTERN[step]
            val unit = CameraBracketsConfig.SHIFT_MAX_DP * window.resources.displayMetrics.density
            target.offset(gx * unit, gy * unit)
        }

        private fun desiredColor(): Int =
            runCatching { StatusBarTextColorHook.getReadableTint() }.getOrDefault(Color.WHITE)

        private fun lerpColor(from: Int, to: Int, t: Float): Int {
            if (from == to) return to
            fun ch(f: Int, g: Int) = (f + (g - f) * t).roundToInt().coerceIn(0, 255)
            val out = Color.argb(
                ch(Color.alpha(from), Color.alpha(to)), ch(Color.red(from), Color.red(to)),
                ch(Color.green(from), Color.green(to)), ch(Color.blue(from), Color.blue(to)),
            )
            // Snap when imperceptible so the settle loop ends.
            return if (abs(Color.red(out) - Color.red(to)) +
                abs(Color.green(out) - Color.green(to)) + abs(Color.blue(out) - Color.blue(to)) < 6) to else out
        }

        /**
         * Fills [target] and returns true when an island is showing (big island, or small pill +
         * camera). Otherwise [target] is the camera cutout and false is returned.
         */
        private fun resolveTarget(window: View, insets: WindowInsets?): Boolean {
            val cutout = insets?.displayCutout?.let { c ->
                c.boundingRectTop.takeIf { !it.isEmpty }
                    ?: c.boundingRects.firstOrNull { it.top <= 0 && !it.isEmpty }
            }
            window.getLocationOnScreen(winLoc)
            var cameraKnown = false
            if (cutout != null) { target.set(cutout); cameraKnown = true }
            var showing = false
            val views = synchronized(tracked) { tracked.keys.toList() }
            for (v in views) {
                if (!v.isAttachedToWindow) continue
                runCatching {
                    if (a.isBig(v) && a.canWrapBig) {
                        val bg = a.background!!.invoke(v) as? View ?: return@runCatching
                        val content = a.bigView?.invoke(v) as? View
                        if (content != null && (content.visibility != View.VISIBLE || content.alpha <= 0f)) return@runCatching
                        bg.getLocationOnScreen(loc)
                        val l = (a.left!!.invoke(bg) as Number).toFloat() + loc[0] - winLoc[0]
                        val t = (a.top!!.invoke(bg) as Number).toFloat() + loc[1] - winLoc[1]
                        val r = (a.right!!.invoke(bg) as Number).toFloat() + loc[0] - winLoc[0]
                        val b = (a.bottom!!.invoke(bg) as Number).toFloat() + loc[1] - winLoc[1]
                        if (r - l > 0f && b - t > 0f) { target.set(l, t, r, b); showing = true }
                    } else if (a.isSmall(v)) {
                        val sv = a.smallView?.invoke(v) as? View ?: return@runCatching
                        if (!sv.isShown || sv.alpha <= 0f || sv.width == 0) return@runCatching
                        sv.getGlobalVisibleRect(tmp)
                        tmp.offset(-winLoc[0], -winLoc[1])
                        if (showing || cameraKnown) {
                            target.union(tmp.left.toFloat(), tmp.top.toFloat(), tmp.right.toFloat(), tmp.bottom.toFloat())
                        } else target.set(tmp)
                        showing = true
                    }
                }
            }
            // No island and no cutout: nothing sensible to draw around.
            if (!showing && !cameraKnown) { target.setEmpty() }
            return showing
        }

        override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
