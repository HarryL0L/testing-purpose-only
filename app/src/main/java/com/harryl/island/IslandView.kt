package com.harryl.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the island with a Canvas and morphs it between the camera-hole pill, the compact pill
 * and the expanded card using springs. Everything is in screen coordinates; the host keeps the
 * overlay window just large enough to contain the current and target shapes.
 *
 * Position and size come from [IslandLayout], computed from the camera cutout Android reports.
 */
class IslandView(
    context: Context,
    private val prefs: Prefs,
    private val host: Host,
    private val listener: Listener,
) : View(context) {

    interface Host {
        fun geometry(): IslandGeometry
        fun attach()
        fun detach()
        fun updateWindow(rect: Rect)
    }

    interface Listener {
        fun onTapCompact(content: IslandContent)
        fun onTapExpanded(content: IslandContent)
        fun onTapCompanion(content: IslandContent)
        fun onSwipeUp(content: IslandContent)
        fun onMediaControl(action: Int)
        fun onOutsideTouch()
    }

    private enum class Hit { NONE, PILL, CARD, COMPANION, PREV, PLAY, NEXT }

    private class Shape(val l: Float, val t: Float, val w: Float, val h: Float, val r: Float) {
        val right get() = l + w
        val bottom get() = t + h
        fun same(o: Shape?) = o != null && abs(l - o.l) < 0.5f && abs(t - o.t) < 0.5f &&
            abs(w - o.w) < 0.5f && abs(h - o.h) < 0.5f && abs(r - o.r) < 0.5f
    }

    // ---- Dimensions ---------------------------------------------------------------------------

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dp(v: Int) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val padV = dp(6)
    private val bubblePad = dp(10)
    private val gap = dp(8)
    private val compGap = dp(6)
    private val bodyPad = dp(14)
    private val expandedRadius = dp(26)
    private val windowEdge = dp(4)

    // ---- Geometry (screen coordinates, from IslandLayout) -------------------------------------

    private var layoutKey: Any? = null
    private var screenW = 1
    private var screenH = 1
    private val hole = RectF()
    private var side = Side.LEFT
    private var pillH = dp(32)
    private var bandTop = 0f
    private var padH = dp(10)
    private var cardL = 0f
    private var cardR = 0f
    private var hasCamera = false
    private var clearL = 0f
    private var clearR = 0f

    // ---- State --------------------------------------------------------------------------------

    private var primary: IslandContent? = null
    private var companion: IslandContent? = null
    /** Last companion drawn; kept so the bubble can fade out after [companion] is cleared. */
    private var drawnCompanion: IslandContent? = null
    private var expanded = false
    private var attached = false
    private var hiding = false
    private var identity: String? = null
    private var target: Shape? = null
    private var targetCompanionW = -1f
    private var noticeLayout: StaticLayout? = null
    private var noticeLayoutKey: String? = null
    private var noticeLayoutWidth = 0

    private val winRect = Rect()

    // ---- Springs ------------------------------------------------------------------------------

    private inner class Spring(initial: Float, stiffness: Float, damping: Float, minChange: Float) {
        private val holder = FloatValueHolder(initial)
        private val anim = SpringAnimation(holder)
            .setSpring(SpringForce(initial).setStiffness(stiffness).setDampingRatio(damping))
            .setMinimumVisibleChange(minChange)
            .apply {
                addUpdateListener { _, _, _ -> invalidate() }
                addEndListener { _, _, _, _ -> onSpringEnd() }
            }
        val value: Float get() = holder.value
        val running: Boolean get() = anim.isRunning
        fun to(target: Float) {
            if (!anim.isRunning && abs(holder.value - target) < 0.01f) return
            anim.animateToFinalPosition(target)
        }
        fun snap(v: Float) {
            anim.cancel()
            holder.value = v
            anim.spring.finalPosition = v
        }
        fun cancel() = anim.cancel()
    }

    // Material 3 Expressive motion scheme: spatial springs bounce a little, effect springs don't.
    private val sL = Spring(0f, SPATIAL_DEFAULT_STIFFNESS, SPATIAL_DEFAULT_DAMPING, 0.5f)
    private val sT = Spring(0f, SPATIAL_DEFAULT_STIFFNESS, SPATIAL_DEFAULT_DAMPING, 0.5f)
    private val sW = Spring(0f, SPATIAL_DEFAULT_STIFFNESS, SPATIAL_DEFAULT_DAMPING, 0.5f)
    private val sH = Spring(0f, SPATIAL_DEFAULT_STIFFNESS, SPATIAL_DEFAULT_DAMPING, 0.5f)
    private val sR = Spring(0f, SPATIAL_DEFAULT_STIFFNESS, 0.9f, 0.5f)
    private val sCompX = Spring(0f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING, 0.5f)
    private val sCompA = Spring(0f, EFFECTS_DEFAULT_STIFFNESS, 1f, 0.004f)
    private val sContentA = Spring(0f, EFFECTS_SLOW_STIFFNESS, 1f, 0.004f)
    private val sBgA = Spring(0f, EFFECTS_FAST_STIFFNESS, 1f, 0.004f)
    private val sRing = Spring(0f, SPATIAL_SLOW_STIFFNESS, SPATIAL_SLOW_DAMPING, 0.004f)
    private val springs = listOf(sL, sT, sW, sH, sR, sCompX, sCompA, sContentA, sBgA, sRing)

    // ---- Paints -------------------------------------------------------------------------------

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.island_bg) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.island_border)
        style = Paint.Style.STROKE
        strokeWidth = dp(1)
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.island_text_dim)
        textSize = sp(12f)
    }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(22f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3)
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.island_track)
        style = Paint.Style.STROKE
        strokeWidth = dp(3)
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val wavePath = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(4)
    }
    private val clipPath = Path()
    private val rectF = RectF()
    private val tmpRect = RectF()
    private val matrix = Matrix()

    private val colorText = ContextCompat.getColor(context, R.color.island_text)
    private val colorDim = ContextCompat.getColor(context, R.color.island_text_dim)
    private val colorTrack = ContextCompat.getColor(context, R.color.island_track)
    private val colorGood = ContextCompat.getColor(context, R.color.battery_good)
    private val colorMid = ContextCompat.getColor(context, R.color.battery_mid)
    private val colorLow = ContextCompat.getColor(context, R.color.battery_low)
    private val brandAccent = ContextCompat.getColor(context, R.color.island_accent)
    /** Material You: the wallpaper's accent (light tone, for the black island) on Android 12+. */
    private val colorAccent: Int
        get() = if (prefs.materialYou && Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_200) else brandAccent
    private val colorTimer = 0xFFFF9F0A.toInt()

    private val icons = HashMap<Int, Drawable>()
    private fun icon(id: Int): Drawable = icons.getOrPut(id) { AppCompatResources.getDrawable(context, id)!!.mutate() }

    // ---- Ticks & effects ----------------------------------------------------------------------

    private val barLevel = floatArrayOf(0.4f, 0.8f, 0.55f)
    private val barTarget = floatArrayOf(0.7f, 0.3f, 0.9f)
    private var seed = 0x9E3779B9.toInt()
    private var tickScheduled = false
    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            if (!attached || hiding) return
            val delay = tickDelay()
            if (delay < 0) return
            if (primary is IslandContent.Music && !expanded) stepBars()
            invalidate()
            tickScheduled = true
            postDelayed(this, delay)
        }
    }

    private var pulse = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 650
        repeatMode = ValueAnimator.REVERSE
        repeatCount = 5
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    // ---- Touch --------------------------------------------------------------------------------

    private var downX = 0f
    private var downY = 0f
    private var downHit = Hit.NONE
    private var swiped = false
    private val swipeThreshold = dp(28)
    private val touchSlop = dp(12)

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // =====================================================================================
    // Public API
    // =====================================================================================

    fun present(content: IslandContent, companion: IslandContent?, expanded: Boolean) {
        val comp = if (expanded) null else companion
        val base = identityOf(content)
        val newIdentity = base + (if (expanded) "+x" else "")
        val identityChanged = newIdentity != identity
        val baseChanged = base != identity?.removeSuffix("+x")
        identity = newIdentity
        primary = content
        this.companion = comp
        if (comp != null) drawnCompanion = comp
        this.expanded = expanded
        val wasHiding = hiding
        hiding = false

        val newlyAttached = !attached
        if (newlyAttached) {
            relayout()
            val h = holeShape()
            snapShape(h)
            sContentA.snap(0f)
            sBgA.snap(0f)
            sCompA.snap(0f)
            sCompX.snap(companionHiddenX(h))
            sRing.snap(0f)
            target = null
            attached = true
        } else if (identityChanged) {
            sContentA.snap(if (baseChanged) 0f else 0.3f)
        }

        if (identityChanged) {
            pulseAnimator.cancel()
            pulse = 0f
            if (expanded && content is IslandContent.Power && content.charging) pulseAnimator.start()
            if (baseChanged && (content is IslandContent.Device || content is IslandContent.Power)) sRing.snap(0f)
        }

        // Only touch the window and the springs when the shape really changes. Media position,
        // battery and live-activity updates arrive often; re-laying out the window for each of
        // them made the island jitter.
        val t = if (expanded) expandedShape(content) else compactShape(content)
        val compW = comp?.let { companionWidth(it) } ?: -1f
        if (newlyAttached || wasHiding || !t.same(target) || compW != targetCompanionW) {
            target = t
            targetCompanionW = compW
            growWindow(t)
            animateShape(t)
            if (comp != null) {
                sCompA.to(1f)
                sCompX.to(companionShownX(t, comp))
            } else {
                sCompA.to(0f)
                sCompX.to(companionHiddenX(t))
            }
            settleIfIdle()
        }
        if (newlyAttached) host.attach()
        sBgA.to(1f)
        sContentA.to(1f)
        sRing.to(1f)
        scheduleTick()
        invalidate()
    }

    fun dismiss() {
        if (!attached || hiding) return
        hiding = true
        target = null
        pulseAnimator.cancel()
        val h = holeShape()
        growWindow(h)
        animateShape(h)
        sContentA.to(0f)
        sBgA.to(0f)
        sCompA.to(0f)
        sCompX.to(companionHiddenX(h))
        invalidate()
        settleIfIdle()
    }

    /** Re-fits the island to the screen and camera the host reports. Cheap no-op when nothing changed. */
    fun relayout() {
        val g = host.geometry()
        val key = listOf<Any?>(g, prefs.anchor, prefs.offsetX, prefs.offsetY)
        if (key == layoutKey) return
        layoutKey = key
        val l = IslandLayout.compute(
            screenW = g.screenW,
            screenH = g.screenH,
            density = density,
            camera = g.camera,
            statusBarH = g.statusBarH,
            offsetXdp = prefs.offsetX,
            offsetYdp = prefs.offsetY,
            forceCenter = prefs.anchor == Prefs.ANCHOR_CENTER,
            keepOut = if (g.source == IslandGeometry.Source.ESTIMATED) g.reported else null,
        )
        screenW = l.screenW
        screenH = l.screenH
        hole.set(l.holeL, l.holeT, l.holeR, l.holeB)
        side = l.side
        pillH = l.pillH
        bandTop = l.bandTop
        padH = l.padH
        cardL = l.cardL
        cardR = l.cardR
        hasCamera = l.followsCamera
        clearL = l.clearL
        clearR = l.clearR
        noticeLayout = null

        val p = primary
        if (attached && p != null && !hiding) {
            val t = if (expanded) expandedShape(p) else compactShape(p)
            target = t
            growWindow(t)
            animateShape(t)
            companion?.let { sCompX.to(companionShownX(t, it)) }
            settleIfIdle()
        }
    }

    fun cancelAll() {
        removeCallbacks(tick)
        tickScheduled = false
        pulseAnimator.cancel()
        springs.forEach { it.cancel() }
        attached = false
        hiding = false
    }

    // =====================================================================================
    // Layout
    // =====================================================================================

    private fun holeShape(): Shape {
        val w = hole.width() + 2 * padH
        return Shape(hole.left - padH, bandTop, w, pillH, pillH / 2)
    }

    private fun compactShape(c: IslandContent): Shape {
        val leftW = compactLeftWidth(c)
        val rightW = compactRightWidth(c)
        val w: Float
        val l: Float
        when (side) {
            Side.LEFT -> {
                w = padH + hole.width() + gap + leftW + gap + rightW + padH
                l = hole.left - padH
            }
            Side.RIGHT -> {
                w = padH + leftW + gap + rightW + gap + hole.width() + padH
                l = hole.right + padH - w
            }
            Side.CENTER -> {
                val half = max(leftW, rightW) + gap + (if (hasCamera) padH else padV)
                w = hole.width() + 2 * half
                l = hole.centerX() - w / 2
            }
        }
        return Shape(l, bandTop, w, pillH, pillH / 2)
    }

    private fun expandedShape(c: IslandContent): Shape {
        val bodyH = bodyHeight(c)
        return Shape(cardL, bandTop, cardR - cardL, pillH + bodyH, min(expandedRadius, (pillH + bodyH) / 2))
    }

    private fun compactIconSize() = pillH - 2 * padV

    private fun compactLeftWidth(c: IslandContent): Float = when (c) {
        is IslandContent.Music, is IslandContent.Notice, is IslandContent.Live -> compactIconSize()
        else -> dp(18)
    }

    private fun compactRightWidth(c: IslandContent): Float = when (c) {
        is IslandContent.Music -> dp(16)
        is IslandContent.Notice -> min(dp(110), smallPaint.measureText(c.title))
        is IslandContent.Live -> min(dp(110), smallPaint.measureText(Clock.widthTemplate(liveCompactText(c))))
        is IslandContent.Device -> smallPaint.measureText(deviceCompactText(c))
        is IslandContent.Power -> smallPaint.measureText(percentText(c.level))
    }

    private fun bodyHeight(c: IslandContent): Float = when (c) {
        is IslandContent.Music -> dp(10 + 52 + 14 + 4 + 14)
        is IslandContent.Device, is IslandContent.Power -> dp(10 + 52 + 12)
        is IslandContent.Live -> dp(10 + 44 + 12) + (if (c.progress >= 0) dp(14) else 0f)
        is IslandContent.Notice -> {
            val layout = noticeLayout(c)
            val textH = dp(20) + layout.height
            dp(10) + max(dp(40), textH) + dp(12)
        }
    }

    private fun noticeLayout(c: IslandContent.Notice): StaticLayout {
        val width = max(1, (expandedBodyWidth() - dp(40) - dp(12)).roundToInt())
        val key = c.key + c.text.hashCode()
        noticeLayout?.let { if (noticeLayoutKey == key && noticeLayoutWidth == width) return it }
        val layout = StaticLayout.Builder.obtain(c.text, 0, c.text.length, subPaint, width)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        noticeLayout = layout
        noticeLayoutKey = key
        noticeLayoutWidth = width
        return layout
    }

    private fun expandedBodyWidth(): Float = (cardR - cardL) - 2 * bodyPad

    private fun companionWidth(c: IslandContent): Float = when (c) {
        is IslandContent.Device -> bubblePad + dp(14) + (if (c.battery >= 0) dp(4) + smallPaint.measureText(percentText(c.battery)) else 0f) + bubblePad
        else -> pillH
    }

    private fun companionShownX(pill: Shape, c: IslandContent): Float =
        if (side == Side.RIGHT) pill.l - compGap - companionWidth(c) else pill.right + compGap

    private fun companionHiddenX(pill: Shape): Float {
        val c = companion ?: drawnCompanion
        val w = if (c != null) companionWidth(c) else pillH
        return if (side == Side.RIGHT) pill.l else pill.right - w
    }

    private fun snapShape(s: Shape) {
        sL.snap(s.l); sT.snap(s.t); sW.snap(s.w); sH.snap(s.h); sR.snap(s.r)
    }

    private fun animateShape(s: Shape) {
        sL.to(s.l); sT.to(s.t); sW.to(s.w); sH.to(s.h); sR.to(s.r)
    }

    /** Make the window big enough for both where we are and where we are going (plus spring overshoot). */
    private fun growWindow(target: Shape) {
        val r = RectF(sL.value, sT.value, sL.value + sW.value, sT.value + sH.value)
        r.union(target.l, target.t, target.right, target.bottom)
        val comp = companion ?: drawnCompanion
        val compW = if (comp != null) companionWidth(comp) else pillH
        r.union(sCompX.value, sT.value, sCompX.value + compW, sT.value + pillH)
        val cx = if (companion != null) companionShownX(target, companion!!) else companionHiddenX(target)
        r.union(cx, target.t, cx + compW, target.t + pillH)
        setWindow(r, grow = true)
    }

    private fun tightenWindow() {
        val r = RectF(sL.value, sT.value, sL.value + sW.value, sT.value + sH.value)
        val comp = companion
        if (comp != null) r.union(sCompX.value, sT.value, sCompX.value + companionWidth(comp), sT.value + pillH)
        setWindow(r, grow = false)
    }

    /**
     * The edge on the camera side keeps a constant inset, so on a left (or right) hole punch the
     * window origin never moves while the island morphs; only the far edge and the bottom change.
     */
    private fun setWindow(r: RectF, grow: Boolean) {
        val overshoot = if (grow) dp(18) else 0f
        val left = r.left - windowEdge - (if (side == Side.LEFT) 0f else overshoot)
        val right = r.right + windowEdge + (if (side == Side.RIGHT) 0f else overshoot)
        val next = Rect(
            left.roundToInt().coerceAtLeast(0),
            (r.top - windowEdge).roundToInt().coerceAtLeast(0),
            right.roundToInt().coerceAtMost(screenW),
            (r.bottom + windowEdge + (if (grow) dp(24) else 0f)).roundToInt().coerceAtMost(screenH),
        )
        if (next == winRect) return
        winRect.set(next)
        host.updateWindow(next)
        invalidate()
    }

    /** If nothing needed to animate, run the end-of-animation bookkeeping right away. */
    private fun settleIfIdle() {
        post { if (springs.none { it.running }) onSpringEnd() }
    }

    private fun onSpringEnd() {
        if (springs.any { it.running }) return
        if (hiding) {
            hiding = false
            attached = false
            removeCallbacks(tick)
            tickScheduled = false
            host.detach()
            primary = null
            companion = null
            drawnCompanion = null
            identity = null
            target = null
        } else if (attached) {
            if (companion == null) drawnCompanion = null
            tightenWindow()
        }
    }

    private fun identityOf(c: IslandContent): String = when (c) {
        is IslandContent.Music -> "music:" + c.trackKey
        is IslandContent.Live -> "live:" + c.key
        is IslandContent.Notice -> "notice:" + c.key
        is IslandContent.Device -> "device:" + c.address + ":" + c.connected
        is IslandContent.Power -> "power:" + c.charging
    }

    // =====================================================================================
    // Ticks
    // =====================================================================================

    private fun tickDelay(): Long = when (val p = primary) {
        is IslandContent.Music -> when {
            !p.playing -> -1
            // 30 fps only while the card is open and the wavy progress is moving.
            expanded -> 33
            prefs.visualizer -> 90
            else -> -1
        }
        // Wake exactly on the next second boundary of the stopwatch / timer.
        is IslandContent.Live -> if (p.chronoBase > 0) 1000 - (abs(System.currentTimeMillis() - p.chronoBase) % 1000) + 5 else -1
        else -> -1
    }

    private fun scheduleTick() {
        if (tickScheduled) return
        if (tickDelay() < 0) return
        tickScheduled = true
        postDelayed(tick, 16)
    }

    private fun stepBars() {
        for (i in barLevel.indices) {
            val diff = barTarget[i] - barLevel[i]
            barLevel[i] += diff * 0.38f
            if (abs(diff) < 0.06f) barTarget[i] = 0.2f + nextRandom() * 0.8f
        }
    }

    private fun nextRandom(): Float {
        seed = seed * 1664525 + 1013904223
        return ((seed ushr 8) and 0xFFFF) / 65535f
    }

    // =====================================================================================
    // Drawing
    // =====================================================================================

    override fun onDraw(canvas: Canvas) {
        if (!attached) return
        val p = primary ?: return
        canvas.translate(-winRect.left.toFloat(), -winRect.top.toFloat())

        val bgA = sBgA.value.coerceIn(0f, 1f)
        val contentA = (sContentA.value.coerceIn(0f, 1f) * bgA)

        drawnCompanion?.let { drawCompanion(canvas, it, bgA) }

        val l = sL.value
        val t = sT.value
        val w = sW.value
        val h = sH.value
        val r = sR.value
        rectF.set(l, t, l + w, t + h)
        bgPaint.alpha = (255 * bgA).roundToInt()
        borderPaint.alpha = (26 * bgA).roundToInt()
        canvas.drawRoundRect(rectF, r, r, bgPaint)
        canvas.drawRoundRect(rectF, r, r, borderPaint)

        if (contentA <= 0.01f) return
        clipPath.reset()
        clipPath.addRoundRect(rectF, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        if (expanded) drawExpanded(canvas, p, l, t, w, contentA) else drawCompact(canvas, p, t, h, contentA)
        canvas.restore()
    }

    // ---- Compact ------------------------------------------------------------------------------

    private fun drawCompact(canvas: Canvas, c: IslandContent, t: Float, h: Float, a: Float) {
        val cy = t + h / 2
        val leftW = compactLeftWidth(c)
        val rightW = compactRightWidth(c)
        val leftX: Float
        val rightX: Float
        when (side) {
            Side.LEFT -> { leftX = hole.right + gap; rightX = leftX + leftW + gap }
            Side.RIGHT -> { rightX = hole.left - gap - rightW; leftX = rightX - gap - leftW }
            Side.CENTER -> { leftX = hole.left - gap - leftW; rightX = hole.right + gap }
        }
        when (c) {
            is IslandContent.Music -> {
                drawArt(canvas, c.art, leftX, cy - leftW / 2, leftW, leftW / 2, a, c.accent)
                drawBars(canvas, rightX, cy, rightW, dp(16), c.accent, a, c.playing)
            }
            is IslandContent.Live -> {
                drawArt(canvas, c.icon, leftX, cy - leftW / 2, leftW, leftW / 2, a, liveColor(c), liveIcon(c))
                drawText(canvas, liveCompactText(c), rightX, cy, rightW, smallPaint, liveColor(c), a)
            }
            is IslandContent.Notice -> {
                drawArt(canvas, c.icon, leftX, cy - leftW / 2, leftW, leftW / 2, a, colorAccent)
                drawText(canvas, c.title, rightX, cy, rightW, smallPaint, colorText, a)
            }
            is IslandContent.Device -> {
                val tint = if (c.connected) batteryColor(c.battery, colorGood) else colorDim
                drawIcon(canvas, deviceIcon(c.kind), leftX + leftW / 2, cy, dp(18), tint, a)
                drawText(canvas, deviceCompactText(c), rightX, cy, rightW, smallPaint, colorText, a)
            }
            is IslandContent.Power -> {
                drawIcon(canvas, R.drawable.ic_bolt, leftX + leftW / 2, cy, dp(18), if (c.charging) colorGood else colorDim, a)
                drawText(canvas, percentText(c.level), rightX, cy, rightW, smallPaint, colorText, a)
            }
        }
    }

    // ---- Expanded -----------------------------------------------------------------------------

    private fun drawExpanded(canvas: Canvas, c: IslandContent, l: Float, t: Float, w: Float, a: Float) {
        // Header label in the band beside the camera.
        val label = when (c) {
            is IslandContent.Music -> context.getString(if (c.playing) R.string.now_playing else R.string.paused)
            is IslandContent.Live -> c.appName
            is IslandContent.Notice -> c.appName
            is IslandContent.Device -> context.getString(if (c.connected) R.string.connected else R.string.disconnected)
            is IslandContent.Power -> context.getString(if (c.charging) R.string.charging else R.string.charger_removed)
        }
        val bandCy = bandTop + pillH / 2
        when (side) {
            Side.LEFT, Side.CENTER -> {
                // Text never starts inside the area Android says the camera is in.
                val lx = if (hasCamera) clearR + gap else l + bodyPad
                drawText(canvas, label, lx, bandCy, l + w - bodyPad - lx, subPaint, colorDim, a)
            }
            Side.RIGHT -> {
                val tw = min(subPaint.measureText(label), clearL - gap - (l + bodyPad))
                drawText(canvas, label, clearL - gap - tw, bandCy, tw, subPaint, colorDim, a)
            }
        }

        val bodyTop = t + pillH
        val bl = l + bodyPad
        val br = l + w - bodyPad
        when (c) {
            is IslandContent.Music -> drawMusicBody(canvas, c, bl, br, bodyTop, a)
            is IslandContent.Live -> drawLiveBody(canvas, c, bl, br, bodyTop, a)
            is IslandContent.Device -> drawRingBody(
                canvas, deviceIcon(c.kind), c.battery, if (c.connected) batteryColor(c.battery, colorGood) else colorDim,
                c.name, deviceSubText(c), bl, br, bodyTop, a, 1f,
            )
            is IslandContent.Power -> drawRingBody(
                canvas, R.drawable.ic_bolt, c.level, if (c.charging) colorGood else colorDim,
                context.getString(if (c.charging) R.string.charging else R.string.charger_removed),
                if (c.level >= 0) context.getString(R.string.battery_percent, c.level) else "",
                bl, br, bodyTop, a, 1f + 0.12f * pulse,
            )
            is IslandContent.Notice -> drawNoticeBody(canvas, c, bl, br, bodyTop, a)
        }
    }

    private fun drawMusicBody(canvas: Canvas, m: IslandContent.Music, bl: Float, br: Float, top: Float, a: Float) {
        val art = dp(52)
        val artTop = top + dp(10)
        drawArt(canvas, m.art, bl, artTop, art, dp(10), a, m.accent)

        val btn = dp(36)
        val ctrlLeft = br - 3 * btn
        val ctrlCy = artTop + art / 2
        drawIcon(canvas, R.drawable.ic_skip_previous, ctrlLeft + btn / 2, ctrlCy, dp(24), colorText, a)
        drawIcon(canvas, if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, ctrlLeft + btn * 1.5f, ctrlCy, dp(28), colorText, a)
        drawIcon(canvas, R.drawable.ic_skip_next, ctrlLeft + btn * 2.5f, ctrlCy, dp(24), colorText, a)

        val tx = bl + art + dp(12)
        val tw = ctrlLeft - dp(8) - tx
        drawText(canvas, m.title, tx, artTop + dp(16), tw, titlePaint, colorText, a)
        drawText(canvas, m.artist, tx, artTop + dp(36), tw, subPaint, colorDim, a)

        val progress = if (m.duration > 0) (m.positionNow().toFloat() / m.duration).coerceIn(0f, 1f) else 0f
        drawProgress(canvas, bl, br, artTop + art + dp(14), progress, m.accent, a, wavy = m.playing)
    }

    private fun drawLiveBody(canvas: Canvas, c: IslandContent.Live, bl: Float, br: Float, top: Float, a: Float) {
        val size = dp(44)
        val iconTop = top + dp(10)
        val color = liveColor(c)
        drawArt(canvas, c.icon, bl, iconTop, size, dp(10), a, color, liveIcon(c))

        val big = c.chronoText() ?: (if (c.progress >= 0) "${c.progress}%" else null)
        var textRight = br
        if (big != null) {
            val bw = bigPaint.measureText(Clock.widthTemplate(big))
            drawText(canvas, big, br - bw, iconTop + size / 2, bw, bigPaint, color, a)
            textRight = br - bw - dp(10)
        }
        val tx = bl + size + dp(12)
        val tw = textRight - tx
        drawText(canvas, c.title, tx, iconTop + dp(13), tw, titlePaint, colorText, a)
        drawText(canvas, c.text, tx, iconTop + dp(32), tw, subPaint, colorDim, a)
        if (c.progress >= 0) drawProgress(canvas, bl, br, iconTop + size + dp(12), c.progress / 100f, color, a)
    }

    /**
     * Material 3 Expressive progress: the active part is a gently travelling wave while [wavy],
     * then a small gap and a flat track to the end, which carries a stop dot.
     */
    private fun drawProgress(
        canvas: Canvas, bl: Float, br: Float, y: Float, progress: Float, color: Int, a: Float, wavy: Boolean = false,
    ) {
        val cy = y + dp(2)
        val cap = dp(2)
        val activeEnd = bl + (br - bl) * progress.coerceIn(0f, 1f)
        val trackStart = min(br, activeEnd + dp(6))

        linePaint.color = colorTrack
        linePaint.alpha = (Color.alpha(colorTrack) * a).roundToInt()
        if (br - cap > trackStart + cap) canvas.drawLine(trackStart + cap, cy, br - cap, cy, linePaint)
        fillPaint.color = color
        fillPaint.alpha = (255 * a).roundToInt()
        canvas.drawCircle(br - cap, cy, dp(2), fillPaint)

        if (activeEnd - bl < 2 * cap) return
        linePaint.color = color
        linePaint.alpha = (255 * a).roundToInt()
        if (!wavy) {
            canvas.drawLine(bl + cap, cy, activeEnd - cap, cy, linePaint)
            return
        }
        val amplitude = dp(1.8f)
        val wavelength = dp(22)
        val phase = (SystemClock.uptimeMillis() % WAVE_PERIOD_MS) / WAVE_PERIOD_MS.toFloat() * TWO_PI
        wavePath.rewind()
        var x = bl + cap
        val end = activeEnd - cap
        val step = dp(1.5f)
        wavePath.moveTo(x, cy + amplitude * sin((x - bl) / wavelength * TWO_PI - phase))
        while (x < end) {
            x = min(end, x + step)
            wavePath.lineTo(x, cy + amplitude * sin((x - bl) / wavelength * TWO_PI - phase))
        }
        canvas.drawPath(wavePath, linePaint)
    }

    private fun drawRingBody(
        canvas: Canvas, iconId: Int, level: Int, tint: Int, title: String, sub: String,
        bl: Float, br: Float, top: Float, a: Float, iconScale: Float,
    ) {
        val size = dp(52)
        val cx = bl + size / 2
        val cy = top + dp(10) + size / 2
        val radius = size / 2 - dp(2)
        tmpRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        trackPaint.alpha = (51 * a).roundToInt()
        canvas.drawOval(tmpRect, trackPaint)
        if (level >= 0) {
            ringPaint.color = tint
            ringPaint.alpha = (255 * a).roundToInt()
            val sweep = 360f * (level / 100f) * sRing.value.coerceIn(0f, 1f)
            if (sweep > 0.5f) canvas.drawArc(tmpRect, -90f, sweep, false, ringPaint)
        }
        drawIcon(canvas, iconId, cx, cy, dp(26) * iconScale, tint, a)

        var textRight = br
        if (level >= 0) {
            val pct = percentText(level)
            val pw = bigPaint.measureText(pct)
            drawText(canvas, pct, br - pw, cy, pw, bigPaint, tint, a)
            textRight = br - pw - dp(10)
        }
        val tx = bl + size + dp(14)
        val tw = textRight - tx
        drawText(canvas, title, tx, cy - dp(10), tw, titlePaint, colorText, a)
        drawText(canvas, sub, tx, cy + dp(10), tw, subPaint, colorDim, a)
    }

    private fun drawNoticeBody(canvas: Canvas, n: IslandContent.Notice, bl: Float, br: Float, top: Float, a: Float) {
        val iconSize = dp(40)
        val iconTop = top + dp(10)
        drawArt(canvas, n.icon, bl, iconTop, iconSize, dp(10), a, colorAccent, R.drawable.ic_bell)
        val tx = bl + iconSize + dp(12)
        val tw = br - tx
        drawText(canvas, n.title, tx, iconTop + dp(9), tw, titlePaint, colorText, a)
        val layout = noticeLayout(n)
        subPaint.color = colorDim
        subPaint.alpha = (255 * a).roundToInt()
        canvas.save()
        canvas.translate(tx, iconTop + dp(20))
        layout.draw(canvas)
        canvas.restore()
        subPaint.alpha = 255
    }

    // ---- Companion bubble ---------------------------------------------------------------------

    private fun drawCompanion(canvas: Canvas, c: IslandContent, bgA: Float) {
        val a = (sCompA.value.coerceIn(0f, 1f) * bgA)
        if (a <= 0.01f && !sCompX.running) return
        val x = sCompX.value
        val w = companionWidth(c)
        val t = sT.value
        rectF.set(x, t, x + w, t + pillH)
        bgPaint.alpha = (255 * a).roundToInt()
        borderPaint.alpha = (26 * a).roundToInt()
        canvas.drawRoundRect(rectF, pillH / 2, pillH / 2, bgPaint)
        canvas.drawRoundRect(rectF, pillH / 2, pillH / 2, borderPaint)
        if (a <= 0.01f) return
        val cy = t + pillH / 2
        when (c) {
            is IslandContent.Device -> {
                val tint = batteryColor(c.battery, colorGood)
                drawIcon(canvas, deviceIcon(c.kind), x + bubblePad + dp(7), cy, dp(14), tint, a)
                if (c.battery >= 0) {
                    val tx = x + bubblePad + dp(14) + dp(4)
                    drawText(canvas, percentText(c.battery), tx, cy, x + w - bubblePad - tx, smallPaint, colorText, a)
                }
            }
            is IslandContent.Music -> drawArt(canvas, c.art, x + padV, t + padV, pillH - 2 * padV, (pillH - 2 * padV) / 2, a, c.accent)
            else -> Unit
        }
    }

    // ---- Primitives ---------------------------------------------------------------------------

    private fun drawArt(
        canvas: Canvas, bmp: Bitmap?, x: Float, y: Float, size: Float, radius: Float, a: Float,
        fallback: Int, fallbackIcon: Int = R.drawable.ic_music_note,
    ) {
        tmpRect.set(x, y, x + size, y + size)
        if (bmp != null && !bmp.isRecycled) {
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = max(size / bmp.width, size / bmp.height)
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate(x - (bmp.width * scale - size) / 2, y - (bmp.height * scale - size) / 2)
            shader.setLocalMatrix(matrix)
            bitmapPaint.shader = shader
            bitmapPaint.alpha = (255 * a).roundToInt()
            canvas.drawRoundRect(tmpRect, radius, radius, bitmapPaint)
            bitmapPaint.shader = null
        } else {
            fillPaint.color = fallback
            fillPaint.alpha = (255 * a).roundToInt()
            canvas.drawRoundRect(tmpRect, radius, radius, fillPaint)
            drawIcon(canvas, fallbackIcon, x + size / 2, y + size / 2, size * 0.6f, colorText, a)
        }
    }

    private fun drawIcon(canvas: Canvas, id: Int, cx: Float, cy: Float, size: Float, tint: Int, a: Float) {
        val d = icon(id)
        d.setTint(tint)
        d.alpha = (255 * a).roundToInt()
        val half = (size / 2).roundToInt()
        d.setBounds(cx.roundToInt() - half, cy.roundToInt() - half, cx.roundToInt() + half, cy.roundToInt() + half)
        d.draw(canvas)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, cy: Float, maxW: Float, paint: TextPaint, color: Int, a: Float) {
        if (text.isEmpty() || maxW <= 0f) return
        val s = TextUtils.ellipsize(text, paint, maxW, TextUtils.TruncateAt.END)
        val fm = paint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2
        paint.color = color
        paint.alpha = (255 * a).roundToInt()
        canvas.drawText(s, 0, s.length, x, baseline, paint)
        paint.alpha = 255
    }

    private fun drawBars(canvas: Canvas, x: Float, cy: Float, w: Float, maxH: Float, color: Int, a: Float, playing: Boolean) {
        val barW = dp(3)
        val step = (w - barW) / 2
        fillPaint.color = color
        fillPaint.alpha = (255 * a).roundToInt()
        for (i in 0 until 3) {
            val level = if (playing && prefs.visualizer) barLevel[i] else 0.25f
            val h = dp(4) + (maxH - dp(4)) * level
            val bx = x + i * step
            tmpRect.set(bx, cy - h / 2, bx + barW, cy + h / 2)
            canvas.drawRoundRect(tmpRect, barW / 2, barW / 2, fillPaint)
        }
    }

    private fun liveCompactText(c: IslandContent.Live): String =
        c.chronoText()
            ?: (if (c.progress >= 0) "${c.progress}%" else null)
            ?: Clock.timeToken(c.title + " " + c.text)
            ?: c.title

    private fun liveColor(c: IslandContent.Live): Int = when (c.kind) {
        LiveKind.CALL -> colorGood
        LiveKind.STOPWATCH, LiveKind.TIMER -> colorTimer
        else -> colorText
    }

    private fun liveIcon(c: IslandContent.Live): Int = when (c.kind) {
        LiveKind.STOPWATCH, LiveKind.TIMER -> R.drawable.ic_timer
        else -> R.drawable.ic_bell
    }

    private fun deviceIcon(kind: DeviceKind): Int = when (kind) {
        DeviceKind.EARBUDS -> R.drawable.ic_earbuds
        DeviceKind.HEADPHONES -> R.drawable.ic_headphones
        DeviceKind.WIRED -> R.drawable.ic_wired
        DeviceKind.WATCH -> R.drawable.ic_watch
        DeviceKind.SPEAKER -> R.drawable.ic_speaker
        DeviceKind.OTHER -> R.drawable.ic_bluetooth
    }

    private fun deviceCompactText(c: IslandContent.Device): String = when {
        !c.connected -> context.getString(R.string.disconnected)
        c.battery >= 0 -> percentText(c.battery)
        else -> context.getString(R.string.connected)
    }

    private fun deviceSubText(c: IslandContent.Device): String = when {
        !c.connected -> context.getString(R.string.disconnected)
        c.battery >= 0 -> context.getString(R.string.connected) + " · " + context.getString(R.string.battery_percent, c.battery)
        else -> context.getString(R.string.connected)
    }

    private fun percentText(level: Int) = if (level >= 0) "$level%" else ""

    private fun batteryColor(level: Int, unknown: Int): Int = when {
        level < 0 -> unknown
        level >= 50 -> colorGood
        level >= 20 -> colorMid
        else -> colorLow
    }

    // =====================================================================================
    // Touch
    // =====================================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            listener.onOutsideTouch()
            return false
        }
        val x = event.x + winRect.left
        val y = event.y + winRect.top
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                swiped = false
                downHit = hitTest(x, y)
                return downHit != Hit.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiped && downHit != Hit.NONE && downY - y > swipeThreshold && abs(x - downX) < swipeThreshold * 2) {
                    swiped = true
                    primary?.let { listener.onSwipeUp(it) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!swiped && downHit != Hit.NONE && abs(x - downX) < touchSlop * 2 && abs(y - downY) < touchSlop * 2) {
                    performClick()
                    click(downHit)
                }
                downHit = Hit.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downHit = Hit.NONE
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitTest(x: Float, y: Float): Hit {
        val l = sL.value
        val t = sT.value
        val w = sW.value
        val h = sH.value
        val p = primary ?: return Hit.NONE
        val comp = companion
        if (comp != null && sCompA.value > 0.5f) {
            val cx = sCompX.value
            if (x in cx..(cx + companionWidth(comp)) && y in t..(t + pillH)) return Hit.COMPANION
        }
        // A little slack around the pill so small targets are easy to hit.
        val slack = dp(6)
        if (x < l - slack || x > l + w + slack || y < t - slack || y > t + h + slack) return Hit.NONE
        if (!expanded) return Hit.PILL
        if (p is IslandContent.Music) {
            val btn = dp(36)
            val ctrlLeft = l + w - bodyPad - 3 * btn
            val cy = t + pillH + dp(10) + dp(26)
            if (x >= ctrlLeft - dp(6) && y >= cy - btn / 2 - dp(6) && y <= cy + btn / 2 + dp(6)) {
                return when {
                    x < ctrlLeft + btn -> Hit.PREV
                    x < ctrlLeft + 2 * btn -> Hit.PLAY
                    else -> Hit.NEXT
                }
            }
        }
        return Hit.CARD
    }

    private fun click(hit: Hit) {
        val p = primary ?: return
        when (hit) {
            Hit.PILL -> listener.onTapCompact(p)
            Hit.CARD -> listener.onTapExpanded(p)
            Hit.COMPANION -> companion?.let { listener.onTapCompanion(it) }
            Hit.PREV -> listener.onMediaControl(MEDIA_PREVIOUS)
            Hit.PLAY -> listener.onMediaControl(MEDIA_PLAY_PAUSE)
            Hit.NEXT -> listener.onMediaControl(MEDIA_NEXT)
            Hit.NONE -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        tickScheduled = false
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleTick()
    }

    companion object {
        const val MEDIA_PREVIOUS = 0
        const val MEDIA_PLAY_PAUSE = 1
        const val MEDIA_NEXT = 2
        // Material 3 Expressive motion tokens (MotionScheme.expressive()).
        private const val SPATIAL_FAST_STIFFNESS = 800f
        private const val SPATIAL_FAST_DAMPING = 0.6f
        private const val SPATIAL_DEFAULT_STIFFNESS = 380f
        private const val SPATIAL_DEFAULT_DAMPING = 0.8f
        private const val SPATIAL_SLOW_STIFFNESS = 200f
        private const val SPATIAL_SLOW_DAMPING = 0.8f
        private const val EFFECTS_FAST_STIFFNESS = 3800f
        private const val EFFECTS_DEFAULT_STIFFNESS = 1600f
        private const val EFFECTS_SLOW_STIFFNESS = 800f

        private const val WAVE_PERIOD_MS = 1600L
        private const val TWO_PI = (2 * Math.PI).toFloat()
    }
}
