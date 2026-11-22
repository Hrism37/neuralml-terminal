package com.neuralml.terminal.core

import com.neuralml.terminal.ui.ConsoleLevel
import com.neuralml.terminal.ui.ConsoleLine
import com.neuralml.terminal.ui.ConsoleView
import com.neuralml.terminal.util.HudKit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Parses console commands and turns them into paced output batches.
 *
 * There is no timer here: each "stage" is a batch of [ConsoleLine]s handed to
 * [ConsoleView.enqueue]; the console's own typewriter drives the rhythm and the
 * next stage is requested from the batch completion callback. That keeps the
 * whole thing on the UI thread and lets `ConsoleView.release()` stop everything.
 */
class CommandProcessor(
    private val engine: SimulationEngine,
    private val console: ConsoleView,
) {

    /** A lazily built batch of output lines; values are read at print time. */
    private class Stage(val build: () -> List<ConsoleLine>)

    fun execute(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        engine.log(LogLevel.INFO, "CONSOLE", "directive: ${input.take(72)}")
        val tokens = input.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val command = tokens[0].lowercase(Locale.US)
        when (command) {
            "help", "?" -> play(listOf(Stage { helpLines() }))
            "scan" -> scan()
            "sync" -> sync()
            "status", "stat" -> play(listOf(Stage { statusLines() }))
            "clear", "cls" -> console.clearScreen()
            "decode" -> decode()
            "train" -> train()
            "predict" -> predict()
            "model" -> model(tokens)
            else -> unknown(input)
        }
    }

    private fun play(stages: List<Stage>) = playFrom(stages, 0)

    private fun playFrom(stages: List<Stage>, index: Int) {
        if (index >= stages.size) return
        console.enqueue(stages[index].build()) { playFrom(stages, index + 1) }
    }

    // ── line helpers ─────────────────────────────────────────────────────────

    private fun line(text: String, level: ConsoleLevel = ConsoleLevel.OUT, pause: Long = 30L) =
        ConsoleLine(text, level, pause)

    private fun out(text: String, pause: Long = 22L) = line(text, ConsoleLevel.OUT, pause)
    private fun ok(text: String, pause: Long = 30L) = line(text, ConsoleLevel.OK, pause)
    private fun err(text: String, pause: Long = 30L) = line(text, ConsoleLevel.ERR, pause)
    private fun sys(text: String, pause: Long = 30L) = line(text, ConsoleLevel.SYS, pause)

    // ── commands ─────────────────────────────────────────────────────────────

    private fun helpLines(): List<ConsoleLine> = listOf(
        sys("commands · 可用指令集", 40),
        out(""),
        out("  help      show command list"),
        out("  scan      sweep sector · lock target"),
        out("  sync      re-sync neuro-link phase"),
        out("  status    dump full engine state"),
        out("  decode    deep neural decode pipeline"),
        out("  train     fine-tune · 12 epochs"),
        out("  predict   30 s emotion forecast"),
        out("  model     switch profile"),
        out("            light|standard|precision"),
        out("  clear     wipe the console buffer"),
        out(""),
        sys("  ENTER 执行 · help 查看指令", 20),
    )

    private fun scan() {
        engine.burst(BurstKind.SCAN)
        engine.pulse(0.8f)
        play(
            listOf(
                Stage {
                    listOf(
                        sys("[SCAN] wide-band sweep · 4-channel array", 40),
                        out("  sector 0x3C  2.402 GHz  [###.......]  28%", 26),
                        out("  sector 0x3D  2.416 GHz  [######....]  61%", 26),
                        out("  sector 0x3E  2.430 GHz  [#########.]  87%", 26),
                        out("  sector 0x3F  2.444 GHz  [##########]  99%", 420),
                    )
                },
                Stage {
                    val id = engine.rotateTarget()
                    engine.log(LogLevel.INFO, "SCAN", "target locked $id")
                    val s = engine.state
                    listOf(
                        ok("  phase lock ........ OK"),
                        ok("  target locked: $id", 90),
                        out(
                            String.format(
                                Locale.US,
                                "  link %s · signal %d/5 · conf %.1f%%",
                                s.linkLabel, s.signalBars, s.decodeConfidence
                            )
                        ),
                        sys("  next: decode · train · predict", 20),
                    )
                },
            )
        )
    }

    private fun sync() {
        engine.burst(BurstKind.SYNC)
        engine.pulse(0.7f)
        play(
            listOf(
                Stage {
                    listOf(
                        sys("[SYNC] neuro-link handshake · 4CH", 40),
                        out("  carrier 2.402 GHz ......... acquired", 40),
                        out("  phase offset 0.410 rad ... correcting", 60),
                        out("  rate match 256 Hz ....... locked", 60),
                        out("  packet loss 0.00% ....... ok", 420),
                    )
                },
                Stage {
                    val s = engine.state
                    listOf(
                        ok("  phase lock OK · drift 0.004 rad", 60),
                        out(
                            String.format(
                                Locale.US,
                                "  sync %.1f%% · conf %.1f%% · %.1f fps",
                                s.neuroSync, s.decodeConfidence, s.fps
                            )
                        ),
                        sys("  link ${s.linkLabel} · ${if (s.encrypted) "AES-256" else "OFF"}", 20),
                    )
                },
            )
        )
    }

    private fun statusLines(): List<ConsoleLine> {
        val lines = ArrayList<ConsoleLine>(12)
        lines.add(sys("engine state · ${engine.nowString()}", 40))
        for (row in engine.statusLines()) {
            for (part in row.split(" | ")) {
                if (part.length <= 42) {
                    lines.add(out("  $part"))
                    continue
                }
                val colon = part.indexOf(':')
                val label = if (colon > 0) part.substring(0, colon + 1).trimEnd() else ""
                if (label.isNotEmpty()) lines.add(out("  $label"))
                val rest = if (colon > 0) part.substring(colon + 1) else part
                for (piece in rest.trim().split(Regex("\\s{2,}"))) {
                    if (piece.isNotEmpty()) lines.add(out("    $piece"))
                }
            }
        }
        lines.add(sys("  type help for the command list", 20))
        return lines
    }

    private fun decode() {
        engine.burst(BurstKind.DECODE)
        play(
            listOf(
                Stage {
                    listOf(
                        sys("[DECODE] deep neural decode · latent 512-d", 40),
                        out("  feature extraction · 128 ch", 40),
                        out(
                            String.format(
                                Locale.US,
                                "  ${engine.state.featureRate.toInt()}%% captured · z-score ±3σ · window 2.0 s"
                            ),
                            40
                        ),
                        out("  tensorize 1 × 512 → fp16 · pad 0", 360),
                    )
                },
                Stage {
                    listOf(
                        out("  forward pass · 24 layers", 40),
                        out(
                            String.format(
                                Locale.US,
                                "  npu %.1f%% · %.0f fps · %.0f ms",
                                engine.state.npu, engine.state.fps, engine.state.latencyMs
                            ),
                            40
                        ),
                        out("  kv-cache hit 98.4% · mem 312MB", 360),
                    )
                },
                Stage {
                    val s = engine.state
                    val emotion = when {
                        s.emotionConfidence > 90f -> "FOCUS 专注"
                        s.emotionConfidence > 80f -> "CALM 平静"
                        else -> "TENSION 紧张"
                    }
                    val intent = when {
                        s.attention > 85f -> "TARGET-SEEK"
                        s.attention > 70f -> "OBSERVE"
                        else -> "HOLD"
                    }
                    listOf(
                        ok("  decode output ─────────────────", 60),
                        ok("  emotion   $emotion · ${HudKit.oneDecimal(s.emotionConfidence)}%", 50),
                        ok("  intent    $intent", 50),
                        ok(
                            String.format(
                                Locale.US,
                                "  confidence %.1f%% · acc %.1f%% · mode %s",
                                s.decodeConfidence, s.decodeAccuracy, s.decodeModeLabel
                            ),
                            60
                        ),
                        sys("  decode complete · latent state echoed", 20),
                    )
                },
            )
        )
    }

    private fun train() {
        engine.burst(BurstKind.TRAIN, 6000L)
        play(
            listOf(
                Stage {
                    val s = engine.state
                    listOf(
                        sys("[TRAIN] fine-tune · lr 3.0e-5", 40),
                        out(String.format(Locale.US, "  warm start · step %,d", s.iteration), 40),
                        out(String.format(Locale.US, "  loss %.4f · acc %.2f%%", s.loss, s.decodeAccuracy), 380),
                    )
                },
                Stage {
                    listOf(
                        out("  epoch  3/12   loss 0.0382   acc 96.9%", 340),
                        out("  epoch  6/12   loss 0.0301   acc 97.6%", 340),
                        out("  epoch  9/12   loss 0.0244   acc 98.4%", 340),
                        out("  epoch 12/12   loss 0.0209   acc 99.1%", 420),
                    )
                },
                Stage {
                    val s = engine.state
                    listOf(
                        out("  loss ###########------  0.0209", 60),
                        ok("  calibration stored · weights flushed", 60),
                        ok(
                            String.format(
                                Locale.US,
                                "  final · step %,d · loss 0.0209 · acc 99.10%%",
                                s.iteration
                            ),
                            60
                        ),
                        sys("  train complete · 12/12 epochs", 20),
                    )
                },
            )
        )
    }

    private fun predict() {
        engine.burst(BurstKind.PREDICT)
        play(
            listOf(
                Stage {
                    val s = engine.state
                    val focus = s.attention.coerceIn(10f, 90f)
                    val calm = (100f - s.gsr * 7f).coerceIn(5f, 90f)
                    val tension = (s.gsr * 7.5f).coerceIn(3f, 80f)
                    val excited = ((s.heartRate - 58f) * 0.75f).coerceIn(3f, 70f)
                    val sum = (focus + calm + tension + excited).coerceAtLeast(1f)
                    val emotions = listOf(
                        "focus" to focus / sum * 100f,
                        "calm" to calm / sum * 100f,
                        "tension" to tension / sum * 100f,
                        "excited" to excited / sum * 100f,
                    )
                    val result = ArrayList<ConsoleLine>(6)
                    result.add(sys("[PREDICT] 情绪概率 · horizon 30 s", 40))
                    for ((name, pct) in emotions) {
                        result.add(
                            out(
                                String.format(
                                    Locale.US,
                                    "  %-8s %s %5.1f%%",
                                    name, bar(pct / 100f), pct
                                ),
                                40
                            )
                        )
                    }
                    result.add(out("", 300))
                    result
                },
                Stage {
                    val s = engine.state
                    val dominant = when {
                        s.attention > 85f -> "focus 专注"
                        s.gsr < 6f -> "calm 平静"
                        else -> "tension 紧张"
                    }
                    listOf(
                        ok("  dominant: $dominant · stability 0.87", 50),
                        ok("  intent: ${if (s.attention > 80f) "TARGET-SEEK" else "OBSERVE"}", 50),
                        ok("  置信度 ${HudKit.oneDecimal(s.decodeConfidence)}% · horizon 30 s", 20),
                    )
                },
            )
        )
    }

    private fun model(tokens: List<String>) {
        val rest = tokens.drop(1)
        val arg: String? = when {
            rest.isEmpty() -> null
            rest[0].equals("switch", ignoreCase = true) -> rest.getOrNull(1)
            else -> rest[0]
        }
        val switchWithoutArg = tokens.size == 2 && tokens[1].equals("switch", ignoreCase = true)
        if (arg == null) {
            if (switchWithoutArg) {
                console.enqueue(
                    listOf(
                        err("usage: model switch light|standard|precision", 40),
                        sys("  also accepts 轻量 / 标准 / 高精度", 20),
                    )
                )
            }
            play(
                listOf(
                    Stage {
                        val s = engine.state
                        listOf(
                            sys("decode profiles · 解码档位", 40),
                            out("  light      72 fps  bias -6   轻量"),
                            out("  standard   58 fps  bias  0   标准"),
                            out("  precision  31 fps  bias +6   高精度"),
                            out(""),
                            sys("  current: ${s.decodeModeKey} ${s.decodeModeLabel} · ${s.inferenceMode}", 20),
                        )
                    }
                )
            )
            return
        }

        val mode = engine.setDecodeMode(arg)
        if (mode == null) {
            console.enqueue(
                listOf(
                    err("unknown profile: \"$arg\"", 60),
                    err("usage: model switch light|standard|precision", 40),
                    sys("  also accepts 轻量 / 标准 / 高精度", 20),
                )
            )
            return
        }

        engine.pulse(0.6f)
        play(
            listOf(
                Stage {
                    listOf(
                        ok("decode profile → ${mode.english}", 40),
                        out(
                            String.format(
                                Locale.US,
                                "  label %s · target %.0f fps · bias %+.0f%%",
                                mode.label, mode.targetFps, mode.confidenceBias
                            ),
                            40
                        ),
                        out("  mode ${engine.state.decodeModeLabel} · ${engine.state.inferenceMode}", 380),
                    )
                },
                Stage {
                    val s = engine.state
                    listOf(
                        ok(
                            String.format(
                                Locale.US,
                                "  profile stable · fps %.1f · conf %.1f%%",
                                s.fps, s.decodeConfidence
                            )
                        ),
                        sys("  mode ${s.decodeModeKey} active", 20),
                    )
                },
            )
        )
    }

    private fun unknown(input: String) {
        engine.log(LogLevel.WARN, "CONSOLE", "unknown directive: ${input.take(64)}")
        console.enqueue(
            listOf(
                err("unknown directive: \"${input.take(32)}\"", 60),
                err("type \"help\" for the command list", 20),
            )
        )
    }

    /** ASCII probability bar, [cells] blocks wide. */
    private fun bar(fraction: Float, cells: Int = 14): String {
        val filled = (fraction.coerceIn(0f, 1f) * cells).roundToInt().coerceIn(0, cells)
        val sb = StringBuilder(cells)
        for (i in 0 until cells) {
            sb.append(if (i < filled) '█' else '░')
        }
        return sb.toString()
    }
}
