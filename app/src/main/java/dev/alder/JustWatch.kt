package dev.alder

import org.json.JSONArray
import org.json.JSONObject

/**
 * What is streaming where: the ten most popular films and the ten most popular series on each service in a country,
 * from JustWatch's public catalogue API (the calls its own site makes; no key, a plain client). The Foreign page
 * lists the countries Alan asked for, each with the big services and its own (Stan, Globoplay, Canal+, U-NEXT…).
 * Each title comes with its IMDb id, so IMDb fills in the poster, plot, rating and genres as on every other row.
 */
class JwCountry(val code: String, val label: String, val services: List<JwService>)

/** A service by the short names JustWatch gives it; Prime Video is "amp" in the US and Japan, "prv" elsewhere. */
class JwService(val name: String, vararg val ids: String)

private val NETFLIX = JwService("Netflix", "nfx")
private val PRIME = JwService("Prime Video", "amp", "prv")
private val DISNEY = JwService("Disney+", "dnp")
private val APPLE = JwService("Apple TV", "atp")
private val MAX = JwService("HBO Max", "mxx", "mxu")
private val PARAMOUNT = JwService("Paramount+", "pmp", "ppp")

/** The countries on the Foreign page's chips, alphabetical, each with the services its own viewers rank. */
val JW_COUNTRIES = listOf(
    JwCountry("AR", "Argentina", listOf(NETFLIX, PRIME, DISNEY, APPLE, MAX, PARAMOUNT)),
    JwCountry("AU", "Australia", listOf(NETFLIX, PRIME, DISNEY, APPLE, JwService("Stan", "stn"), JwService("BINGE", "bng"), PARAMOUNT)),
    JwCountry("BR", "Brazil", listOf(NETFLIX, PRIME, DISNEY, APPLE, MAX, JwService("Globoplay", "gop"), PARAMOUNT)),
    JwCountry("FR", "France", listOf(NETFLIX, PRIME, DISNEY, APPLE, JwService("Canal+", "cpd"), PARAMOUNT)),
    JwCountry("IT", "Italy", listOf(NETFLIX, PRIME, DISNEY, APPLE, JwService("Now TV", "ntv"), JwService("RaiPlay", "rai"), PARAMOUNT)),
    JwCountry("JP", "Japan", listOf(NETFLIX, PRIME, DISNEY, APPLE, JwService("U-NEXT", "unx"), JwService("Hulu", "hlu"))),
    JwCountry("ES", "Spain", listOf(NETFLIX, PRIME, DISNEY, APPLE, MAX, JwService("Movistar Plus+", "mp9"))),
    JwCountry("US", "United States", listOf(NETFLIX, PRIME, DISNEY, APPLE, MAX, JwService("Hulu", "hlu"), JwService("Peacock", "pct"), PARAMOUNT)),
)

fun jwCountry(code: String): JwCountry? = JW_COUNTRIES.firstOrNull { it.code == code }

/** One row: a service's ten films or ten series. */
class JwList(val service: JwService, val tv: Boolean) {
    val title: String get() = "Top 10 ${if (tv) "TV" else "films"} on ${service.name}"
    val key: String get() = "${service.ids.first()}/${if (tv) "tv" else "film"}"
}

class JwPick(val imdbId: String?, val title: String, val year: Int?, val poster: String?)

/** A placed title: its rank on the service, JustWatch's poster (IMDb's is preferred), and IMDb's details. */
class JwTitle(val rank: Int, val poster: String?, val title: ImdbPick) {
    fun json(): JSONObject = JSONObject().put("rank", rank).put("poster", poster).put("title", title.json())
    companion object {
        fun of(j: JSONObject): JwTitle? = runCatching { JwTitle(j.getInt("rank"), j.text("poster"), imdbPickOf(j.getJSONObject("title"))!!) }.getOrNull()
    }
}

internal const val JW_URL = "https://apis.justwatch.com/graphql"
internal const val JW_SIZE = 10

/** The request JustWatch's site sends for a browse page: popular titles in [country] on the service, films or series. */
internal fun jwQuery(country: String, list: JwList): String = JSONObject()
    .put("operationName", "CAS")
    .put("variables", JSONObject().put("country", country).put("first", JW_SIZE)
        .put("filter", JSONObject().put("packages", JSONArray(list.service.ids.toList())).put("objectTypes", JSONArray(listOf(if (list.tv) "SHOW" else "MOVIE"))))
        .put("sortBy", "POPULAR"))
    .put("query", "query CAS(\$country: Country!, \$first: Int!, \$filter: TitleFilter, \$sortBy: PopularTitlesSorting!) { " +
        "popularTitles(country: \$country, first: \$first, filter: \$filter, sortBy: \$sortBy) { edges { node { objectType " +
        "content(country: \$country, language: \"en\") { title originalReleaseYear externalIds { imdbId } posterUrl } } } } }")
    .toString()

/** The answer's titles in rank order; one without a name is skipped. */
internal fun parseJwList(json: JSONObject): List<JwPick> =
    json.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges").objects().mapNotNull { e ->
        val c = e.optJSONObject("node")?.optJSONObject("content") ?: return@mapNotNull null
        val name = c.text("title") ?: return@mapNotNull null
        JwPick(c.optJSONObject("externalIds")?.text("imdbId")?.takeIf { it.startsWith("tt") }, name,
            c.optInt("originalReleaseYear", 0).takeIf { it > 0 }, jwPoster(c.text("posterUrl")))
    }

/** JustWatch's poster path, "/poster/123/{profile}/name.{format}", as a picture of a card's width. */
internal fun jwPoster(path: String?): String? = path?.let { "https://images.justwatch.com" + it.replace("{profile}", "s332").replace("{format}", "jpg") }

private val jwCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<JwTitle>>>()

/** The row as fetched within the hour, or null: drawn at once, nothing read or asked. An empty row counts too. */
internal fun cachedJwList(list: JwList, country: String): List<JwTitle>? =
    jwCache["$country/${list.key}"]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.second

/** The row the last run kept on disk, or null; one file, no network. */
internal fun savedJwList(list: JwList, country: String): List<JwTitle>? =
    savedRow("cas://jw/$country/${list.key}")?.objects()?.mapNotNull { JwTitle.of(it) }?.ifEmpty { null }

/**
 * The service's ten in [country], each with IMDb's details, looked up from [home] (the TV's own country, so names
 * read as they do on the other pages). A title JustWatch gives no IMDb id is placed by name; one IMDb cannot place
 * is left out. A service the country does not have answers with nothing, and its row goes away.
 */
internal fun jwList(list: JwList, country: String, home: String = java.util.Locale.getDefault().country.ifEmpty { "GB" }): List<JwTitle> {
    cachedJwList(list, country)?.let { return it }
    val source = Source(home)
    val picks = parseJwList(JSONObject(Http.post(JW_URL, jwQuery(country, list), timeoutMs = 15_000)))
    val kinds = if (list.tv) IMDB_KINDS_TV else IMDB_KINDS_FILM
    val ids = picks.map { p -> p.imdbId ?: source.imdbId(p.title, p.year, kinds) }
    val titles = source.imdbTitles(ids.filterNotNull())
    return picks.indices.mapNotNull { i -> ids[i]?.let { titles[it] }?.let { JwTitle(i + 1, picks[i].poster, it) } }.distinctBy { it.title.id }.also { rows ->
        jwCache["$country/${list.key}"] = System.currentTimeMillis() to rows
        if (rows.isNotEmpty()) keepRow("cas://jw/$country/${list.key}", JSONArray(rows.map { it.json() }))
    }
}
