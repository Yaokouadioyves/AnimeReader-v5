package com.animereader.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.animereader.data.HistoryEntry
import com.animereader.data.Settings
import com.animereader.data.SettingsRepository
import com.animereader.data.TranslationMethod
import com.animereader.internal.ApiKeys
import com.animereader.ocr.ScreenOcr
import com.animereader.translate.Translators
import com.animereader.tts.DeviceTts
import com.animereader.tts.Voices
import com.animereader.ui.theme.resolvePalette
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 */
class OverlayService : LifecycleService() {

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var repo: SettingsRepository
    @Volatile private var settings = Settings()

    private lateinit var ocr: ScreenOcr
    private lateinit var deviceTts: DeviceTts
    private val apiKeys = ApiKeys()

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var captureJob: Job? = null

    // Overlay windows
    private var bubble: View? = null
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
    private var transImg: ImageView? = null

    private var overlaysAdded = false
    private var expanded = false
    private var playing = false
    private var lastSpoken = ""
    @Volatile private var currentSpeechId: String? = null

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
        deviceTts.onSpeechDone = { id -> ui.post { onSpeechFinished(id) } }
        lifecycleScope.launch { repo.settings.collect { settings = it; ui.post { if (overlaysAdded) updateDock() } } }
    }

    /** When voice reading is on, hide the subtitle the moment the matching utterance finishes. */
    private fun onSpeechFinished(id: String?) {
        if (settings.ttsEnabled && id != null && id == currentSpeechId) {
            ui.removeCallbacks(hideCaption)
            captionBar?.visibility = View.GONE
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
                if (!overlaysAdded) addOverlays()
                _running.value = true
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
    private fun methodColorInt(): Int {
        val p = resolvePalette(settings.direction, settings.mode, settings.accent)
        return if (settings.method == TranslationMethod.OFFLINE) p.offline.toArgb() else p.online.toArgb()
    }

    private fun addOverlays() {
        val (w, h, _) = displaySize()
        rw = (w * 0.78f).toInt(); rh = dp(120)
        rx = (w - rw) / 2; ry = (h * 0.34f).toInt()

        // Bubble
        val b = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentInt()) }
            addView(ImageView(this@OverlayService).apply {
                setImageResource(R.drawable.ic_target)
                val pad = dp(13); setPadding(pad, pad, pad, pad)
            })
            elevation = dp(8).toFloat()
        }
        val bp = overlayParams(dp(56), dp(56), touchable = true).apply { x = w - dp(72); y = (h * 0.55f).toInt() }
        attachBubbleTouch(b, bp)
        wm.addView(b, bp); bubble = b

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
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat(); setColor(elevInt()); setStroke(dp(1), borderInt())
            }
            elevation = dp(6).toFloat()
        }
        // Play / pause — accent circle
        playImg = ImageView(this).apply {
            setImageResource(R.drawable.ic_play); setColorFilter(onAccentInt())
            val p = dp(14); setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentInt()) }
        }
        dock.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
            addView(playImg, FrameLayout.LayoutParams(dp(50), dp(50)))
            setOnClickListener { togglePlay() }
        })
        dock.addView(dockSep())
        // Language pill — method dot + tag + chevron
        val langPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(AColor.TRANSPARENT) }
            setOnClickListener { cycleLanguage() }
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
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { leftMargin = dp(3) }
        })
        dock.addView(langPill)
        // Translate toggle + open-app
        transImg = dockIcon(R.drawable.ic_translate, if (settings.translateOn) accentInt() else textMutedInt()) { toggleTranslate() }
        dock.addView(transImg)
        dock.addView(dockIcon(R.drawable.ic_settings, textMutedInt()) { openApp() })
        return dock
    }

    private fun dockSep() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply { leftMargin = dp(4); rightMargin = dp(4) }
        setBackgroundColor(borderInt())
    }

    private fun dockIcon(res: Int, tint: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint)
        val p = dp(12); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AColor.TRANSPARENT) }
        setOnClickListener { onClick() }
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
                MotionEvent.ACTION_DOWN -> { sx = rx; sy = ry; rawX = e.rawX; rawY = e.rawY; wake(); true }
                MotionEvent.ACTION_MOVE -> {
                    rx = sx + (e.rawX - rawX).toInt(); ry = sy + (e.rawY - rawY).toInt()
                    syncRect(); wake(); true
                }
                else -> true
            }
        }
    }

    private fun attachSizeTouch(handle: View) {
        var sw = 0; var sh = 0; var rawX = 0f; var rawY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sw = rw; sh = rh; rawX = e.rawX; rawY = e.rawY; wake(); true }
                MotionEvent.ACTION_MOVE -> {
                    rw = (sw + (e.rawX - rawX).toInt()).coerceAtLeast(dp(120))
                    rh = (sh + (e.rawY - rawY).toInt()).coerceAtLeast(dp(70))
                    syncRect(); wake(); true
                }
                else -> true
            }
        }
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
        else { playing = false; stopLoop(); deviceTts.stop(); ui.removeCallbacks(hideCaption); captionBar?.visibility = View.GONE }
    }

    private fun togglePlay() {
        playing = !playing
        if (playing) startLoop() else { stopLoop(); deviceTts.stop() }
        updateDock()
    }

    private fun cycleLanguage() {
        val codes = Catalog.languages.map { it.code }
        val next = codes[(codes.indexOf(settings.lang) + 1) % codes.size]
        lifecycleScope.launch { repo.update { it.copy(lang = next) } }
        ui.postDelayed({ updateDock() }, 60)
    }

    private fun toggleTranslate() {
        lifecycleScope.launch { repo.update { it.copy(translateOn = !it.translateOn) } }
    }

    private fun updateDock() {
        playImg?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playImg?.setColorFilter(onAccentInt())
        langBtn?.text = Catalog.lang(settings.lang).tag
        methodDot?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(methodColorInt()) }
        transImg?.setColorFilter(if (settings.translateOn) accentInt() else textMutedInt())
    }

    // ── Capture → OCR → translate → speak ──
    private fun startLoop() {
        if (captureJob?.isActive == true) return
        captureJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && expanded && playing) {
                val region = Rect(rx, ry, rx + rw, ry + rh)
                val bmp = capturer?.captureRegion(region)
                if (bmp != null) {
                    val text = runCatching { ocr.recognize(bmp) }.getOrDefault("")
                    bmp.recycle()
                    if (text.isNotBlank() && text != lastSpoken) handleRecognized(text)
                }
                delay((settings.captureInterval * 1000).toLong().coerceAtLeast(400))
            }
        }
    }

    private fun stopLoop() { captureJob?.cancel(); captureJob = null }

    private suspend fun handleRecognized(original: String) {
        lastSpoken = original
        val s = settings
        var translated: String? = null
        if (s.translateOn && s.lang != "fr") {
            translated = runCatching {
                Translators.create(s.method, s.provider, apiKeys).translate(original, s.lang)
            }.getOrElse {
                // Online stub/unconfigured → fall back to offline ML Kit.
                runCatching { Translators.create(TranslationMethod.OFFLINE, s.provider, apiKeys).translate(original, s.lang) }.getOrNull()
            }
        }
        val toSpeak = translated ?: original
        if (s.ttsEnabled) {
            val provider = Voices.create(s.voiceMethod, s.voiceProvider, apiKeys, deviceTts)
            val langTag = if (translated != null) s.lang else "fr"
            currentSpeechId = toSpeak.hashCode().toString()
            provider.speak(toSpeak, langTag, s.speed, s.timbre)
        }
        withContext(Dispatchers.Main) { showCaption(original, translated) }
        OverlayController.pushHistory(
            HistoryEntry(
                time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                original = original,
                translated = translated,
                langTag = if (translated != null) s.lang.uppercase() else null,
                method = if (translated != null) s.method else null,
                providerName = if (translated != null && s.method == TranslationMethod.ONLINE) s.provider.displayName else if (translated != null) "ML Kit" else null,
            )
        )
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
        listOfNotNull(bubble, frame, moveHandle, sizeHandle, controls, captionBar).forEach { runCatching { wm.removeView(it) } }
        capturer?.stop()
        runCatching { projection?.stop() }
        ocr.close(); deviceTts.shutdown()
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

/** App-facing control surface for the overlay service + a small in-memory history bus. */
object OverlayController {
    val running: StateFlow<Boolean> get() = OverlayService.running

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history
    fun pushHistory(entry: HistoryEntry) { _history.value = (listOf(entry) + _history.value).take(40) }

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
}
