package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal val IMDB_KINDS_FILM = setOf("movie", "tvMovie")
internal val IMDB_KINDS_TV = setOf("tvSeries", "tvMiniSeries")
internal val IMDB_KINDS_ANY = IMDB_KINDS_FILM + IMDB_KINDS_TV

/**
 * How a list from the web is read: live, each answer kept on disk, or [offline], from those kept answers alone, so a
 * row can show at once before today's answer is in. IMDb lookups ride along, asked from [country].
 */
internal class Source(val country: String, val offline: Boolean) {
    private val headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized", "x-imdb-user-country" to country)

    fun text(url: String, json: String? = null): String? =
        if (offline) Http.savedAnswer(url, json)
        else if (json == null) Http.get(url, keep = true, timeoutMs = 15_000)
        else Http.post(url, json, keep = true, timeoutMs = 15_000, headers = headers)

    /** IMDb's id for a title by name, from its search-box lookup; null when nothing fits or the lookup fails. */
    fun imdbId(name: String, year: Int?, kinds: Set<String>): String? =
        runCatching { text(imdbSuggestUrl(name))?.let { imdbTitleFor(name, year, JSONObject(it), kinds) } }.getOrNull()

    /** The titles' details by id, in one request: poster, year, rating, plot, genres. */
    fun imdbTitles(ids: Collection<String>): Map<String, ImdbPick> {
        if (ids.isEmpty()) return emptyMap()
        val query = JSONObject().put("query", "query CAS_$country{titles(ids:${JSONArray(ids.distinct())}){$IMDB_TITLE_FIELDS}}").toString()
        return text("https://api.graphql.imdb.com/", query)?.let { JSONObject(it).optJSONObject("data")?.optJSONArray("titles").objects() }
            ?.mapNotNull { imdbPick(it) }?.associateBy { it.id } ?: emptyMap()
    }
}

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
