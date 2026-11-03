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
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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

/**
 * Draws the island with a Canvas and morphs it between the camera-hole pill, the compact pill
 * and the expanded card using springs. Everything is in screen coordinates; the host keeps the
 * overlay window just large enough to contain the current and target shapes.
 */
class IslandView(
    context: Context,
    private val prefs: Prefs,
    private val host: Host,
    private val listener: Listener,
) : View(context) {

    interface Host {
        fun screenSize(): Point
        fun cutoutRect(): Rect?
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

    private enum class Side { LEFT, CENTER, RIGHT }
    private enum class Hit { NONE, PILL, CARD, COMPANION, PREV, PLAY, NEXT }
    private class Shape(val l: Float, val t: Float, val w: Float, val h: Float, val r: Float) {
        val right get() = l + w
        val bottom get() = t + h
    }

    // ---- Dimensions ---------------------------------------------------------------------------

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dp(v: Int) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val padH = dp(10)
    private val padV = dp(6)
    private val margin = dp(10)
    private val gap = dp(8)
    private val compGap = dp(6)
    private val bodyPad = dp(14)
    private val expandedRadius = dp(26)

    // ---- Geometry (screen coordinates) --------------------------------------------------------

    private var screenW = 1080
    private var screenH = 2400
    private val hole = Rect()
    private var side = Side.LEFT
    private var pillH = dp(36)
    private var bandTop = 0f

    // ---- State --------------------------------------------------------------------------------

    private var primary: IslandContent? = null
    private var companion: IslandContent? = null
    /** Last companion we drew; kept around so the bubble can fade out after [companion] is cleared. */
    private var drawnCompanion: IslandContent? = null
    private var expanded = false
    private var attached = false
    private var hiding = false
    private var identity: String? = null
    private var noticeLayout: StaticLayout? = null
    private var noticeLayoutKey: String? = null
    private var noticeLayoutWidth = 0

    private val winRect = Rect()
    /** Cutout as reported through window insets once attached; more reliable than Display.getCutout(). */
    private var insetCutout: Rect? = null

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

    private val sL = Spring(0f, SHAPE_STIFFNESS, SHAPE_DAMPING, 0.5f)
    private val sT = Spring(0f, SHAPE_STIFFNESS, SHAPE_DAMPING, 0.5f)
    private val sW = Spring(0f, SHAPE_STIFFNESS, SHAPE_DAMPING, 0.5f)
    private val sH = Spring(0f, SHAPE_STIFFNESS, SHAPE_DAMPING, 0.5f)
    private val sR = Spring(0f, SHAPE_STIFFNESS, 0.9f, 0.5f)
    private val sCompX = Spring(0f, SHAPE_STIFFNESS * 0.8f, 0.62f, 0.5f)
    private val sCompA = Spring(0f, 700f, 1f, 0.004f)
    private val sContentA = Spring(0f, 420f, 1f, 0.004f)
    private val sBgA = Spring(0f, 900f, 1f, 0.004f)
    private val sRing = Spring(0f, 120f, 0.85f, 0.004f)
    private val springs = listOf(sL, sT, sW, sH, sR, sCompX, sCompA, sContentA, sBgA, sRing)

    // ---- Paints -------------------------------------------------------------------------------

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.island_bg) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.island_border)
        style = Paint.Style.STROKE
        strokeWidth = dp(1)
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.island_text)
        textSize = sp(14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.island_text_dim)
        textSize = sp(12f)
    }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.island_text)
        textSize = sp(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.island_text)
        textSize = sp(22f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
    private val colorAccent = ContextCompat.getColor(context, R.color.island_accent)

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
            if (delay < 500) stepBars()
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
    private val swipeThreshold = dp(36)
    private val touchSlop = dp(10)

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // =====================================================================================
    // Public API
    // =====================================================================================

    fun present(content: IslandContent, companion: IslandContent?, expanded: Boolean) {
        val base = identityOf(content)
        val newIdentity = base + (if (expanded) "+x" else "")
        val contentChanged = newIdentity != identity
        val baseChanged = base != identity?.removeSuffix("+x")
        identity = newIdentity
        primary = content
        this.companion = if (expanded) null else companion
        if (this.companion != null) drawnCompanion = this.companion
        this.expanded = expanded
        hiding = false

        if (!attached) {
            relayout()
            val h = holeShape()
            snapShape(h)
            sContentA.snap(0f)
            sBgA.snap(0f)
            sCompA.snap(0f)
            sCompX.snap(companionHiddenX(h))
            sRing.snap(0f)
            attached = true
            growWindow()
            host.attach()
        } else if (contentChanged) {
            sContentA.snap(if (baseChanged) 0f else 0.3f)
        }

        if (contentChanged) {
            pulseAnimator.cancel()
            if (expanded && content is IslandContent.Power && content.charging) pulseAnimator.start() else pulse = 0f
            if (baseChanged && (content is IslandContent.Device || content is IslandContent.Power)) sRing.snap(0f)
        }

        val target = if (expanded) expandedShape(content) else compactShape(content)
        growWindow(target)
        animateShape(target)
        sBgA.to(1f)
        sContentA.to(1f)
        sRing.to(1f)

        val comp = this.companion
        if (comp != null) {
            sCompA.to(1f)
            sCompX.to(companionShownX(target, comp))
        } else {
            sCompA.to(0f)
            sCompX.to(companionHiddenX(target))
        }
        scheduleTick()
        invalidate()
        settleIfIdle()
    }

    fun dismiss() {
        if (!attached || hiding) return
        hiding = true
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

    /** If nothing needed to animate, run the end-of-animation bookkeeping right away. */
    private fun settleIfIdle() {
        post { if (springs.none { it.running }) onSpringEnd() }
    }

    fun relayout() {
        val size = host.screenSize()
        screenW = size.x
        screenH = size.y
        val anchor = prefs.anchor
        val cut = if (anchor == Prefs.ANCHOR_AUTO) (insetCutout ?: host.cutoutRect()) else null
        val offX = dp(prefs.offsetX)
        val offY = dp(prefs.offsetY)

        if (cut != null && cut.width() < screenW / 2 && cut.top < screenH / 4) {
            hole.set(cut)
        } else if (anchor == Prefs.ANCHOR_CENTER) {
            hole.set(screenW / 2, dp(10).roundToInt(), screenW / 2, dp(34).roundToInt())
        } else {
            // Realme X7 Max: hole-punch camera in the top-left corner.
            val l = dp(12).roundToInt()
            val t = dp(12).roundToInt()
            val s = dp(24).roundToInt()
            hole.set(l, t, l + s, t + s)
        }
        hole.offset(offX.roundToInt(), offY.roundToInt())

        side = when {
            hole.centerX() < screenW * 0.35f -> Side.LEFT
            hole.centerX() > screenW * 0.65f -> Side.RIGHT
            else -> Side.CENTER
        }
        pillH = max(dp(34), hole.height() + 2 * padV)
        bandTop = max(dp(2), hole.centerY() - pillH / 2)

        val p = primary
        if (attached && p != null && !hiding) {
            val target = if (expanded) expandedShape(p) else compactShape(p)
            growWindow(target)
            animateShape(target)
            companion?.let { sCompX.to(companionShownX(target, it)) }
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
                val half = max(leftW, rightW) + gap + padH
                w = hole.width() + 2 * half
                l = hole.centerX() - w / 2
            }
        }
        return Shape(l, bandTop, w, pillH, pillH / 2)
    }

    private fun expandedShape(c: IslandContent): Shape {
        val bodyH = bodyHeight(c)
        val l: Float
        val r: Float
        when (side) {
            Side.LEFT -> { l = hole.left - padH; r = screenW - margin }
            Side.RIGHT -> { l = margin; r = hole.right + padH }
            Side.CENTER -> { l = margin; r = screenW - margin }
        }
        return Shape(l, bandTop, r - l, pillH + bodyH, min(expandedRadius, (pillH + bodyH) / 2))
    }

    private fun compactIconSize() = pillH - 2 * padV

    private fun compactLeftWidth(c: IslandContent): Float = when (c) {
        is IslandContent.Music -> compactIconSize()
        is IslandContent.Notice -> compactIconSize()
        else -> dp(18)
    }

    private fun compactRightWidth(c: IslandContent): Float = when (c) {
        is IslandContent.Music -> dp(16)
        is IslandContent.Notice -> min(dp(110), smallPaint.measureText(c.title))
        is IslandContent.Device -> smallPaint.measureText(deviceCompactText(c))
        is IslandContent.Power -> smallPaint.measureText(percentText(c.level))
    }

    private fun bodyHeight(c: IslandContent): Float = when (c) {
        is IslandContent.Music -> dp(10 + 52 + 14 + 4 + 14)
        is IslandContent.Device, is IslandContent.Power -> dp(10 + 52 + 12)
        is IslandContent.Notice -> {
            val layout = noticeLayout(c)
            val textH = dp(20) + layout.height
            dp(10) + max(dp(40), textH) + dp(12)
        }
    }

    private fun noticeLayout(c: IslandContent.Notice): StaticLayout {
        val shape = expandedBodyWidth()
        val width = max(1, (shape - dp(40) - dp(12)).roundToInt())
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

    private fun expandedBodyWidth(): Float {
        val w = when (side) {
            Side.LEFT -> screenW - margin - (hole.left - padH)
            Side.RIGHT -> hole.right + padH - margin
            Side.CENTER -> screenW - 2 * margin
        }
        return w - 2 * bodyPad
    }

    private fun companionWidth(c: IslandContent): Float = when (c) {
        is IslandContent.Device -> padH + dp(14) + (if (c.battery >= 0) dp(4) + smallPaint.measureText(percentText(c.battery)) else 0f) + padH
        is IslandContent.Music -> pillH
        else -> pillH
    }

    private fun companionShownX(pill: Shape, c: IslandContent): Float =
        if (side == Side.RIGHT) pill.l - compGap - companionWidth(c) else pill.right + compGap

    private fun companionHiddenX(pill: Shape): Float {
        val c = companion
        val w = if (c != null) companionWidth(c) else pillH
        return if (side == Side.RIGHT) pill.l else pill.right - w
    }

    private fun snapShape(s: Shape) {
        sL.snap(s.l); sT.snap(s.t); sW.snap(s.w); sH.snap(s.h); sR.snap(s.r)
    }

    private fun animateShape(s: Shape) {
        sL.to(s.l); sT.to(s.t); sW.to(s.w); sH.to(s.h); sR.to(s.r)
    }

    /** Make the window big enough for both where we are and where we are going. */
    private fun growWindow(target: Shape? = null) {
        val r = RectF(sL.value, sT.value, sL.value + sW.value, sT.value + sH.value)
        if (target != null) r.union(target.l, target.t, target.right, target.bottom)
        val comp = companion
        val compW = if (comp != null) companionWidth(comp) else pillH
        r.union(sCompX.value, sT.value, sCompX.value + compW, sT.value + pillH)
        if (target != null) {
            val cx = if (comp != null) companionShownX(target, comp) else companionHiddenX(target)
            r.union(cx, target.t, cx + compW, target.t + pillH)
        }
        setWindow(r, dp(14), dp(22))
    }

    private fun tightenWindow() {
        val r = RectF(sL.value, sT.value, sL.value + sW.value, sT.value + sH.value)
        val comp = companion
        if (comp != null) r.union(sCompX.value, sT.value, sCompX.value + companionWidth(comp), sT.value + pillH)
        setWindow(r, dp(3), dp(3))
    }

    private fun setWindow(r: RectF, insetX: Float, insetBottom: Float) {
        val next = Rect(
            (r.left - insetX).roundToInt().coerceAtLeast(0),
            (r.top - dp(2)).roundToInt().coerceAtLeast(0),
            (r.right + insetX).roundToInt().coerceAtMost(screenW),
            (r.bottom + insetBottom).roundToInt().coerceAtMost(screenH),
        )
        if (next == winRect) return
        winRect.set(next)
        host.updateWindow(next)
        invalidate()
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
        } else if (attached) {
            tightenWindow()
        }
    }

    private fun identityOf(c: IslandContent): String = when (c) {
        is IslandContent.Music -> "music:" + c.trackKey
        is IslandContent.Notice -> "notice:" + c.key
        is IslandContent.Device -> "device:" + c.address + ":" + c.connected
        is IslandContent.Power -> "power:" + c.charging
    }

    // =====================================================================================
    // Ticks
    // =====================================================================================

    private fun tickDelay(): Long {
        val m = primary as? IslandContent.Music ?: return -1
        if (!m.playing) return -1
        if (expanded) return 1000
        return if (prefs.visualizer) 90 else -1
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
        if (expanded) drawExpanded(canvas, p, l, t, w, contentA) else drawCompact(canvas, p, l, t, w, h, contentA)
        canvas.restore()
    }

    // ---- Compact ------------------------------------------------------------------------------

    private fun drawCompact(canvas: Canvas, c: IslandContent, l: Float, t: Float, w: Float, h: Float, a: Float) {
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
        // Header label beside the camera hole.
        val label = when (c) {
            is IslandContent.Music -> context.getString(if (c.playing) R.string.now_playing else R.string.paused)
            is IslandContent.Notice -> c.appName
            is IslandContent.Device -> context.getString(if (c.connected) R.string.connected else R.string.disconnected)
            is IslandContent.Power -> context.getString(if (c.charging) R.string.charging else R.string.charger_removed)
        }
        val bandCy = bandTop + pillH / 2
        when (side) {
            Side.LEFT, Side.CENTER -> drawText(canvas, label, hole.right + gap, bandCy, l + w - bodyPad - (hole.right + gap), subPaint, colorDim, a)
            Side.RIGHT -> {
                val tw = min(subPaint.measureText(label), hole.left - gap - (l + bodyPad))
                drawText(canvas, label, hole.left - gap - tw, bandCy, tw, subPaint, colorDim, a)
            }
        }

        val bodyTop = t + pillH
        val bl = l + bodyPad
        val br = l + w - bodyPad
        when (c) {
            is IslandContent.Music -> drawMusicBody(canvas, c, bl, br, bodyTop, a)
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
        val ctrlRight = br
        val ctrlLeft = ctrlRight - 3 * btn
        val ctrlCy = artTop + art / 2
        drawIcon(canvas, R.drawable.ic_skip_previous, ctrlLeft + btn / 2, ctrlCy, dp(24), colorText, a)
        drawIcon(canvas, if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, ctrlLeft + btn * 1.5f, ctrlCy, dp(28), colorText, a)
        drawIcon(canvas, R.drawable.ic_skip_next, ctrlLeft + btn * 2.5f, ctrlCy, dp(24), colorText, a)

        val tx = bl + art + dp(12)
        val tw = ctrlLeft - dp(8) - tx
        drawText(canvas, m.title, tx, artTop + dp(16), tw, titlePaint, colorText, a)
        drawText(canvas, m.artist, tx, artTop + dp(36), tw, subPaint, colorDim, a)

        val barY = artTop + art + dp(14)
        val progress = if (m.duration > 0) (m.positionNow().toFloat() / m.duration).coerceIn(0f, 1f) else 0f
        fillPaint.color = colorTrack
        fillPaint.alpha = (fillPaint.alpha * a).roundToInt()
        tmpRect.set(bl, barY, br, barY + dp(4))
        canvas.drawRoundRect(tmpRect, dp(2), dp(2), fillPaint)
        fillPaint.color = m.accent
        fillPaint.alpha = (255 * a).roundToInt()
        tmpRect.set(bl, barY, bl + (br - bl) * progress, barY + dp(4))
        canvas.drawRoundRect(tmpRect, dp(2), dp(2), fillPaint)
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
            bigPaint.color = tint
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
        if (n.icon != null) drawArt(canvas, n.icon, bl, iconTop, iconSize, dp(10), a, colorAccent)
        else drawArt(canvas, null, bl, iconTop, iconSize, dp(10), a, colorAccent, R.drawable.ic_bell)
        val tx = bl + iconSize + dp(12)
        val tw = br - tx
        drawText(canvas, n.title, tx, iconTop + dp(9), tw, titlePaint, colorText, a)
        val layout = noticeLayout(n)
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
        if (a <= 0.01f && !sCompX.running) {
            if (companion == null) drawnCompanion = null
            return
        }
        val x = sCompX.value
        val w = companionWidth(c)
        val t = sT.value
        rectF.set(x, t, x + w, t + pillH)
        bgPaint.alpha = (255 * bgA).roundToInt()
        borderPaint.alpha = (26 * bgA).roundToInt()
        canvas.drawRoundRect(rectF, pillH / 2, pillH / 2, bgPaint)
        canvas.drawRoundRect(rectF, pillH / 2, pillH / 2, borderPaint)
        if (a <= 0.01f) return
        val cy = t + pillH / 2
        when (c) {
            is IslandContent.Device -> {
                val tint = batteryColor(c.battery, colorGood)
                drawIcon(canvas, deviceIcon(c.kind), x + padH + dp(7), cy, dp(14), tint, a)
                if (c.battery >= 0) {
                    val tx = x + padH + dp(14) + dp(4)
                    drawText(canvas, percentText(c.battery), tx, cy, x + w - padH - tx, smallPaint, colorText, a)
                }
            }
            is IslandContent.Music -> drawArt(canvas, c.art, x + padV, t + padV, pillH - 2 * padV, (pillH - 2 * padV) / 2, a, c.accent)
            else -> Unit
        }
    }

    // ---- Primitives ---------------------------------------------------------------------------

    private fun drawArt(canvas: Canvas, bmp: Bitmap?, x: Float, y: Float, size: Float, radius: Float, a: Float, fallback: Int, fallbackIcon: Int = R.drawable.ic_music_note) {
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
        if (x < l || x > l + w || y < t || y > t + h) return Hit.NONE
        if (!expanded) return Hit.PILL
        if (p is IslandContent.Music) {
            val btn = dp(36)
            val ctrlRight = l + w - bodyPad
            val ctrlLeft = ctrlRight - 3 * btn
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

    // =====================================================================================
    // Window insets: refine the camera hole once the window is actually on screen.
    // =====================================================================================

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (prefs.anchor == Prefs.ANCHOR_AUTO) {
            val cut = insets.displayCutout?.boundingRectTop
            if (cut != null && !cut.isEmpty && cut.width() < screenW / 2 && cut != insetCutout) {
                insetCutout = Rect(cut)
                relayout()
            }
        }
        return super.onApplyWindowInsets(insets)
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
        private const val SHAPE_STIFFNESS = 320f
        private const val SHAPE_DAMPING = 0.68f
    }
}
