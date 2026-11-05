package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal val IMDB_KINDS_FILM = setOf("movie", "tvMovie")
internal val IMDB_KINDS_TV = setOf("tvSeries", "tvMiniSeries")
internal val IMDB_KINDS_ANY = IMDB_KINDS_FILM + IMDB_KINDS_TV

/** IMDb lookups for the lists read from the web, asked from [country]. Each name is resolved once per run of the app. */
internal class Source(val country: String) {
    private val headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized", "x-imdb-user-country" to country)

    /** A page from the web; [keep] saves the answer on disk for next time (small lookups only, pages are not worth it). */
    fun text(url: String, json: String? = null, keep: Boolean = false): String =
        if (json == null) Http.get(url, keep = keep, timeoutMs = 15_000)
        else Http.post(url, json, keep = keep, timeoutMs = 15_000, headers = headers)

    /** IMDb's id for a title by name, from its search-box lookup; null when nothing fits or the lookup fails. */
    fun imdbId(name: String, year: Int?, kinds: Set<String>): String? {
        val k = "$name/$year/${kinds.sorted()}"
        imdbIdCache[k]?.let { return it.ifEmpty { null } }
        val id = runCatching { imdbTitleFor(name, year, JSONObject(text(imdbSuggestUrl(name), keep = true)), kinds) }.getOrNull()
        imdbIdCache[k] = id.orEmpty() // "" is a name IMDb could not place: not asked again this run
        return id
    }

    /** The titles' details by id, in one request: poster, year, rating, plot, genres. */
    fun imdbTitles(ids: Collection<String>): Map<String, ImdbPick> {
        if (ids.isEmpty()) return emptyMap()
        val query = JSONObject().put("query", "query CAS_$country{titles(ids:${JSONArray(ids.distinct())}){$IMDB_TITLE_FIELDS}}").toString()
        return runCatching { JSONObject(text("https://api.graphql.imdb.com/", query)).optJSONObject("data")?.optJSONArray("titles").objects() }.getOrNull()
            ?.mapNotNull { imdbPick(it) }?.associateBy { it.id } ?: emptyMap()
    }
}

/** Name → IMDb id, shared by every list and the hero's lookups, so no name is asked of IMDb twice in a run. */
private val imdbIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

internal fun imdbSuggestUrl(name: String) =
    "https://v3.sg.media-imdb.com/suggestion/x/${URLEncoder.encode(name.lowercase().take(60), "UTF-8").replace("+", "%20")}.json"

/**
 * The IMDb title a name means, from IMDb's search-box answer: the same name (ignoring case and punctuation) of one of
 * the [kinds], in [year] or the two before it, wins; then the same name from any year (a re-release in cinemas keeps
 * its old year on IMDb); then the first of those kinds in the year whose name starts the same. Without a year the
 * first by name wins (IMDb lists the best known first). Null when nothing fits.
 */
internal fun imdbTitleFor(name: String, year: Int?, json: JSONObject, kinds: Set<String> = IMDB_KINDS_ANY): String? {
    fun key(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val wanted = key(name)
    val titles = json.optJSONArray("d").objects().filter { it.text("qid") in kinds }
    fun yearFits(t: JSONObject) = year == null || t.optInt("y", 0).let { it in (year - 2)..year }
    return (titles.firstOrNull { key(it.text("l").orEmpty()) == wanted && yearFits(it) }
        ?: titles.firstOrNull { key(it.text("l").orEmpty()) == wanted }
        ?: titles.firstOrNull { yearFits(it) && key(it.text("l").orEmpty()).startsWith(wanted) })?.text("id")
}

/**
 * A finished row kept on disk as one small file, under a name of CAS's own ("cas://ebert/GB"): the next start reads
 * and parses that alone, instead of replaying the feed pages and every lookup that made it.
 */
internal fun savedRow(key: String): JSONArray? = Http.savedAnswer(key)?.let { runCatching { JSONArray(it) }.getOrNull() }
internal fun keepRow(key: String, rows: JSONArray) = Http.keep(key, null, rows.toString())
