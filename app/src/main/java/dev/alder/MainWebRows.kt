package dev.alder

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import dev.alder.Screen.Companion.CARD_GAP
import dev.alder.MainActivity.Companion.CARD_H
import dev.alder.MainActivity.Companion.CARD_W
import dev.alder.Screen.Companion.DOT
import dev.alder.MainActivity.Companion.EDGE
import dev.alder.MainActivity.Companion.GHOST
import dev.alder.MainActivity.Companion.GHOSTS
import dev.alder.MainActivity.Companion.GHOST_MS
import dev.alder.Screen.Companion.PAD
import dev.alder.MainActivity.Companion.QUIET
import dev.alder.MainActivity.Companion.ROW_BOTTOM_ROOM
import dev.alder.MainActivity.Companion.ROW_TOP_ROOM
import dev.alder.MainActivity.Companion.START
import dev.alder.MainActivity.Companion.WORLD
import dev.alder.MainActivity.Item
import dev.alder.MainActivity.Page

// The rows a page reads from the web (IMDb, Rotten Tomatoes, RogerEbert.com, the Foreign page's services): which
// rows each page has, how they fill in waves, and how each list becomes cards. Part of MainActivity, in a file of
// its own: the main screen was one file of three thousand lines, and a change to one page risked the others.

internal fun MainActivity.buildImdb(g: Int) = buildWebRows(webRows(Page.IMDB), g)


/**
 * The web rows in waves, not all at once: their slots go in now, in order, so the page's shape is fixed; the
 * first few fill at once, the rest three at a time as the page scrolls or every couple of seconds, whichever
 * comes first. A cold Content page then costs a handful of requests before it's usable, not a hundred.
 */
internal fun MainActivity.buildWebRows(rows: List<Pair<String, (LinearLayout, Int) -> Unit>>, g: Int,
    tail: List<Pair<String, (LinearLayout, Int) -> Unit>> = emptyList(), between: () -> Unit = {}) {
    val pending = rows.map { it.second to slot() }.toMutableList()
    between() // the catalogs' slots, so the tail rows close the page under them
    pending += tail.map { it.second to slot() }
    fun fill(n: Int) { repeat(n) { pending.removeFirstOrNull()?.let { (build, slot) -> build(slot, g) } } }
    fill(WEB_ROWS_FIRST)
    webRowsMore = { if (g == generation && pending.isNotEmpty()) fill(WEB_ROWS_WAVE) else if (pending.isEmpty()) webRowsMore = null }
    val wave = object : Runnable { override fun run() { if (g != generation) return; webRowsMore?.invoke(); if (pending.isNotEmpty()) main.postDelayed(this, WEB_ROWS_PAUSE) } }
    main.postDelayed(wave, WEB_ROWS_PAUSE)
}

/**
 * The rows read from the web, by page and in order (Alan's, 2026-11-05 and 2026-11-07): title to builder. Movies
 * opens with RogerEbert.com's reviews and Rotten Tomatoes' Certified Fresh, then in season IMDb's Christmas films,
 * then "Because you watched" rows from the viewer's own library, IMDb's box office, family films and popular
 * films. TV opens with Rotten Tomatoes' popular series, then Christmas TV in season, "Because you watched",
 * IMDb's new series, family TV and popular TV. On both, the Top 250 ([TAIL_ROWS]) closes the page under the
 * add-ons' catalogs (Alan, 2026-11-06). Content has everything: the trailer lists (with this month's most
 * anticipated), the reviews and Rotten Tomatoes, what's popular on each streaming service, the charts, the
 * family and seasonal rows, and the two Top 250s at the bottom.
 */
internal fun MainActivity.webRows(p: Page): List<Pair<String, (LinearLayout, Int) -> Unit>> {
    fun imdb(l: ImdbList): Pair<String, (LinearLayout, Int) -> Unit> = l.title to { s, g -> imdbRow(s, l, g) }
    fun rt(l: RtList): Pair<String, (LinearLayout, Int) -> Unit> = l.title to { s, g -> rtRow(s, l, g) }
    val ebert: Pair<String, (LinearLayout, Int) -> Unit> = EBERT_ROW to { s, g -> ebertRow(s, g) }
    val because: Pair<String, (LinearLayout, Int) -> Unit> = BECAUSE_ROW to { s, g -> becauseRows(s, p, g) }
    val season = christmasSeason(java.time.LocalDate.now().toString())
    // The short CAS: no two rows that say the same thing (the box office and the reviews go; one "popular" each).
    // Home has one row from the web, under the viewer's own: the ten most popular today, numbered.
    if (p == Page.HOME) return listOf(imdb(ImdbList.TOP_TEN))
    if (simple) return when (p) {
        Page.MOVIES -> listOfNotNull(imdb(ImdbList.CHRISTMAS_MOVIES).takeIf { season }, because, rt(RtList.FRESH_IN_CINEMAS), imdb(ImdbList.FAMILY_MOVIES), imdb(ImdbList.TOP_MOVIES))
        Page.SHOWS -> listOfNotNull(imdb(ImdbList.CHRISTMAS_TV).takeIf { season }, because, rt(RtList.POPULAR_TV), imdb(ImdbList.FAMILY_TV), imdb(ImdbList.TOP_TV))
        else -> emptyList()
    }
    return when (p) {
        Page.MOVIES -> listOfNotNull(ebert, rt(RtList.FRESH_IN_CINEMAS), imdb(ImdbList.CHRISTMAS_MOVIES).takeIf { season }, because,
            imdb(ImdbList.BOX_OFFICE), imdb(ImdbList.FAMILY_MOVIES), imdb(ImdbList.HOT_MOVIES), imdb(ImdbList.TOP_MOVIES))
        Page.SHOWS -> listOfNotNull(rt(RtList.POPULAR_TV), imdb(ImdbList.CHRISTMAS_TV).takeIf { season }, because,
            imdb(ImdbList.NEW_SERIES), imdb(ImdbList.FAMILY_TV), imdb(ImdbList.HOT_TV), imdb(ImdbList.TOP_TV))
        // Foreign: what the chosen country has made best (its series, then its films), then its services in
        // its own order, each as ten films then ten series.
        // World: every country's highest rated TV, one row each, to scroll down through rather than hop chips.
        Page.FOREIGN -> if (foreignCountry() == WORLD) JW_COUNTRIES.map { c ->
            val title = "${c.label}: highest rated TV"
            title to { slot: LinearLayout, g: Int -> bestRow(slot, c, true, g, title, lead = doorItem(c)) }
        } else jwCountry(foreignCountry())?.let { c ->
            listOf(true, false).map { tv -> c.bestTitle(tv) to { slot: LinearLayout, g: Int -> bestRow(slot, c, tv, g) } } +
                c.services.flatMap { s -> listOf(JwList(s, false), JwList(s, true)) }.map { l -> l.title to { slot: LinearLayout, g: Int -> jwRow(slot, l, g) } }
        }.orEmpty()
        Page.IMDB -> listOf(ImdbList.TRENDING, ImdbList.ANTICIPATED, ImdbList.THIS_MONTH, ImdbList.POPULAR, ImdbList.RECENT).map(::imdb) +
            ebert + rt(RtList.FRESH_IN_CINEMAS) + rt(RtList.POPULAR_TV) +
            listOf(ImdbList.ON_NETFLIX, ImdbList.ON_PRIME, ImdbList.ON_DISNEY, ImdbList.ON_APPLE, ImdbList.ON_MAX, ImdbList.ON_PEACOCK).map(::imdb) +
            listOf(ImdbList.BOX_OFFICE, ImdbList.HOT_MOVIES, ImdbList.HOT_TV, ImdbList.NEW_SERIES).map(::imdb) +
            (if (season) listOf(ImdbList.CHRISTMAS_MOVIES, ImdbList.CHRISTMAS_TV) else emptyList()).map(::imdb) +
            listOf(ImdbList.FAMILY_MOVIES, ImdbList.FAMILY_TV).map(::imdb) +
            listOf(ImdbList.TOP_MOVIES, ImdbList.TOP_TV).map(::imdb)
        else -> emptyList()
    }
}

/**
 * "Because you watched …" (Alan, 2026-11-07): for the last title ([BECAUSE_SEEDS]) of the page's kind the viewer
 * watched, the titles IMDb finds most like each, bar what is in the library already. One row each, in [slot].
 * The seeds come from the library as this process has it (Home read it), else last time's from disk: no request.
 * A row the viewer hid (by its own title) stays hidden; the next seed is not promoted in its place.
 */
internal fun MainActivity.becauseRows(slot: LinearLayout, p: Page, g: Int) {
    val key = profile.authKey
    val today = java.time.LocalDate.now().toString()
    val kind = if (p == Page.SHOWS) "series" else "movie"
    background({
        val items = Stremio.knownLibrary(key) ?: Stremio.savedLibrary(key).orEmpty()
        val have = items.filter { !it.optBoolean("removed") }.map { it.optString("_id") }.toSet()
        val seeds = items.filter { it.optString("type") == kind && it.optString("_id").startsWith("tt") && it.optJSONObject("state")?.text("lastWatched") != null }
            .sortedByDescending { it.optJSONObject("state")?.text("lastWatched") }.take(BECAUSE_SEEDS).map { it.optString("_id") to it.optString("name") }
        seeds to have
    }) { r ->
        val (seeds, have) = r.getOrNull() ?: return@background
        if (g != generation) return@background
        seeds.forEach { (id, name) ->
            val title = "$BECAUSE_ROW $name"
            if ("${p.label}|$title" in hiddenSeen.orEmpty()) return@forEach
            val sub = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { slot.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            fun items(picks: List<ImdbPick>?) = picks?.filter { it.id !in have && it.id != id }?.map { imdbItem(it, today) }
            fetchedRow(sub, title, g, cached = { c -> items(cachedImdbSimilar(id, c)) }, saved = { c -> items(savedImdbSimilar(id, c)) }) { c -> items(imdbSimilar(id, c)).orEmpty() }
        }
    }
}

/**
 * A row that has been asked for and not answered yet: its name and a line of quiet blank cards in [slot], the
 * size of the row to come, so the page has its shape at once and the row arrives in place (Netflix's way). The
 * row replaces it; one that never comes (the site failed, or had nothing) takes it away after [GHOST_MS].
 */
internal fun MainActivity.waiting(slot: LinearLayout, title: String) {
    if (slot.childCount > 0 || page == Page.LIBRARY) return
    val ghost = column(Gravity.START,
        caps(title).onArt().apply { setPadding(dp(EDGE), 0, dp(PAD), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; alpha = QUIET },
        LinearLayout(this).apply {
            setPadding(dp(EDGE), dp(ROW_TOP_ROOM), dp(PAD), dp(ROW_BOTTOM_ROOM))
            repeat(GHOSTS) { i -> addView(View(context).apply { background = rounded(GHOST, 6) },
                LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H)).apply { if (i > 0) marginStart = dp(CARD_GAP) }) }
        })
    slot.addView(ghost)
    main.postDelayed({ if (ghost.parent === slot) slot.removeView(ghost) }, GHOST_MS)
}

/** One of IMDb's lists as a row in [slot]: last time's from disk at once, today's in its place when it differs. */
internal fun MainActivity.imdbRow(slot: LinearLayout, list: ImdbList, g: Int) {
    val today = java.time.LocalDate.now().toString()
    var shownIds = emptyList<String>()
    fun clear() {
        rows.filter { isInside(it.view, slot) }.forEach { r -> r.cards.forEach { it.unload() }; rows.remove(r) }
        slot.removeAllViews()
    }
    fun show(picks: List<ImdbPick>) {
        val ids = picks.map { it.id }
        if (g != generation || picks.isEmpty() || ids == shownIds) return
        shownIds = ids
        clear()
        val items = picks.map { imdbItem(it, today, list.trailers, numbered = list == ImdbList.TOP_TEN) }
        slot.addView(row(list.title, items.take(START), later(items)).view)
    }
    val country = imdbCountry()
    cachedImdbList(list, today, country)?.let { show(it); return } // fetched within the hour: no disk, no network
    waiting(slot, list.title)
    background({ savedImdbList(list, today, country) }) { r -> r.getOrNull()?.let(::show) }
    background({ imdbList(list, today, country) }) { r ->
        r.exceptionOrNull()?.let { android.util.Log.w("CAS", "IMDb list $list failed: $it") }
        r.getOrNull()?.let(::show)
    }
}

internal fun MainActivity.imdbItem(pk: ImdbPick, today: String, trailers: Boolean = false, numbered: Boolean = false): Item {
    val note = when {
        pk.rank != null -> "No. ${pk.rank}"
        pk.date != null && pk.date > today -> shortDay(pk.date, today)?.let { if (pk.type == "movie") "In cinemas $it" else "From $it" }
        else -> null
    }
    val facts = listOfNotNull(pk.genres.take(2).joinToString(", ").ifEmpty { null }, pk.year, pk.rating?.let { "IMDb $it" }, pk.gross)
    return Item(pk.id, pk.type, pk.name, pk.poster, null, Images.logoFor(pk.id), pk.plot ?: "", facts,
        null, 0f, resume = false, left = null, note = note, trailerId = pk.trailerId?.takeIf { trailers }, released = pk.date,
        rank = pk.rank.takeIf { numbered }) // Top 10: the numeral on the card
}

/**
 * RogerEbert.com's newest reviews as a poster row, on Movies and the IMDb page: the film as IMDb knows it, the
 * critic's name on the card, their one-line verdict where the plot would go. The site's star ratings are not in
 * its feed, so the IMDb rating stands in. Last time's row from disk first, today's in its place.
 */
internal fun MainActivity.ebertRow(slot: LinearLayout, g: Int) {
    val today = java.time.LocalDate.now().toString()
    fetchedRow(slot, EBERT_ROW, g, cached = { c -> cachedEbertReviews(c)?.map { ebertItem(it, today) } },
        saved = { c -> savedEbertReviews(c)?.map { ebertItem(it, today) } }) { c -> ebertReviews(c).map { ebertItem(it, today) } }
}

/** A service's ten in the Foreign page's country, as a poster row numbered from one. */
internal fun MainActivity.jwRow(slot: LinearLayout, list: JwList, g: Int) =
    fetchedRow(slot, list.title, g, country = foreignCountry(), cached = { c -> cachedJwList(list, c)?.map(::jwItem) },
        saved = { c -> savedJwList(list, c)?.map(::jwItem) }) { c -> jwList(list, c).map(::jwItem) }

internal fun MainActivity.jwItem(t: JwTitle) = rankedItem(t.title, t.rank, t.poster)

/** IMDb's highest rated series or films from the Foreign page's country, numbered from one. */
internal fun MainActivity.bestRow(slot: LinearLayout, c: JwCountry, tv: Boolean, g: Int, title: String = c.bestTitle(tv), lead: Item? = null) =
    fetchedRow(slot, title, g, country = c.code, lead = lead, cached = { cachedBest(c, tv)?.map { rankedItem(it, it.rank ?: 0) } },
        saved = { savedBest(c, tv)?.map { rankedItem(it, it.rank ?: 0) } }) { bestList(c, tv).map { rankedItem(it, it.rank ?: 0) } }

internal fun MainActivity.rankedItem(pk: ImdbPick, rank: Int, poster: String? = null): Item {
    val facts = listOfNotNull(pk.genres.take(2).joinToString(", ").ifEmpty { null }, pk.year, pk.rating?.let { "IMDb $it" })
    val origin = listOfNotNull(pk.countries.firstOrNull(), pk.languages.firstOrNull()).joinToString(DOT).ifEmpty { null }
    return Item(pk.id, pk.type, pk.name, pk.poster ?: poster, null, Images.logoFor(pk.id), pk.plot ?: "", facts,
        null, 0f, resume = false, left = null, note = "No. $rank".takeIf { rank > 0 }, released = pk.date,
        rank = rank.takeIf { it > 0 }, original = pk.original, origin = origin)
}

/** A Rotten Tomatoes list as a poster row: the Tomatometer on the card, both scores on the title line. */
internal fun MainActivity.rtRow(slot: LinearLayout, list: RtList, g: Int) =
    fetchedRow(slot, list.title, g, cached = { c -> cachedRtList(list, c)?.map(::rtItem) }, saved = { c -> savedRtList(list, c)?.map(::rtItem) }) { c -> rtList(list, c).map(::rtItem) }

/**
 * A row whose titles come from the web: [cached] (fetched within the hour) draws it at once with nothing read
 * or asked; otherwise the one file the last run kept ([saved]) fills it, and [fetch] runs live behind it. Each
 * answer replaces the row only when its titles differ from those shown.
 */
internal fun MainActivity.fetchedRow(slot: LinearLayout, title: String, g: Int, country: String = imdbCountry(), lead: Item? = null,
                       cached: (String) -> List<Item>?, saved: (String) -> List<Item>?, fetch: (String) -> List<Item>) {
    var shownIds = emptyList<String>()
    fun show(items: List<Item>) {
        val ids = items.map { it.id }
        if (g != generation || items.isEmpty() || ids == shownIds) return
        shownIds = ids
        rows.filter { isInside(it.view, slot) }.forEach { r -> r.cards.forEach { it.unload() }; rows.remove(r) }
        slot.removeAllViews()
        val all = listOfNotNull(lead) + items // a World row opens with its country's door
        slot.addView(row(title, all.take(START), later(all)).view)
    }
    cached(country)?.let { show(it); return }
    waiting(slot, title)
    background({ saved(country) }) { r -> r.getOrNull()?.let(::show) }
    background({ fetch(country) }) { r ->
        r.exceptionOrNull()?.let { android.util.Log.w("CAS", "$title failed: $it") }
        r.getOrNull()?.let(::show)
    }
}

internal fun MainActivity.rtItem(t: RtTitle): Item {
    val pk = t.title
    val rt = t.rt
    // Scores the short way Alan asked for: C for critics, A for audience.
    val meters = listOfNotNull(rt.critics?.let { "C$it%" }, rt.audience?.let { "A$it%" }).joinToString(" ").ifEmpty { null }
    val facts = listOfNotNull(pk.genres.take(2).joinToString(", ").ifEmpty { null }, pk.year, meters, "Certified Fresh".takeIf { rt.certified }, pk.rating?.let { "IMDb $it" })
    val note = meters
    return Item(pk.id, pk.type, pk.name, pk.poster ?: rt.poster, null, Images.logoFor(pk.id), pk.plot ?: "", facts,
        null, 0f, resume = false, left = null, note = note, released = pk.date)
}

internal fun MainActivity.ebertItem(e: EbertPick, today: String): Item {
    val pk = e.title
    val said = e.review.verdict?.let { v -> "\u201C$v\u201D" + (e.review.critic?.let { " \u2014 $it" } ?: "") }
    val facts = listOfNotNull(pk.genres.take(2).joinToString(", ").ifEmpty { null }, pk.year, pk.rating?.let { "IMDb $it" },
        e.review.date?.let { d -> shortDay(d, today)?.let { "Reviewed $it" } })
    return Item(pk.id, pk.type, pk.name, pk.poster, null, Images.logoFor(pk.id), said ?: pk.plot ?: "", facts,
        null, 0f, resume = false, left = null, note = e.review.critic, released = pk.date)
}

internal fun MainActivity.metaItem(m: Meta) = Item(
    m.id, m.type, m.name, m.poster, m.background, m.logo ?: Images.logoFor(m.id), m.description ?: "", facts(m),
    null, 0f, resume = false, left = null,
)
