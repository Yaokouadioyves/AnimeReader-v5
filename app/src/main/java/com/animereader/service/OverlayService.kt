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
import android.graphics.RectF
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
import com.animereader.ocr.Bubble
import com.animereader.ocr.BubbleClusters
import com.animereader.ocr.BubbleEdges
import com.animereader.ocr.Edge
import com.animereader.ocr.ScanText
import com.animereader.ocr.ScreenOcr
import com.animereader.overlay.OverlayLayout
import com.animereader.overlay.TranslationFrameView
import com.animereader.pipeline.BubbleReader
import com.animereader.pipeline.PageBubble
import com.animereader.pipeline.ReadLine
import com.animereader.pipeline.ScrollTracker
import com.animereader.pipeline.TargetLang
import com.animereader.pipeline.TextTranslation
import com.animereader.pipeline.VisionBatchCall
import com.animereader.pipeline.VisionOcrCall
import com.animereader.translate.GoogleWebTranslator
import com.animereader.translate.Translators
import com.animereader.tts.DeviceTts
import com.animereader.tts.EdgeVoice
import com.animereader.tts.PhononVoice
import com.animereader.tts.SpeechItem
import com.animereader.tts.SpeechQueue
import com.animereader.tts.SpeechText
import com.animereader.tts.VoiceProvider
import com.animereader.ui.theme.resolvePalette
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
    private lateinit var phononVoice: PhononVoice
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
    private var frame: TranslationFrameView? = null
    private var frameBg: GradientDrawable? = null
    private var touchWatcher: View? = null
    private var overlayImg: ImageView? = null
    private var moveHandle: View? = null
    private var sizeHandle: View? = null
    private var controls: View? = null
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
    /** Which bubbles of the scrolling strip are whole, settled and not read yet. */
    private val tracker = ScrollTracker()
    @Volatile private var lastGrid: IntArray? = null

    // Overlay mode: translations drawn over the bubbles of a still page.
    /** Boxes (region pixels) of the translations on screen; empty = none drawn. */
    @Volatile private var overlayBoxes: List<Box> = emptyList()
    /** Bumped whenever the page may have moved: a translation computed before is not drawn. */
    @Volatile private var overlayGen = 0
    /** The capture may still show our just-hidden drawing until then → no OCR. */
    @Volatile private var ocrPausedUntil = 0L
    /** Our last drawn translations (normalised) and when they were hidden — never read as page text. */
    @Volatile private var recentOverlay: Pair<Long, List<String>> = 0L to emptyList()
    /** Set by a touch anywhere on the screen: the loop restarts its "is the page still" watch. */
    @Volatile private var touchSeen = false
    /** Original text of the bubble being read aloud (outlined when its translation is drawn). */
    @Volatile private var speakingOriginal: String? = null

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
        phononVoice = PhononVoice(this, deviceTts)
        phononVoice.onSpeechDone = { id -> onUtteranceDone(id) }
        phononVoice.onError = { msg ->
            ui.post { android.widget.Toast.makeText(this, "Voix Phonon ⚠️ $msg", android.widget.Toast.LENGTH_LONG).show() }
        }
        // Edge failing (no network, endpoint down) → the offline neural voice, then the device one.
        edgeVoice = EdgeVoice(this, phononVoice)
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
                val provider: VoiceProvider = when (settings.voiceMethod) {
                    VoiceMethod.ONLINE -> edgeVoice
                    VoiceMethod.PHONON -> phononVoice
                    VoiceMethod.OFFLINE -> deviceTts
                }
                provider.speak(item.text, item.langTag, item.rate, item.timbre)
            },
            onItemStart = { item ->
                lastSpeechId = item.id
                speakingOriginal = item.original
                ui.post {
                    // The translation is already drawn on the bubble → outline it while it is read.
                    if (settings.overlayMode && frame?.hasKey(item.original) == true) frame?.highlight(item.original)
                }
            },
            // Synthesize the next bubbles while the current one plays → no gap between them.
            prefetch = { item ->
                when (settings.voiceMethod) {
                    VoiceMethod.ONLINE -> edgeVoice.prefetch(item.text, item.langTag, item.rate, item.timbre)
                    VoiceMethod.PHONON -> phononVoice.prefetch(item.text, item.langTag, item.rate, item.timbre)
                    VoiceMethod.OFFLINE -> {}
                }
            },
        )
        lifecycleScope.launch {
            repo.settings.collect { newS ->
                val prevLang = settings.lang
                val prevOverlay = settings.overlayMode
                settings = newS
                ui.post { if (overlaysAdded) updateDock() }
                if (prevOverlay != newS.overlayMode) {
                    clearOverlays()
                    touchSeen = true // re-examine the still page (draws or not, in the new mode)
                    if (!newS.overlayMode) ui.post { frame?.highlight(null) }
                }
                // Changing the target language re-translates the current bubble immediately.
                if (overlaysAdded && expanded && playing && prevLang != newS.lang) {
                    speech.clear()
                    stopSpeaking()
                    replayCurrent()
                }
            }
        }
    }

    /** Providers report completion here; the queue advances, and the outline goes when idle. */
    private fun onUtteranceDone(id: String?) {
        speech.signalDone(id)
        ui.post {
            if (settings.ttsEnabled && id != null && id == lastSpeechId && speech.currentId == null) {
                frame?.highlight(null)
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

    private fun withAlpha(color: Int, alpha: Int): Int =
        AColor.argb(alpha, AColor.red(color), AColor.green(color), AColor.blue(color))

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
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(accentInt())
                setStroke(dp(2), AColor.argb(90, 255, 255, 255))
            }
            addView(ImageView(this@OverlayService).apply {
                setImageResource(R.drawable.ic_bubble_logo)
                val pad = dp(9); setPadding(pad, pad, pad, pad)
            })
            elevation = dp(5).toFloat()
        }
        val bp = overlayParams(dp(BUBBLE_DP), dp(BUBBLE_DP), touchable = true).apply {
            x = if (s.bubbleX >= 0) s.bubbleX.coerceIn(0, (w - dp(BUBBLE_DP)).coerceAtLeast(0)) else w - dp(BUBBLE_DP + 14)
            y = if (s.bubbleY >= 0) s.bubbleY.coerceIn(0, (h - dp(BUBBLE_DP)).coerceAtLeast(0)) else (h * 0.55f).toInt()
        }
        attachBubbleTouch(b, bp)
        wm.addView(b, bp); bubble = b; bubbleLp = bp

        // Rectangle frame + the translations drawn over the bubbles (non-touchable — the manga
        // scrolls through it). Android 12+ only lets a touch through an untrusted overlay window
        // whose opacity is ≤ 0.8: above that, a scroll starting on the rectangle is swallowed.
        val bg = frameDrawable().also { frameBg = it }
        val f = TranslationFrameView(this).apply { background = bg; accent = accentInt() }
        wm.addView(f, overlayParams(rw, rh, touchable = false).apply { x = rx; y = ry; alpha = MAX_PASS_THROUGH_ALPHA })
        frame = f

        // Invisible 1-px window told about every touch elsewhere on the screen (ACTION_OUTSIDE):
        // the drawn translations vanish the moment the user touches the page to scroll.
        touchWatcher = View(this).also { v ->
            v.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onUserTouch()
                false
            }
            wm.addView(v, overlayParams(1, 1, touchable = true).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                alpha = 0f
            })
        }

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
            wm.addView(it, overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(14)
            })
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
            setPadding(dp(4), dp(4), dp(6), dp(4))
            // Slightly see-through glass pill: the page stays readable behind it.
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat(); setColor(withAlpha(elevInt(), 232))
                setStroke(dp(1), withAlpha(borderInt(), 150))
            }
            elevation = dp(4).toFloat()
        }
        // Play / pause — accent circle
        playImg = ImageView(this).apply {
            setImageResource(R.drawable.ic_play); setColorFilter(onAccentInt())
            val p = dp(8); setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentInt()) }
        }
        dock.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(DOCK_BTN_DP), dp(DOCK_BTN_DP)).apply { rightMargin = dp(2) }
            addView(playImg, FrameLayout.LayoutParams(dp(DOCK_BTN_DP), dp(DOCK_BTN_DP)))
            setOnClickListener { togglePlay() }
        })
        // re-read the current bubble on demand (bypasses the de-dup)
        dock.addView(dockIcon(R.drawable.ic_replay, textMutedInt()) { replayCurrent() })
        // Translations on the bubbles ↔ subtitle bar (off = see the original page again)
        overlayImg = dockIcon(R.drawable.ic_translate, if (settings.overlayMode) accentInt() else textMutedInt()) {
            lifecycleScope.launch { repo.update { it.copy(overlayMode = !it.overlayMode) } }
        }
        dock.addView(overlayImg)
        dock.addView(dockSep())
        // Language pill — tap opens the language dropdown
        val langPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(AColor.TRANSPARENT) }
            setOnClickListener { toggleLangPopup() }
        }
        methodDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(5) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(methodColorInt()) }
        }
        langPill.addView(methodDot)
        langBtn = TextView(this).apply {
            text = Catalog.lang(settings.lang).tag
            setTextColor(textInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        langPill.addView(langBtn)
        langPill.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_down); setColorFilter(textMutedInt())
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply { leftMargin = dp(1) }
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
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(16)).apply { leftMargin = dp(4); rightMargin = dp(2) }
        setBackgroundColor(withAlpha(borderInt(), 170))
    }

    private fun dockIcon(res: Int, tint: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint)
        val p = dp(8); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(DOCK_BTN_DP), dp(DOCK_BTN_DP))
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
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(60)
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
        if (::phononVoice.isInitialized) phononVoice.stop()
    }

    /** Re-read whatever is currently in the rectangle, ignoring the de-dup memory. */
    private fun replayCurrent() {
        lifecycleScope.launch(Dispatchers.Default) {
            if (overlayBoxes.isNotEmpty()) { clearOverlays(); delay(OVERLAY_HIDE_SETTLE_MS) }
            val bmp = capturer?.captureRegion(Rect(rx, ry, rx + rw, ry + rh))
            touchSeen = true // the loop redraws the translations (new language, …) on the still page
            if (bmp == null) return@launch
            val frame = analyzeFrame(bmp)
            // Everything visible except the tail of a bubble that already went past the top.
            val ready = frame.withIndex()
                .filter { it.value.edge != Edge.CUT_TOP }
                .map { ScrollTracker.Ready(it.index, it.value.bubble.text) }
            val page = toPage(bmp, frame, ready)
            bmp.recycle()
            if (page.isEmpty()) return@launch
            synchronized(tracker) { ready.forEach { tracker.markRead(it.text) } }
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

    /** One bubble of a capture and whether an edge of the rectangle cuts it. */
    private class FrameBubble(val bubble: Bubble, val edge: Edge)

    /**
     * OCR the frame, group the text lines into bubbles, drop scan-group watermarks and stray
     * marks, order the bubbles for reading and tell, for each one, whether it is whole or cut
     * by the top/bottom of the rectangle (see [BubbleEdges]).
     */
    private suspend fun analyzeFrame(bmp: Bitmap): List<FrameBubble> {
        val pieces = runCatching { ocr.recognize(bmp) }.getOrDefault(emptyList())
        // Our own translations, if a capture caught them just before they vanished.
        val echo = recentOverlay.takeIf { System.currentTimeMillis() - it.first < ECHO_MEMORY_MS }?.second.orEmpty()
        var grid: BubbleEdges.Grid? = null // built only when the outlines are needed
        fun grid() = grid ?: edgeGrid(bmp).also { grid = it }
        val bubbles = BubbleClusters.readingOrder(
            BubbleClusters.clusterLines(
                pieces.filterNot { ScanText.isWatermark(it.text) },
                apart = { upper, lower -> BubbleEdges.separated(grid(), upper, lower) },
            )
                .map { it.copy(text = ScanText.polish(it.text)) }
                .filterNot { ScanText.isNoise(it.text) }
                .filterNot { echo.isNotEmpty() && TextDedup.isDuplicate(TextDedup.normalize(it.text), echo, 0.75) },
            rtl = settings.readingRtl,
        )
        return bubbles.map { b ->
            val near = BubbleEdges.nearTop(b.box, b.lineHeight) ||
                BubbleEdges.nearBottom(b.box, b.lineHeight, bmp.height)
            val outline = if (near) {
                BubbleEdges.probe(grid(), b.box)
            } else null
            FrameBubble(b, BubbleEdges.classify(b.box, b.lineHeight, bmp.height, outline))
        }
    }

    /** Darkest-pixel grid of the capture (~400 cells on the long side) for the outline probe. */
    private fun edgeGrid(bmp: Bitmap): BubbleEdges.Grid {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return BubbleEdges.minPool(px, w, h, (maxOf(w, h) / 400).coerceAtLeast(1))
    }

    /** The bubbles to read now, with their crop for the vision backends (if enabled). */
    private fun toPage(bmp: Bitmap, frame: List<FrameBubble>, ready: List<ScrollTracker.Ready>): List<PageBubble> {
        val withJpeg = visionEnabled()
        return ready.map { r ->
            val crop = r.index?.takeIf { withJpeg }?.let { cropJpeg(bmp, frame[it].bubble.box) }
            PageBubble(r.text, crop)
        }
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
                MotionEvent.ACTION_DOWN -> { sx = rx; sy = ry; rawX = e.rawX; rawY = e.rawY; interacting = true; clearOverlays(); wake(); true }
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
                MotionEvent.ACTION_DOWN -> { sw = rw; sh = rh; rawX = e.rawX; rawY = e.rawY; interacting = true; clearOverlays(); wake(); true }
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
        frameBg?.alpha = 255; moveHandle?.alpha = 1f; sizeHandle?.alpha = 1f
        dimRunnable?.let { ui.removeCallbacks(it) }
        // Only the frame dims — translations drawn inside it stay fully visible.
        dimRunnable = Runnable { frameBg?.alpha = 56; moveHandle?.alpha = 0.3f; sizeHandle?.alpha = 0.3f }
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
            playing = false; stopLoop(); speech.clear(); stopSpeaking(); clearOverlays()
            closeLangPopup()
        }
    }

    private fun togglePlay() {
        playing = !playing
        if (playing) startLoop() else { stopLoop(); speech.clear(); stopSpeaking(); clearOverlays() }
        updateDock()
    }

    private fun updateDock() {
        playImg?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playImg?.setColorFilter(onAccentInt())
        langBtn?.text = Catalog.lang(settings.lang).tag
        methodDot?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(methodColorInt()) }
        voiceImg?.setImageResource(if (settings.ttsEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off)
        voiceImg?.setColorFilter(if (settings.ttsEnabled) accentInt() else textMutedInt())
        overlayImg?.setColorFilter(if (settings.overlayMode) accentInt() else textMutedInt())
        if (langPopupOpen) refreshLangPopup()
    }

    // ── Capture → bubbles → OCR → translate → speak ──
    /**
     * Vertical-scroll reading: capture often while the strip moves (every [MOVING_INTERVAL_MS]),
     * hand each capture to the [tracker], which releases a bubble as soon as it is whole and its
     * text has settled. Reading/translating runs in its own coroutine so the capture keeps
     * following the scroll meanwhile. Once the page has been still for a few captures the OCR is
     * skipped until something moves again (battery).
     *
     * Overlay mode: on the first still capture every readable bubble is translated and drawn
     * over the page. While drawn, the OCR stops (the capture sees our drawing) and the loop only
     * watches the page AROUND the translations for a scroll; a touch anywhere hides them at once.
     */
    private fun startLoop() {
        if (captureJob?.isActive == true) return
        synchronized(tracker) { tracker.reset() }
        lastGrid = null
        captureJob = lifecycleScope.launch(Dispatchers.Default) {
            val work = Channel<Work>(Channel.UNLIMITED)
            launch { for (w in work) handleWork(w) }
            var stillCount = 0
            var overlayAsked = false // translations requested for the current still page
            var movedWatches = 0     // consecutive watches that saw the page move around them
            try {
                while (isActive && expanded && playing) {
                    if (interacting) { clearOverlays(); delay(150); continue } // the frame is moving
                    if (touchSeen) {
                        touchSeen = false
                        lastGrid = null; stillCount = 0; overlayAsked = false
                    }
                    val region = Rect(rx, ry, rx + rw, ry + rh)
                    val bmp = capturer?.captureRegion(region)
                    if (bmp == null) { pause(MOVING_INTERVAL_MS); continue }
                    val grid = sampleGrid(bmp)

                    val drawn = overlayBoxes
                    if (drawn.isNotEmpty()) {
                        // Only a real scroll hides them (a touch already did, the moment it came):
                        // most of the page around them must change, on two watches in a row — a
                        // fading scrollbar, an image finishing to load or an animated ad don't count.
                        val mask = OverlayLayout.gridMask(drawn, bmp.width, bmp.height, GRID, margin = 1)
                        bmp.recycle()
                        val moved = FrameDiff.changedFraction(lastGrid, grid, ignore = mask) >= OVERLAY_MOVED_DIFF
                        movedWatches = if (moved) movedWatches + 1 else 0
                        if (movedWatches >= 2) {
                            clearOverlays() // scrolled without a touch we saw
                            lastGrid = null; stillCount = 0; overlayAsked = false; movedWatches = 0
                        } else {
                            pause(if (moved) MOVING_INTERVAL_MS else OVERLAY_WATCH_MS)
                        }
                        continue
                    }
                    movedWatches = 0
                    if (System.currentTimeMillis() < ocrPausedUntil) { bmp.recycle(); delay(40); continue }

                    val still = FrameDiff.changedFraction(lastGrid, grid) < STILL_DIFF
                    lastGrid = grid
                    stillCount = if (still) stillCount + 1 else 0
                    if (!still && overlayAsked) { overlayAsked = false; overlayGen++ }
                    if (stillCount > STILL_FRAMES_BEFORE_IDLE) {
                        bmp.recycle()
                    } else {
                        val frame = analyzeFrame(bmp)
                        val seen = frame.map { ScrollTracker.Seen(it.bubble.text, it.edge) }
                        val drawNow = settings.overlayMode && still && !overlayAsked
                        val ready = synchronized(tracker) {
                            val r = tracker.onFrame(seen, still)
                            // Drawing pauses the OCR: release now what the next capture would have.
                            if (drawNow) r + tracker.flushStill(seen) else r
                        }
                        val job = if (drawNow) { overlayAsked = true; overlayJob(bmp, frame) } else null
                        val page = toPage(bmp, frame, ready)
                        bmp.recycle()
                        if (page.isNotEmpty() || job != null) work.send(Work(page, job))
                    }
                    pause(
                        if (stillCount == 0) MOVING_INTERVAL_MS
                        else (settings.captureInterval * 1000).toLong().coerceAtLeast(400)
                    )
                }
            } finally {
                work.close()
            }
        }
    }

    /** Waits [ms], cut short by a touch on the screen (the page is probably about to move). */
    private suspend fun pause(ms: Long) {
        var left = ms
        while (left > 0 && !touchSeen) { val step = minOf(left, 50L); delay(step); left -= step }
    }

    /** Bubbles to speak, and/or translations to draw over a still page. */
    private class Work(val speak: List<PageBubble>, val overlay: OverlayJob?)
    private class OverlayJob(val gen: Int, val items: List<OverlayItem>)
    private class OverlayItem(val page: PageBubble, val bubble: Bubble, val paper: Int)

    /** The readable bubbles of a still capture, with their crop and paper colour. */
    private fun overlayJob(bmp: Bitmap, frame: List<FrameBubble>): OverlayJob? {
        val eligible = frame.filter { ScrollTracker.readableWhenStill(ScrollTracker.Seen(it.bubble.text, it.edge)) }
        if (eligible.isEmpty()) return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val withJpeg = visionEnabled()
        val items = eligible.map { f ->
            val pad = OverlayLayout.padFor(f.bubble.lineHeight)
            val b = f.bubble.box
            OverlayItem(
                PageBubble(f.bubble.text, if (withJpeg) cropJpeg(bmp, b) else null),
                f.bubble,
                OverlayLayout.paperColor(px, w, h, Box(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)),
            )
        }
        return OverlayJob(overlayGen, items)
    }

    private suspend fun handleWork(w: Work) {
        if (w.speak.isNotEmpty()) handlePage(w.speak)
        val job = w.overlay ?: return
        if (job.gen != overlayGen) return
        val s = settings
        // Mostly cache hits: the bubbles just spoken were translated by handlePage above.
        val lines = reader.readPage(job.items.map { it.page }, TargetLang(s.lang, langName(s.lang)))
        val todo = job.items.zip(lines).filter { (_, l) -> !l.translated.isNullOrBlank() }
        if (todo.isEmpty() || job.gen != overlayGen) return
        withContext(Dispatchers.Main) {
            if (job.gen == overlayGen && expanded && playing && settings.overlayMode) showOverlays(todo)
        }
    }

    /** Draws each translation over its bubble (main thread). */
    private fun showOverlays(items: List<Pair<OverlayItem, ReadLine>>) {
        val view = frame ?: return
        val minSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, OVERLAY_MIN_SP, resources.displayMetrics)
        val shown = items.map { (item, line) ->
            val text = line.translated!!
            val ink = OverlayLayout.inkFor(item.paper)
            val p = OverlayLayout.place(item.bubble.box, item.bubble.lineHeight, rw, rh, minSize) { size, width ->
                view.textLayout(text, size, width, ink).height
            }
            val b = p.box
            TranslationFrameView.Shown(
                RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()),
                view.textLayout(text, p.textSizePx, b.width - 2 * p.inset, ink),
                item.paper, p.inset.toFloat(), line.original,
            ) to b
        }
        view.setShown(shown.map { it.first })
        overlayBoxes = shown.map { it.second }
        recentOverlay = Long.MAX_VALUE to items.map { TextDedup.normalize(it.second.translated!!) }
        // The bubble being read right now gets its outline.
        val speaking = speakingOriginal?.takeIf { speech.currentId != null }
        if (speaking != null && view.hasKey(speaking)) view.highlight(speaking)
    }

    /** Hides the drawn translations (any thread) and voids the ones still being computed. */
    private fun clearOverlays() {
        overlayGen++
        if (overlayBoxes.isEmpty()) return
        overlayBoxes = emptyList()
        val now = System.currentTimeMillis()
        ocrPausedUntil = now + OVERLAY_HIDE_SETTLE_MS
        recentOverlay = now to recentOverlay.second
        ui.post { frame?.clearShown() }
    }

    /** A touch anywhere (main thread): the user is about to scroll — hide the translations now. */
    private fun onUserTouch() {
        if (!overlaysAdded) return
        overlayGen++
        if (overlayBoxes.isNotEmpty()) {
            overlayBoxes = emptyList()
            val now = System.currentTimeMillis()
            ocrPausedUntil = now + OVERLAY_HIDE_SETTLE_MS
            recentOverlay = now to recentOverlay.second
            frame?.clearShown()
        }
        touchSeen = true
    }

    /** 32×32 luminance grid of the captured region, for the frame-diff skip. */
    private fun sampleGrid(bmp: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(bmp, GRID, GRID, false)
        val px = IntArray(GRID * GRID)
        s.getPixels(px, 0, GRID, 0, 0, GRID, GRID)
        if (s !== bmp) s.recycle()
        return FrameDiff.luminance(px)
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
        // Load the offline model during the OCR wait too (only the first page pays the load).
        if (s.ttsEnabled && s.voiceMethod == VoiceMethod.PHONON) phononVoice.prewarm()

        val lines = reader.readPage(page, TargetLang(s.lang, langName(s.lang)))
        for (line in lines) {
            if (line.original.isBlank()) continue
            // Spoken copy cleaned for the voice (stretched letters, "?!?!", ♪…); the drawing keeps the text.
            val toSpeak = SpeechText.prepare(line.translated ?: line.original)
            if (s.ttsEnabled && toSpeak.isNotBlank()) {
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
        listOfNotNull(bubble, frame, touchWatcher, moveHandle, sizeHandle, controls, langPopup, langScrim).forEach { runCatching { wm.removeView(it) } }
        capturer?.stop()
        runCatching { projection?.stop() }
        ocr.close(); deviceTts.shutdown(); edgeVoice.shutdown(); phononVoice.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.animereader.START"
        const val ACTION_STOP = "com.animereader.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val CHANNEL = "animereader_service"
        private const val NOTIF_ID = 42
        /** Capture period while the strip is scrolling (fast enough to follow the bubbles). */
        private const val MOVING_INTERVAL_MS = 350L
        /** Still captures still analysed (lets a bubble settle) before the OCR goes idle. */
        private const val STILL_FRAMES_BEFORE_IDLE = 2
        /** Side of the luminance grid compared between captures, and the "page moved" share. */
        private const val GRID = 32
        private const val STILL_DIFF = 0.006
        /** Translations drawn: how often the page around them is checked for a scroll. */
        private const val OVERLAY_WATCH_MS = 400L
        /** Share of the page around the translations that must change to count as a scroll. */
        private const val OVERLAY_MOVED_DIFF = 0.12
        /** After hiding them, captures may still show them for a frame or two. */
        private const val OVERLAY_HIDE_SETTLE_MS = 250L
        /** How long OCR text matching a hidden translation is taken for our own drawing. */
        private const val ECHO_MEMORY_MS = 3000L
        /** Smallest text size of a drawn translation. */
        private const val OVERLAY_MIN_SP = 10f
        /** Floating bubble diameter and dock button size (dp). */
        private const val BUBBLE_DP = 46
        private const val DOCK_BTN_DP = 34
        /** Highest window opacity Android 12+ still lets touches pass through. */
        private const val MAX_PASS_THROUGH_ALPHA = 0.8f

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
