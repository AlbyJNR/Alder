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
    /** The field's name in the one request that asks for a whole country. */
    val alias: String get() = "l_${service.ids.first()}_${if (tv) "tv" else "film"}"
}

/** A title as JustWatch lists it: enough for a card and the title line without asking IMDb. */
class JwPick(val imdbId: String?, val title: String, val year: Int?, val poster: String?, val tv: Boolean,
             val plot: String?, val genres: List<String>, val rating: String?)

/** A placed title: its rank on the service and its details in the shape every other row uses. */
class JwTitle(val rank: Int, val poster: String?, val title: ImdbPick) {
    fun json(): JSONObject = JSONObject().put("rank", rank).put("poster", poster).put("title", title.json())
    companion object {
        fun of(j: JSONObject): JwTitle? = runCatching { JwTitle(j.getInt("rank"), j.text("poster"), imdbPickOf(j.getJSONObject("title"))!!) }.getOrNull()
    }
}

internal const val JW_URL = "https://apis.justwatch.com/graphql"
internal const val JW_SIZE = 10

private const val JW_FIELDS = "edges { node { objectType content(country: \$country, language: \"en\") { title originalReleaseYear shortDescription externalIds { imdbId } posterUrl genres { shortName } scoring { imdbScore } } } }"

/** JustWatch's genre codes, as words. */
private val JW_GENRES = mapOf("act" to "Action", "ani" to "Animation", "cmy" to "Comedy", "crm" to "Crime", "doc" to "Documentary",
    "drm" to "Drama", "fml" to "Family", "fnt" to "Fantasy", "hrr" to "Horror", "hst" to "History", "msc" to "Music", "rma" to "Romance",
    "scf" to "Sci-Fi", "spt" to "Sport", "trl" to "Thriller", "war" to "War", "wsn" to "Western", "rly" to "Reality")

/** All of a country's lists in one request: each service's ten films and ten series under its own field name. */
internal fun jwCountryQuery(country: JwCountry): String {
    val fields = country.services.flatMap { s -> listOf(JwList(s, false), JwList(s, true)) }.joinToString(" ") { l ->
        "${l.alias}: popularTitles(country: \$country, first: $JW_SIZE, sortBy: POPULAR, filter: {packages: ${JSONArray(l.service.ids.toList())}, objectTypes: [${if (l.tv) "SHOW" else "MOVIE"}]}) { $JW_FIELDS }"
    }
    return JSONObject().put("operationName", "CAS").put("variables", JSONObject().put("country", country.code))
        .put("query", "query CAS(\$country: Country!) { $fields }").toString()
}

/** The answer's lists by row key, each in rank order; a title without a name is skipped. */
internal fun parseJwCountry(json: JSONObject, country: JwCountry): Map<String, List<JwPick>> {
    val data = json.optJSONObject("data") ?: return emptyMap()
    return country.services.flatMap { s -> listOf(JwList(s, false), JwList(s, true)) }.associate { l ->
        l.key to data.optJSONObject(l.alias)?.optJSONArray("edges").objects().mapNotNull { e ->
            val c = e.optJSONObject("node")?.optJSONObject("content") ?: return@mapNotNull null
            val name = c.text("title") ?: return@mapNotNull null
            JwPick(c.optJSONObject("externalIds")?.text("imdbId")?.takeIf { it.startsWith("tt") }, name,
                c.optInt("originalReleaseYear", 0).takeIf { it > 0 }, jwPoster(c.text("posterUrl")), l.tv,
                c.text("shortDescription"), c.optJSONArray("genres").objects().mapNotNull { JW_GENRES[it.text("shortName")] },
                c.optJSONObject("scoring")?.optDouble("imdbScore", 0.0)?.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.US, it) })
        }
    }
}

/** The pick in the shape the cards and the title line read, under its IMDb id. */
internal fun jwTitle(p: JwPick, id: String, rank: Int) = JwTitle(rank, p.poster,
    ImdbPick(id, p.title, if (p.tv) "series" else "movie", p.poster, p.year?.toString(), p.plot, p.genres, p.rating, rank, null, null, null, null))

/** JustWatch's poster path, "/poster/123/{profile}/name.{format}", as a picture of a card's width. */
internal fun jwPoster(path: String?): String? = path?.let { "https://images.justwatch.com" + it.replace("{profile}", "s332").replace("{format}", "jpg") }

private val jwCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<String, List<JwTitle>>>>()
private val jwSaved = java.util.concurrent.ConcurrentHashMap<String, Map<String, List<JwTitle>>>() // the disk file, read once a run
private val jwLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
private val jwAhead = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "cas-jw-ahead").apply { isDaemon = true } }

/** The country's lists as fetched within the hour, or null. */
private fun cachedJwCountry(country: String) = jwCache[country]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.second

/** The row as fetched within the hour, or null: drawn at once, nothing read or asked. An empty row counts too. */
internal fun cachedJwList(list: JwList, country: String): List<JwTitle>? = cachedJwCountry(country)?.let { it[list.key].orEmpty() }

/** The row from the country's file on disk (the last run's answer), or null; the file is read once a run. */
internal fun savedJwList(list: JwList, country: String): List<JwTitle>? = jwSaved.getOrPut(country) {
    Http.savedAnswer("cas://jw/$country")?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { j ->
        j.keys().asSequence().associateWith { k -> j.optJSONArray(k).objects().mapNotNull { JwTitle.of(it) } }
    } ?: emptyMap()
}[list.key]?.ifEmpty { null }

/**
 * Every list of [country] from one request, kept for the hour and on disk for the next start. Rows asking at the
 * same time share the one fetch. A title JustWatch gives no IMDb id is placed by name (rare); one IMDb cannot
 * place is left out, since its page could not open.
 */
internal fun jwCountryLists(country: String, home: String = java.util.Locale.getDefault().country.ifEmpty { "GB" }): Map<String, List<JwTitle>> {
    cachedJwCountry(country)?.let { return it }
    synchronized(jwLocks.getOrPut(country) { Any() }) {
        cachedJwCountry(country)?.let { return it }
        val c = jwCountry(country) ?: return emptyMap()
        val picks = parseJwCountry(JSONObject(Http.post(JW_URL, jwCountryQuery(c), timeoutMs = 20_000)), c)
        val source = Source(home)
        val lists = picks.mapValues { (_, ps) ->
            ps.mapIndexedNotNull { i, p ->
                val id = p.imdbId ?: source.imdbId(p.title, p.year, if (p.tv) IMDB_KINDS_TV else IMDB_KINDS_FILM) ?: return@mapIndexedNotNull null
                jwTitle(p, id, i + 1)
            }.distinctBy { it.title.id }
        }
        jwCache[country] = System.currentTimeMillis() to lists
        if (lists.values.any { it.isNotEmpty() }) {
            Http.keep("cas://jw/$country", null, JSONObject(lists.mapValues { (_, l) -> JSONArray(l.map { it.json() }) }).toString())
            jwSaved.remove(country)
        }
        return lists
    }
}

/** The service's ten in [country]; the country's one request answers every row. */
internal fun jwList(list: JwList, country: String): List<JwTitle> = jwCountryLists(country)[list.key].orEmpty()

/** The other countries, one after another on a quiet thread, so a move along the chips finds each ready. */
internal fun jwFetchAhead(except: String) {
    jwAhead.execute {
        JW_COUNTRIES.filter { it.code != except && cachedJwCountry(it.code) == null }.forEach { c ->
            runCatching { jwCountryLists(c.code) }.onFailure { android.util.Log.w("CAS", "JustWatch ahead ${c.code} failed: $it") }
        }
    }
}
