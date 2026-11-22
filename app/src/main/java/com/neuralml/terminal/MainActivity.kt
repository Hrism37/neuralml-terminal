package com.neuralml.terminal

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.neuralml.terminal.core.CommandProcessor
import com.neuralml.terminal.core.SimulationEngine
import com.neuralml.terminal.ui.BiometricsPanelView
import com.neuralml.terminal.ui.BootOverlayView
import com.neuralml.terminal.ui.BrainWireframeView
import com.neuralml.terminal.ui.CodeRainView
import com.neuralml.terminal.ui.ConsoleLevel
import com.neuralml.terminal.ui.ConsoleLine
import com.neuralml.terminal.ui.ConsoleView
import com.neuralml.terminal.ui.CrtOverlayView
import com.neuralml.terminal.ui.EngineStatusPanelView
import com.neuralml.terminal.ui.LogPanelView
import com.neuralml.terminal.ui.SyncMeterView
import com.neuralml.terminal.ui.TopStatusBarView
import com.neuralml.terminal.ui.WaveformView

/**
 * Single-activity terminal shell.
 *
 * Responsibilities:
 *  - immersive + keep-screen-on window setup
 *  - bind the custom views to the one [SimulationEngine]
 *  - drive a single invalidation pass per engine frame (no per-view timers)
 *  - own the boot self-test overlay and the console command processor
 */
class MainActivity : Activity() {

    private lateinit var engine: SimulationEngine
    private lateinit var consoleView: ConsoleView
    private lateinit var bootOverlay: BootOverlayView
    private lateinit var processor: CommandProcessor

    /** Views that redraw once per engine frame. */
    private val animatedViews = ArrayList<View>(10)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bootDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        applyImmersiveMode()

        engine = SimulationEngine()
        bindViews()

        processor = CommandProcessor(engine, consoleView)
        consoleView.onCommand = { raw -> processor.execute(raw) }

        bootOverlay.onFinished = { finishBoot() }
        bootOverlay.startBoot()
        // Safety net: never leave the UI behind the boot overlay if its callback
        // is lost (view detached/re-attached mid-boot).
        mainHandler.postDelayed({ finishBoot() }, 3600L)

        engine.frameListener = {
            for (i in animatedViews.indices) {
                animatedViews[i].invalidate()
            }
        }
        engine.start()
    }

    private fun bindViews() {
        val codeRain = findViewById<CodeRainView>(R.id.codeRain)
        val topBar = findViewById<TopStatusBarView>(R.id.topBar)
        val biometrics = findViewById<BiometricsPanelView>(R.id.biometricsPanel)
        val enginePanel = findViewById<EngineStatusPanelView>(R.id.enginePanel)
        val waveform = findViewById<WaveformView>(R.id.waveformView)
        val brain = findViewById<BrainWireframeView>(R.id.brainView)
        val syncMeter = findViewById<SyncMeterView>(R.id.syncMeter)
        val logPanel = findViewById<LogPanelView>(R.id.logPanel)
        val crt = findViewById<CrtOverlayView>(R.id.crtOverlay)

        topBar.bind(engine)
        biometrics.bind(engine)
        enginePanel.bind(engine)
        waveform.bind(engine)
        brain.bind(engine)
        syncMeter.bind(engine)
        logPanel.bind(engine)

        animatedViews.clear()
        animatedViews.add(codeRain)
        animatedViews.add(topBar)
        animatedViews.add(biometrics)
        animatedViews.add(enginePanel)
        animatedViews.add(waveform)
        animatedViews.add(brain)
        animatedViews.add(syncMeter)
        animatedViews.add(logPanel)
        animatedViews.add(crt)

        consoleView = findViewById(R.id.consoleView)
        bootOverlay = findViewById(R.id.bootOverlay)
    }

    /** Boot sequence finished: fade the overlay out and print the console banner. */
    private fun finishBoot() {
        if (bootDone) return
        bootDone = true
        bootOverlay.animate()
            .alpha(0f)
            .setDuration(320L)
            .withEndAction {
                bootOverlay.visibility = View.GONE
                consoleView.focusInput()
            }
            .start()

        consoleView.enqueue(
            listOf(
                ConsoleLine("NEURAL-ML DECODER TERMINAL v5.1.0 · LOCAL NODE", ConsoleLevel.SYS),
                ConsoleLine("link established · AES-256 channel · 4-ch EEG front-end", ConsoleLevel.SYS),
                ConsoleLine("NeuralDecoder-XL v3.7.2 ready · type \"help\" for commands", ConsoleLevel.OK),
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        if (this::engine.isInitialized) engine.start()
    }

    override fun onPause() {
        super.onPause()
        // Suspend the simulation loop so a backgrounded app uses no CPU.
        if (this::engine.isInitialized) engine.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (this::engine.isInitialized) engine.stop()
        if (this::consoleView.isInitialized) consoleView.release()
        super.onDestroy()
    }
}
