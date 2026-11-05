package dev.alder

import org.json.JSONObject

/**
 * Rotten Tomatoes' browse lists, read the way its own pages page them: thirty titles a call with the Tomatometer, the
 * audience score and whether critics certified it fresh. Its poster and name place the title on IMDb for the rest.
 */
enum class RtList(val title: String, val path: String, val tv: Boolean, val calls: Int = 1) {
    FRESH_IN_CINEMAS("Certified Fresh in cinemas", "movies_in_theaters/critics:certified_fresh~sort:popular", tv = false),
    POPULAR_TV("Popular TV on Rotten Tomatoes", "tv_series_browse/sort:popular", tv = true, calls = 2),
}

class RtPick(val title: String, val critics: Int?, val certified: Boolean, val audience: Int?, val poster: String?, val released: String?) {
    /** The year in "Opened Oct 23, 2026"; series say only "Latest Episode: Oct 30", so none. */
    val year: Int? get() = released?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }
}

class RtTitle(val rt: RtPick, val title: ImdbPick)

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

/** The list with each title's IMDb details; titles IMDb cannot place by name are left out. */
internal fun rtList(list: RtList, country: String, offline: Boolean = false): List<RtTitle> {
    val k = "$list/$country"
    if (!offline) rtCache[k]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.let { return it.second }
    val source = Source(country, offline)
    val picks = mutableListOf<RtPick>()
    var after: String? = null
    for (i in 1..list.calls) {
        val (page, next) = source.text(rtListUrl(list, after))?.let { parseRtList(JSONObject(it)) } ?: break
        picks += page
        after = next ?: break
    }
    val kinds = if (list.tv) IMDB_KINDS_TV else IMDB_KINDS_FILM
    val found = picks.mapNotNull { p -> source.imdbId(p.title, p.year, kinds)?.let { p to it } }
    val titles = source.imdbTitles(found.map { it.second })
    return found.mapNotNull { (p, id) -> titles[id]?.let { RtTitle(p, it) } }.distinctBy { it.title.id }
        .also { if (!offline && it.isNotEmpty()) rtCache[k] = System.currentTimeMillis() to it }
}

/**
 * What Rotten Tomatoes says of one title: both meters, whether each is certified, and the critics' one-line consensus.
 * A series has no consensus of its own, so [consensus] is its newest season's and [season] says which.
 */
class RtScores(val url: String, val critics: Int?, val criticsCertified: Boolean, val audience: Int?, val audienceCertified: Boolean,
               val consensus: String?, val season: Int? = null) {
    fun withConsensus(line: String?, season: Int?) = RtScores(url, critics, criticsCertified, audience, audienceCertified, line, season)
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
    fun key(s: String) = plainText(s).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
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

/** Rotten Tomatoes on a title, by name: null when it has no page for it. Two page reads, each kept on disk. */
internal fun rtScores(name: String, year: Int?, tv: Boolean): RtScores? {
    val k = "$name/$year/$tv"
    rtScoreCache[k]?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.let { return it.second }
    val url = rtSearchMatch(Http.get(rtSearchUrl(name), keep = true, timeoutMs = 15_000), name, year, tv)
    val page = url?.let { Http.get(it, keep = true, timeoutMs = 15_000) }
    var scores = page?.let { parseRtScores(it, url) }
    // A series' consensus is written per season: the newest season that has one (the newest may not have aired).
    if (scores != null && scores.consensus == null && tv) for ((season, seasonUrl) in rtSeasonUrls(page!!, url).take(3)) {
        val line = runCatching { rtConsensus(Http.get(seasonUrl, keep = true, timeoutMs = 15_000)) }.getOrNull() ?: continue
        scores = scores?.withConsensus(line, season)
        break
    }
    rtScoreCache[k] = System.currentTimeMillis() to scores
    return scores
}
