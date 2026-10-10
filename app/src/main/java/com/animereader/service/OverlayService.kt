package com.animereader.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.animereader.MainActivity
import com.animereader.R
import com.animereader.capture.ScreenCapturer
import com.animereader.data.Catalog
import com.animereader.data.ScanScript
import com.animereader.data.HistoryEntry
import com.animereader.data.HistoryStore
import com.animereader.data.Settings
import com.animereader.data.SettingsRepository
import com.animereader.data.TranslationMethod
import com.animereader.data.VoiceMethod
import com.animereader.internal.ApiKeys
import com.animereader.internal.FrameDiff
import com.animereader.internal.GeminiClient
import com.animereader.internal.MistralClient
import com.animereader.internal.RateGate
import com.animereader.internal.TextDedup
import com.animereader.ocr.Box
import com.animereader.ocr.BubbleClusters
import com.animereader.ocr.ScanText
import com.animereader.ocr.ScreenOcr
import com.animereader.pipeline.BubbleReader
import com.animereader.pipeline.PageBubble
import com.animereader.pipeline.ReadLine
import com.animereader.pipeline.TargetLang
import com.animereader.pipeline.TextTranslation
import com.animereader.pipeline.VisionBatchCall
import com.animereader.pipeline.VisionOcrCall
import com.animereader.translate.GoogleWebTranslator
import com.animereader.translate.Translators
import com.animereader.tts.DeviceTts
import com.animereader.tts.EdgeVoice
import com.animereader.tts.SpeechItem
import com.animereader.tts.SpeechQueue
import com.animereader.tts.VoiceProvider
import com.animereader.ui.theme.resolvePalette
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service implementing the Android overlay model from the design:
 *  - Start  → only the floating bubble (chat-head) is shown.
 *  - Tap    → toggles the reading rectangle + control dock; starts/stops reading.
 *  - Long-press bubble → opens the app.
 *  - Stop   → from the notification or the app.
 * The rectangle auto-dims after a few seconds without interaction.
 *
 * The OCR→translation decisions (cache, rate gates, Gemini→Mistral fallback) live in
 * [BubbleReader]; utterance sequencing lives in [SpeechQueue] — both JVM-unit-tested.
 */
class OverlayService : LifecycleService() {

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var repo: SettingsRepository
    @Volatile private var settings = Settings()

    private lateinit var ocr: ScreenOcr
    private lateinit var deviceTts: DeviceTts
    private lateinit var edgeVoice: EdgeVoice
    private lateinit var reader: BubbleReader
    private lateinit var speech: SpeechQueue
    private val apiKeys = ApiKeys()
    private val googleWeb = GoogleWebTranslator()

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var captureJob: Job? = null

    // Overlay windows
    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var frame: View? = null
    private var moveHandle: View? = null
    private var sizeHandle: View? = null
    private var controls: View? = null
    private var captionBar: View? = null
    private var captionMain: TextView? = null
    private var captionSub: TextView? = null
    private var captionExpanded = false
    private var playImg: ImageView? = null
    private var langBtn: TextView? = null
    private var methodDot: View? = null
    private var voiceImg: ImageView? = null
    private var langPopup: View? = null
    private var langScrim: View? = null
    private var langPopupOpen = false

    private var overlaysAdded = false
    private var expanded = false
    private var playing = false
    @Volatile private var interacting = false
    @Volatile private var lastSpeechId: String? = null
    // Written from the capture loop and replay coroutines → keep visibility across threads.
    @Volatile private var lastOcrErr = 0L
    private val recentSpoken = ArrayDeque<String>()
    private val stableFrames = 2
    @Volatile private var lastGrid: IntArray? = null

    // Geometry (screen pixels)
    private var rx = 0; private var ry = 0; private var rw = 0; private var rh = 0
    private var dimRunnable: Runnable? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = SettingsRepository(this)
        ocr = ScreenOcr()
        deviceTts = DeviceTts(this)
        deviceTts.onSpeechDone = { id -> onUtteranceDone(id) }
        edgeVoice = EdgeVoice(this, deviceTts)
        edgeVoice.onSpeechDone = { id -> onUtteranceDone(id) }
        edgeVoice.onError = { msg ->
            ui.post { android.widget.Toast.makeText(this, "Voix en ligne ⚠️ $msg", android.widget.Toast.LENGTH_LONG).show() }
        }
        reader = BubbleReader(
            backends = listOf(
                BubbleReader.Backend(
                    name = "Gemini",
                    available = { settings.method == TranslationMethod.ONLINE && settings.geminiKey.isNotBlank() },
                    single = VisionOcrCall { jpeg, lang, ctx -> GeminiClient.ocrTranslate(settings.geminiKey, jpeg, lang, ctx) },
                    batch = VisionBatchCall { jpegs, lang, ctx -> GeminiClient.ocrTranslateBatch(settings.geminiKey, jpegs, lang, ctx) },
                    gate = RateGate(minGapMs = 3500L, cooldownMs = 45000L), // ~17 req/min ceiling, 45s after a 429
                ),
                BubbleReader.Backend(
                    name = "Mistral",
                    available = { settings.method == TranslationMethod.ONLINE && settings.mistralKey.isNotBlank() },
                    single = VisionOcrCall { jpeg, lang, ctx -> MistralClient.ocrTranslate(settings.mistralKey, jpeg, lang, ctx) },
                    batch = VisionBatchCall { jpegs, lang, ctx -> MistralClient.ocrTranslateBatch(settings.mistralKey, jpegs, lang, ctx) },
                    gate = RateGate(minGapMs = 1500L, cooldownMs = 45000L),
                ),
            ),
            // Text chain for what the vision backends didn't translate: keyless Google web
            // translate for the whole page in one request (cloud NMT — the quality the
            // screen-translate apps are known for, plus the detected language of each bubble),
            // then on-device ML Kit for whatever Google couldn't do.
            textTranslate = { texts, code -> translateTexts(texts, code) },
            onNotice = { msg -> toastThrottled(msg) },
        )
        speech = SpeechQueue(
            scope = lifecycleScope,
            speak = { item ->
                val provider: VoiceProvider =
                    if (settings.voiceMethod == VoiceMethod.ONLINE) edgeVoice else deviceTts
                provider.speak(item.text, item.langTag, item.rate, item.timbre)
            },
            onItemStart = { item ->
                lastSpeechId = item.id
                ui.post { showCaption(item.original, item.translated) }
            },
        )
        lifecycleScope.launch {
            repo.settings.collect { newS ->
                val prevLang = settings.lang
                settings = newS
                ui.post { if (overlaysAdded) updateDock() }
                // Changing the target language re-translates the current bubble immediately.
                if (overlaysAdded && expanded && playing && prevLang != newS.lang) {
                    speech.clear()
                    stopSpeaking()
                    replayCurrent()
                }
            }
        }
    }

    /** Providers report completion here; the queue advances, and the caption hides when idle. */
    private fun onUtteranceDone(id: String?) {
        speech.signalDone(id)
        ui.post {
            if (settings.ttsEnabled && id != null && id == lastSpeechId && speech.currentId == null) {
                ui.removeCallbacks(hideCaption)
                captionBar?.visibility = View.GONE
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                startAsForeground()
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (projection == null && rc == Activity.RESULT_OK && data != null) setupProjection(rc, data)
                // Wait for the persisted settings (saved rectangle/bubble geometry) before
                // building the overlays, so they reappear where the user left them.
                lifecycleScope.launch {
                    settings = repo.settings.first()
                    withContext(Dispatchers.Main) {
                        if (!overlaysAdded) addOverlays()
                        _running.value = true
                    }
                }
            }
        }
        return START_STICKY
    }

    // ── MediaProjection ──
    private fun setupProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: return
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { stopSelf() } }
        }, ui)
        projection = proj
        val (w, h, dpi) = displaySize()
        capturer = ScreenCapturer(proj, w, h, dpi).also { it.start() }
    }

    private fun displaySize(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dm.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    // ── Overlays ──
    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun accentInt(): Int {
        val pal = resolvePalette(settings.direction, settings.mode, settings.accent)
        return pal.accent.toArgb()
    }
    private fun elevInt(): Int = resolvePalette(settings.direction, settings.mode, settings.accent).elev.toArgb()
    private fun textInt(): Int = resolvePalette(settings.direction, settings.mode, settings.accent).text.toArgb()
    private fun onAccentInt(): Int = resolvePalette(settings.direction, settings.mode, settings.accent).onAccent.toArgb()
    private fun borderInt(): Int = resolvePalette(settings.direction, settings.mode, settings.accent).border.toArgb()
    private fun textMutedInt(): Int = resolvePalette(settings.direction, settings.mode, settings.accent).textMuted.toArgb()
    private fun accentSoftInt(): Int = resolvePalette(settings.direction, settings.mode, settings.accent).accentSoft.toArgb()
    private fun methodColorInt(): Int {
        val p = resolvePalette(settings.direction, settings.mode, settings.accent)
        return if (settings.method == TranslationMethod.OFFLINE) p.offline.toArgb() else p.online.toArgb()
    }

    private fun addOverlays() {
        val (w, h, _) = displaySize()
        val s = settings
        // Restore the saved rectangle/bubble placement; fall back to the design defaults.
        rw = if (s.rectW > 0) s.rectW.coerceIn(dp(120), w) else (w * 0.78f).toInt()
        rh = if (s.rectH > 0) s.rectH.coerceIn(dp(70), h) else dp(120)
        rx = if (s.rectX >= 0) s.rectX.coerceIn(0, (w - rw).coerceAtLeast(0)) else (w - rw) / 2
        ry = if (s.rectY >= 0) s.rectY.coerceIn(0, (h - rh).coerceAtLeast(0)) else (h * 0.34f).toInt()

        // Bubble
        val b = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentInt()) }
            addView(ImageView(this@OverlayService).apply {
                setImageResource(R.drawable.ic_target)
                val pad = dp(13); setPadding(pad, pad, pad, pad)
            })
            elevation = dp(8).toFloat()
        }
        val bp = overlayParams(dp(56), dp(56), touchable = true).apply {
            x = if (s.bubbleX >= 0) s.bubbleX.coerceIn(0, (w - dp(56)).coerceAtLeast(0)) else w - dp(72)
            y = if (s.bubbleY >= 0) s.bubbleY.coerceIn(0, (h - dp(56)).coerceAtLeast(0)) else (h * 0.55f).toInt()
        }
        attachBubbleTouch(b, bp)
        wm.addView(b, bp); bubble = b; bubbleLp = bp

        // Rectangle frame (non-touchable — manga scrolls through it)
        val f = View(this).apply { background = frameDrawable() }
        wm.addView(f, overlayParams(rw, rh, touchable = false).apply { x = rx; y = ry }); frame = f

        // Handles (separate touchable windows)
        moveHandle = handleView(R.drawable.ic_target).also {
            wm.addView(it, overlayParams(dp(30), dp(30), true).apply { x = rx - dp(15); y = ry - dp(15) })
            attachMoveTouch(it)
        }
        sizeHandle = handleView(R.drawable.ic_target).also {
            wm.addView(it, overlayParams(dp(30), dp(30), true).apply { x = rx + rw - dp(15); y = ry + rh - dp(15) })
            attachSizeTouch(it)
        }

        // Control dock at the bottom
        controls = buildControls().also {
            wm.addView(it, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(24)
            })
        }

        // Subtitle bar just above the dock (its own window so the blur-behind only frosts the bar)
        captionBar = buildCaptionBar().also {
            val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(100)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.setBlurBehindRadius(dp(16))
            }
            wm.addView(it, lp)
        }

        // Language dropdown views are built now but only attached to the window manager while
        // open (a full-screen touchable scrim must not sit in the tree while closed).
        langScrim = buildScrim()
        langPopup = buildLangPopup()

        overlaysAdded = true
        setExpanded(false)
    }

    private fun frameDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setStroke(dp(3), accentInt())
        setColor(AColor.argb(20, AColor.red(accentInt()), AColor.green(accentInt()), AColor.blue(accentInt())))
    }

    private fun handleView(icon: Int) = ImageView(this).apply {
        setImageResource(icon)
        val pad = dp(7); setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentInt()) }
    }

    private fun buildControls(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat(); setColor(elevInt()); setStroke(dp(1), borderInt())
            }
            elevation = dp(6).toFloat()
        }
        // Play / pause — accent circle
        playImg = ImageView(this).apply {
            setImageResource(R.drawable.ic_play); setColorFilter(onAccentInt())
            val p = dp(12); setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentInt()) }
        }
        dock.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            addView(playImg, FrameLayout.LayoutParams(dp(44), dp(44)))
            setOnClickListener { togglePlay() }
        })
        // re-read the current bubble on demand (bypasses the de-dup)
        dock.addView(dockIcon(R.drawable.ic_replay, textMutedInt()) { replayCurrent() })
        dock.addView(dockSep())
        // Language pill — tap opens the language dropdown
        val langPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(7), dp(9), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(AColor.TRANSPARENT) }
            setOnClickListener { toggleLangPopup() }
        }
        methodDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(7) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(methodColorInt()) }
        }
        langPill.addView(methodDot)
        langBtn = TextView(this).apply {
            text = Catalog.lang(settings.lang).tag
            setTextColor(textInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        langPill.addView(langBtn)
        langPill.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_down); setColorFilter(textMutedInt())
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { leftMargin = dp(2) }
        })
        dock.addView(langPill)
        // Voice on/off — off = translate-only (no audio)
        voiceImg = dockIcon(
            if (settings.ttsEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off,
            if (settings.ttsEnabled) accentInt() else textMutedInt(),
        ) { toggleVoice() }
        dock.addView(voiceImg)
        return dock
    }

    private fun dockSep() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(26)).apply { leftMargin = dp(3); rightMargin = dp(3) }
        setBackgroundColor(borderInt())
    }

    private fun dockIcon(res: Int, tint: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint)
        val p = dp(10); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AColor.TRANSPARENT) }
        setOnClickListener { onClick() }
    }

    // ── Language dropdown (popover above the dock) ──
    private fun buildLangPopup(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(elevInt()); setStroke(dp(1), borderInt())
            }
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }
        Catalog.languages.forEach { l ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(20), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(AColor.TRANSPARENT) }
                tag = l.code
                setOnClickListener { setLang(l.code) }
            }
            row.addView(TextView(this).apply {
                text = l.tag; setWidth(dp(34))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(this).apply {
                text = l.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            })
            card.addView(row)
        }
        return card
    }

    private fun buildScrim(): View = View(this).apply {
        setBackgroundColor(AColor.argb(36, 0, 0, 0))
        visibility = View.GONE
        setOnClickListener { closeLangPopup() }
    }

    private fun refreshLangPopup() {
        val card = langPopup as? LinearLayout ?: return
        for (i in 0 until card.childCount) {
            val row = card.getChildAt(i) as? LinearLayout ?: continue
            val on = (row.tag as? String) == settings.lang
            (row.background as? GradientDrawable)?.setColor(if (on) accentSoftInt() else AColor.TRANSPARENT)
            (row.getChildAt(0) as? TextView)?.setTextColor(if (on) accentInt() else textInt())
            (row.getChildAt(1) as? TextView)?.setTextColor(if (on) accentInt() else textMutedInt())
        }
    }

    private fun toggleLangPopup() { if (langPopupOpen) closeLangPopup() else openLangPopup() }
    private fun openLangPopup() {
        if (langPopupOpen) return
        val scrim = langScrim ?: return
        val pop = langPopup ?: return
        langPopupOpen = true
        refreshLangPopup()
        scrim.visibility = View.VISIBLE; pop.visibility = View.VISIBLE
        runCatching {
            wm.addView(scrim, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable = true))
            wm.addView(pop, overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(84)
            })
        }
    }
    private fun closeLangPopup() {
        if (!langPopupOpen) return
        langPopupOpen = false
        runCatching { langScrim?.let { wm.removeView(it) } }
        runCatching { langPopup?.let { wm.removeView(it) } }
    }

    private fun setLang(code: String) {
        lifecycleScope.launch { repo.update { it.copy(lang = code) } }
        closeLangPopup()
    }

    private fun toggleVoice() {
        lifecycleScope.launch { repo.update { it.copy(ttsEnabled = !it.ttsEnabled) } }
        speech.clear()
        stopSpeaking()
    }

    private fun stopSpeaking() {
        deviceTts.stop()
        if (::edgeVoice.isInitialized) edgeVoice.stop()
    }

    /** Re-read whatever is currently in the rectangle, ignoring the de-dup memory. */
    private fun replayCurrent() {
        lifecycleScope.launch(Dispatchers.Default) {
            val bmp = capturer?.captureRegion(Rect(rx, ry, rx + rw, ry + rh)) ?: return@launch
            val page = pageFromBitmap(bmp)
            bmp.recycle()
            if (page.isEmpty()) return@launch
            val norm = TextDedup.normalize(page.joinToString(" ") { it.mlkitText })
            synchronized(recentSpoken) {
                if (norm !in recentSpoken) { recentSpoken.addLast(norm); while (recentSpoken.size > 8) recentSpoken.removeFirst() }
            }
            handlePage(page)
        }
    }

    private fun visionEnabled(): Boolean =
        settings.method == TranslationMethod.ONLINE &&
            (settings.geminiKey.isNotBlank() || settings.mistralKey.isNotBlank())

    private suspend fun translateTexts(texts: List<String>, code: String): List<TextTranslation?> {
        val viaGoogle =
            if (settings.method == TranslationMethod.ONLINE)
                runCatching { withContext(Dispatchers.IO) { googleWeb.translateBatch(texts, code) } }.getOrNull()
            else null
        return texts.mapIndexed { i, text ->
            viaGoogle?.getOrNull(i)?.let { return@mapIndexed TextTranslation(it.text, "Google", it.detected) }
            runCatching {
                TextTranslation(
                    Translators.create(TranslationMethod.OFFLINE, settings.provider, apiKeys).translate(text, code),
                    "ML Kit", null,
                )
            }.getOrNull()
        }
    }

    /**
     * OCR the frame with the model of the scans' script, group the text into bubbles, drop
     * scan-group watermarks and stray marks, order the bubbles for reading and crop each one.
     */
    private suspend fun pageFromBitmap(bmp: Bitmap): List<PageBubble> {
        val s = settings
        val pieces = runCatching { ocr.recognize(bmp, s.scanScript) }.getOrDefault(emptyList())
        val clustered = if (s.scanScript == ScanScript.LATIN) {
            BubbleClusters.clusterLines(pieces.filterNot { ScanText.isWatermark(it.text) })
                .map { it.copy(text = ScanText.polish(it.text)) }
        } else {
            val gap = (minOf(bmp.width, bmp.height) * 0.06f).toInt().coerceAtLeast(dp(10))
            BubbleClusters.cluster(pieces, gap)
        }
        val bubbles = BubbleClusters.readingOrder(clustered.filterNot { ScanText.isNoise(it.text) }, rtl = s.readingRtl)
        val withJpeg = visionEnabled()
        return bubbles.map { b -> PageBubble(b.text, if (withJpeg) cropJpeg(bmp, b.box) else null) }
    }

    /** Crop a bubble (with padding), downscale big crops, and JPEG-encode for upload. */
    private fun cropJpeg(bmp: Bitmap, box: Box): ByteArray {
        val pad = dp(8)
        val l = (box.left - pad).coerceIn(0, bmp.width - 1)
        val t = (box.top - pad).coerceIn(0, bmp.height - 1)
        val r = (box.right + pad).coerceIn(l + 1, bmp.width)
        val btm = (box.bottom + pad).coerceIn(t + 1, bmp.height)
        var crop = Bitmap.createBitmap(bmp, l, t, r - l, btm - t)
        val maxDim = 1024
        val longest = maxOf(crop.width, crop.height)
        if (longest > maxDim) {
            val sc = maxDim.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                crop, (crop.width * sc).toInt().coerceAtLeast(1), (crop.height * sc).toInt().coerceAtLeast(1), true,
            )
            if (scaled !== crop) { crop.recycle(); crop = scaled }
        }
        val out = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (crop !== bmp) crop.recycle()
        return out.toByteArray()
    }

    /** Subtitle bar: dark high-contrast scrim, white translation (translation-only),
     *  tap to reveal the original. Theme-independent so it stays readable over any page. */
    private fun buildCaptionBar(): View {
        val maxW = displaySize().first - dp(40)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(AColor.argb(186, 8, 8, 12)) }
            visibility = View.GONE
        }
        captionMain = TextView(this).apply {
            setTextColor(AColor.WHITE)
            gravity = Gravity.CENTER
            maxWidth = maxW
            setLineSpacing(0f, 1.1f)
        }
        captionSub = TextView(this).apply {
            setTextColor(AColor.argb(155, 255, 255, 255))
            gravity = Gravity.CENTER
            maxWidth = maxW
            setPadding(0, dp(5), 0, 0)
            visibility = View.GONE
        }
        bar.addView(captionMain); bar.addView(captionSub)
        bar.setOnClickListener {
            captionExpanded = !captionExpanded
            captionSub?.visibility =
                if (captionExpanded && (captionSub?.text?.isNotEmpty() == true)) View.VISIBLE else View.GONE
        }
        return bar
    }

    // ── Touch handlers ──
    private fun attachBubbleTouch(view: View, lp: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var rawX = 0f; var rawY = 0f
        var moved = false; var longFired = false
        val longPress = Runnable { if (!moved) { longFired = true; openApp() } }
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; rawX = e.rawX; rawY = e.rawY
                    moved = false; longFired = false
                    ui.postDelayed(longPress, 480); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - rawX).toInt(); val dy = (e.rawY - rawY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4))) {
                        moved = true; ui.removeCallbacks(longPress)
                    }
                    if (moved) { lp.x = startX + dx; lp.y = startY + dy; safeUpdate(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longPress)
                    if (!moved && !longFired) toggleExpanded()
                    if (moved) saveBubblePos(lp.x, lp.y)
                    true
                }
                else -> false
            }
        }
    }

    private fun attachMoveTouch(handle: View) {
        var sx = 0; var sy = 0; var rawX = 0f; var rawY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = rx; sy = ry; rawX = e.rawX; rawY = e.rawY; interacting = true; wake(); true }
                MotionEvent.ACTION_MOVE -> {
                    rx = sx + (e.rawX - rawX).toInt(); ry = sy + (e.rawY - rawY).toInt()
                    syncRect(); wake(); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.postDelayed({ interacting = false }, 350)
                    saveRectGeometry(); true
                }
                else -> true
            }
        }
    }

    private fun attachSizeTouch(handle: View) {
        var sw = 0; var sh = 0; var rawX = 0f; var rawY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sw = rw; sh = rh; rawX = e.rawX; rawY = e.rawY; interacting = true; wake(); true }
                MotionEvent.ACTION_MOVE -> {
                    rw = (sw + (e.rawX - rawX).toInt()).coerceAtLeast(dp(120))
                    rh = (sh + (e.rawY - rawY).toInt()).coerceAtLeast(dp(70))
                    syncRect(); wake(); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.postDelayed({ interacting = false }, 350)
                    saveRectGeometry(); true
                }
                else -> true
            }
        }
    }

    /** Persist the rectangle so the next session reopens it exactly where the user left it. */
    private fun saveRectGeometry() {
        val x = rx; val y = ry; val w = rw; val h = rh
        lifecycleScope.launch { repo.update { it.copy(rectX = x, rectY = y, rectW = w, rectH = h) } }
    }

    private fun saveBubblePos(x: Int, y: Int) {
        lifecycleScope.launch { repo.update { it.copy(bubbleX = x, bubbleY = y) } }
    }

    private fun syncRect() {
        frame?.let { safeUpdate(it, (it.layoutParams as WindowManager.LayoutParams).apply { x = rx; y = ry; width = rw; height = rh }) }
        moveHandle?.let { safeUpdate(it, (it.layoutParams as WindowManager.LayoutParams).apply { x = rx - dp(15); y = ry - dp(15) }) }
        sizeHandle?.let { safeUpdate(it, (it.layoutParams as WindowManager.LayoutParams).apply { x = rx + rw - dp(15); y = ry + rh - dp(15) }) }
    }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) { runCatching { wm.updateViewLayout(v, lp) } }

    private fun wake() {
        frame?.alpha = 1f; moveHandle?.alpha = 1f; sizeHandle?.alpha = 1f
        dimRunnable?.let { ui.removeCallbacks(it) }
        dimRunnable = Runnable { frame?.alpha = 0.22f; moveHandle?.alpha = 0.3f; sizeHandle?.alpha = 0.3f }
        ui.postDelayed(dimRunnable!!, 2500)
    }

    // ── State ──
    private fun toggleExpanded() = setExpanded(!expanded)

    private fun setExpanded(value: Boolean) {
        expanded = value
        val vis = if (value) View.VISIBLE else View.GONE
        frame?.visibility = vis; moveHandle?.visibility = vis; sizeHandle?.visibility = vis; controls?.visibility = vis
        if (value) { playing = true; wake(); startLoop(); updateDock() }
        else {
            playing = false; stopLoop(); speech.clear(); stopSpeaking()
            ui.removeCallbacks(hideCaption); captionBar?.visibility = View.GONE; closeLangPopup()
        }
    }

    private fun togglePlay() {
        playing = !playing
        if (playing) startLoop() else { stopLoop(); speech.clear(); stopSpeaking() }
        updateDock()
    }

    private fun updateDock() {
        playImg?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playImg?.setColorFilter(onAccentInt())
        langBtn?.text = Catalog.lang(settings.lang).tag
        methodDot?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(methodColorInt()) }
        voiceImg?.setImageResource(if (settings.ttsEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off)
        voiceImg?.setColorFilter(if (settings.ttsEnabled) accentInt() else textMutedInt())
        if (langPopupOpen) refreshLangPopup()
    }

    // ── Capture → bubbles → OCR → translate → speak ──
    private fun startLoop() {
        if (captureJob?.isActive == true) return
        synchronized(recentSpoken) { recentSpoken.clear() }
        lastGrid = null
        captureJob = lifecycleScope.launch(Dispatchers.Default) {
            var pending = ""
            var stable = 0
            while (isActive && expanded && playing) {
                if (interacting) { delay(150); continue }   // don't read while the frame is moving
                val region = Rect(rx, ry, rx + rw, ry + rh)
                val bmp = capturer?.captureRegion(region)
                if (bmp != null) {
                    // Cheap luminance diff: while the page hasn't moved and the current text is
                    // already settled, skip the OCR entirely (battery + CPU).
                    val grid = sampleGrid(bmp)
                    val unchanged = FrameDiff.changedFraction(lastGrid, grid) < 0.006
                    lastGrid = grid
                    if (unchanged && stable >= stableFrames) {
                        bmp.recycle()
                    } else {
                        val page = pageFromBitmap(bmp)
                        bmp.recycle()
                        val norm = TextDedup.normalize(page.joinToString(" ") { it.mlkitText })
                        var trigger = false
                        if (norm.length >= 2) {
                            if (norm == pending) stable++ else { pending = norm; stable = 1 }
                            // Read only once the OCR has settled (same text across captures) and
                            // the page isn't a repeat/continuation of something we just read.
                            if (stable == stableFrames && !isRecentDuplicate(norm)) {
                                trigger = true
                                synchronized(recentSpoken) {
                                    recentSpoken.addLast(norm); while (recentSpoken.size > 8) recentSpoken.removeFirst()
                                }
                            }
                        }
                        if (trigger) handlePage(page)
                    }
                }
                delay((settings.captureInterval * 1000).toLong().coerceAtLeast(400))
            }
        }
    }

    /** 32×32 luminance grid of the captured region, for the frame-diff skip. */
    private fun sampleGrid(bmp: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(bmp, 32, 32, false)
        val px = IntArray(32 * 32)
        s.getPixels(px, 0, 32, 0, 0, 32, 32)
        if (s !== bmp) s.recycle()
        return FrameDiff.luminance(px)
    }

    private fun isRecentDuplicate(norm: String): Boolean {
        val snapshot = synchronized(recentSpoken) { recentSpoken.toList() }
        return TextDedup.isDuplicate(norm, snapshot)
    }

    private fun stopLoop() { captureJob?.cancel(); captureJob = null }

    /**
     * Read every bubble of the page: ONE online request for all of them (see
     * [BubbleReader.readPage]), then queue the utterances in manga order — the
     * speech queue reads them one after the other without cutting.
     */
    private suspend fun handlePage(page: List<PageBubble>) {
        if (page.isEmpty()) return
        val s = settings
        // Open the voice socket now so its ~0.7 s handshake overlaps the OCR/translation wait.
        if (s.ttsEnabled && s.voiceMethod == VoiceMethod.ONLINE) edgeVoice.prewarm()

        val lines = reader.readPage(page, TargetLang(s.lang, langName(s.lang)))
        val spoken = ArrayList<ReadLine>()
        for (line in lines) {
            if (line.original.isBlank()) continue
            spoken.add(line)
            if (s.ttsEnabled) {
                val toSpeak = line.translated ?: line.original
                speech.enqueue(
                    SpeechItem(
                        id = toSpeak.hashCode().toString(),
                        text = toSpeak,
                        // The voice follows the text actually read: the target language for a
                        // translation, otherwise the bubble's own language (EN scan → EN voice).
                        langTag = if (line.translated != null) s.lang else line.lang ?: detectSpokenLang(line.original),
                        rate = s.speed,
                        timbre = s.timbre,
                        original = line.original,
                        translated = line.translated,
                    )
                )
            }
            OverlayController.pushHistory(
                HistoryEntry(
                    time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    original = line.original,
                    translated = line.translated,
                    langTag = if (line.translated != null) s.lang.uppercase() else null,
                    method = if (line.translated != null) s.method else null,
                    providerName = line.provider,
                )
            )
        }
        // Translate-only mode has no utterance pacing → show everything at once.
        if (!s.ttsEnabled && spoken.isNotEmpty()) {
            val original = spoken.joinToString("  ") { it.original }
            val translated = spoken.mapNotNull { it.translated }.joinToString("  ").ifBlank { null }
            withContext(Dispatchers.Main) { showCaption(original, translated) }
        }
    }

    /** Natural-language name of the target for the vision prompts ("anglais", "coréen", …). */
    private fun langName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH).ifBlank { "anglais" }

    /** Voice language when reading untranslated source: Hangul → KO, CJK → JA, else FR. */
    private fun detectSpokenLang(text: String): String = when {
        text.any { it.code in 0xAC00..0xD7AF || it.code in 0x1100..0x11FF } -> "ko"
        text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF } -> "ja"
        else -> "fr"
    }

    private fun toastThrottled(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastOcrErr > 5000) {
            lastOcrErr = now
            ui.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showCaption(original: String, translated: String?) {
        val main = captionMain ?: return
        val sub = captionSub ?: return
        val bar = captionBar ?: return
        val scale = settings.captionScale
        main.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * scale)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * scale)
        // Translation-only by default: the translation is the main line; the original (FR)
        // is the secondary line, hidden until the user taps the bar.
        if (!translated.isNullOrBlank()) {
            main.text = translated
            sub.text = original
        } else {
            main.text = original
            sub.text = ""
        }
        sub.visibility = if (captionExpanded && sub.text.isNotEmpty()) View.VISIBLE else View.GONE
        bar.visibility = View.VISIBLE
        ui.removeCallbacks(hideCaption)
        // When reading aloud, the utterance's onDone hides it; keep a long safety fallback.
        // In translate-only mode there's no audio, so use a fixed readable duration.
        ui.postDelayed(hideCaption, if (settings.ttsEnabled) 12000L else 4500L)
    }
    private val hideCaption = Runnable { captionBar?.visibility = View.GONE }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    // ── Foreground notification ──
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(getString(R.string.service_notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.service_notif_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        _running.value = false
        stopLoop()
        speech.clear()
        listOfNotNull(bubble, frame, moveHandle, sizeHandle, controls, captionBar, langPopup, langScrim).forEach { runCatching { wm.removeView(it) } }
        capturer?.stop()
        runCatching { projection?.stop() }
        ocr.close(); deviceTts.shutdown(); edgeVoice.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.animereader.START"
        const val ACTION_STOP = "com.animereader.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val CHANNEL = "animereader_service"
        private const val NOTIF_ID = 42

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
    }
}

/** App-facing control surface for the overlay service + the persisted reading history. */
object OverlayController {
    val running: StateFlow<Boolean> get() = OverlayService.running

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var store: HistoryStore? = null

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    /** Called once from Application.onCreate: reload the saved history from disk. */
    fun bind(context: Context) {
        if (store != null) return
        val s = HistoryStore(context.applicationContext)
        store = s
        io.launch {
            val loaded = s.load()
            if (loaded.isNotEmpty()) _history.value = (_history.value + loaded).take(MAX_HISTORY)
        }
    }

    fun pushHistory(entry: HistoryEntry) {
        val next = (listOf(entry) + _history.value).take(MAX_HISTORY)
        _history.value = next
        store?.let { s -> io.launch { s.save(next) } }
    }

    fun start(context: Context, resultCode: Int, data: Intent) {
        val i = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(context, i)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP })
    }

    private const val MAX_HISTORY = 100
}
