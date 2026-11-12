package dev.alder

import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A person's page (Alan, 2026-11-10): an actor, a director, a writer, opened from their name on a title's page.
 * One plain layout for all of them. Who they are sits down the left: the photograph, the name, their work, where
 * and when they were born, a few lines about them. The rest of the screen is what the page is for: everything they
 * made, as posters, the best known first or the newest first, films or TV or both, more arriving as the remote
 * goes down. OK on a poster opens that title. All of it is IMDb's; a name IMDb does not know goes to a plain search.
 */
class PersonActivity : Screen() {
    private val asked by lazy { intent.getStringExtra("name").orEmpty() }
    private val country by lazy { java.util.Locale.getDefault().country.ifEmpty { "GB" } }
    private var who: ImdbPerson? = null
    private var newest = false
    private var kind = ALL
    private var cursor: String? = null
    private var more = false
    private var loading = false
    private var request = 0
    private val tiles = mutableListOf<View>()
    private lateinit var about: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var count: TextView
    private lateinit var grid: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (asked.isBlank()) return finish()
        about = column(Gravity.START)
        chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        count = text("", SMALL, color = TEXT_3)
        grid = column(Gravity.START).apply { clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(8), dp(6), dp(40)) }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(chips, LinearLayout.LayoutParams(0, -2, 1f))
            addView(count, wrap())
        }
        val works = column(Gravity.START).apply {
            clipChildren = false
            addView(top, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(6); marginEnd = dp(6) })
            // A pane of its own around the posters, and it clips: Android clips a view to its bounds only through its
            // parent, and this column must not clip (the choices above grow under the remote). Without the pane the
            // rows that had scrolled up drew over the choices.
            addView(FrameLayout(context).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BASE)
            clipChildren = false
            addView(about, FrameLayout.LayoutParams(dp(ABOUT_W), -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(PAD); topMargin = dp(TOP) })
            addView(works, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(PAD + ABOUT_W + GUTTER - 6); rightMargin = dp(PAD - 6); topMargin = dp(TOP - 4) })
        })
        about.addView(text(asked, SERIF_TITLE, serif = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        grid.addView(ring(), LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(20) })
        background({ imdbPersonFind(asked, country) }) { r ->
            if (isFinishing) return@background
            val found = r.getOrNull() ?: return@background plainSearch()
            who = found
            showAbout(found)
            showChips()
            load(reset = true)
        }
    }

    /** IMDb knows no one by the name: the plain search for it, as before there was a page. */
    private fun plainSearch() {
        startActivity(Intent(this, SearchActivity::class.java).putExtra("query", asked))
        finish()
    }

    private fun showAbout(p: ImdbPerson) {
        about.removeAllViews()
        if (p.photo != null) {
            val photo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(GLASS)
                outlineProvider = roundedOutline(12)
                clipToOutline = true
            }
            about.addView(photo, LinearLayout.LayoutParams(dp(PHOTO_W), dp(PHOTO_W * 4 / 3)).apply { bottomMargin = dp(16) })
            Images.load(p.photo, photo, dp(PHOTO_W))
        }
        about.addView(text(p.name, SERIF_TITLE, serif = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (p.jobs.isNotEmpty()) about.addView(caps(p.jobs.take(2).joinToString("  ·  ")).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.marginTop(8))
        listOfNotNull(p.born?.let { "Born $it" }, p.birthplace).joinToString(DOT).takeIf { it.isNotEmpty() }?.let {
            about.addView(text(it, SMALL, color = TEXT_3).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }.marginTop(8))
        }
        p.bio?.let {
            about.addView(text(synopsisAtSentence(it, BIO_CHARS), BODY, color = TEXT_2).apply {
                maxLines = if (p.photo != null) BIO_LINES else BIO_LINES + 6
                ellipsize = TextUtils.TruncateAt.END
            }.marginTop(14))
        }
    }

    /** The two choices over the posters: the order, and films or TV. Drawn again on each change, the remote kept on its choice. */
    private fun showChips(focus: String? = null) {
        chips.removeAllViews()
        fun add(label: String, on: Boolean, gap: Int, pick: () -> Unit) = chips.addView(chip(label, on).apply {
            setOnClickListener { if (!on) { pick(); showChips(label); load(reset = true) } }
            if (label == focus) post { requestFocus() }
        }, LinearLayout.LayoutParams(-2, -2).apply { if (chips.childCount > 0) marginStart = dp(gap) })
        add("Best known", !newest, 0) { newest = false }
        add("Newest", newest, 8) { newest = true }
        add("All", kind == ALL, 22) { kind = ALL }
        add("Films", kind == FILMS, 8) { kind = FILMS }
        add("TV", kind == TV, 8) { kind = TV }
    }

    private fun load(reset: Boolean) {
        val p = who ?: return
        if (reset) {
            request++
            cursor = null
            loading = false
            tiles.clear()
            grid.removeAllViews()
            scroll.scrollTo(0, 0)
            count.text = ""
            grid.addView(ring(), LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(20) })
        } else if (loading || !more) return
        loading = true
        val token = request
        val after = cursor
        background({ imdbFilmography(p.id, country, newest, kind, after) }) { r ->
            if (isFinishing || token != request) return@background
            loading = false
            val page = r.getOrNull()
            if (after == null) grid.removeAllViews()
            if (page == null) {
                if (after == null) grid.addView(failed("Couldn't reach IMDb. Check the TV's connection.") { load(reset = true) })
                return@background
            }
            cursor = page.cursor
            more = page.cursor != null
            count.text = if (page.total == 1) "1 title" else "${page.total} titles"
            if (after == null && page.picks.isEmpty()) return@background grid.addView(text("Nothing here", BODY, color = TEXT_2), wrap().apply { topMargin = dp(12); marginStart = dp(2) }).let { }
            val first = tiles.isEmpty()
            page.picks.forEach(::addTile)
            if (first && !chips.hasFocus()) tiles.firstOrNull()?.requestFocus()
        }
    }

    /** One title: its poster, its name under it, its year. In rows of [COLUMNS]; reaching the last rows asks for more. */
    private fun addTile(pk: ImdbPick) {
        val index = tiles.size
        val row = (grid.getChildAt(grid.childCount - 1) as? LinearLayout)?.takeIf { it.childCount < COLUMNS && index % COLUMNS != 0 }
            ?: LinearLayout(this).apply { clipChildren = false; clipToPadding = false }.also { grid.addView(it, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) }) }
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(GLASS)
            outlineProvider = roundedOutline(8)
            clipToOutline = true
        }
        val rim = View(this)
        val title = text(pk.name, SMALL, color = TEXT_2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END } // one line: every row the same height
        val year = text(pk.year ?: "", CAPS, color = TEXT_3)
        val tile = column(Gravity.START).apply {
            isFocusable = true
            defaultFocusHighlightEnabled = false // the white rim is the focus; Android's own grey wash sat behind the name
            clipChildren = false
            contentDescription = pk.name
            addView(FrameLayout(context).apply {
                addView(poster, FrameLayout.LayoutParams(-1, -1))
                addView(rim, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_W * 3 / 2)))
            addView(title, LinearLayout.LayoutParams(dp(TILE_W), -2).apply { topMargin = dp(7) })
            addView(year, LinearLayout.LayoutParams(dp(TILE_W), -2).apply { topMargin = dp(1) })
            setOnFocusChangeListener { v, has ->
                rim.background = if (has) GradientDrawable().apply { setStroke(dp(2), TEXT); cornerRadius = dp(8).toFloat() } else null
                title.setTextColor(if (has) TEXT else TEXT_2)
                v.animate().scaleX(if (has) TILE_GROW else 1f).scaleY(if (has) TILE_GROW else 1f).setDuration(150).start()
                if (has && index >= tiles.size - COLUMNS * 2) load(reset = false)
            }
            setOnClickListener {
                startActivity(Intent(this@PersonActivity, DetailsActivity::class.java)
                    .putExtra("type", pk.type).putExtra("id", pk.id).putExtra("name", pk.name).putExtra("poster", pk.poster))
            }
        }
        row.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { if (row.childCount > 0) marginStart = dp(TILE_GAP) })
        tiles += tile
        Images.load(pk.poster, poster, dp(TILE_W))
    }

    private fun roundedOutline(radiusDp: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
    }

    private companion object {
        const val TOP = 44 // dp from the top to the name and the choices
        const val ABOUT_W = 236 // dp, the column about the person
        const val GUTTER = 40 // dp between it and the posters
        const val PHOTO_W = 120
        const val BIO_CHARS = 210 // what the lines below hold: the text ends at a sentence, not at dots
        const val BIO_LINES = 7
        const val COLUMNS = 5
        const val TILE_W = 104
        const val TILE_GAP = 14
        const val TILE_GROW = 1.05f
    }
}
