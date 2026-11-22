package com.neuralml.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.neuralml.terminal.core.SimulationEngine
import com.neuralml.terminal.util.HudKit
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Slowly rotating 3D wireframe brain with a live 8-region heat map.
 *
 * The model (112 ellipsoid vertices + ~170 nearest-neighbour edges + region
 * assignment) is generated once in `init`. Every frame only does rotation,
 * perspective projection and drawing into preallocated arrays; the 128 cached
 * paints are picked from a heat table instead of being created per frame.
 */
class BrainWireframeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val N = 112
        const val REGIONS = 8
        const val LEVELS = 8
        const val NEIGHBOURS = 3
        const val FOCAL = 5.5f
        const val ROTATION_PERIOD_MS = 15000f
        // Octant naming: bit0 = x>=0 (R), bit1 = y>=0 (U), bit2 = z>=0 (F).
        val REGION_NAMES = arrayOf("L-D-B", "R-D-B", "L-U-B", "R-U-B", "L-D-F", "R-D-F", "L-U-F", "R-U-F")
    }

    private var engine: SimulationEngine? = null

    private val vx = FloatArray(N)
    private val vy = FloatArray(N)
    private val vz = FloatArray(N)
    private val region = IntArray(N)

    private var edgeA = IntArray(0)
    private var edgeB = IntArray(0)

    private val rz = FloatArray(N)
    private val sx = FloatArray(N)
    private val sy = FloatArray(N)
    private val perspArr = FloatArray(N)

    private val edgePaints = Array(REGIONS) { Array(LEVELS) { arrayOfNulls<Paint>(2) } }
    private val pointPaints = Array(REGIONS) { Array(LEVELS) { arrayOfNulls<Paint>(2) } }

    private var startMs = 0L
    private var footerKey = Int.MIN_VALUE
    private var footerText = ""
    private var hotKey = Int.MIN_VALUE
    private var hotText = ""

    /** Used when the view is drawn before [bind] is called. */
    private val emptyRegions = FloatArray(REGIONS)

    /** Per-region tint used to differentiate the heat map. */
    private val regionTint = intArrayOf(
        0xFF00FFFF.toInt(), 0xFF00FF99.toInt(), 0xFF66CCFF.toInt(), 0xFF00FF66.toInt(),
        0xFFFFCC00.toInt(), 0xFFFF9966.toInt(), 0xFFFF66CC.toInt(), 0xFF99CCFF.toInt(),
    )

    init {
        isClickable = false
        isFocusable = false
        buildModel()
        buildPaints()
        startMs = SystemClock.uptimeMillis()
    }

    fun bind(engine: SimulationEngine) {
        this.engine = engine
        invalidate()
    }

    // ── model generation ─────────────────────────────────────────────────────

    private fun buildModel() {
        val goldenAngle = 2.39996323f
        for (i in 0 until N) {
            val y0 = 1f - (i + 0.5f) / N * 2f
            val r = sqrt((1f - y0 * y0).coerceAtLeast(0f))
            val theta = i * goldenAngle
            var x = cos(theta) * r
            var y = y0
            var z = sin(theta) * r
            // Brain-ish proportions + gentle cortical wrinkles.
            val wrinkle = 1f + 0.055f * sin(5.1f * y + 2.7f * theta) + 0.035f * cos(7.3f * x - 3.1f * y)
            x *= 1.10f * wrinkle
            y *= 0.82f * wrinkle
            z *= 1.16f * wrinkle
            // Flatten the underside a little (brain stem side).
            if (y < 0f) y *= 0.86f
            vx[i] = x
            vy[i] = y
            vz[i] = z
            region[i] = regionOf(x, y, z)
        }

        val seen = HashSet<Long>(N * NEIGHBOURS * 2)
        val aList = ArrayList<Int>(N * NEIGHBOURS)
        val bList = ArrayList<Int>(N * NEIGHBOURS)
        val dist = FloatArray(N)
        for (i in 0 until N) {
            for (j in 0 until N) {
                val dx = vx[i] - vx[j]
                val dy = vy[i] - vy[j]
                val dz = vz[i] - vz[j]
                dist[j] = dx * dx + dy * dy + dz * dz
            }
            dist[i] = Float.MAX_VALUE
            repeat(NEIGHBOURS) {
                var best = -1
                var bestD = Float.MAX_VALUE
                for (j in 0 until N) {
                    if (dist[j] < bestD) {
                        bestD = dist[j]
                        best = j
                    }
                }
                if (best >= 0) {
                    dist[best] = Float.MAX_VALUE
                    val lo = min(i, best)
                    val hi = if (lo == i) best else i
                    val key = lo.toLong() * 1000L + hi
                    if (seen.add(key)) {
                        aList.add(lo)
                        bList.add(hi)
                    }
                }
            }
        }
        edgeA = aList.toIntArray()
        edgeB = bList.toIntArray()
    }

    private fun regionOf(x: Float, y: Float, z: Float): Int {
        var r = 0
        if (x >= 0f) r += 1
        if (y >= 0f) r += 2
        if (z >= 0f) r += 4
        return r.coerceIn(0, REGIONS - 1)
    }

    private fun buildPaints() {
        for (r in 0 until REGIONS) {
            for (level in 0 until LEVELS) {
                val t = level.toFloat() / (LEVELS - 1)
                val color = heatColor(t, r)
                edgePaints[r][level][0] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    this.color = HudKit.alpha(color, 68)
                    strokeWidth = 1f
                }
                edgePaints[r][level][1] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    this.color = HudKit.alpha(color, 205)
                    strokeWidth = 1.1f
                }
                pointPaints[r][level][0] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    this.color = HudKit.alpha(color, 90)
                }
                pointPaints[r][level][1] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    this.color = HudKit.alpha(color, 240)
                }
            }
        }
    }

    private fun heatColor(t: Float, region: Int): Int {
        val dim = 0xFF0B3A3A.toInt()
        val cyan = HudKit.CYAN
        val amber = HudKit.AMBER
        val white = 0xFFFFFFFF.toInt()
        val base = when {
            t < 0.45f -> blend(dim, cyan, t / 0.45f)
            t < 0.80f -> blend(cyan, amber, (t - 0.45f) / 0.35f)
            else -> blend(amber, white, (t - 0.80f) / 0.20f)
        }
        // A per-region tint keeps the eight regions distinguishable at low heat.
        return blend(base, regionTint[region], 0.22f)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val fa = f.coerceIn(0f, 1f)
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * fa).toInt()
        val g = (ag + (bg - ag) * fa).toInt()
        val bl = (ab + (bb - ab) * fa).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = engine
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 100f || h < 80f) return

        HudKit.panel(canvas, 0f, 0f, w, h, HudKit.CYAN)

        val density = resources.displayMetrics.density
        val pad = 9f * density
        val titleSize = (h * 0.075f).coerceIn(8f * density, 12.5f * density)
        val titleBaseline = pad + titleSize
        HudKit.title(canvas, "NEURAL WIREFRAME · 三维脑区热力", pad, titleBaseline, titleSize, HudKit.CYAN)

        val rightPaint = HudKit.textPaint(titleSize * 0.72f, HudKit.GREY)
        val rightText = "8 REGIONS · LIVE HEATMAP"
        val titleW = HudKit.textPaint(titleSize, HudKit.CYAN, bold = true).measureText("NEURAL WIREFRAME · 三维脑区热力")
        val rightW = rightPaint.measureText(rightText)
        if (pad + titleW + pad + rightW < w - pad) {
            canvas.drawText(rightText, w - pad - rightW, titleBaseline, rightPaint)
        }

        // Reserve room at the bottom for the overlaid dual sync meter.
        val reserveBottom = min(76f * density, h * 0.55f)
        val contentTop = titleBaseline + titleSize * 0.9f
        val contentBottom = h - pad - reserveBottom
        val contentH = contentBottom - contentTop
        if (contentH < 50f) return
        val plotW = w - pad * 2f
        val cx = w / 2f
        val cy = (contentTop + contentBottom) / 2f

        val now = SystemClock.uptimeMillis()
        val elapsed = now - startMs
        val yaw = (elapsed % ROTATION_PERIOD_MS.toLong()).toFloat() / ROTATION_PERIOD_MS * (Math.PI * 2.0).toFloat()
        val cosY = cos(yaw)
        val sinY = sin(yaw)
        val pitch = -0.16f
        val cosP = cos(pitch)
        val sinP = sin(pitch)

        // FOCAL 5.5 keeps perspective gain <= ~1.28 so the brain never spills out.
        val scale = min(plotW * 0.32f, contentH * 0.45f)
        for (i in 0 until N) {
            val x1 = vx[i] * cosY - vz[i] * sinY
            val z1 = vx[i] * sinY + vz[i] * cosY
            val y1 = vy[i] * cosP - vz[i] * sinP
            val z2 = vy[i] * sinP + vz[i] * cosP
            rz[i] = z2
            val persp = FOCAL / (FOCAL + z2)
            perspArr[i] = persp
            sx[i] = cx + x1 * scale * persp
            sy[i] = cy - y1 * scale * persp
        }

        // Faint orbital rings give the object a holographic footprint.
        val ringPaint = HudKit.stroke(HudKit.alpha(HudKit.CYAN, 44), 1f)
        canvas.drawOval(cx - scale * 1.18f, cy - scale * 0.40f, cx + scale * 1.18f, cy + scale * 0.40f, ringPaint)
        canvas.drawOval(cx - scale * 1.28f, cy - scale * 0.28f, cx + scale * 1.28f, cy + scale * 0.28f, ringPaint)

        val regions = if (e != null) e.brainRegions else emptyRegions
        val boost = e?.state?.brainBoost ?: 0f

        // Back faces first, then front faces, for a readable depth ordering.
        for (pass in 0..1) {
            val wantBack = pass == 0
            for (idx in edgeA.indices) {
                val a = edgeA[idx]
                val b = edgeB[idx]
                val zAvg = (rz[a] + rz[b]) * 0.5f
                val isBack = zAvg < 0f
                if (isBack != wantBack) continue
                val reg = region[a]
                val activation = ((regions[reg] + regions[region[b]]) * 0.5f + boost * 0.25f).coerceIn(0f, 1f)
                val level = (activation * (LEVELS - 1)).toInt().coerceIn(0, LEVELS - 1)
                val paint = edgePaints[reg][level][if (isBack) 0 else 1] ?: continue
                canvas.drawLine(sx[a], sy[a], sx[b], sy[b], paint)
            }
        }

        // Region nodes with a breathing pulse.
        for (i in 0 until N) {
            val reg = region[i]
            val activation = (regions[reg] + boost * 0.2f).coerceIn(0f, 1f)
            val level = (activation * (LEVELS - 1)).toInt().coerceIn(0, LEVELS - 1)
            val isBack = rz[i] < 0f
            val pulse = 0.85f + 0.15f * sin(elapsed * 0.0024f + reg * 1.1f)
            val radius = (0.9f + activation * 2.6f) * density * perspArr[i] * pulse
            pointPaints[reg][level][if (isBack) 0 else 1]?.let { paint ->
                canvas.drawCircle(sx[i], sy[i], radius.coerceAtLeast(0.6f), paint)
            }
        }

        // Footer: rotation angle and heat state.
        val deg = ((yaw * 180.0 / Math.PI).toInt() % 360 + 360) % 360
        val footerSize = titleSize * 0.72f
        val footerPaint = HudKit.textPaint(footerSize, HudKit.GREY)
        val heatPct = (boost * 100).toInt()
        val key = deg * 1000 + heatPct
        if (key != footerKey) {
            footerKey = key
            footerText = "ROT ${deg}° · PITCH -9° · REGIONS ${REGIONS} · HEAT ${heatPct}%"
        }
        canvas.drawText(footerText, pad, contentBottom - footerSize * 0.25f, footerPaint)

        // Hottest-region tag (top right corner of the plot).
        if (e != null) {
            var hotRegion = 0
            var hotValue = -1f
            for (r in 0 until REGIONS) {
                if (regions[r] > hotValue) {
                    hotValue = regions[r]
                    hotRegion = r
                }
            }
            val hotPct = (hotValue * 100).toInt()
            val hotCacheKey = hotRegion * 1000 + hotPct
            if (hotValue > 0.45f) {
                if (hotCacheKey != hotKey) {
                    hotKey = hotCacheKey
                    hotText = "HOT ${REGION_NAMES[hotRegion]} ${hotPct}%"
                }
                val tagPaint = HudKit.textPaint(footerSize, HudKit.AMBER, bold = true)
                canvas.drawText(hotText, w - pad - tagPaint.measureText(hotText), contentTop + footerSize, tagPaint)
            }
        }
    }
}
