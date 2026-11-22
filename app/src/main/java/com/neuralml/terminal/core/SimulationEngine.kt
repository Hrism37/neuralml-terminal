package com.neuralml.terminal.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A short burst of simulated model activity triggered by a console command. */
enum class BurstKind { SCAN, SYNC, DECODE, TRAIN, PREDICT }

/**
 * One of the three decode profiles reachable through `model switch`.
 *
 * [targetFps] and [confidenceBias] directly influence the state the engine
 * generates, so switching profiles visibly changes frame rate / confidence.
 */
data class DecodeMode(
    val key: String,
    val label: String,
    val english: String,
    val targetFps: Float,
    val confidenceBias: Float,
) {
    companion object {
        val LIGHT = DecodeMode("light", "轻量", "LIGHT", 72f, -6f)
        val STANDARD = DecodeMode("standard", "标准", "STANDARD", 58f, 0f)
        val PRECISION = DecodeMode("precision", "高精度", "PRECISION", 31f, 6f)
        val ALL = listOf(LIGHT, STANDARD, PRECISION)

        /** Accepts key (`light`), Chinese label (`轻量`) or English name (`LIGHT`). */
        fun byKey(raw: String): DecodeMode? {
            val trimmed = raw.trim()
            val lower = trimmed.lowercase(Locale.US)
            return ALL.firstOrNull {
                it.key == lower || it.english.lowercase(Locale.US) == lower || it.label == trimmed
            }
        }
    }
}

/**
 * The single source of simulated telemetry for the whole terminal.
 *
 * A main-thread loop runs at ~30 Hz: it drifts target values, smoothly
 * interpolates the visible state toward them, appends waveform samples, moves
 * the brain-region heat map, and occasionally emits log lines / signal-jitter
 * events. Everything is local and cosmetic; there is no model and no I/O.
 */
class SimulationEngine {

    companion object {
        /** ~30 Hz frame loop. */
        const val FRAME_MS = 33L
        /** Number of independently highlighted brain regions. */
        const val REGION_COUNT = 8
    }

    val state = SimState()
    val waves = WaveData()
    val logBus = LogBus()

    /** 0..1 activation per brain region, consumed by the wireframe brain view. */
    val brainRegions = FloatArray(REGION_COUNT) { 0.35f }

    /** Called once per engine tick after the state has been updated. */
    var frameListener: (() -> Unit)? = null

    var isRunning: Boolean = false
        private set

    /** Currently active decode profile. */
    var mode: DecodeMode = DecodeMode.STANDARD
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random()
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val clockDate = Date()
    private var lastClockSecond = -1L
    private var frameIndex = 0L

    // Drift targets; state smoothly follows them so nothing ever jumps.
    private var tCpu = 34f
    private var tNpu = 58f
    private var tMem = 47f
    private var tFps = 58f
    private var tAlpha = 10.4f
    private var tBeta = 18.6f
    private var tTheta = 6.2f
    private var tDelta = 2.4f
    private var tHeart = 76f
    private var tGsr = 6.4f
    private var tAttention = 84f
    private var tNeural = 72f
    private var tFeature = 92f
    private var tEmotion = 88f
    private var tAccuracy = 96f
    private var tLoss = 0.041f
    private var tLatency = 14f
    private var tSync = 91f
    private var tConfidence = 87f

    private var nextDriftAt = 0L
    private var nextLogAt = 0L
    private var nextEngineLogAt = 0L
    private var nextSignalLogAt = 0L

    private var burstKind: BurstKind? = null
    private var burstStartedAt = 0L
    private var burstEndAt = 0L
    private var burstStrength = 0f

    private var pulseUntil = 0L
    private var pulseStrength = 0f

    private var jitterUntil = 0L
    private var jitterStrength = 0f
    private var jitterRecovered = true

    private var trainingEndAt = 0L

    private val regionTargets = FloatArray(REGION_COUNT) { 0.35f }

    // ── lifecycle ────────────────────────────────────────────────────────────

    private var bootBannerShown = false

    fun start() {
        if (isRunning) return
        isRunning = true
        frameIndex = 0L
        val now = SystemClock.uptimeMillis()
        nextDriftAt = now + 200L
        nextLogAt = now + 900L
        nextEngineLogAt = now + 1500L
        nextSignalLogAt = now + 3000L
        if (!bootBannerShown) {
            bootBannerShown = true
            bootBanner()
        }
        handler.post(tick)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            step()
            frameListener?.invoke()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    // ── public control surface (used by CommandProcessor) ────────────────────

    /** Current wall-clock string, `HH:mm:ss`. */
    fun nowString(): String {
        clockDate.time = System.currentTimeMillis()
        return clockFormat.format(clockDate)
    }

    fun log(level: LogLevel, source: String, text: String) {
        logBus.post(LogEntry(nowString(), level, source, text))
    }

    /** Small waveform / brain tremor, used right after a command is accepted. */
    fun pulse(strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return
        pulseStrength = max(pulseStrength, s)
        pulseUntil = max(pulseUntil, SystemClock.uptimeMillis() + 1100L)
        val now = SystemClock.uptimeMillis()
        repeat(7) {
            waves.addMark(rng.nextInt(WaveData.CHANNELS), rng.nextFloat(), 0.6f + rng.nextFloat() * 0.4f, now)
        }
    }

    /** Starts a multi-second model-activity burst (decode / train / scan / ...). */
    fun burst(kind: BurstKind, durationMs: Long = 2600L) {
        val now = SystemClock.uptimeMillis()
        burstKind = kind
        burstStartedAt = now
        burstEndAt = now + durationMs.coerceIn(400L, 20000L)
        burstStrength = 1f
        state.brainBoost = 1f
        when (kind) {
            BurstKind.SCAN -> {
                state.linkLabel = "TARGET SCAN"
                log(LogLevel.INFO, "SCAN", "wide-band sweep started · sector 0x3C")
            }
            BurstKind.SYNC -> {
                state.linkLabel = "HANDSHAKE"
                log(LogLevel.INFO, "LINK", "neuro-link handshake · re-syncing phase lock")
            }
            BurstKind.DECODE -> {
                state.linkLabel = "DECODING"
                tConfidence = 93f
                log(LogLevel.INFO, "DECODE", "tensor stream opened · 512-d latent space")
            }
            BurstKind.TRAIN -> {
                trainingEndAt = burstEndAt
                log(LogLevel.INFO, "TRAIN", "online fine-tune started · lr 3.0e-5")
            }
            BurstKind.PREDICT -> {
                tConfidence = 90f
                log(LogLevel.INFO, "PREDICT", "predictive head engaged · horizon 30s")
            }
        }
        pulse(0.9f)
    }

    /** Switches decode profile; returns null when the argument is unknown. */
    fun setDecodeMode(raw: String): DecodeMode? {
        val next = DecodeMode.byKey(raw) ?: return null
        mode = next
        state.decodeModeKey = next.key
        state.decodeModeLabel = next.label
        state.inferenceMode = when (next) {
            DecodeMode.LIGHT -> "轻量流式解码"
            DecodeMode.PRECISION -> "高精度流式解码"
            else -> "实时流式解码"
        }
        tFps = next.targetFps
        tConfidence = (tConfidence + next.confidenceBias).coerceIn(55f, 99.5f)
        log(
            LogLevel.INFO,
            "ML",
            String.format(
                Locale.US,
                "decode profile → %s (%s) · target %.0f fps · confidence bias %+.0f%%",
                next.english, next.label, next.targetFps, next.confidenceBias
            )
        )
        return next
    }

    /** Rotates the locked target id and returns the new id (used by `scan`). */
    fun rotateTarget(): String {
        val letters = arrayOf("Δ", "Ω", "Σ", "Θ", "Λ", "Φ")
        val id = String.format(
            Locale.US,
            "TGT-%04X-%s",
            rng.nextInt(0xFFFF),
            letters[rng.nextInt(letters.size)]
        )
        state.targetId = id
        return id
    }

    /** Convenience snapshot for the `status` command. */
    fun statusLines(): List<String> {
        val s = state
        return listOf(
            String.format(Locale.US, "target   : %s | link %s | signal %d/5", s.targetId, s.linkLabel, s.signalBars),
            String.format(Locale.US, "neuro    : α %.1fHz  β %.1fHz  θ %.1fHz  δ %.1fHz", s.alphaHz, s.betaHz, s.thetaHz, s.deltaHz),
            String.format(Locale.US, "vitals   : HR %.0f BPM | GSR %.1fµS | attention %.0f%% | activity %.0f%%", s.heartRate, s.gsr, s.attention, s.neuralActivity),
            String.format(Locale.US, "engine   : %s | %s | %s", s.modelName, s.inferenceMode, s.decodeModeLabel),
            String.format(Locale.US, "metrics  : feat %.1f%% | emotion %.1f%% | acc %.1f%%", s.featureRate, s.emotionConfidence, s.decodeAccuracy),
            String.format(Locale.US, "train    : step %,d | loss %.4f | latency %.0fms", s.iteration, s.loss, s.latencyMs),
            String.format(Locale.US, "sync     : neuro-sync %.1f%% | decode-conf %.1f%% | fps %.1f", s.neuroSync, s.decodeConfidence, s.fps),
            String.format(Locale.US, "host     : cpu %.1f%% | npu %.1f%% | mem %.1f%%", s.cpu, s.npu, s.mem),
        )
    }

    // ── the frame loop ───────────────────────────────────────────────────────

    private fun step() {
        val now = SystemClock.uptimeMillis()
        frameIndex++
        updateClock()
        updateBurst(now)
        updatePulse(now)
        updateJitter(now)
        if (now >= nextDriftAt) drift(now)
        smooth()
        updateBrain()
        emitWave(now)
        updateCounters(now)
        maybeLog(now)
    }

    private fun updateClock() {
        val nowSeconds = System.currentTimeMillis() / 1000L
        if (nowSeconds != lastClockSecond) {
            lastClockSecond = nowSeconds
            clockDate.time = System.currentTimeMillis()
            state.clock = clockFormat.format(clockDate)
        }
    }

    private fun updateBurst(now: Long) {
        if (burstEndAt > 0L && now >= burstEndAt) {
            val finished = burstKind
            burstKind = null
            burstEndAt = 0L
            burstStartedAt = 0L
            burstStrength = 0f
            state.brainBoost = 0f
            state.linkLabel = "SECURE LINK"
            if (finished != null) {
                log(LogLevel.INFO, "ML", burstFinishText(finished))
            }
        }
        if (burstKind != null) {
            val total = (burstEndAt - burstStartedAt).toFloat().coerceAtLeast(1f)
            val progress = ((now - burstStartedAt).toFloat() / total).coerceIn(0f, 1f)
            burstStrength = 1f - progress
            state.brainBoost = burstStrength
            tSync = (95f + rng.nextFloat() * 4f).coerceAtMost(99f)
            tFeature = 96f + rng.nextFloat() * 3.5f
            if (burstKind == BurstKind.TRAIN) {
                tLoss = max(0.0208f, tLoss - 0.0016f)
                tAccuracy = min(99.4f, tAccuracy + 0.05f)
            } else {
                tConfidence = (90f + rng.nextFloat() * 8f).coerceAtMost(99.4f)
            }
        }
    }

    private fun burstFinishText(kind: BurstKind): String = when (kind) {
        BurstKind.SCAN -> "target scan complete · lock maintained"
        BurstKind.SYNC -> "neuro-link stable · phase error 0.004 rad"
        BurstKind.DECODE -> "decode burst complete · latent state frozen"
        BurstKind.TRAIN -> "online fine-tune complete · calibration stored"
        BurstKind.PREDICT -> "prediction window closed · distribution committed"
    }

    private fun updatePulse(now: Long) {
        if (now >= pulseUntil) {
            pulseStrength = 0f
        } else {
            pulseStrength = (pulseStrength * 0.985f).coerceIn(0f, 1f)
        }
        val gainTarget = 1f + burstStrength * 0.85f + pulseStrength * 0.55f + jitterStrength * 0.6f
        state.waveGain = approach(state.waveGain, gainTarget, 0.10f)
    }

    private fun updateJitter(now: Long) {
        if (jitterUntil > 0L && now >= jitterUntil) {
            jitterUntil = 0L
            jitterStrength = 0f
            if (!jitterRecovered) {
                jitterRecovered = true
                state.linkLabel = "SECURE LINK"
                state.signalBars = 4
                log(LogLevel.INFO, "RECAL", "auto-recalibration complete · link restored (err 0.000)")
            }
        }
        if (jitterUntil > 0L) {
            jitterStrength = (jitterStrength * 0.94f).coerceIn(0.12f, 1f)
            state.signalBars = if (rng.nextFloat() < 0.5f) 2 else 3
        }
        // Low-probability signal jitter event: brief disturbance, auto recovery.
        if (jitterUntil == 0L && rng.nextFloat() < 0.0011f) {
            jitterUntil = now + 2400L
            jitterStrength = 0.85f
            jitterRecovered = false
            state.linkLabel = "RECALIBRATING"
            state.signalBars = 2
            tConfidence = max(58f, tConfidence - 14f)
            log(LogLevel.WARN, "LINK", "signal jitter detected · packet loss 12.4% · fallback model engaged")
        }
    }

    private fun drift(now: Long) {
        nextDriftAt = now + 620L + rng.nextInt(760).toLong()
        tCpu = 24f + rng.nextFloat() * 36f
        tNpu = 42f + rng.nextFloat() * 44f
        tMem = 36f + rng.nextFloat() * 24f
        tFps = mode.targetFps * (0.86f + rng.nextFloat() * 0.20f)
        tAlpha = 8.2f + rng.nextFloat() * 3.6f
        tBeta = 14.0f + rng.nextFloat() * 9.2f
        tTheta = 4.2f + rng.nextFloat() * 3.4f
        tDelta = 1.5f + rng.nextFloat() * 2.4f
        tHeart = 63f + rng.nextFloat() * 28f
        tGsr = 3.2f + rng.nextFloat() * 7.8f
        tAttention = 64f + rng.nextFloat() * 32f
        tNeural = 54f + rng.nextFloat() * 40f
        tFeature = 85f + rng.nextFloat() * 14f
        tEmotion = 77f + rng.nextFloat() * 21f
        tAccuracy = 92.0f + rng.nextFloat() * 7.2f
        tLoss = 0.024f + rng.nextFloat() * 0.052f
        tLatency = 10f + rng.nextFloat() * 10f
        if (burstKind == null) {
            tSync = 72f + rng.nextFloat() * 26f
            tConfidence = (70f + rng.nextFloat() * 26f + mode.confidenceBias).coerceIn(45f, 99.5f)
        }
    }

    private fun smooth() {
        val k = 0.085f
        state.cpu = approach(state.cpu, tCpu, k)
        state.npu = approach(state.npu, tNpu, k)
        state.mem = approach(state.mem, tMem, 0.05f)
        state.fps = approach(state.fps, tFps, k)
        state.alphaHz = approach(state.alphaHz, tAlpha, k)
        state.betaHz = approach(state.betaHz, tBeta, k)
        state.thetaHz = approach(state.thetaHz, tTheta, k)
        state.deltaHz = approach(state.deltaHz, tDelta, k)
        state.heartRate = approach(state.heartRate, tHeart, 0.055f)
        state.gsr = approach(state.gsr, tGsr, 0.05f)
        state.attention = approach(state.attention, tAttention, k).coerceIn(0f, 100f)
        state.neuralActivity = approach(state.neuralActivity, tNeural, k).coerceIn(0f, 100f)
        state.featureRate = approach(state.featureRate, tFeature, 0.07f).coerceIn(0f, 100f)
        state.emotionConfidence = approach(state.emotionConfidence, tEmotion, 0.06f).coerceIn(0f, 100f)
        state.decodeAccuracy = approach(state.decodeAccuracy, tAccuracy, 0.04f).coerceIn(0f, 100f)
        state.loss = approach(state.loss, tLoss, 0.05f).coerceIn(0.02f, 0.08f)
        state.latencyMs = approach(state.latencyMs, tLatency, 0.25f).coerceIn(10f, 20f)
        state.neuroSync = approach(state.neuroSync, tSync, 0.10f).coerceIn(72f, 99f)
        state.decodeConfidence = approach(state.decodeConfidence, tConfidence, 0.12f).coerceIn(45f, 99.7f)
    }

    private fun updateBrain() {
        for (i in 0 until REGION_COUNT) {
            if (rng.nextFloat() < 0.018f) {
                regionTargets[i] = 0.12f + rng.nextFloat() * 0.88f
            }
            if (burstStrength > 0f && rng.nextFloat() < 0.16f) {
                regionTargets[i] = 0.5f + rng.nextFloat() * 0.5f
            }
            brainRegions[i] = approach(brainRegions[i], regionTargets[i], 0.045f).coerceIn(0f, 1f)
        }
    }

    private fun emitWave(now: Long) {
        val t = frameIndex * (FRAME_MS / 1000.0)
        val gain = state.waveGain
        val jitterNoise = jitterStrength * 0.35f
        val values = FloatArray(WaveData.CHANNELS)
        values[0] = (gain * (0.52 * sin(t * 2.10) + 0.24 * sin(t * 5.70 + 1.1) + 0.10 * sin(t * 13.3)) +
            noise(0.055f + jitterNoise)).toFloat()
        values[1] = (gain * (0.44 * sin(t * 3.30 + 2.0) + 0.28 * sin(t * 1.25) + 0.12 * sin(t * 9.10 + 0.4)) +
            noise(0.055f + jitterNoise)).toFloat()
        values[2] = (gain * (0.38 * sin(t * 1.60 + 4.2) + 0.30 * sin(t * 7.40 + 0.8) + 0.14 * sin(t * 17.0)) +
            noise(0.050f + jitterNoise)).toFloat()
        values[3] = (gain * (0.60 * sin(t * 0.85 + 1.7) + 0.18 * sin(t * 4.10) + 0.09 * sin(t * 11.0 + 2.2)) +
            noise(0.040f + jitterNoise)).toFloat()
        waves.append(values)

        val markChance = 0.045f + burstStrength * 0.16f + jitterStrength * 0.30f
        if (rng.nextFloat() < markChance) {
            waves.addMark(
                rng.nextInt(WaveData.CHANNELS),
                rng.nextFloat(),
                0.45f + rng.nextFloat() * 0.55f,
                now
            )
        }
        if (rng.nextFloat() < 0.02f) waves.marks(now) // prunes stale marks
    }

    private fun noise(amplitude: Float): Double = (rng.nextDouble() - 0.5) * 2.0 * amplitude

    private fun updateCounters(now: Long) {
        val training = burstKind == BurstKind.TRAIN
        state.iteration += if (training) (9 + rng.nextInt(11)).toLong() else rng.nextInt(3).toLong()
        if (training && now >= nextEngineLogAt) {
            nextEngineLogAt = now + 1300L
            log(
                LogLevel.INFO,
                "TRAIN",
                String.format(
                    Locale.US,
                    "epoch %d/12 · loss %.4f · acc %.2f%% · lr 3.0e-5",
                    ((now - burstStartedAt) / 400L).toInt().coerceAtLeast(1),
                    state.loss,
                    state.decodeAccuracy
                )
            )
        }
        if (!training && now >= nextSignalLogAt) {
            nextSignalLogAt = now + 7000L + rng.nextInt(6000).toLong()
            state.signalBars = 3 + rng.nextInt(3)
        }
    }

    private fun maybeLog(now: Long) {
        if (now < nextLogAt) return
        nextLogAt = now + 2600L + rng.nextInt(3200).toLong()
        when (rng.nextInt(10)) {
            0 -> log(LogLevel.INFO, "NPU", String.format(Locale.US, "tensor batch 0x%04X committed · %.1f ms", rng.nextInt(0xFFFF), state.latencyMs))
            1 -> log(LogLevel.INFO, "CORE", String.format(Locale.US, "frame sync ok · ring buffer %d samples", waves.count()))
            2 -> log(LogLevel.INFO, "FEATURE", String.format(Locale.US, "extracted %d neuro-features · dr %.3f", 24 + rng.nextInt(40), 0.75f + rng.nextFloat() * 0.2f))
            3 -> log(LogLevel.INFO, "LINK", String.format(Locale.US, "beacon 2.4GHz · rssi -%d dBm · loss %.2f%%", 41 + rng.nextInt(18), rng.nextFloat() * 0.4f))
            4 -> log(LogLevel.INFO, "ML", String.format(Locale.US, "inference #%,d · confidence %.1f%%", state.iteration, state.decodeConfidence))
            5 -> log(LogLevel.INFO, "SENSOR", "electrode impedance nominal · 4/4 channels")
            6 -> log(LogLevel.WARN, "NPU", String.format(Locale.US, "thermal drift %.1f°C · fan curve adjusted", 38f + rng.nextFloat() * 4f))
            7 -> log(LogLevel.INFO, "ML", "latent space re-projected · 512-d → 64-d")
            8 -> log(LogLevel.INFO, "CORE", String.format(Locale.US, "queue depth %d · backpressure nominal", rng.nextInt(6)))
            else -> log(LogLevel.INFO, "SYNC", "phase lock stable · drift 0.004 rad")
        }
        if (rng.nextFloat() < 0.06f) {
            log(LogLevel.ERROR, "LINK", String.format(Locale.US, "packet 0x%04X retransmit · recovered", rng.nextInt(0xFFFF)))
        }
    }

    private fun bootBanner() {
        log(LogLevel.INFO, "BOOT", "neural-ml decoder terminal v5.1.0 · local build")
        log(LogLevel.INFO, "BOOT", "4-channel acquisition front-end online")
        log(LogLevel.INFO, "BOOT", "NeuralDecoder-XL v3.7.2 weights mapped · 187 MB")
        log(LogLevel.INFO, "NPU", String.format(Locale.US, "inference engine ready · %d ips target", 42_000))
        log(LogLevel.INFO, "LINK", "e2e encrypted channel established · key rotated")
    }

    private fun approach(current: Float, target: Float, factor: Float): Float {
        val f = factor.coerceIn(0.001f, 1f)
        return current + (target - current) * f
    }
}
