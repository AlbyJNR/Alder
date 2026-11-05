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
