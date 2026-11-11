package dev.alder

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A title's art, sharp, fading out into the glass on its left (under the text) and towards the bottom, from
 * [bottomFrom] to [bottomTo] of its height. A mask on the picture itself, not a colour painted over it, so there's no
 * box where the fade meets the blurred scene behind.
 */
class FadedArt(context: Context, private val bottomFrom: Float = 0.45f, private val bottomTo: Float = 1f) : ImageView(context) {
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var left: Shader? = null
    private var bottom: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Under the text (the left 60%) only a hint of the picture stays, so bright art can't fight the words.
        left = LinearGradient(0f, 0f, w * 0.8f, 0f, intArrayOf(0x1F000000, 0x40000000, Color.BLACK), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bottom = LinearGradient(0f, h * bottomFrom, 0f, h * bottomTo, intArrayOf(Color.BLACK, 0x59000000, 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        for (s in listOf(left, bottom)) {
            mask.shader = s
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        }
        canvas.restoreToCount(layer)
    }
}

/** Keeps text readable straight on art: a soft dark shadow instead of a panel over the picture. */
fun TextView.onArt() = apply { setShadowLayer(resources.displayMetrics.density * 8, 0f, resources.displayMetrics.density, 0xCC000000.toInt()) }

internal fun filmGlow(pixels: IntArray): Int {
    require(pixels.isNotEmpty())
    val red = pixels.sumOf { it ushr 16 and 255 } / pixels.size
    val green = pixels.sumOf { it ushr 8 and 255 } / pixels.size
    val blue = pixels.sumOf { it and 255 } / pixels.size
    val lightness = (maxOf(red, green, blue) + minOf(red, green, blue)) / 2f
    val scale = if (lightness > 178.5f) 178.5f / lightness else 1f
    return (0x8C shl 24) or ((red * scale).toInt() shl 16) or ((green * scale).toInt() shl 8) or (blue * scale).toInt()
}

/**
 * A [w] x [h] copy of [src]'s pixels, or null. Decoded art is often a hardware bitmap, which can't be read: scaling
 * keeps it hardware, so the small copy is moved to software before reading. Every pixel sample goes through here.
 */
internal fun samplePixels(src: Bitmap, w: Int, h: Int): IntArray? = runCatching {
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val soft = if (small.config == Bitmap.Config.HARDWARE) small.copy(Bitmap.Config.ARGB_8888, false) else small
    IntArray(w * h).also { soft.getPixels(it, 0, w, 0, 0, w, h) }
        .also { if (small !== src) small.recycle(); if (soft !== small) soft.recycle() }
}.getOrNull()

internal fun brightLeft(pixels: IntArray, width: Int): Boolean {
    require(width > 0 && pixels.isNotEmpty() && pixels.size % width == 0)
    val left = maxOf(1, width / 2)
    val luminance = pixels.indices.filter { it % width < left }.sumOf {
        val color = pixels[it]
        (color ushr 16 and 255) * 0.2126 + (color ushr 8 and 255) * 0.7152 + (color and 255) * 0.0722
    }
    return luminance / (left * (pixels.size / width) * 255) > 0.5
}

/**
 * The title's own colour, deep enough to be a room's wall: the art's hue, found from its more colourful pixels, at a
 * fixed low lightness. Everything that was near-black ([Screen.BASE]) around a title's art takes this instead, so a
 * blue film sits in blue and a desert film in amber, as Apple's Now Playing does with a record's sleeve.
 */
internal fun filmTint(pixels: IntArray): Int {
    require(pixels.isNotEmpty())
    var red = 0.0; var green = 0.0; var blue = 0.0; var total = 0.0
    for (p in pixels) {
        val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
        // Colourful pixels say what colour the picture is; grey ones barely vote, dark ones a little less again.
        val weight = (maxOf(r, g, b) - minOf(r, g, b)) + 6.0
        red += r * weight; green += g * weight; blue += b * weight; total += weight
    }
    val r = red / total / 255; val g = green / total / 255; val b = blue / total / 255
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val light = (max + min) / 2
    var sat = if (max == min) 0.0 else (max - min) / (1 - Math.abs(2 * light - 1))
    val hue = when {
        max == min -> 0.0
        max == r -> ((g - b) / (max - min) + 6) % 6
        max == g -> (b - r) / (max - min) + 2
        else -> (r - g) / (max - min) + 4
    } * 60
    // A little richer than the picture averages to (averaging dulls), capped so no room is garish.
    sat = (sat * 1.5).coerceIn(0.0, 0.6)
    return hsl(hue, sat, TINT_LIGHTNESS)
}

/** An opaque colour from hue (0..360), saturation and lightness (0..1). */
internal fun hsl(hue: Double, sat: Double, light: Double): Int {
    val c = (1 - Math.abs(2 * light - 1)) * sat
    val x = c * (1 - Math.abs(hue / 60 % 2 - 1))
    val m = light - c / 2
    val (r1, g1, b1) = when {
        hue < 60 -> Triple(c, x, 0.0); hue < 120 -> Triple(x, c, 0.0); hue < 180 -> Triple(0.0, c, x)
        hue < 240 -> Triple(0.0, x, c); hue < 300 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
    }
    fun ch(v: Double) = Math.round((v + m) * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
}

/** [a] moved [t] of the way (0..1) to [b], channel by channel; alpha from [a]. */
internal fun mix(a: Int, b: Int, t: Double): Int {
    fun ch(shift: Int) = Math.round((a ushr shift and 255) + ((b ushr shift and 255) - (a ushr shift and 255)) * t).toInt()
    return (a and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** [color] at [alpha] (0..255): the shades are one colour at many strengths. */
internal fun withAlpha(color: Int, alpha: Int) = (alpha shl 24) or (color and 0xFFFFFF)

// Deep, but a colour a TV can show: at 0.085 the wall read as black from the sofa; the art and white words sit on it.
internal const val TINT_LIGHTNESS = 0.15

/** What every screen shares: the signed-in account, background work, and the few view helpers the plain UI uses. */
/** The band colour of an age rating, British or American, film or TV; grey for one CAS does not know. */
internal fun certColour(cert: String): Int = when (cert.trim().uppercase()) {
    "U", "UC", "G", "TV-Y", "TV-G", "E", "0", "ALL" -> 0xFF57B36A.toInt()
    "PG", "TV-PG", "TV-Y7", "TV-Y7-FV", "7" -> 0xFFF2C744.toInt()
    "12", "12A", "PG-13", "TV-14", "13", "14" -> 0xFFF08A3C.toInt()
    "15", "R", "16" -> 0xFFE8608A.toInt()
    "18", "R18", "NC-17", "TV-MA", "X", "17" -> 0xFFDB4040.toInt()
    else -> 0xFFB8B8BD.toInt()
}

/** Draws its text as a small filled mark: a rounded box of [fill] with the letters in [ink], centred on the line. */
internal class CertSpan(private val fill: Int, private val ink: Int, private val pad: Float, private val radius: Float) : android.text.style.ReplacementSpan() {
    private fun sized(paint: android.graphics.Paint) = android.graphics.Paint(paint).apply { textSize = paint.textSize * 0.8f; isFakeBoldText = true; clearShadowLayer() }
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int =
        (sized(paint).measureText(text, start, end) + pad * 2).toInt()
    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val p = sized(paint)
        val width = p.measureText(text, start, end) + pad * 2
        val m = paint.fontMetrics
        val mid = y + (m.ascent + m.descent) / 2
        val half = p.textSize * 0.72f
        p.color = fill
        canvas.drawRoundRect(x, mid - half, x + width, mid + half, radius, radius, p)
        p.color = ink
        val pm = p.fontMetrics
        canvas.drawText(text, start, end, x + pad, mid - (pm.ascent + pm.descent) / 2, p)
    }
}

abstract class Screen : Activity() {
    protected val main = Handler(Looper.getMainLooper())
    /** The Stremio account of the profile in use. */
    protected val authKey: String? get() = Profiles.active(this)?.authKey

    private var textScaleHere = 1f // the profile's text size as this screen was made with it

    /** The profile's text size (Settings › Profile › Text size) on top of the TV's own, for every word on the screen. */
    override fun attachBaseContext(base: Context) {
        textScaleHere = runCatching { Settings.textScale(base) }.getOrDefault(1f)
        super.attachBaseContext(if (textScaleHere == 1f) base else base.createConfigurationContext(
            android.content.res.Configuration(base.resources.configuration).apply { fontScale *= textScaleHere }))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Images.trim(level)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        if (Http.saved != null) return
        // The first screen after a crash says so, once; the trace waits in Settings › About.
        if (Crash.unseen(this)) main.postDelayed({
            if (!isFinishing) android.widget.Toast.makeText(this, "CAS closed unexpectedly last time. Settings › About › Last crash has the details.", android.widget.Toast.LENGTH_LONG).show()
        }, 2_500)
        // The disk cache for pictures, and last time's answers.
        Http.init(cacheDir)
        Http.saved = java.io.File(cacheDir, "answers")
        // ASCII only: a model name with other characters would make every request throw before it was sent.
        Http.userAgent = "CAS/${Updates.mine(this)} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
            .filter { it.code in 0x20..0x7E }
    }

    override fun onPause() {
        hideTrailer() // no trailer plays on behind another app
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Downloads [release] and hands it to Android's installer. CAS closes to be replaced and doesn't open again by
     * itself, so the viewer is told first. Without permission to install apps, Android's page for it opens, and the
     * install goes on when the viewer comes back with it allowed; a TV without that page gets the steps instead.
     */
    protected fun installUpdate(release: Updates.Release) {
        if (!Updates.canInstall(this)) {
            pendingInstall = release
            if (!Updates.askToInstall(this)) {
                pendingInstall = null
                choose("Allow CAS to install updates", listOf("OK"),
                    "On Fire TV: Settings › My Fire TV › Developer options › Install unknown apps, then turn on CAS. " +
                        "On Google TV: Settings › Apps › Security & restrictions › Unknown sources, then turn on CAS. " +
                        "Then select Install again.") {}
            }
            return
        }
        android.widget.Toast.makeText(this, "Downloading CAS ${release.version}… CAS closes to update. Open it again after.",
            android.widget.Toast.LENGTH_LONG).show()
        background({ Updates.install(applicationContext, release) }) { r ->
            if (r.isFailure) choose("Couldn't download the update", listOf("OK"), "Check the TV's connection and try again from Settings › About.") {}
        }
    }

    // An update waiting on the TV's permission to install apps: it goes on when the viewer comes back with it allowed.
    private var pendingInstall: Updates.Release? = null

    override fun onResume() {
        super.onResume()
        if (Settings.textScale(this) != textScaleHere) return recreate() // changed in Settings: this screen is made again in the new size
        val release = pendingInstall ?: return
        pendingInstall = null
        if (Updates.canInstall(this)) installUpdate(release)
    }

    /** Runs [work] off the main thread and hands its result back on it, unless the screen is gone. */
    /**
     * As [background], on a lane of its own: for what the viewer did and must not wait (watch progress, My List,
     * add-ons saved to Stremio). On the shared lane a save queued behind six slow add-ons, and was lost if the TV
     * was switched off before its turn.
     */
    protected fun <T> urgent(work: () -> T, done: (Result<T>) -> Unit) {
        savePool.execute {
            val result = runCatching(work)
            main.post { if (!isDestroyed && !isFinishing) done(result) }
        }
    }

    protected fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val result = runCatching(work)
            main.post { if (!isDestroyed && !isFinishing) done(result) } // a dialog on a finishing screen throws
        }
    }

    // Read once for the whole app, not per screen: Inter alone is 870 KB, read twice, on the main thread.
    private val fonts get() = loadedFonts ?: Fonts(applicationContext.assets).also { loadedFonts = it }

    /** Shared CAS typography. The legacy serif flag now selects a display weight. */
    protected fun text(value: String, sp: Float, bold: Boolean = false, color: Int = TEXT, serif: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            // Large type takes Inter's display cut and sits a little tighter, as a display face does; text sizes keep the text cut.
            val display = sp >= SERIF_NAME
            typeface = if (serif || bold) (if (display) fonts.displaySemiBold else fonts.semiBold) else if (display) fonts.displayMedium else fonts.medium
            if (sp >= SERIF_TITLE) letterSpacing = DISPLAY_TRACKING
            LEADING[sp]?.let { lead ->
                val want = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, lead, resources.displayMetrics)
                setLineSpacing(want - paint.getFontMetricsInt(null), 1f)
            }
        }

    /**
     * An age rating as its mark: the rating on the colour cinema and TV use for its band (green for all, yellow for
     * parental guidance, orange for 12 to 14, pink for 15, red for 18), so it reads at a glance and before its
     * letters do. As text with a span, so it sits in a line of facts or stands alone. CAS's own drawing: the
     * boards' symbols themselves are their trademarks.
     */
    protected fun certMark(cert: String): CharSequence = android.text.SpannableString(cert).apply {
        setSpan(CertSpan(certColour(cert), BASE, dp(5).toFloat(), dp(3).toFloat()), 0, length, 0)
    }

    protected fun caps(value: String, color: Int = TEXT_2) = text(value.uppercase(), CAPS, bold = true, color = color).apply {
        letterSpacing = 0.12f
    }

    protected fun column(gravity: Int, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false // focused pills and pictures grow past their own bounds
        this.gravity = gravity
        // Wrap-content by default: a vertical LinearLayout would otherwise stretch every child to full width.
        views.forEach { addView(it, it.layoutParams ?: wrap()) }
    }

    protected fun <V : View> V.marginTop(top: Int) = apply { layoutParams = wrap().apply { topMargin = dp(top) } }

    protected fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * IMDb's badge and [rating], then Rotten Tomatoes' badge and [meters] ("C92% A86%"), then [tail] (the library's
     * "Added …"): a dot between each pair and, with [lead], before the first, so the line continues the facts it follows.
     */
    protected fun scoresLine(rating: String?, meters: String?, lead: Boolean, tail: View? = null): LinearLayout {
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun dot() { if (lead || line.childCount > 0) line.addView(text("·", SMALL, color = TEXT_2), wrap().apply { marginStart = dp(7); marginEnd = dp(7) }) }
        // The rating behind a warm star, not the letters of where it is from (Alan, 2026-11-10).
        rating?.let {
            dot()
            line.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_star); imageTintList = android.content.res.ColorStateList.valueOf(TUNGSTEN)
                contentDescription = "IMDb rating"
            }, LinearLayout.LayoutParams(dp(METER_ICON + 1), dp(METER_ICON + 1)))
            line.addView(text(it, SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(4) })
        }
        meters?.let {
            dot()
            // A tomato, not the letters RT (Alan, 2026-11-11): the scores after it are Rotten Tomatoes'.
            line.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_tomato); contentDescription = "Rotten Tomatoes" },
                LinearLayout.LayoutParams(dp(METER_ICON + 2), dp(METER_ICON + 2)))
            // The critics as a pen, the audience as a person: what each number is, with no letter to decode.
            val parts = listOfNotNull(METER_C.find(it)?.groupValues?.get(1)?.let { v -> R.drawable.ic_pen to "$v%" },
                METER_A.find(it)?.groupValues?.get(1)?.let { v -> R.drawable.ic_person to "$v%" })
            if (parts.isEmpty()) line.addView(text(it, SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(6) })
            parts.forEachIndexed { i, (icon, value) ->
                line.addView(android.widget.ImageView(this).apply {
                    setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(TEXT_2)
                }, LinearLayout.LayoutParams(dp(METER_ICON), dp(METER_ICON)).apply { marginStart = dp(if (i == 0) 6 else 8) })
                line.addView(text(value, SMALL, color = TEXT_2).onArt(), wrap().apply { marginStart = dp(3) })
            }
        }
        tail?.let { dot(); line.addView(it, wrap()) }
        return line
    }

    /** The width [v] asks for on its own, before any layout: what a line must keep for it. */
    protected fun widest(v: View): Int {
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return v.measuredWidth
    }

    private var windowShade: View? = null
    private var windowArt: ImageView? = null
    private var shadeSource: Bitmap? = null
    private var shadeBright = false
    // Every shade around the art, with its strengths: recoloured together when the title's tint changes.
    private val tintedShades = mutableListOf<Pair<GradientDrawable, IntArray>>()
    private var tintAnimator: android.animation.ValueAnimator? = null

    /** The colour the screen's near-black takes from the title's art ([filmTint]); [BASE] until art is in. */
    protected var tint = BASE
        private set

    /** The art's colour is known (or changed): screens with shades of their own recolour them here. */
    protected open fun onTint(color: Int) {}

    private fun shade(orientation: GradientDrawable.Orientation, alphas: IntArray) =
        GradientDrawable(orientation, IntArray(alphas.size) { withAlpha(tint, alphas[it]) }).also { tintedShades += it to alphas }

    // The text column sits on the wall (dense to about half the screen), the art stays sharp on the right.
    private fun leftShadeAlphas(bright: Boolean) = if (bright) intArrayOf(0xF7, 0xD8, 0x70, 0x00) else intArrayOf(0xF2, 0xA8, 0x28, 0x00)

    /** Moves every shade to [color] over a moment, so a tint arriving after the art doesn't snap. */
    private fun setTint(color: Int) {
        if (color == tint) return
        tintAnimator?.cancel()
        val from = tint
        val eval = android.animation.ArgbEvaluator()
        tintAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener { a ->
                tint = eval.evaluate(a.animatedFraction, from, color) as Int
                tintedShades.forEach { (d, alphas) -> d.colors = IntArray(alphas.size) { withAlpha(tint, alphas[it]) } }
                onTint(tint)
            }
            start()
        }
    }

    /** Takes the screen's tint from [art] (a bitmap the screen shows), off the main thread. */
    protected fun sampleTint(art: Bitmap) {
        background({ samplePixels(art, 16, 24)?.let(::filmTint) }) { r -> r.getOrNull()?.let(::setTint) }
    }

    /** The left shade is for text over bare art; a screen that puts its text on glass can lift it off the art. */
    protected fun leftShade(on: Boolean) {
        windowShade?.animate()?.alpha(if (on) 1f else 0f)?.setDuration(260)?.start()
    }

    private fun updateWindowShade() {
        val art = (windowArt?.drawable as? BitmapDrawable)?.bitmap ?: return
        if (art === shadeSource) return
        shadeSource = art
        // Sampled off the main thread: reading a hardware bitmap back from the GPU stalls it for several ms.
        background({ samplePixels(art, 16, 24)?.let { brightLeft(it, 16) to filmTint(it) } }) { r ->
            val (bright, colour) = r.getOrNull() ?: return@background
            if (art !== shadeSource) return@background
            // A bright film keeps the shade dark across the whole text column, not just at the screen's edge.
            if (bright != shadeBright) {
                shadeBright = bright
                tintedShades.removeAll { (d, _) -> d === windowShade?.background }
                windowShade?.background = shade(GradientDrawable.Orientation.LEFT_RIGHT, leftShadeAlphas(bright))
            }
            setTint(colour)
        }
    }

    /** Sharp film art fills the screen; shade stays behind text and the bottom row. */
    protected fun setWindowed(content: View, backdrop: View = View(this).apply { background = mood() }) {
        windowArt = backdrop as? ImageView
        shadeSource = null
        shadeBright = false
        tintedShades.clear()
        window.setBackgroundDrawable(null) // the scene paints the whole screen
        setContentView(FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            // The shades are the title's tint, not a fixed near-black: they take the art's colour as it arrives.
            windowShade = View(context).apply {
                background = shade(GradientDrawable.Orientation.LEFT_RIGHT, leftShadeAlphas(false))
            }.also { addView(it, FrameLayout.LayoutParams(-1, -1)) }
            addView(View(context).apply {
                background = shade(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF7, 0x00, 0x00))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = shade(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x8C, 0, 0, 0, 0, 0, 0))
            }, FrameLayout.LayoutParams(-1, -1))
            for (edge in listOf(GradientDrawable.Orientation.LEFT_RIGHT, GradientDrawable.Orientation.RIGHT_LEFT,
                GradientDrawable.Orientation.TOP_BOTTOM, GradientDrawable.Orientation.BOTTOM_TOP)) {
                addView(View(context).apply {
                    background = GradientDrawable(edge,
                        intArrayOf(0x73000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            addView(content, FrameLayout.LayoutParams(-1, -1))
        })
        updateWindowShade()
    }

    /** A quiet tinted row on the pane (episodes, sources): no edge, no glass of its own. */
    protected fun tile(radiusDp: Int = TILE_RADIUS) = GradientDrawable().apply {
        setColor(0x14FFFFFF)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** Solid fallback until a title's art is available. */
    private fun mood() = rounded(BASE, 0)

    protected fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    /** House Lights' single glass recipe; focus brightens the face (no rim: dots are the only indicator). */
    protected fun glass(radiusDp: Int, focused: Boolean = false): Drawable {
        val face = GradientDrawable().apply {
            setColor(if (focused) GLASS_FOCUSED else GLASS)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), GLASS_EDGE)
        }
        return LayerDrawable(arrayOf(face, Highlight(dp(radiusDp).toFloat())))
    }

    /** The glass's top highlight, like CSS `inset 0 1px 0`: a 1 dp sliver that follows the corners and thins out down
     *  their curve, rather than a flat bar that stops short of them and reads as a stray line. */
    private inner class Highlight(private val radius: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_HIGHLIGHT }
        private var path: Path? = null // cut once for these bounds, not on every draw
        override fun onBoundsChange(bounds: android.graphics.Rect) { path = null }
        override fun draw(canvas: Canvas) = canvas.drawPath(path ?: highlight(RectF(bounds), radius).also { path = it }, paint)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun highlight(box: RectF, radius: Float): Path {
        val r = minOf(radius, box.height() / 2, box.width() / 2) // as GradientDrawable clamps a pill's radius
        val shape = Path().apply { addRoundRect(box, r, r, Path.Direction.CW) }
        val lower = Path().apply { addRoundRect(RectF(box).apply { offset(0f, dp(1).toFloat()) }, r, r, Path.Direction.CW) }
        return shape.apply { op(lower, Path.Op.DIFFERENCE) }
    }

    /** The warm-white primary action; focus lifts and grows it. */
    protected fun pill(label: String, icon: Int = 0): TextView = text(label, SMALL, bold = true).apply {
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(BUTTON_H)
        setPadding(dp(if (icon != 0) 14 else 16), 0, dp(16), 0)
        compoundDrawablePadding = dp(6)
        if (icon != 0) setCompoundDrawablesRelative(getDrawable(icon)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }, null, null, null)
        fun style(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(TEXT)
                cornerRadius = dp(10).toFloat()
            }
            setTextColor(BASE)
            compoundDrawablesRelative[0]?.setTint(BASE)
            elevation = if (focused) dp(8).toFloat() else 0f
            if (android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = TUNGSTEN_GLOW
        }
        style(false)
        setOnFocusChangeListener { v, has ->
            style(has)
            v.grow(has)
        }
    }

    /**
     * Focus growth for every control: scales by [by] and first lets its layout parents draw it past their bounds, so a
     * plain container can't cut the grown edge, rim and glow flat. It stops at the first scrolling view and leaves that
     * one alone: a scroller's clip is what hides rows and cards that have scrolled out, and unclipping it let Home's
     * rows slide over the title. Rows give their cards room to grow with their own padding.
     */
    protected fun View.grow(on: Boolean, by: Float = CARD_GROW): android.view.ViewPropertyAnimator {
        if (on) generateSequence(parent as? android.view.ViewGroup) { it.parent as? android.view.ViewGroup }
            .takeWhile { it !is android.widget.ScrollView && it !is android.widget.HorizontalScrollView && it !is android.widget.AbsListView }
            .forEach { it.clipChildren = false; it.clipToPadding = false }
        focusScale[this] = if (on) by else 1f
        // As a layer while it moves: the control is drawn once into a picture and the GPU sizes that picture each
        // frame, where before its text, face and shadow were drawn again each frame, over the art and its shades.
        return animate().scaleX(if (on) by else 1f).scaleY(if (on) by else 1f).withLayer()
            .setInterpolator(if (on) FOCUS_IN else FOCUS_OUT).setDuration(if (on) FOCUS_IN_MS else FOCUS_OUT_MS)
    }

    // The scale each control is meant to rest at while focused: the press release returns to this, not to whatever
    // mid-animation value it happened to have when OK went down.
    private val focusScale = java.util.WeakHashMap<View, Float>()

    /** A control handed focus back after its line was rebuilt: at its focused size now, not growing into it again. */
    protected fun View.settleFocus() {
        val s = focusScale[this] ?: return
        animate().scaleX(s).scaleY(s).setDuration(0).start()
    }

    // ---------- trailer box ----------

    private var trailerOverlay: FrameLayout? = null
    private var trailerWeb: WebView? = null
    private var trailerInfo: TextView? = null
    private val trailerProbe = Runnable { probeTrailer() }

    /** Called once the trailer box has gone, so the screen can put the remote back where it was. */
    protected open fun onTrailerClosed() {}

    /**
     * A YouTube trailer in a box over the screen, not a page of its own. The TV's own WebView loads YouTube's embed
     * page directly (the way TV apps do; CAS gains no weight), and the remote drives the page's video element: OK
     * pauses, Left and Right skip ten seconds, Back closes. The box closes itself when the trailer ends or CAS goes to
     * the background. A trailer YouTube won't embed opens in the YouTube app instead.
     */
    protected fun showTrailer(ytId: String, title: String) {
        if (!ytId.matches(YT_ID)) return // an add-on's data goes into a URL: only a real YouTube id does
        hideTrailer()
        when (Settings.trailerPlayer(this)) {
            "app" -> return openTrailerOutside(ytId)
            "browser" -> return openTrailerInBrowser(ytId)
        }
        val web = runCatching { WebView(this) }.getOrNull() ?: return openTrailerOutside(ytId)
        // Fire OS's engine appears to draw video on a layer behind the app's window (sound, no picture, in the box).
        // There, the window goes see-through and the box leaves a hole in an otherwise opaque shade, so whatever is
        // drawn behind the window shows inside the box and nowhere else.
        val punch = android.os.Build.MANUFACTURER.equals("Amazon", true)
        trailerWeb = web
        web.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(if (punch) Color.TRANSPARENT else Color.BLACK)
            isFocusable = false // the remote's keys stay with the screen (dispatchKeyEvent), not the page
            isFocusableInTouchMode = false
            webChromeClient = WebChromeClient() // without one, Android plays a video's sound over a black box
            webViewClient = object : WebViewClient() {
                // The page is YouTube's player and nothing else: no navigating away from it.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame
                override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(TRAILER_HOOK, null) }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun ended() = main.post { hideTrailer() }
                @JavascriptInterface fun failed(code: Int) = main.post { hideTrailer(); openTrailerOutside(ytId) }
            }, "CAS")
            // YouTube's player refuses to run without a Referer ("configuration error 153"): the app's own page stands in.
            loadUrl("https://www.youtube.com/embed/$ytId?autoplay=1&controls=0&rel=0&modestbranding=1&playsinline=1&fs=0&iv_load_policy=3&disablekb=1&cc_load_policy=0",
                mapOf("Referer" to TRAILER_REFERER))
        }
        // The corners are cut on the box around the page, not on the page itself: clipping the video's own surface
        // is what some TVs' WebViews refuse to draw.
        val box = boxFrame(web).apply {
            if (punch) background = GradientDrawable().apply { cornerRadius = dp(TILE_RADIUS + 1).toFloat(); setStroke(dp(1), GLASS_EDGE) }
        }
        val hints = boxHints()
        val info = text("", SMALL, color = TEXT_3).apply { maxLines = 1; alpha = 0.8f; visibility = View.GONE } // the probe's findings go to the log now
        trailerInfo = info
        openBox("Trailer", title, box, hints, info, punch)
        trailerProbes = 0
        main.postDelayed(trailerProbe, TRAILER_PROBE_MS)
    }

    /** The box over the screen, holding [box] under a label and above the key hints. One recipe for trailers and previews. */
    private fun openBox(kind: String, title: String, box: View, hints: View, extra: View? = null, punch: Boolean = false) {
        val boxW = (resources.displayMetrics.widthPixels * TRAILER_W).toInt()
        val boxH = boxW * 9 / 16
        val artBehind = Settings.titlePage(this, SettingsActivity.TRAILER_ART)
        if (punch) {
            window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            val content = findViewById<FrameLayout>(android.R.id.content)
            trailerHidContent = content.getChildAt(0)?.also { it.visibility = View.INVISIBLE }
        }
        trailerOverlay = FrameLayout(this).apply {
            if (punch) background = HoleShade(box, dp(TILE_RADIUS + 1).toFloat()) else setBackgroundColor(if (artBehind) TRAILER_SCRIM else TRAILER_BLACK)
            isFocusable = true // holds the remote while the box is up
            addView(column(Gravity.CENTER_HORIZONTAL,
                caps(kind, TEXT_2).apply { letterSpacing = 0.16f },
                text(title, SMALL, color = TEXT_3).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = boxW }.marginTop(4),
                box.apply { layoutParams = LinearLayout.LayoutParams(boxW, boxH).apply { topMargin = dp(14) } },
                hints.marginTop(14),
                *listOfNotNull(extra?.marginTop(10)).toTypedArray(),
            ), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            alpha = 0f
        }
        findViewById<FrameLayout>(android.R.id.content).addView(trailerOverlay, FrameLayout.LayoutParams(-1, -1))
        if (punch) trailerOverlay?.alpha = 1f else trailerOverlay?.animate()?.alpha(1f)?.setDuration(220)?.start()
        trailerOverlay?.requestFocus()
    }

    private fun boxFrame(content: View) = FrameLayout(this).apply {
        background = glass(TILE_RADIUS + 1)
        setPadding(dp(1), dp(1), dp(1), dp(1))
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(TILE_RADIUS + 1).toFloat())
        }
        clipToOutline = true
        addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    private fun boxHints() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(keyHint("OK", "Play / pause"))
        addView(keyHint("◂ ▸", "Skip 10 s"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18) })
        addView(keyHint("Back", "Close"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18) })
    }

    // ---------- preview ----------

    private var boxPlayer: ExoPlayer? = null
    private var previewToken = 0
    private val previewEnd = Runnable { hideTrailer() }

    /**
     * A minute of the title itself in the box: the sources Play would use, found by [find] off the main thread while
     * the box shows a ring, the best one started a little way in (past the logos, before the story), and the box
     * closes itself when the minute is up. OK pauses, Left and Right skip ten seconds, Back closes. Progress isn't
     * kept: a preview is a look, not a watch. A source that fails gives way to the next; none left closes with a word.
     */
    protected fun showPreview(title: String, find: () -> List<Stream>) =
        showClip("Preview", title, PREVIEW_FROM, PREVIEW_MS, find) {}

    /**
     * A trailer as a file, in the box through CAS's own player (IMDb's video service or an add-on's own file), whole
     * and from the start. [orElse] runs when no file turns up: the YouTube routes, which need the YouTube id.
     */
    protected fun showTrailerClip(title: String, find: () -> List<Stream>, orElse: () -> Unit) =
        showClip("Trailer", title, 0.0, 0L, find, orElse)

    private fun showClip(kind: String, title: String, from: Double, limitMs: Long, find: () -> List<Stream>, orElse: () -> Unit) {
        hideTrailer()
        val token = ++previewToken
        val surface = android.view.SurfaceView(this)
        val ring = ring()
        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            addView(ring, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        openBox(kind, title, boxFrame(content), boxHints())
        background(find) { result ->
            if (token != previewToken || trailerOverlay == null) return@background
            val sources = result.getOrDefault(emptyList())
            if (sources.isEmpty()) {
                hideTrailer()
                return@background if (kind == "Trailer") orElse()
                    else choose("Preview unavailable", listOf("OK"), "No source for this title could be played right now.") {}
            }
            // As the player sets its sound up: decoded here unless a receiver takes Dolby and DTS; ffmpeg after the TV's decoders.
            val passthrough = Settings.passthrough(this)
            val renderers = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    if (passthrough) DefaultAudioSink.Builder(context).build() else
                    object : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
                        override fun getFormatSupport(format: Format): Int =
                            if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
                        override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
                    }
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            val player = ExoPlayer.Builder(this, renderers).build()
            boxPlayer = player
            player.setVideoSurfaceView(surface)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(Settings.audioLanguage(this)).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            var at = 0
            var placed = false
            fun start(s: Stream) {
                placed = false
                ring.visibility = View.VISIBLE
                val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(s.headers)
                    .apply { Http.userAgent?.let { setUserAgent(it) } }
                val item = MediaItem.Builder().setUri(s.url).apply { if (s.url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
                player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)).createMediaSource(item))
                player.prepare()
                player.playWhenReady = true
            }
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    // A short file, over before the minute is. Closed on the next frame: the box releases the player, and
                    // a player shouldn't be released from inside its own callback.
                    if (state == Player.STATE_ENDED) return main.post { if (boxPlayer === player) hideTrailer() }.let {}
                    if (state != Player.STATE_READY || placed) return
                    placed = true
                    val length = player.duration
                    if (from > 0 && length > PREVIEW_MIN_LENGTH) player.seekTo((length * from).toLong())
                }
                override fun onRenderedFirstFrame() {
                    ring.visibility = View.GONE
                    main.removeCallbacks(previewEnd)
                    if (limitMs > 0) main.postDelayed(previewEnd, limitMs)
                }
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.w("CAS", "preview source ${at + 1} failed: ${error.errorCodeName}")
                    if (++at < sources.size) start(sources[at])
                    else main.post { if (boxPlayer === player) { hideTrailer(); if (kind == "Trailer") orElse() else choose("Preview unavailable", listOf("OK"), "No source for this title could be played right now.") {} } }
                }
            })
            start(sources[0])
        }
    }

    private fun boxPlayerKey(code: Int): Boolean {
        val p = boxPlayer ?: return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> p.playWhenReady = !p.playWhenReady
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p.seekTo(p.currentPosition + 10_000)
        }
        return true
    }

    protected fun hideTrailer() {
        main.removeCallbacks(trailerProbe)
        main.removeCallbacks(previewEnd)
        previewToken++
        boxPlayer?.let { runCatching { it.release() } }
        boxPlayer = null
        val overlay = trailerOverlay ?: return
        trailerOverlay = null
        trailerInfo = null
        findViewById<FrameLayout>(android.R.id.content).removeView(overlay) // off the screen before destroy(): attached, it has crashed TVs
        trailerWeb?.let { web -> runCatching { web.loadUrl("about:blank"); web.stopLoading(); web.destroy() } }
        trailerWeb = null
        trailerHidContent?.let { it.visibility = View.VISIBLE; window.setFormat(android.graphics.PixelFormat.OPAQUE) }
        trailerHidContent = null
        onTrailerClosed()
    }

    private var trailerHidContent: View? = null // the screen's own content, hidden while the window is see-through

    /** An opaque charcoal shade with one rounded hole where [box] is: the see-through window shows only there. */
    private inner class HoleShade(private val box: View, private val radius: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BASE }
        private val path = Path()
        private val hole = RectF()
        private val at = IntArray(2)
        override fun draw(canvas: Canvas) {
            val me = IntArray(2); (callback as? View)?.getLocationInWindow(me)
            box.getLocationInWindow(at)
            val x = (at[0] - me[0] + box.paddingLeft).toFloat(); val y = (at[1] - me[1] + box.paddingTop).toFloat()
            hole.set(x, y, x + box.width - box.paddingLeft - box.paddingRight, y + box.height - box.paddingTop - box.paddingBottom)
            path.reset(); path.fillType = Path.FillType.EVEN_ODD
            path.addRect(RectF(bounds), Path.Direction.CW)
            path.addRoundRect(hole, radius, radius, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** The TV's browser (Silk on Fire TV), at the trailer on YouTube's site, full screen. */
    private fun openTrailerInBrowser(ytId: String) {
        if (!ytId.matches(YT_ID)) return
        val uri = Uri.parse("https://www.youtube.com/watch?v=$ytId")
        val silk = Intent(Intent.ACTION_VIEW, uri).setPackage("com.amazon.cloud9")
        if (runCatching { startActivity(silk); true }.getOrDefault(false)) return
        if (!openOutside(uri)) choose("Trailer unavailable", listOf("OK"), "This TV has no browser to play the trailer.") {}
    }

    /** The TV's YouTube app, straight to the trailer, full screen; failing that, a word. */
    private fun openTrailerOutside(ytId: String) {
        if (!ytId.matches(YT_ID)) return
        // The YouTube app's own scheme first (Fire TV and Android TV both answer it), then the web link for whatever
        // else is installed. On a TV with neither, Android's own "no app" word shows.
        if (!openOutside(Uri.parse("vnd.youtube:$ytId")) && !openOutside(Uri.parse("https://www.youtube.com/watch?v=$ytId")))
            choose("Trailer unavailable", listOf("OK"), "No trailer could be played on this TV.") {}
    }

    /** Hands [uri] to whatever app takes it (the YouTube app, a browser). False when nothing on this TV does. */
    protected fun openOutside(uri: Uri): Boolean = try {
        // startActivity, not resolveActivity: on Android 11+ the latter can't see other apps and says "none".
        startActivity(Intent(Intent.ACTION_VIEW, uri)); true
    } catch (e: android.content.ActivityNotFoundException) { false }

    private fun trailerJs(script: String) { trailerWeb?.evaluateJavascript(script, null) }

    /**
     * A few seconds in, asks the page what it has: whether a video element exists and is producing frames, and which
     * codecs this TV's WebView says it can decode. Shown in the small line under the box while trailers are being
     * proven on real TVs; a page with no video at all (YouTube refused to embed) opens outside instead.
     */
    private var trailerProbes = 0

    private fun probeTrailer() {
        val web = trailerWeb ?: return
        trailerProbes++
        web.evaluateJavascript(TRAILER_PROBE) { value ->
            val json = runCatching { org.json.JSONObject(value) }.getOrNull() ?: return@evaluateJavascript
            if (trailerWeb !== web) return@evaluateJavascript
            val hasVideo = json.optBoolean("video")
            val line = listOfNotNull(
                json.optString("chrome").ifEmpty { "WebView ?" },
                "H.264 " + json.optString("h264").ifEmpty { "no" },
                "VP9 " + json.optString("vp9").ifEmpty { "no" },
                if (hasVideo) "${json.optInt("w")}×${json.optInt("h")} · ready ${json.optInt("rs")}" +
                    (if (json.optBoolean("paused")) " · paused" else " · playing") +
                    (json.optInt("err").takeIf { it != 0 }?.let { " · error $it" } ?: "")
                else "no video element",
            ).joinToString("  ·  ")
            android.util.Log.i("CAS", "trailer probe $trailerProbes: $line · page says: ${json.optString("txt").replace('\n', ' ').take(160)}")
            trailerInfo?.text = line
            // A slow TV may still be loading the first time; a page with no video the second time has refused. While
            // frames haven't arrived yet the line is read again, so it ends up saying what the TV actually drew.
            when {
                !hasVideo && trailerProbes >= 2 -> { hideTrailer(); openTrailerOutside(json.optString("id")) }
                // Playing for 14 s with no frame drawn: this TV's engine sends video somewhere the box can't show
                // (Fire TV). Trailers go to the YouTube app from now on; Settings › Title page can bring the box back.
                hasVideo && json.optInt("w") == 0 && json.optInt("rs") >= 2 && trailerProbes >= 2 -> {
                    Settings.setTrailerPlayer(this, if (android.os.Build.MANUFACTURER.equals("Amazon", true)) "browser" else "app")
                    hideTrailer(); showTrailer(json.optString("id"), "")
                }
                (!hasVideo || json.optInt("w") == 0) && trailerProbes < 4 -> main.postDelayed(trailerProbe, TRAILER_PROBE_MS)
            }
        }
    }

    // ---------- press feedback ----------

    // OK on a control dips it, and letting go springs it back: the remote feels joined to the screen. Done once here
    // for every screen rather than per view, so no view's own key listener is displaced.
    private var pressedView: View? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (trailerOverlay != null) {
            if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MEDIA_STOP -> hideTrailer()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> if (!boxPlayerKey(event.keyCode)) trailerJs(TRAILER_TOGGLE)
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (!boxPlayerKey(event.keyCode)) trailerJs(trailerSeek(-10))
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!boxPlayerKey(event.keyCode)) trailerJs(trailerSeek(10))
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            val v = currentFocus
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && v != null && v.isClickable &&
                v !is android.widget.EditText && v !is android.widget.ScrollView) {
                pressedView = v
                val rest = focusScale[v] ?: 1f
                v.animate().scaleX(rest * PRESS_DIP).scaleY(rest * PRESS_DIP).setInterpolator(FOCUS_OUT).setDuration(80).start()
            } else if (event.action == KeyEvent.ACTION_UP) pressedView?.let { p ->
                pressedView = null
                val rest = if (p.hasFocus()) focusScale[p] ?: 1f else 1f
                if (p.isAttachedToWindow) p.animate().scaleX(rest).scaleY(rest).setInterpolator(FOCUS_IN).setDuration(FOCUS_IN_MS).start()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A persistent choice: its dot remains after remote focus moves. */
    protected fun chip(label: String, selected: Boolean = false) = text(label, SMALL).apply {
        select(label, selected)
        isFocusable = true
        gravity = Gravity.CENTER
        minHeight = dp(CHIP_H)
        setPadding(dp(16), 0, dp(16), 0)
        background = glass(CHIP_H / 2)
        setOnFocusChangeListener { v, has -> chipFocus(has); v.grow(has, CHIP_GROW).start() }
    }

    /** A chip under the remote turns white with dark words; at rest it's glass, its words full or dimmed by [select]. */
    protected fun TextView.chipFocus(has: Boolean) {
        background = if (has) rounded(TEXT, CHIP_H / 2) else glass(CHIP_H / 2)
        setTextColor(if (has) BASE else if (text.startsWith("•")) TEXT else TEXT_2)
    }

    /** A [chip]'s open state: the tungsten dot and full-strength words. */
    protected fun TextView.select(label: String, selected: Boolean) {
        setTextColor(if (selected) TEXT else TEXT_2)
        text = if (!selected) label else android.text.SpannableString("•  $label").apply {
            setSpan(android.text.style.ForegroundColorSpan(TUNGSTEN), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * An information block, as the Apple TV app's product page sets its panels: frosted glass with a quiet label, then
     * its content, each piece on its own line. Views that bring their own layout params keep them (a name's hug).
     */
    protected fun block(label: String, vararg content: View) = column(Gravity.START).apply {
        background = glass(BLOCK_RADIUS)
        setPadding(dp(BLOCK_PAD), dp(BLOCK_PAD - 2), dp(BLOCK_PAD), dp(BLOCK_PAD))
        clipChildren = false
        clipToPadding = false
        addView(caps(label, TEXT_3))
        content.forEachIndexed { i, v ->
            addView(v, (v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply { topMargin = dp(if (i == 0) 8 else 4) })
        }
    }

    protected fun caption(value: String) = text(value, SMALL).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = glassOver(this, 16)
    }

    /** "[OK] Play / pause": the key in a small box, then what it does. */
    protected fun keyHint(key: String, does: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(key, BADGE, bold = true, color = BASE).apply {
            background = rounded(TEXT_2, 3)
            includeFontPadding = false
            setPadding(dp(4), dp(1), dp(4), dp(1))
        })
        addView(text(does, SMALL, color = TEXT_2).onArt(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
    }

    /** A quiet outlined tag beside a row's name ("Built-in", "ElfHosted", "THIS PROFILE"). */
    protected fun tag(label: String, color: Int = TEXT_3) = text(label, SMALL, color = color).apply {
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(5), dp(2), dp(5), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setStroke(dp(1), GLASS_EDGE) }
    }

    /** A hairline under a list row. */
    protected fun divider() = View(this).apply { setBackgroundColor(GLASS_EDGE) }

    /** CAS wordmark: a single, quiet signature on every entry screen. */
    protected fun brand(size: Float = WORDMARK) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        // The aperture, as tall as the capitals beside it.
        val markPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size * 0.95f, resources.displayMetrics).toInt()
        addView(ImageView(this@Screen).apply {
            setImageResource(R.drawable.ic_mark)
            imageTintList = android.content.res.ColorStateList.valueOf(TEXT)
        }, LinearLayout.LayoutParams(markPx, markPx).apply { marginEnd = (markPx * 0.42f).toInt() })
        addView(text("CAS", size, bold = true).apply {
            letterSpacing = 0.16f
            includeFontPadding = false
            contentDescription = "CAS"
        }, wrap())
    }

    /** The IMDb tag beside a rating, as tall as the letters. */
    protected fun imdbTag() = text("IMDb", SMALL, bold = true, color = BASE).apply {
        background = rounded(TUNGSTEN, 3)
        includeFontPadding = false
        setLineSpacing(0f, 1f)
        setPadding(dp(4), dp(1), dp(4), dp(1))
    }

    /** Null animates loading; for countdowns set progress to fraction × max as time changes. */
    protected fun ring(fraction: Float? = null): ProgressBar = object : ProgressBar(this, null,
        // A countdown needs a style that keeps its progress; the default one is indeterminate-only and reads 0.
        if (fraction == null) android.R.attr.progressBarStyle else android.R.attr.progressBarStyleHorizontal) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            if (fraction == null) return super.onDraw(canvas)
            val inset = stroke.strokeWidth / 2
            val bounds = RectF(inset, inset, width - inset, height - inset)
            stroke.color = PROGRESS_TRACK
            canvas.drawArc(bounds, 0f, 360f, false, stroke)
            stroke.color = TUNGSTEN
            canvas.drawArc(bounds, -90f, progress.toFloat() / max * 360f, false, stroke)
        }
    }.apply {
        require(fraction == null || (fraction.isFinite() && fraction in 0f..1f))
        isIndeterminate = fraction == null
        indeterminateTintList = android.content.res.ColorStateList.valueOf(TUNGSTEN)
        max = 1000
        if (fraction != null) progress = (fraction * max).toInt()
    }

    /**
     * A glass control's focus, as Apple TV does it: the face turns white and the word or glyph dark, with a little
     * growth. A brighter glass alone was too easy to miss from the sofa.
     */
    protected fun TextView.lightFocus(radiusDp: Int, grow: Float = CHIP_GROW) {
        isFocusable = true
        // Both faces made once and swapped: a new glass face on every move of the remote was a new shape to cut and
        // send to the GPU, twice per move (the control left, the control arrived), while both were also in motion.
        val rest = glass(radiusDp)
        val lit = rounded(TEXT, radiusDp)
        background = rest
        setOnFocusChangeListener { v, has ->
            v.background = if (has) lit else rest
            setTextColor(if (has) BASE else TEXT)
            v.grow(has, grow).start()
        }
    }

    /** One list-row focus treatment across settings, episodes, sources and menus. */
    protected fun View.rowFocus(radiusDp: Int = 8, rest: () -> android.graphics.drawable.Drawable? = { null }, also: (Boolean) -> Unit = {}) {
        isFocusable = true
        background = rest()
        setOnFocusChangeListener { v, has ->
            v.background = if (has) GradientDrawable().apply {
                setColor(ROW_FOCUSED)
                cornerRadius = dp(radiusDp).toFloat()
            } else rest()
            also(has)
        }
    }

    /** One poster/portrait focus treatment; the poster itself supplies its glow colour. */
    protected fun View.cardFocus(radiusDp: Int = 6, also: (Boolean) -> Unit = {}) {
        isFocusable = true
        setOnFocusChangeListener { v, has ->
            v.lift(has, radiusDp)
            also(has)
        }
    }

    /** An icon of [sizeDp] in the middle of whatever it's drawn over (a round button's face). */
    protected fun centred(res: Int, sizeDp: Int, tint: Int) = getDrawable(res)?.mutate()?.let { d ->
        d.setTint(tint)
        LayerDrawable(arrayOf(d)).apply { setLayerSize(0, dp(sizeDp), dp(sizeDp)); setLayerGravity(0, Gravity.CENTER) }
    }

    /**
     * The main action as a white disc with a dark glyph, the way Apple's artist page leads with Play: at rest it is
     * already the brightest thing in its line; focus grows it and lifts it on its own glow.
     */
    protected fun disc(sizeDp: Int, icon: Int, description: String): TextView = text("", BODY).apply {
        isFocusable = true
        gravity = Gravity.CENTER
        contentDescription = description
        foreground = centred(icon, (sizeDp * 0.46f).toInt(), BASE)
        background = rounded(TEXT, sizeDp / 2)
        elevation = dp(2).toFloat()
        if (android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = TUNGSTEN_GLOW
        setOnFocusChangeListener { v, has ->
            // The lift is set, not animated: a shadow that rises is a soft shadow drawn again every frame, the dearest
            // thing on the page for a Fire TV Stick's GPU. The growth carries the arrival.
            v.translationZ = if (has) dp(CARD_LIFT).toFloat() else 0f
            v.grow(has, DISC_GROW).start()
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // Focus by light: a column's rows under the remote at full brightness, the others stepped back.
    private val lightLevels = java.util.WeakHashMap<View, Float>()
    private val restAlpha = java.util.WeakHashMap<View, Float>() // a row's parts as they were before the first dimming

    /**
     * How Apple's lyrics page shows where you are: the line under the remote is bright and sharp and the lines
     * around it step back. For a column of rows (episodes, sources, settings, a menu): while the remote is in it,
     * every focusable row but the focused one dims; once it leaves, all rows come back. Brightness rather than a
     * border is what reads from the sofa.
     */
    protected fun ViewGroup.focusByLight(dim: Float = LIGHT_DIM) {
        val column = this
        val listener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
            val inside = now != null && isInside(now, column)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (!child.isFocusable) continue // headings and notes stay as they are
                child.light(if (!inside || isInside(now!!, child)) 1f else dim)
            }
        }
        var observer: android.view.ViewTreeObserver? = null
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { observer = v.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(listener) } }
            override fun onViewDetachedFromWindow(v: View) { observer?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(listener); observer = null }
        })
        if (isAttachedToWindow) observer = viewTreeObserver.also { it.addOnGlobalFocusChangeListener(listener) }
    }

    /**
     * Shows a row at [level] of its brightness (1 = as it is) by fading what is in it: its words and marks, not the
     * row itself, whose own alpha rows use for their own reasons (an unaired episode, an SD source) and whose
     * background is the focus pill. A child's own alpha is kept and scaled, and restored when the row is lit again.
     */
    private fun View.light(level: Float) {
        val from = lightLevels[this] ?: 1f
        if (from == level) return
        lightLevels[this] = level
        val parts = if (this is ViewGroup && childCount > 0) (0 until childCount).map { getChildAt(it) } else listOf(this)
        for (part in parts) {
            val rest = restAlpha.getOrPut(part) { part.alpha }
            part.animate().alpha(rest * level).setDuration(if (level < from) 180 else 120).start()
        }
    }

    private val glowCache = java.util.WeakHashMap<View, Pair<Bitmap, Int>>()

    private fun posterBitmap(view: View): Bitmap? = when (view) {
        is ImageView -> (view.drawable as? BitmapDrawable)?.bitmap
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { posterBitmap(view.getChildAt(it)) }
        else -> null
    }

    protected fun View.lift(on: Boolean, radiusDp: Int = 6) {
        foreground = if (on) GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), TUNGSTEN_RIM) // a hairline: the glow and the lift carry focus
        } else null
        if (on && android.os.Build.VERSION.SDK_INT >= 28) {
            val poster = posterBitmap(this)
            val known = if (poster == null) TUNGSTEN_GLOW else glowCache[this]?.takeIf { it.first === poster }?.second
            outlineSpotShadowColor = known ?: TUNGSTEN_GLOW
            // Reading a poster's pixels back from the GPU took ~7 ms on the main thread per press: the glow is sampled
            // off it and tints the shadow a moment later, while the lift is still growing.
            if (known == null && poster != null) background({ samplePixels(poster, 16, 24)?.let(::filmGlow) }) { r ->
                r.getOrNull()?.let { glow -> glowCache[this] = poster to glow; if (hasFocus()) outlineSpotShadowColor = glow }
            }
        }
        grow(on)
            .translationZ(if (on) dp(CARD_LIFT).toFloat() else 0f).setDuration(150).start()
    }

    /** The screen's art, behind its glass: what [glassOver] frosts. Screens with art behind them name it. */
    protected open val backdrop: ImageView? get() = null

    private var frostSource: Bitmap? = null
    private var frostBitmap: Bitmap? = null
    // Weak: hosts come and go with dialogs, search results and rebuilt panes, and must not be kept alive here.
    private val frostHosts: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /**
     * Frosted glass, as the episode list draws it: the art behind, blurred, seen through a rounded pane under the
     * glass tint. A background for [host] (pills, the top bar, panels); without a [backdrop] it's the plain tint.
     */
    /** [shade]: how much of the screen's own shade lies over the art there (0..1), so glass over a shaded part of the art
     *  is as dark as the art around it instead of a milky patch of the unshaded picture. */
    protected fun glassOver(host: View, radiusDp: Int, shade: Float = 0f): Drawable {
        // No art to frost (Settings): the glass sits on the base colour, so a dialog doesn't show the rows under it.
        val behind = backdrop ?: return LayerDrawable(arrayOf(rounded(BASE, radiusDp), glass(radiusDp)))
        frostHosts += host
        return Frost(host, behind, dp(radiusDp).toFloat(), shade)
    }

    /** The art behind changed: glass redraws with the new picture once it's blurred. */
    protected fun refrost() {
        frostHosts.forEach { it.invalidate() }
        updateWindowShade()
    }

    /** The art blurred once per picture ([Images.frost]); the last one stays up while the next is made. */
    private fun frosted(d: Drawable?): Bitmap? {
        val src = (d as? BitmapDrawable)?.bitmap ?: return frostBitmap
        if (src !== frostSource) {
            frostSource = src
            background({ Images.frost(src, FROST_W) }) { r -> if (frostSource === src) { frostBitmap = r.getOrNull(); refrost() } }
        }
        return frostBitmap
    }

    private inner class Frost(private val host: View, private val behind: ImageView, private val radius: Float,
                              private val shade: Float = 0f) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_EDGE; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_HIGHLIGHT }
        private val path = Path()
        private val at = IntArray(2)
        private val art = IntArray(2)

        override fun draw(canvas: Canvas) {
            path.reset()
            path.addRoundRect(RectF(bounds), radius, radius, Path.Direction.CW)
            val d = behind.drawable
            frosted(d)?.let { f ->
                canvas.save()
                canvas.clipPath(path)
                // The blurred art lines up with the sharp art behind: where the host sits on screen, through the same crop.
                // On screen, not in the window: a menu's glass lives in a dialog's window over the screen's art.
                host.getLocationOnScreen(at)
                behind.getLocationOnScreen(art)
                canvas.translate((art[0] - at[0]).toFloat(), (art[1] - at[1]).toFloat())
                canvas.concat(behind.imageMatrix)
                // As bright as the sharp art around it: a dimmed backdrop (Settings') dims its frosted copy too.
                if (behind.alpha < 1f) canvas.drawColor(BASE)
                paint.alpha = (behind.alpha * 255).toInt()
                canvas.drawBitmap(f, null, Rect(0, 0, d?.intrinsicWidth ?: f.width, d?.intrinsicHeight ?: f.height), paint)
                if (shade > 0f) canvas.drawColor(Color.argb((shade * 255).toInt(), 14, 12, 10))
                canvas.restore()
            }
            canvas.drawPath(path, tint)
            canvas.drawPath(path, edge)
            canvas.drawPath(highlight(RectF(bounds), radius), shine)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** A thin watched-so-far bar over a dark track. */
    protected fun progressBar(progress: Float, color: Int = TUNGSTEN) = LinearLayout(this).apply {
        val p = progress.coerceIn(0f, 1f)
        setBackgroundColor(PROGRESS_TRACK)
        addView(View(context).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(0, -1, p))
        addView(View(context), LinearLayout.LayoutParams(0, -1, 1f - p))
    }

    protected fun isInside(v: View, parent: View): Boolean = generateSequence(v) { it.parent as? View }.any { it === parent }

    /**
     * A short list to pick from (a menu, a genre, a yes or no), on glass with tungsten focus, in
     * place of Android's grey dialog. [checked] gets a tick (the choice in use); the remote starts on [focus].
     */
    protected fun choose(title: String, items: List<String>, message: String? = null, checked: Int = -1,
                         focus: Int = maxOf(checked, 0), pick: (Int) -> Unit) {
        if (isFinishing || isDestroyed) return // Back pressed while the answer was on its way: nothing to show it on
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val list = column(Gravity.START).apply { setPadding(dp(DIALOG_PAD - 4), dp(DIALOG_PAD), dp(DIALOG_PAD - 4), dp(DIALOG_PAD - 4)) }
        val words = column(Gravity.START, text(title, BODY, bold = true)).apply { setPadding(dp(16), 0, dp(16), dp(12)) }
        message?.let { words.addView(text(it, SMALL, color = TEXT_2).marginTop(6)) }
        list.addView(words, LinearLayout.LayoutParams(-1, -2))
        val rows = items.mapIndexed { i, label ->
            text(label, BODY).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                if (i == checked) setCompoundDrawablesRelative(null, null, getDrawable(R.drawable.ic_check)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }, null)
                rowFocus()
                setOnClickListener { dialog.dismiss(); pick(i) }
                list.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        list.focusByLight()
        dialog.setContentView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = glassOver(this, 16) // the episode list's glass, over the screen's art
            addView(list)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(dp(DIALOG_W), -2)
            setDimAmount(DIALOG_DIM)
        }
        dialog.show()
        rows.getOrNull(focus)?.requestFocus()
    }

    /** A short name typed with the TV's keyboard: a list's name. [done] gets the trimmed words, never blank. */
    protected fun askName(title: String, initial: String = "", hint: String = "Name", done: (String) -> Unit) {
        if (isFinishing || isDestroyed) return
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val field = android.widget.EditText(this).apply {
            this.hint = hint
            textSize = BODY
            setTextColor(TEXT)
            setHintTextColor(TEXT_3)
            setPadding(dp(14), 0, dp(14), 0)
            background = glass(8)
            setOnFocusChangeListener { v, has -> v.background = glass(8, has) }
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setText(initial)
            setSelection(initial.length)
        }
        fun finish() {
            val words = field.text.toString().trim()
            if (words.isEmpty()) return
            dialog.dismiss()
            done(words)
        }
        field.setOnEditorActionListener { _, _, _ -> finish(); true }
        val ok = pill("Done").apply { setOnClickListener { finish() } }
        dialog.setContentView(column(Gravity.START,
            text(title, BODY, bold = true),
            field.apply { minHeight = dp(44); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) } },
            ok.marginTop(16),
        ).apply { setPadding(dp(DIALOG_PAD), dp(DIALOG_PAD), dp(DIALOG_PAD), dp(DIALOG_PAD)); background = glassOver(this, 16) })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(dp(DIALOG_W), -2)
            setDimAmount(DIALOG_DIM)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        field.requestFocus()
    }

    /**
     * Hold OK on a title: its place in the viewer's own lists (Lists). One row per list, "Add to" or "Remove from";
     * with no list yet, an offer to make one. [before] are the title's other hold options; [changed] runs after an edit.
     */
    protected fun listsMenu(entry: Lists.Entry, before: List<Pair<String, () -> Unit>> = emptyList(), skip: String? = null, changed: () -> Unit = {}) {
        val lists = Lists.all(this).filterNot { it.id == skip } // the row held in offers its own entries in [before]
        val options = before.map { it.first } +
            lists.map { l -> if (l.items.any { it.id == entry.id }) "Remove from ${l.name}" else "Add to ${l.name}" } +
            "Add to a new list…" // always there: a second or third list starts from the title too, not only in Settings
        choose(entry.name, options) { i ->
            when {
                i < before.size -> before[i].second()
                i - before.size < lists.size -> { Lists.toggle(this, lists[i - before.size].id, entry); changed() }
                else -> askName("Name the list", hint = "Kids, AC, Film night…") { name -> Lists.toggle(this, Lists.create(this, name).id, entry); changed() }
            }
        }
    }

    /**
     * "What's new" for [version]: its entries from CHANGELOG.md (packaged with the app), the first [SHORT_LIST] on glass
     * with Got it, and See all changes to open the rest in a taller panel that the remote steps through line by line.
     * [quiet]: nothing at all when there are no notes (the pop-up after an update), else a short message (Settings).
     */
    protected fun whatsNew(version: String = Updates.mine(this), quiet: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val changes = runCatching { assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }.getOrNull()
            ?.let { changesFor(it, version) }.orEmpty()
        if (changes.isEmpty()) {
            if (!quiet) choose("What's new", listOf("OK"), "There are no notes for version $version.") {}
            return
        }
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val list = column(Gravity.START).apply { setPadding(dp(22), dp(20), dp(22), dp(18)) }
        list.addView(caps("What's new", TUNGSTEN))
        list.addView(text("CAS $version", SERIF_TITLE, serif = true).marginTop(4))
        val entries = column(Gravity.START)
        list.addView(entries, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        fun entry(change: String) = text("", BODY, color = TEXT).apply {
            text = changeLine(change)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            entries.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        changes.take(SHORT_LIST).forEach(::entry)
        val gotIt = pill("Got it").apply { setOnClickListener { dialog.dismiss() } }
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(gotIt) }
        list.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        if (changes.size > SHORT_LIST) buttons.addView(chip("See all changes").apply {
            setOnClickListener {
                // Taller, every entry, each one a stop for the remote so Down moves through them; Got it at the end.
                changes.drop(SHORT_LIST).forEach(::entry)
                (0 until entries.childCount).forEach { entries.getChildAt(it).rowFocus() }
                buttons.removeView(this)
                dialog.window?.setLayout(dp(WHATS_NEW_W), resources.displayMetrics.heightPixels * 82 / 100)
                entries.getChildAt(SHORT_LIST).requestFocus()
            }
        }, LinearLayout.LayoutParams(-2, dp(CHIP_H + 6)).apply { marginStart = dp(8) })
        dialog.setContentView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = glassOver(this, 16)
            addView(list)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(dp(WHATS_NEW_W), -2)
            setDimAmount(0.6f)
        }
        dialog.show()
        gotIt.requestFocus()
    }

    /** One glass error card with a plain message and a next action. */
    protected fun failed(message: String, retry: () -> Unit) = column(Gravity.START,
        text("!", SERIF_NAME, color = TUNGSTEN, serif = true),
        text(message, BODY, color = TEXT).marginTop(10),
        pill("Try again").apply { setOnClickListener { retry() } }.marginTop(16),
    ).apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = glassOver(this, 14)
    }

    // CAS shared values. Screens keep their layout and content; the helpers above own component styling.
    private class Fonts(assets: android.content.res.AssetManager) {
        val medium: Typeface = Typeface.Builder(assets, "fonts/inter.ttf").setFontVariationSettings("'wght' 500").setWeight(500).build()
        val semiBold: Typeface = Typeface.Builder(assets, "fonts/inter.ttf").setFontVariationSettings("'wght' 600").setWeight(600).build()
        // Inter has an optical size axis, as Apple's San Francisco has its Text and Display: 14 is its text cut (the
        // default), 32 its display cut, drawn for large sizes. San Francisco itself is licensed for Apple's own
        // platforms only, so it cannot be in CAS; Inter is the open face made to the same model.
        val displayMedium: Typeface = Typeface.Builder(assets, "fonts/inter.ttf").setFontVariationSettings("'wght' 500, 'opsz' 32").setWeight(500).build()
        val displaySemiBold: Typeface = Typeface.Builder(assets, "fonts/inter.ttf").setFontVariationSettings("'wght' 600, 'opsz' 32").setWeight(600).build()
    }

    companion object {
        private var loadedFonts: Fonts? = null // main thread only
        const val PAD = 48
        const val TILE_RADIUS = 10
        const val GLASS_INSET = 28 // dp a glass panel sits in from the screen's edges
        const val DIALOG_W = 400 // dp: one width for the app's dialogs (menus, a name, a URL)
        const val DIALOG_PAD = 20
        const val DIALOG_DIM = 0.6f
        const val FROST_W = 360 // px the art is shrunk to before the blur: wider is less blur (240 read as fog, 480 as smeared)
        const val HERO_W = 600 // dp: a title's block on Home and its page: a measure that holds a synopsis in a few full lines
        const val TITLE_H = 72 // dp: the name's area on Home and the title page, one height whatever the name, so what follows never moves
        const val METER_ICON = 14 // dp: the pen and the person on the Rotten Tomatoes line
        const val LOGO_W = 340 // dp: the title's logo, where it has one, within that area
        const val BLOCK_W = 300 // dp: an information block down the right of a page
        const val BLOCK_RADIUS = 12
        const val BLOCK_PAD = 16
        const val BLOCK_GAP = 12 // dp between blocks
        const val BUTTON_H = 34
        const val SHORT_LIST = 5 // "What's new" entries shown before See all changes
        const val WHATS_NEW_W = 480 // dp
        const val CHIP_H = 30 // a chip's least height; it grows with the TV's font size
        // Focus motion: a little overshoot on the way in, settling over 220 ms, is most of what makes focus feel weighted.
        // Quick and nearly straight: a control is at its size 160 ms after the remote lands, with the least bounce that
        // still reads as arriving. At 220 ms with more bounce, moving along a title's controls felt behind the remote.
        val FOCUS_IN: android.view.animation.Interpolator = android.view.animation.OvershootInterpolator(0.7f)
        val FOCUS_OUT: android.view.animation.Interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        const val FOCUS_IN_MS = 160L
        const val FOCUS_OUT_MS = 120L
        const val PRESS_DIP = 0.96f // OK held: the control's scale, relative to where focus left it
        // The trailer box.
        const val TRAILER_W = 0.68f // share of the screen the box takes: a box, not a page, but big enough to watch
        const val TRAILER_SCRIM = 0xCC08090B.toInt() // the art stays, dimmed, behind the box
        const val TRAILER_BLACK = 0xFA08090B.toInt() // Settings › Title page › Art behind the trailer: Off
        const val TRAILER_PROBE_MS = 7_000L
        const val PREVIEW_MS = 60_000L // how long a preview plays
        const val PREVIEW_FROM = 0.12 // where it starts, as a share of the title: past the logos, before the story
        const val PREVIEW_MIN_LENGTH = 10 * 60_000L // shorter than this (a short, a clip) plays from the start
        const val TRAILER_REFERER = "https://albyjnr.github.io/cas/"
        val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")
        /** Finds the embed page's video once it exists, reports its end and errors back, and makes sure it's playing. */
        const val TRAILER_HOOK = "(function(){var s=document.createElement('style');s.textContent='.ytp-caption-window-container,.ytp-watermark,.ytp-pause-overlay,.ytp-gradient-top,.ytp-chrome-top{display:none!important}';document.head.appendChild(s);" +
            "var t=0;function hook(){var v=document.querySelector('video');if(!v){if(++t<60)setTimeout(hook,250);return;}" +
            "v.addEventListener('ended',function(){CAS.ended()});v.addEventListener('error',function(){CAS.failed(v.error?v.error.code:0)});" +
            "if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){})}}hook();})()"
        const val TRAILER_TOGGLE = "(function(){var v=document.querySelector('video');if(v){if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){})}else v.pause()}})()"
        fun trailerSeek(seconds: Int) = "(function(){var v=document.querySelector('video');if(v){v.currentTime=Math.max(0,v.currentTime+($seconds))}})()"
        const val TRAILER_PROBE = "(function(){var v=document.querySelector('video');var c=document.createElement('video');var m=navigator.userAgent.match(/Chrome\\/(\\d+)/);" +
            "return {chrome:m?'Chrome '+m[1]:'',h264:c.canPlayType('video/mp4; codecs=\"avc1.42E01E\"'),vp9:c.canPlayType('video/webm; codecs=\"vp9\"')," +
            "video:!!v,w:v?v.videoWidth:0,h:v?v.videoHeight:0,rs:v?v.readyState:0,paused:v?v.paused:true,err:(v&&v.error)?v.error.code:0,id:(location.pathname.split('/').pop()||''),txt:(document.body?document.body.innerText:'').slice(0,200)}})()"
        const val CARD_GROW = 1.06f
        const val DISC_GROW = 1.1f // the white disc: bigger growth than a card, it is the one thing on its line
        const val DISC = 52 // dp, the title page's Play
        const val LIGHT_DIM = 0.55f // brightness of the rows around the focused one (focus by light)
        const val CHIP_GROW = 1.04f // a wide chip grown as much as a card filled the gap to its neighbour
        const val CARD_LIFT = 8
        // Spacing, everywhere rows of pictures appear: between cards, from a row's heading to its cards (plus room for
        // the focused card to grow), and above a heading, so each heading belongs to the row under it.
        const val CARD_GAP = 18
        const val POSTER_W = 88
        const val POSTER_H = 132
        const val HEADING_GAP = 16 // dp between a row's heading and its cards
        const val SECTION_GAP = 32
        const val ROW_H = 42 // one-line list row (Settings)
        const val ROW_H2 = 48 // two-line list row (name over what it does)
        const val PANEL_W = 0.573f // share of the screen a list panel takes, beside its side note
        const val DOT = "  ·  "

        const val WORDMARK = 22f // sp, "CAS" beside its mark
        const val DISPLAY_TRACKING = -0.012f // em, on titles of 28 sp and up
        const val SERIF_HERO = 38f
        const val SERIF_TITLE = 28.5f
        const val SERIF_NAME = 19f
        const val BODY = 14.5f
        const val SMALL = 14f // the label step: facts, captions, chips, keys, hints. 12.5 read small from a sofa.
        const val BADGE = 11f // the small white badges and key caps (IMDb, RT, NEW, OK)
        const val CAPS = 12.5f // tracked capitals read wide, so they keep the smaller size
        const val CODE = 36f
        val LEADING = mapOf(SERIF_HERO to 40f, SERIF_TITLE to 30f, SERIF_NAME to 21f, BODY to 20f, SMALL to 19f, CAPS to 17f, CODE to 40f)

        const val BASE = 0xFF08090B.toInt()
        const val TEXT = 0xFFF5F5F7.toInt()
        const val TEXT_2 = 0xB8F5F5F7.toInt()
        const val TEXT_3 = 0x73F5F5F7
        const val TUNGSTEN = 0xFFF3E7D3.toInt() // the accent: a warm, lit white, apart from the cool white of the words
        const val ALERT = 0xFFF0A08C.toInt() // an error's words: a soft coral, the one colour that is not white
        const val TUNGSTEN_RIM = 0x99FFFFFF.toInt()
        const val TUNGSTEN_GLOW = 0x59FFFFFF
        const val GLASS = 0x99202226.toInt()
        const val GLASS_FOCUSED = 0x3DF5F5F7 // a lit face: with no rim, the fill alone carries focus
        const val GLASS_EDGE = 0x1FFFFFFF
        const val GLASS_HIGHLIGHT = 0x4DFFFFFF
        const val ROW_FOCUSED = 0x38F5F5F7 // a list row under the remote: brighter than it was, still a row not a button
        const val PROGRESS_TRACK = 0x4DFFFFFF // light enough that a short fill still reads as a bar, not a dash
        // Fetching and parsing sit behind the main thread and the RenderThread: on a four-core stick, a page's answers
        // parsed at the main thread's priority took frames from the row sliding in.
        private val savePool = Executors.newFixedThreadPool(2)
        private val pool = Executors.newFixedThreadPool(6) { r ->
            Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }
        }
    }
}

/** The entries under "## [version]" in CHANGELOG.md, without their "- "; empty when that version has no section. */
internal fun changesFor(changelog: String, version: String): List<String> {
    val lines = changelog.lines()
    val start = lines.indexOfFirst { it.trim() == "## $version" }.takeIf { it >= 0 } ?: return emptyList()
    return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        .filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
}

/** A changelog entry as it reads on screen: **bold** in bold, `code` and the other marks left out. */
internal fun changeLine(change: String): CharSequence {
    val out = android.text.SpannableStringBuilder()
    Regex("""\*\*(.+?)\*\*|`(.+?)`""").let { marks ->
        var at = 0
        marks.findAll(change).forEach { m ->
            out.append(change, at, m.range.first)
            val bold = m.groupValues[1]
            if (bold.isNotEmpty()) {
                val start = out.length
                out.append(bold)
                out.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, out.length, 0)
            } else out.append(m.groupValues[2])
            at = m.range.last + 1
        }
        out.append(change, at, change.length)
    }
    return out
}

private val METER_C = Regex("C(\\d+)%") // Rotten Tomatoes' critics' meter, as the rows and lookups word it
private val METER_A = Regex("A(\\d+)%") // and the audience's
