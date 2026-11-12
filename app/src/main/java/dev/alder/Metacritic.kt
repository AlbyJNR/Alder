package dev.alder

import org.json.JSONArray
import org.json.JSONObject

/**
 * Metacritic (Alan, 2026-11-12): its Metascore beside the other ratings, and its best-reviewed films and TV of the
 * last two years as rows. Read from the service Metacritic's own pages ask (it answers a plain request), each title
 * then placed on IMDb by name and year, as the Rotten Tomatoes rows are, so it opens and plays like any other.
 */
enum class McList(val title: String, val tv: Boolean) {
    BEST_MOVIES("Best films on Metacritic", tv = false),
    BEST_TV("Best TV on Metacritic", tv = true),
}

class McPick(val title: String, val year: Int?, val score: Int) {
    fun json(): JSONObject = JSONObject().put("title", title).put("year", year).put("score", score)
    companion object {
        fun of(j: JSONObject) = McPick(j.getString("title"), j.optInt("year", 0).takeIf { it > 0 }, j.getInt("score"))
    }
}

class McTitle(val mc: McPick, val title: ImdbPick) {
    fun json(): JSONObject = JSONObject().put("mc", mc.json()).put("title", title.json())
    companion object {
        fun of(j: JSONObject): McTitle? = runCatching { McTitle(McPick.of(j.getJSONObject("mc")), imdbPickOf(j.getJSONObject("title"))!!) }.getOrNull()
    }
}

private const val MC = "https://backend.metacritic.com/finder/metacritic"
internal const val MC_ASK = 40 // titles asked for: some have too few reviews, some IMDb cannot place
internal const val MC_REVIEWS = 7 // critics' reviews a title needs before its score stands for much

/** The best reviewed of the kind since [yearFrom], highest Metascore first. */
internal fun mcListUrl(list: McList, yearFrom: Int) =
    "$MC/web?sortBy=-metaScore&productType=${if (list.tv) "tv" else "movies"}&page=1&releaseYearMin=$yearFrom&offset=0&limit=$MC_ASK"

private fun mcItems(json: JSONObject) = json.optJSONObject("data")?.optJSONArray("items").objects()

/** The list's titles in order: those with a score and enough reviews behind it. */
internal fun parseMcList(json: JSONObject): List<McPick> = mcItems(json).mapNotNull { t ->
    val name = t.text("title") ?: return@mapNotNull null
    val s = t.optJSONObject("criticScoreSummary") ?: return@mapNotNull null
    val score = s.optInt("score", 0)
    if (score <= 0 || s.optInt("reviewCount", 0) < MC_REVIEWS) null else McPick(name, t.optInt("premiereYear", 0).takeIf { it > 0 }, score)
}

private val mcCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<McTitle>>>()

internal fun cachedMcList(list: McList, country: String): List<McTitle>? =
    mcCache["$list/$country"]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.second

internal fun savedMcList(list: McList, country: String): List<McTitle>? = savedRow("cas://mc/$list/$country")?.objects()?.mapNotNull { McTitle.of(it) }?.ifEmpty { null }

/** The list with each title's IMDb details; titles IMDb cannot place by name are left out. Blocks. */
internal fun mcList(list: McList, country: String, yearFrom: Int = java.time.LocalDate.now().year - 1): List<McTitle> {
    cachedMcList(list, country)?.let { return it }
    val source = Source(country)
    val picks = parseMcList(JSONObject(source.text(mcListUrl(list, yearFrom))))
    val kinds = if (list.tv) IMDB_KINDS_TV else IMDB_KINDS_FILM
    val found = picks.mapNotNull { p -> source.imdbId(p.title, p.year, kinds)?.let { p to it } }
    val titles = source.imdbTitles(found.map { it.second })
    return found.mapNotNull { (p, id) -> titles[id]?.let { McTitle(p, it) } }.distinctBy { it.title.id }.also { rows ->
        if (rows.isNotEmpty()) {
            mcCache["$list/$country"] = System.currentTimeMillis() to rows
            keepRow("cas://mc/$list/$country", JSONArray(rows.map { it.json() }))
        }
    }
}

internal fun mcSearchUrl(name: String, tv: Boolean) =
    "$MC/search/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") + "/web?mcoTypeId=${if (tv) 1 else 2}&limit=5"

private val NOT_WORDS = Regex("[^\\p{L}\\p{N}]+")
private fun plainName(s: String) = s.lowercase().replace(NOT_WORDS, "")

/** The Metascore of the title named [name] in a search answer: the same name, and a year within one of [year] when both are known. */
internal fun mcMatch(json: JSONObject, name: String, year: Int?): Int? = mcItems(json).firstOrNull { t ->
    val y = t.optInt("premiereYear", 0)
    plainName(t.text("title").orEmpty()) == plainName(name) && (year == null || y == 0 || kotlin.math.abs(y - year) <= 1)
}?.optJSONObject("criticScoreSummary")?.optInt("score", 0)?.takeIf { it > 0 }

private val mcScoreCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

/** One title's Metascore by its name and year, or null when Metacritic has none; asked once a run. Blocks. */
internal fun mcScore(name: String, year: Int?, tv: Boolean): Int? {
    val k = "$name/$year/$tv"
    mcScoreCache[k]?.let { return it.takeIf { s -> s > 0 } }
    val score = runCatching { mcMatch(JSONObject(Http.get(mcSearchUrl(name, tv), keep = true, timeoutMs = 12_000)), name, year) }
        .onFailure { android.util.Log.w("CAS", "Metacritic score for $name failed: $it") }.getOrNull()
    mcScoreCache.capped()[k] = score ?: 0
    return score
}

/** Metacritic's own bands: green from 61, yellow from 40, red under it. */
internal fun metaColour(score: Int): Int = when {
    score >= 61 -> 0xFF6BC24A.toInt()
    score >= 40 -> 0xFFF2C744.toInt()
    else -> 0xFFE5604F.toInt()
}
