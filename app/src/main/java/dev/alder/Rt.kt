package dev.alder

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rotten Tomatoes' browse lists, read the way its own pages page them: thirty titles a call with the Tomatometer, the
 * audience score and whether critics certified it fresh. Its poster and name place the title on IMDb for the rest.
 */
enum class RtList(val title: String, val path: String, val tv: Boolean, val calls: Int = 1) {
    FRESH_IN_CINEMAS("Certified Fresh in cinemas", "movies_in_theaters/critics:certified_fresh~sort:popular", tv = false),
    POPULAR_TV("Popular TV on Rotten Tomatoes", "tv_series_browse/sort:popular", tv = true),
}

private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
private val NOT_LETTERS = Regex("[^\\p{L}\\p{N}]+")

class RtPick(val title: String, val critics: Int?, val certified: Boolean, val audience: Int?, val poster: String?, val released: String?) {
    /** The year in "Opened Oct 23, 2026"; series say only "Latest Episode: Oct 30", so none. */
    val year: Int? get() = released?.let { YEAR.find(it)?.value?.toIntOrNull() }

    fun json(): JSONObject = JSONObject().put("title", title).put("critics", critics).put("certified", certified)
        .put("audience", audience).put("poster", poster).put("released", released)
    companion object {
        fun of(j: JSONObject) = RtPick(j.getString("title"), j.optInt("critics", -1).takeIf { it >= 0 }, j.optBoolean("certified"),
            j.optInt("audience", -1).takeIf { it >= 0 }, j.text("poster"), j.text("released"))
    }
}

class RtTitle(val rt: RtPick, val title: ImdbPick) {
    fun json(): JSONObject = JSONObject().put("rt", rt.json()).put("title", title.json())
    companion object {
        fun of(j: JSONObject): RtTitle? = runCatching { RtTitle(RtPick.of(j.getJSONObject("rt")), imdbPickOf(j.getJSONObject("title"))!!) }.getOrNull()
    }
}

internal fun rtListUrl(list: RtList, after: String? = null) =
    "https://www.rottentomatoes.com/cnapi/browse/${list.path}" + (after?.let { "?after=$it" } ?: "")

/** One answer's titles in order, and the cursor for the next thirty when there are more. */
internal fun parseRtList(json: JSONObject): Pair<List<RtPick>, String?> {
    fun score(o: JSONObject?) = o?.text("score")?.toIntOrNull()
    val picks = json.optJSONObject("grid")?.optJSONArray("list").objects().mapNotNull { t ->
        val name = t.text("title") ?: return@mapNotNull null
        RtPick(name, score(t.optJSONObject("criticsScore")), t.optJSONObject("criticsScore")?.optBoolean("certified") == true,
            score(t.optJSONObject("audienceScore")), t.text("posterUri"), t.text("releaseDateText"))
    }
    val page = json.optJSONObject("pageInfo")
    return picks to page?.takeIf { it.optBoolean("hasNextPage") }?.text("endCursor")
}

private val rtCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<RtTitle>>>()

/** The row as fetched within the hour, or null: drawn at once, nothing read or asked. */
internal fun cachedRtList(list: RtList, country: String): List<RtTitle>? =
    rtCache["$list/$country"]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.second

/** The row the last run kept on disk, or null; one file, no network. */
internal fun savedRtList(list: RtList, country: String): List<RtTitle>? = savedRow("cas://rt/$list/$country")?.objects()?.mapNotNull { RtTitle.of(it) }?.ifEmpty { null }

/** The list with each title's IMDb details; titles IMDb cannot place by name are left out. */
internal fun rtList(list: RtList, country: String): List<RtTitle> {
    cachedRtList(list, country)?.let { return it }
    val source = Source(country)
    val picks = mutableListOf<RtPick>()
    var after: String? = null
    for (i in 1..list.calls) {
        val (page, next) = parseRtList(JSONObject(source.text(rtListUrl(list, after))))
        picks += page
        after = next ?: break
    }
    val kinds = if (list.tv) IMDB_KINDS_TV else IMDB_KINDS_FILM
    val found = picks.mapNotNull { p -> source.imdbId(p.title, p.year, kinds)?.let { p to it } }
    val titles = source.imdbTitles(found.map { it.second })
    return found.mapNotNull { (p, id) -> titles[id]?.let { RtTitle(p, it) } }.distinctBy { it.title.id }.also { rows ->
        if (rows.isNotEmpty()) {
            rtCache["$list/$country"] = System.currentTimeMillis() to rows
            keepRow("cas://rt/$list/$country", JSONArray(rows.map { it.json() }))
        }
    }
}

/**
 * What Rotten Tomatoes says of one title: both meters, whether each is certified, and the critics' one-line consensus.
 * A series has no consensus of its own, so [consensus] is its newest season's and [season] says which.
 */
class RtScores(val url: String, val critics: Int?, val criticsCertified: Boolean, val audience: Int?, val audienceCertified: Boolean,
               val consensus: String?, val season: Int? = null) {
    fun withConsensus(line: String?, season: Int?) = RtScores(url, critics, criticsCertified, audience, audienceCertified, line, season)

    fun json(): JSONObject = JSONObject().put("url", url).put("critics", critics).put("criticsCertified", criticsCertified)
        .put("audience", audience).put("audienceCertified", audienceCertified).put("consensus", consensus).put("season", season)
    companion object {
        fun of(j: JSONObject): RtScores? = j.text("url")?.let { url ->
            RtScores(url, j.optInt("critics", -1).takeIf { it >= 0 }, j.optBoolean("criticsCertified"), j.optInt("audience", -1).takeIf { it >= 0 },
                j.optBoolean("audienceCertified"), j.text("consensus"), j.optInt("season", 0).takeIf { it > 0 })
        }
    }
}

/** A series page's season pages, newest first, by number. */
internal fun rtSeasonUrls(html: String, url: String): List<Pair<Int, String>> =
    Regex("href=\"(/tv/[^/\"]+/s(\\d+))\"").findAll(html).map { it.groupValues[2].toInt() to "https://www.rottentomatoes.com" + it.groupValues[1] }
        .filter { it.second.startsWith(url.trimEnd('/') + "/s") }.distinct().sortedByDescending { it.first }.toList()

internal fun rtConsensus(html: String): String? =
    Regex("<div id=\"critics-consensus\"[^>]*>.*?<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let(::plainText)?.ifBlank { null }

internal fun rtSearchUrl(name: String) = "https://www.rottentomatoes.com/search?search=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

/**
 * The address of the title on Rotten Tomatoes, from its search page: a film row (/m/) or series row (/tv/) of the
 * same name (ignoring case and punctuation), in [year] or a year either side when one is known. Null when none is.
 */
internal fun rtSearchMatch(html: String, name: String, year: Int?, tv: Boolean): String? {
    fun key(s: String) = plainText(s).lowercase().replace(NOT_LETTERS, " ").trim()
    val wanted = key(name)
    val rows = Regex("<search-page-media-row(.*?)</search-page-media-row>", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { m ->
        val row = m.groupValues[1]
        val url = Regex("href=\"(https://www\\.rottentomatoes\\.com/(m|tv)/[^\"]+)\"").find(row) ?: return@mapNotNull null
        if ((url.groupValues[2] == "tv") != tv) return@mapNotNull null
        val title = Regex("slot=\"title\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(row)?.groupValues?.get(1) ?: return@mapNotNull null
        val rowYear = Regex("(?:release-?year|start-?year)=\"(\\d{4})\"").find(row)?.groupValues?.get(1)?.toIntOrNull()
        Triple(url.groupValues[1], key(title), rowYear)
    }.filter { it.second == wanted }.toList()
    return (rows.firstOrNull { year == null || it.third == null || it.third in (year - 1)..(year + 1) } ?: rows.firstOrNull { year == null })?.first
}

/** The meters and consensus out of a title page: its scorecard JSON and the Critics Consensus paragraph. */
internal fun parseRtScores(html: String, url: String): RtScores? {
    val card = Regex("<script[^>]*id=\"media-scorecard-json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    fun pct(o: JSONObject?) = o?.text("scorePercent")?.trimEnd('%')?.toIntOrNull()
    val critics = card.optJSONObject("criticsScore")
    val audience = card.optJSONObject("audienceScore")
    return RtScores(url, pct(critics), critics?.optBoolean("certified") == true, pct(audience), audience?.optBoolean("certified") == true, rtConsensus(html))
}

private val rtScoreCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, RtScores?>>()
private const val RT_SCORES_KEEP = 7 * 24 * 3600_000L // a week: scores move slowly once a title is out
private const val RT_MISS_KEEP = 24 * 3600_000L // a day: a title not on Rotten Tomatoes yet may be tomorrow

/**
 * Rotten Tomatoes on a title, by name: null when it has no page for it. Its search and title pages are read and
 * dropped; what they said is kept as a few hundred bytes for a week (a day for a miss), in memory and on disk.
 */
internal fun rtScores(name: String, year: Int?, tv: Boolean): RtScores? {
    val k = "cas://rt-scores/$name/$year/$tv"
    rtScoreCache[k]?.let { (at, s) -> if (System.currentTimeMillis() - at < (if (s == null) RT_MISS_KEEP else RT_SCORES_KEEP)) return s }
    Http.savedAnswer(k)?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()?.let { j ->
            val at = j.optLong("at", 0L)
            val s = RtScores.of(j)
            if (System.currentTimeMillis() - at < (if (s == null) RT_MISS_KEEP else RT_SCORES_KEEP)) { rtScoreCache[k] = at to s; return s }
        }
    }
    val url = rtSearchMatch(Http.get(rtSearchUrl(name), timeoutMs = 15_000), name, year, tv)
    val page = url?.let { Http.get(it, timeoutMs = 15_000) }
    var scores = page?.let { parseRtScores(it, url) }
    // A series' consensus is written per season: the newest season that has one (the newest may not have aired).
    if (scores != null && scores.consensus == null && tv) for ((season, seasonUrl) in rtSeasonUrls(page!!, url).take(3)) {
        val line = runCatching { rtConsensus(Http.get(seasonUrl, timeoutMs = 15_000)) }.getOrNull() ?: continue
        scores = scores?.withConsensus(line, season)
        break
    }
    val now = System.currentTimeMillis()
    rtScoreCache[k] = now to scores
    Http.keep(k, null, (scores?.json() ?: JSONObject()).put("at", now).toString())
    return scores
}
