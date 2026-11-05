package dev.alder

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.MimeTypes
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit

/**
 * One installed addon, spoken to with the Stremio addon protocol: plain GETs for catalogs, metadata and streams.
 * [descriptor] is the account's entry for it as Stremio stores it, kept whole so it can be copied to another profile.
 */
class Addon(val descriptor: JSONObject) {
    private val manifest: JSONObject = descriptor.getJSONObject("manifest")
    val id: String = manifest.optString("id")
    val name: String = manifest.optString("name")
    val transportUrl: String = descriptor.getString("transportUrl")
    /** Stremio's built-ins (Cinemeta, Local Files), which every account already has. */
    val official = descriptor.optJSONObject("flags")?.optBoolean("official") == true
    /** Stremio's own that can't be removed (Cinemeta, Local Files); they can still be moved. */
    val locked = descriptor.optJSONObject("flags")?.optBoolean("protected") == true
    val providesStreams: Boolean = manifest.optJSONArray("resources").items().any {
        (it as? String ?: (it as? JSONObject)?.optString("name")) == "stream"
    }
    /** Reads one person's history or lists (MyTrakt also declares `stream`): never shared or copied by default. */
    val personal = listOf("watchly", "trakt", "simkl", "syncribullet", "aiolists").any { name.contains(it, ignoreCase = true) }
    /** What a profile lends when it shares its streaming sources: its own stream add-ons, not Stremio's or personal ones. */
    val shareable get() = !official && providesStreams && !personal
    private val base = transportUrl.removeSuffix("/manifest.json")

    /**
     * [pages]: the catalog takes Stremio's `skip` extra, so a row can ask for the titles after the first page.
     * [required]: extras it can't be asked without, with the value Stremio would pick (`genre` → "None").
     */
    class Catalog(val addon: Addon, val type: String, val id: String, val name: String, val pages: Boolean, val genres: List<String>,
                  val required: Map<String, String> = emptyMap())

    /**
     * Catalogs Home can show. One with a required extra is asked with that extra's default or first option, as
     * Stremio's Discover does: AIOMetadata marks the genre of every catalog not shown on Stremio's board as required
     * (its first option "None"), and skipping those left a Shows page with no rows. A required extra with no options
     * (a search query) still leaves the catalog out.
     */
    val catalogs: List<Catalog> = manifest.optJSONArray("catalogs").objects().mapNotNull { c ->
        val extra = c.optJSONArray("extra").objects()
        val extras = c.optJSONArray("extraSupported").strings() + extra.map { it.optString("name") }
        // The genres it can filter by: the protocol's `genre` extra options, or the older `genres` list.
        val options = extra.firstOrNull { it.optString("name") == "genre" }?.optJSONArray("options").strings()
            .ifEmpty { c.optJSONArray("genres").strings() }
        val requiredNames = c.optJSONArray("extraRequired").strings() + extra.filter { it.optBoolean("isRequired") }.map { it.optString("name") }
        val required = requiredNames.distinct().associateWith { name ->
            val own = extra.firstOrNull { it.optString("name") == name }
            val choices = own?.optJSONArray("options").strings().ifEmpty { if (name == "genre") options else emptyList() }
            own?.text("default")?.takeIf { it in choices } ?: choices.firstOrNull() ?: return@mapNotNull null
        }
        Catalog(this, c.getString("type"), c.getString("id"), c.optString("name").ifEmpty { c.getString("id") }, "skip" in extras,
            options.filterNot { it.equals("None", ignoreCase = true) }, required) // "None" is no filter, not a genre
    }

    /** Whether this addon answers [resource] ("meta", "stream", …) for this type and id. */
    fun serves(resource: String, type: String, id: String): Boolean {
        val entry = manifest.optJSONArray("resources").items()
            .firstOrNull { (it as? String ?: (it as? JSONObject)?.optString("name")) == resource } ?: return false
        val own = entry as? JSONObject
        val types = (own?.optJSONArray("types") ?: manifest.optJSONArray("types")).strings()
        val prefixes = (own?.optJSONArray("idPrefixes") ?: manifest.optJSONArray("idPrefixes"))?.strings()
        return type in types && (prefixes == null || prefixes.any { id.startsWith(it) })
    }

    // Catalogs (one [genre] of them with Stremio's `genre` extra) and details are kept on disk as they arrive; the saved*
    // versions read last time's answer, no network.
    fun catalog(c: Catalog, genre: String? = null, timeoutMs: Int = 20_000): List<Meta> =
        JSONObject(Http.get(catalogUrl(c, genre), true, timeoutMs)).optJSONArray("metas").objects().map(::Meta)

    /** The titles after the first [skip] (Stremio's paging extra); not kept on disk. */
    fun catalog(c: Catalog, skip: Int): List<Meta> =
        JSONObject(Http.get(catalogUrl(c, null, skip))).optJSONArray("metas").objects().map(::Meta)

    fun savedCatalog(c: Catalog, genre: String? = null): List<Meta>? =
        Http.savedAnswer(catalogUrl(c, genre))?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONArray("metas")?.objects()?.map(::Meta)

    /** /catalog/type/id/genre=Drama&skip=20.json: the catalog's required extras, the [genre] picked, and the page. */
    private fun catalogUrl(c: Catalog, genre: String?, skip: Int = 0): String {
        val extras = c.required + listOfNotNull(genre?.let { "genre" to it }, ("skip" to "$skip").takeIf { skip > 0 })
        val path = url(arrayOf("catalog", c.type, c.id))
        if (extras.isEmpty()) return path
        return path.removeSuffix(".json") + "/" + extras.entries.joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" } + ".json"
    }

    /** Every search catalog the addon advertises, as Stremio's catalog protocol specifies. */
    fun search(query: String): List<Meta> {
        require(query.isNotBlank())
        val catalogs = manifest.optJSONArray("catalogs").objects()
            .filter { c ->
                c.optString("type") in listOf("movie", "series") &&
                    "search" in (c.optJSONArray("extraRequired").strings() + c.optJSONArray("extraSupported").strings() +
                        c.optJSONArray("extra").objects().map { it.optString("name") }) &&
                    c.optJSONArray("extraRequired").strings().all { it == "search" } &&
                    c.optJSONArray("extra").objects().none { it.optBoolean("isRequired") && it.optString("name") != "search" }
            }
            .distinctBy { it.optString("type") to it.optString("id") }
        fun fetch(c: JSONObject): List<Meta> {
            val path = url(arrayOf("catalog", c.getString("type"), c.getString("id"))).removeSuffix(".json")
            val searchUrl = "$path/search=${Uri.encode(query)}.json"
            val answer = JSONObject(Http.get(searchUrl, timeoutMs = 5_000))
            return (answer.optJSONArray("metas") ?: throw IOException("Search catalog failed")).objects().map(::Meta)
        }
        val completion = ExecutorCompletionService<Pair<Int, Result<List<Meta>>>>(catalogSearchPool)
        val pending = catalogs.mapIndexed { index, catalog -> completion.submit { index to runCatching { fetch(catalog) } } }
        val deadline = System.currentTimeMillis() + 5_000
        val answers = mutableListOf<Pair<Int, List<Meta>>>()
        var succeeded = false
        try {
            repeat(catalogs.size) {
                val finished = completion.poll((deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    ?: return@repeat
                val (index, result) = finished.get()
                result.getOrNull()?.let { succeeded = true; answers += index to it }
            }
        } finally { pending.forEach { it.cancel(true) } }
        if (catalogs.isNotEmpty() && !succeeded) throw IOException("Search catalog unavailable")
        return answers.sortedBy { it.first }.flatMap { it.second }
    }

    fun meta(type: String, id: String): Meta? = get(true, "meta", type, id).optJSONObject("meta")?.let { meta(it, id) }

    fun savedMeta(type: String, id: String): Meta? = saved("meta", type, id)?.optJSONObject("meta")?.let { meta(it, id) }

    /** Some add-ons leave the title's own id out of its details: it's the one asked for. */
    private fun meta(json: JSONObject, id: String) = Meta(if (json.text("id") == null) json.put("id", id) else json)

    /** A title-specific catalog, when an installed addon advertises the protocol's required `similar` extra. */
    fun similar(type: String, id: String): List<Meta>? {
        val catalog = manifest.optJSONArray("catalogs").objects().firstOrNull { c ->
            c.optString("type") == type && c.optJSONArray("extra").objects().any {
                it.optString("name") == "similar" && it.optBoolean("isRequired")
            } && c.optJSONArray("idPrefixes").strings().let { it.isEmpty() || it.any(id::startsWith) }
        } ?: return null
        val path = url(arrayOf("catalog", type, catalog.getString("id"))).removeSuffix(".json")
        return JSONObject(Http.get("$path/similar=${Uri.encode(id, ":")}.json", true))
            .optJSONArray("metas").objects().map(::Meta)
    }

    /** Playable streams only: torrent-only results (no url) need a streaming server, which this app doesn't have. */
    fun streams(type: String, videoId: String): Streams {
        val all = get(false, "stream", type, videoId).optJSONArray("streams").objects()
        return Streams(all.mapNotNull { Stream.from(it) }, all.count { it.text("url") == null && it.text("infoHash") != null })
    }

    /** [torrents]: results CAS can't play, a torrent with no debrid link (an infoHash, no url); counted to say why. */
    class Streams(val playable: List<Stream>, val torrents: Int)

    /** Subtitle files for one video (OpenSubtitles and co. through Stremio's `subtitles` resource). */
    fun subtitles(type: String, videoId: String): List<Subtitle> =
        get(false, "subtitles", type, videoId).optJSONArray("subtitles").objects().mapNotNull { s ->
            Subtitle(s.text("url") ?: return@mapNotNull null, s.text("lang") ?: "und", name)
        }

    // Ids such as "tt0903747:1:2" keep their colons, like Stremio's own requests.
    private fun url(path: Array<out String>) = base + "/" + path.joinToString("/") { Uri.encode(it, ":=") } + ".json"

    private fun get(keep: Boolean, vararg path: String) = JSONObject(Http.get(url(path), keep))

    private fun saved(vararg path: String) = Http.savedAnswer(url(path))?.let { runCatching { JSONObject(it) }.getOrNull() }

    private companion object { val catalogSearchPool = Images.newestFirst(8) }
}

/**
 * A title's details from the first add-on, in the account's order, that has them, as Stremio's apps do: one that fails
 * or doesn't know the title (a metadata add-on without its API key, a catalog add-on that claims details) passes to the
 * next, instead of "Couldn't load this title's details" on every title. [together] asks them all at once, for a screen
 * that waits on the answer: a dead add-on then costs no more than its own wait. Otherwise one at a time, so Home's
 * cards don't ask every add-on for every title.
 */
fun List<Addon>.meta(type: String, id: String, together: Boolean = false): Meta? {
    val serving = filter { it.serves("meta", type, id) }
    fun ask(a: Addon) = runCatching { a.meta(type, id) }
        .onFailure { android.util.Log.w("CAS", "details from ${a.name} failed", it) }.getOrNull()
        .also { if (it == null) android.util.Log.i("CAS", "no details for $type $id from ${a.name}") }
    if (!together || serving.size < 2) return serving.firstNotNullOfOrNull(::ask)
    return serving.map { a -> metaPool.submit<Meta?> { ask(a) } }.firstNotNullOfOrNull { it.get() }
}

/** Last time's details from disk, from the first add-on in the account's order that kept some; no network. */
fun List<Addon>.savedMeta(type: String, id: String): Meta? =
    filter { it.serves("meta", type, id) }.firstNotNullOfOrNull { runCatching { it.savedMeta(type, id) }.getOrNull() }

private val metaPool = java.util.concurrent.Executors.newCachedThreadPool()

private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

// ---------- trailers as files ----------

private val imdbTrailerCache = java.util.concurrent.ConcurrentHashMap<String, List<Stream>>()

/**
 * The title's trailer as video files from IMDb's own video service (MP4 and HLS, 1080p down to SD), through the same
 * query IMDb's site makes. Playable in CAS's player, so the box shows a picture on every TV. Empty when IMDb has none,
 * the id isn't IMDb's, or the call fails; cached for the app's life. Blocks: run it off the main thread.
 */
internal fun imdbTrailerStreams(imdbId: String): List<Stream> {
    if (!imdbId.startsWith("tt")) return emptyList()
    imdbTrailerCache[imdbId]?.let { return it }
    return runCatching {
        val query = JSONObject().put("query",
            "{title(id:\"$imdbId\"){latestTrailer{$IMDB_VIDEO_FIELDS}primaryVideos(first:10){edges{node{$IMDB_VIDEO_FIELDS}}}}}").toString()
        // A TV stick's first call to a new host can take a few seconds (DNS, TLS): time enough, and one more try.
        val raw = (1..2).firstNotNullOfOrNull { attempt ->
            runCatching { Http.post("https://api.graphql.imdb.com/", query, timeoutMs = 12_000, headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized")) }
                .onFailure { android.util.Log.w("CAS", "IMDb trailer call $attempt for $imdbId failed: $it") }.getOrNull()
        } ?: return emptyList()
        parseImdbTrailerStreams(JSONObject(raw)).also { imdbTrailerCache[imdbId] = it }
    }.getOrDefault(emptyList())
}

private val imdbCertificateCache = java.util.concurrent.ConcurrentHashMap<String, String>()
private val imdbRatingCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** IMDb's rating for a title ("7.4"), for a card whose add-on gave none; null when IMDb has none either. */
internal fun imdbRating(imdbId: String): String? {
    if (!imdbId.startsWith("tt")) return null
    imdbRatingCache[imdbId]?.let { return it }
    return runCatching {
        val query = JSONObject().put("query", "{title(id:\"$imdbId\"){ratingsSummary{aggregateRating}}}").toString()
        val raw = Http.post("https://api.graphql.imdb.com/", query, timeoutMs = 12_000, headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized"))
        JSONObject(raw).optJSONObject("data")?.optJSONObject("title")?.optJSONObject("ratingsSummary")?.optDouble("aggregateRating", 0.0)
            ?.takeIf { it > 0 }?.let { "%.1f".format(Locale.US, it) }?.also { imdbRatingCache[imdbId] = it }
    }.onFailure { android.util.Log.w("CAS", "IMDb rating for $imdbId failed: $it") }.getOrNull()
}

/**
 * The title's age rating from IMDb's own service ("PG-13", "TV-MA", "15"), through the same query IMDb's site makes.
 * Null when IMDb has none or the call fails (asked again next time); cached for the app's life. Blocks.
 */
internal fun imdbCertificate(imdbId: String): String? {
    if (!imdbId.startsWith("tt")) return null
    imdbCertificateCache[imdbId]?.let { return it }
    return runCatching {
        val query = JSONObject().put("query", "{title(id:\"$imdbId\"){certificate{rating}}}").toString()
        val raw = Http.post("https://api.graphql.imdb.com/", query, timeoutMs = 12_000, headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized"))
        parseImdbCertificate(JSONObject(raw))?.also { imdbCertificateCache[imdbId] = it }
    }.onFailure { android.util.Log.w("CAS", "IMDb certificate for $imdbId failed: $it") }.getOrNull()
}

// ---------- coming soon ----------

/** A title IMDb lists as coming soon: out on [date] (ISO yyyy-mm-dd) in the viewer's country. */
class Upcoming(val id: String, val name: String, val poster: String?, val date: String, val genres: List<String>, val hasTrailer: Boolean)

private val comingSoonCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Upcoming>>>()

private fun comingSoonQuery(kind: String, from: String) = JSONObject().put("query",
    "{comingSoon(first:40,comingSoonType:$kind,releasingOnOrAfter:\"$from\"){edges{node{id titleText{text} releaseDate{day month year} primaryImage{url} genres{genres{text}} primaryVideos(first:1){edges{node{id}}}}}}}").toString()

/**
 * IMDb's coming-soon list for films ([kind] "MOVIE") or series ("TV") in [country] (ISO code), from [from] (ISO date),
 * through the same service the trailers come from. Six hours in memory, and kept on disk for the next cold start
 * ([savedComingSoon]). Blocks: run it off the main thread.
 */
internal fun imdbComingSoon(kind: String, country: String, from: String): List<Upcoming> {
    val k = "$kind/$country/$from"
    comingSoonCache[k]?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.let { return it.second }
    val raw = Http.post("https://api.graphql.imdb.com/", comingSoonQuery(kind, from), keep = true, timeoutMs = 12_000,
        headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized", "x-imdb-user-country" to country))
    return parseImdbComingSoon(JSONObject(raw)).also { comingSoonCache[k] = System.currentTimeMillis() to it }
}

/** Last time's list from disk, or null; no network. */
internal fun savedComingSoon(kind: String, from: String): List<Upcoming>? =
    Http.savedAnswer("https://api.graphql.imdb.com/", comingSoonQuery(kind, from))?.let { runCatching { parseImdbComingSoon(JSONObject(it)) }.getOrNull() }

/** The titles in IMDb's coming-soon answer, in its order; posters asked for at card size rather than full size. */
internal fun parseImdbComingSoon(json: JSONObject): List<Upcoming> =
    json.optJSONObject("data")?.optJSONObject("comingSoon")?.optJSONArray("edges").objects().mapNotNull { edge ->
        val node = edge.optJSONObject("node") ?: return@mapNotNull null
        val id = node.text("id")?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
        val name = node.optJSONObject("titleText")?.text("text") ?: return@mapNotNull null
        val d = node.optJSONObject("releaseDate") ?: return@mapNotNull null
        if (d.optInt("year") == 0 || d.optInt("month") == 0 || d.optInt("day") == 0) return@mapNotNull null
        val poster = node.optJSONObject("primaryImage")?.text("url")?.replace("._V1_.jpg", "._V1_QL80_UX360_.jpg")
        val genres = node.optJSONObject("genres")?.optJSONArray("genres").objects().mapNotNull { it.text("text") }
        val trailer = node.optJSONObject("primaryVideos")?.optJSONArray("edges").objects().isNotEmpty()
        Upcoming(id, name, poster, "%04d-%02d-%02d".format(d.optInt("year"), d.optInt("month"), d.optInt("day")), genres, trailer)
    }

/** "20 Nov", or "20 Nov 2027" when not this year, from an ISO date; null when it isn't one. */
internal fun shortDay(date: String, today: String): String? {
    if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return null
    val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { f.parse(date) }.getOrNull() ?: return null
    val pattern = if (date.take(4) == today.take(4)) "d MMM" else "d MMM yyyy"
    return java.text.SimpleDateFormat(pattern, Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(parsed)
}

/** The rating text in IMDb's answer, or null when there is none (unrated, or an id IMDb doesn't know). */
internal fun parseImdbCertificate(json: JSONObject): String? =
    json.optJSONObject("data")?.optJSONObject("title")?.optJSONObject("certificate")?.text("rating")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8 }

private const val IMDB_VIDEO_FIELDS = "id name{value}contentType{displayName{value}}runtime{value}createdDate playbackURLs{displayName{value}url videoMimeType}"

/**
 * The trailer among IMDb's videos, its files best quality first. IMDb's featured videos for a title lead with whatever
 * is newest, often clips and interviews (a series mid-season has nothing else in its first five), and a 30-second TV
 * spot counts as a "Trailer" too. So: only trailers and teasers, never a clip; the newest full-length official trailer
 * first, a short spot or a teaser only when that is all there is. The title's `latestTrailer` is in the running as
 * well, for the series whose featured videos are all clips. Empty when IMDb has no trailer: the YouTube route is next.
 */
internal fun parseImdbTrailerStreams(json: JSONObject): List<Stream> {
    val title = json.optJSONObject("data")?.optJSONObject("title") ?: return emptyList()
    val nodes = (listOfNotNull(title.optJSONObject("latestTrailer")) +
        title.optJSONObject("primaryVideos")?.optJSONArray("edges").objects().mapNotNull { it.optJSONObject("node") })
        .distinctBy { it.text("id") ?: it.toString() }
        .filter { node -> node.optJSONArray("playbackURLs").objects().any { it.text("url") != null } }
    val best = nodes.filter { trailerRank(it) < 9 }.sortedWith(compareBy<JSONObject> { trailerRank(it) }.thenByDescending { it.text("createdDate").orEmpty() }).firstOrNull()
        ?: return emptyList()
    return playbackStreams(best)
}

/** One IMDb video's files as streams, best quality first (1080p, then the adaptive stream, then down). */
internal fun playbackStreams(video: JSONObject): List<Stream> {
    val name = video.optJSONObject("name")?.text("value")?.ifBlank { null } ?: "Trailer"
    val order = listOf("1080p", "auto", "720p", "480p", "sd")
    return video.optJSONArray("playbackURLs").objects()
        .mapNotNull { u -> u.text("url")?.let { url -> (u.optJSONObject("displayName")?.text("value") ?: u.text("videoMimeType") ?: "") to url } }
        .sortedBy { (quality, _) -> order.indexOf(quality.lowercase()).let { if (it < 0) order.size else it } }
        .distinctBy { it.second }
        .map { (quality, url) -> Stream(if (quality.isNotBlank() && !quality.equals("auto", true)) "$name ($quality)" else name, quality, url, emptyMap(), 0L, null, null) }
}

/**
 * The files of one IMDb video by its id ("vi…"), fetched when it is about to play: the links are signed and expire
 * within hours, so a list kept from this morning can't carry them. Empty when the call fails. Blocks.
 */
internal fun imdbVideoStreams(videoId: String): List<Stream> {
    if (!videoId.startsWith("vi")) return emptyList()
    return runCatching {
        val query = JSONObject().put("query", "{video(id:\"$videoId\"){name{value}playbackURLs{displayName{value}url videoMimeType}}}").toString()
        val raw = Http.post("https://api.graphql.imdb.com/", query, timeoutMs = 12_000, headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized"))
        JSONObject(raw).optJSONObject("data")?.optJSONObject("video")?.let(::playbackStreams) ?: emptyList()
    }.getOrDefault(emptyList())
}

// ---------- IMDb's own lists: the IMDb page ----------

/**
 * A title on one of IMDb's lists. The trailer lists carry the title's latest trailer ([trailerId], [still], [seconds]);
 * the charts carry its [rank]. [type] is Stremio's: "movie" or "series".
 */
class ImdbPick(
    val id: String, val name: String, val type: String, val poster: String?, val year: String?, val plot: String?,
    val genres: List<String>, val rating: String?, val rank: Int?, val date: String?,
    val trailerId: String?, val still: String?, val seconds: Int?,
    val gross: String? = null, // the box office chart: "$42.1M weekend"
)

/** The pick as JSON, for a finished row kept on disk; [imdbPickOf] reads it back. */
internal fun ImdbPick.json(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("poster", poster).put("year", year)
    .put("plot", plot).put("genres", JSONArray(genres)).put("rating", rating).put("rank", rank).put("date", date)
    .put("trailerId", trailerId).put("still", still).put("seconds", seconds).put("gross", gross)
internal fun imdbPickOf(j: JSONObject): ImdbPick? {
    val id = j.text("id") ?: return null
    val name = j.text("name") ?: return null
    return ImdbPick(id, name, j.text("type") ?: "movie", j.text("poster"), j.text("year"), j.text("plot"), j.optJSONArray("genres").strings(),
        j.text("rating"), j.optInt("rank", 0).takeIf { it > 0 }, j.text("date"), j.text("trailerId"), j.text("still"),
        j.optInt("seconds", 0).takeIf { it > 0 }, j.text("gross"))
}

/**
 * IMDb's lists on the IMDb page, in the order they first sit down it (the viewer can move them): the trailer lists
 * from imdb.com/trailers, the weekend box office (Box Office Mojo's chart, as IMDb carries it), what's on TV and
 * streaming in the TV's country (new series this month, then what's popular on each service), then IMDb's charts.
 * [provider] is IMDb's watch-provider id for the streaming rows; [region] pins a row to one country, for a service
 * that streams only there (Peacock, the US), so every viewer gets the same rows.
 */
enum class ImdbList(val title: String, val trailers: Boolean = false, val provider: String? = null, val region: String? = null) {
    TRENDING("Trending trailers", true), ANTICIPATED("Most anticipated", true), THIS_MONTH("Most anticipated this month"),
    POPULAR("Popular trailers", true), RECENT("New trailers", true),
    BOX_OFFICE("Top box office"), NEW_SERIES("New series this month"),
    ON_NETFLIX("Popular on Netflix", provider = "netflix"), ON_PRIME("Popular on Prime Video", provider = "prime_video"),
    ON_DISNEY("Popular on Disney+", provider = "disneyplus"), ON_APPLE("Popular on Apple TV+", provider = "appletv"),
    ON_MAX("Popular on HBO Max", provider = "hbo_max"), ON_PEACOCK("Popular on Peacock", provider = "peacock", region = "US"),
    TOP_MOVIES("Top 250 films"), TOP_TV("Top 250 TV"), HOT_MOVIES("Most popular films"), HOT_TV("Most popular TV");
}

internal const val IMDB_TITLE_FIELDS = "id titleText{text}titleType{id}primaryImage{url}releaseYear{year}releaseDate{day month year}ratingsSummary{aggregateRating}plot{plotText{plainText}}genres{genres{text}}"
private const val IMDB_CLIP_FIELDS = "id name{value}runtime{value}description{value}thumbnail{url}"

/** The list's query, as imdb.com asks it ([today] ISO: the trailer lists split on the release date). */
internal fun imdbListQuery(list: ImdbList, today: String, region: String = "GB"): String {
    val ahead = java.time.LocalDate.parse(today).plusDays(90).toString()
    val titleWithTrailer = "$IMDB_TITLE_FIELDS latestTrailer{$IMDB_CLIP_FIELDS}"
    val body = when (list) {
        ImdbList.TRENDING -> "trendingTitles(limit:$IMDB_LIST_SIZE){titles{$titleWithTrailer}}"
        ImdbList.ANTICIPATED -> "popularTitles(limit:$IMDB_LIST_SIZE,queryFilter:{releaseDateRange:{start:\"$today\",end:\"$ahead\"}}){titles{$titleWithTrailer}}"
        ImdbList.POPULAR -> "popularTitles(limit:$IMDB_LIST_SIZE,queryFilter:{releaseDateRange:{end:\"$today\"}}){titles{$titleWithTrailer}}"
        // imdb.com/most-anticipated/this-month as data: the month's releases, films and series, most awaited first.
        ImdbList.THIS_MONTH -> {
            val month = java.time.YearMonth.from(java.time.LocalDate.parse(today))
            "popularTitles(limit:$IMDB_LIST_SIZE,queryFilter:{releaseDateRange:{start:\"${month.atDay(1)}\",end:\"${month.atEndOfMonth()}\"}}){titles{$titleWithTrailer}}"
        }
        ImdbList.RECENT -> "recentVideos(limit:$IMDB_LIST_SIZE,queryFilter:{contentTypes:[TRAILER]}){videos{$IMDB_CLIP_FIELDS primaryTitle{$IMDB_TITLE_FIELDS}}}"
        ImdbList.TOP_MOVIES -> "titleChartRankings(first:$IMDB_LIST_SIZE,input:{rankingsChartType:TOP_250}){edges{node{item{$IMDB_TITLE_FIELDS}}}}"
        ImdbList.TOP_TV -> "titleChartRankings(first:$IMDB_LIST_SIZE,input:{rankingsChartType:TOP_250_TV}){edges{node{item{$IMDB_TITLE_FIELDS}}}}"
        ImdbList.HOT_MOVIES -> "chartTitles(first:$IMDB_LIST_SIZE,chart:{chartType:MOST_POPULAR_MOVIES}){edges{currentRank node{$IMDB_TITLE_FIELDS}}}"
        ImdbList.HOT_TV -> "chartTitles(first:$IMDB_LIST_SIZE,chart:{chartType:MOST_POPULAR_TV_SHOWS}){edges{currentRank node{$IMDB_TITLE_FIELDS}}}"
        ImdbList.BOX_OFFICE -> "boxOfficeWeekendChart(limit:$IMDB_LIST_SIZE){entries{title{$IMDB_TITLE_FIELDS}weekendGross{total{amount currency}}}}"
        ImdbList.NEW_SERIES -> {
            val month = java.time.YearMonth.from(java.time.LocalDate.parse(today))
            "advancedTitleSearch(first:$IMDB_LIST_SIZE,constraints:{releaseDateConstraint:{releaseDateRange:{start:\"${month.atDay(1)}\",end:\"${month.atEndOfMonth()}\"}}," +
                "titleTypeConstraint:{anyTitleTypeIds:[\"tvSeries\",\"tvMiniSeries\"]}},sort:{sortBy:POPULARITY,sortOrder:ASC}){edges{node{title{$IMDB_TITLE_FIELDS}}}}"
        }
        // What people are watching on the service in the TV's country (the country rides in a header): titles it
        // streams on the subscription, not its rentals, popularity first.
        ImdbList.ON_NETFLIX, ImdbList.ON_PRIME, ImdbList.ON_DISNEY, ImdbList.ON_APPLE, ImdbList.ON_MAX, ImdbList.ON_PEACOCK ->
            "advancedTitleSearch(first:$IMDB_LIST_SIZE,constraints:{watchOptionsConstraint:{anyWatchProviderIds:[\"amzn1.imdb.w2w.provider.${list.provider}\"],hasWatchOptionTypes:[SUBSCRIPTION]}," +
                "titleTypeConstraint:{anyTitleTypeIds:[\"movie\",\"tvSeries\",\"tvMiniSeries\"]}},sort:{sortBy:POPULARITY,sortOrder:ASC}){edges{node{title{$IMDB_TITLE_FIELDS}}}}"
    }
    // The country goes in the operation's name: IMDb ignores it, and the disk cache then keeps one answer per country.
    return JSONObject().put("query", "query CAS_$region{$body}").toString()
}

private val imdbListCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ImdbPick>>>()

/** One of IMDb's lists, fresh, for the TV's [country] (ISO): an hour in memory, kept on disk for the next cold start ([savedImdbList]). Blocks. */
/** The list as fetched within the hour, or null: drawn at once, nothing read or asked. */
internal fun cachedImdbList(list: ImdbList, today: String, country: String): List<ImdbPick>? =
    imdbListCache["$list/$today/${list.region ?: country}"]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.second

internal fun imdbList(list: ImdbList, today: String, country: String): List<ImdbPick> {
    val region = list.region ?: country
    val k = "$list/$today/$region"
    cachedImdbList(list, today, country)?.let { return it }
    val raw = Http.post("https://api.graphql.imdb.com/", imdbListQuery(list, today, region), keep = true, timeoutMs = 15_000,
        headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized", "x-imdb-user-country" to region))
    return parseImdbList(list, JSONObject(raw)).also { imdbListCache[k] = System.currentTimeMillis() to it }
}

/** Last time's list from disk, or null; no network. */
internal fun savedImdbList(list: ImdbList, today: String, country: String): List<ImdbPick>? =
    Http.savedAnswer("https://api.graphql.imdb.com/", imdbListQuery(list, today, list.region ?: country))?.let { runCatching { parseImdbList(list, JSONObject(it)) }.getOrNull() }

/** One IMDb title as a pick: [clip] its trailer when the list carries one, [rank] its place in a chart, [gross] its weekend. */
internal fun imdbPick(t: JSONObject, clip: JSONObject? = null, rank: Int? = null, gross: String? = null): ImdbPick? {
    val id = t.text("id")?.takeIf { it.startsWith("tt") } ?: return null
    val name = t.optJSONObject("titleText")?.text("text") ?: return null
    val kind = t.optJSONObject("titleType")?.text("id").orEmpty()
    val d = t.optJSONObject("releaseDate")
    val date = d?.takeIf { it.optInt("year") > 0 && it.optInt("month") > 0 && it.optInt("day") > 0 }
        ?.let { "%04d-%02d-%02d".format(it.optInt("year"), it.optInt("month"), it.optInt("day")) }
    val rating = t.optJSONObject("ratingsSummary")?.optDouble("aggregateRating", 0.0)?.takeIf { it > 0 }?.let { "%.1f".format(Locale.US, it) }
    return ImdbPick(
        id, name, if (kind.startsWith("tv") && kind != "tvMovie" && kind != "tvSpecial") "series" else "movie",
        t.optJSONObject("primaryImage")?.text("url")?.replace("._V1_.jpg", "._V1_QL80_UX360_.jpg"),
        t.optJSONObject("releaseYear")?.optInt("year", 0)?.takeIf { it > 0 }?.toString(),
        t.optJSONObject("plot")?.optJSONObject("plotText")?.text("plainText"),
        t.optJSONObject("genres")?.optJSONArray("genres").objects().mapNotNull { it.text("text") },
        rating, rank, date,
        clip?.text("id"), clip?.optJSONObject("thumbnail")?.text("url")?.replace("._V1_.jpg", "._V1_QL80_UX640_.jpg"),
        clip?.optJSONObject("runtime")?.optInt("value", 0)?.takeIf { it > 0 },
        gross,
    )
}

/** The titles in IMDb's answer to [list], in its order; charts numbered from one, trailer lists only where a trailer exists. */
internal fun parseImdbList(list: ImdbList, json: JSONObject): List<ImdbPick> {
    val data = json.optJSONObject("data") ?: return emptyList()
    fun title(t: JSONObject, clip: JSONObject?, rank: Int?, gross: String? = null) = imdbPick(t, clip, rank, gross)
    fun searched() = data.optJSONObject("advancedTitleSearch")?.optJSONArray("edges").objects()
        .mapNotNull { e -> e.optJSONObject("node")?.optJSONObject("title")?.let { title(it, null, null) } }
    return when (list) {
        ImdbList.TRENDING -> data.optJSONObject("trendingTitles")?.optJSONArray("titles").objects().mapNotNull { t -> t.optJSONObject("latestTrailer")?.let { title(t, it, null) } }
        ImdbList.ANTICIPATED, ImdbList.POPULAR -> data.optJSONObject("popularTitles")?.optJSONArray("titles").objects().mapNotNull { t -> t.optJSONObject("latestTrailer")?.let { title(t, it, null) } }
        ImdbList.THIS_MONTH -> data.optJSONObject("popularTitles")?.optJSONArray("titles").objects().mapNotNull { t -> title(t, t.optJSONObject("latestTrailer"), null) }
        ImdbList.RECENT -> data.optJSONObject("recentVideos")?.optJSONArray("videos").objects().mapNotNull { v -> v.optJSONObject("primaryTitle")?.let { title(it, v, null) } }
        ImdbList.TOP_MOVIES, ImdbList.TOP_TV -> data.optJSONObject("titleChartRankings")?.optJSONArray("edges").objects()
            .mapIndexedNotNull { i, e -> e.optJSONObject("node")?.optJSONObject("item")?.let { title(it, null, i + 1) } }
        ImdbList.HOT_MOVIES, ImdbList.HOT_TV -> data.optJSONObject("chartTitles")?.optJSONArray("edges").objects()
            .mapIndexedNotNull { i, e -> e.optJSONObject("node")?.let { title(it, null, e.optInt("currentRank", 0).takeIf { r -> r > 0 } ?: (i + 1)) } }
        ImdbList.BOX_OFFICE -> data.optJSONObject("boxOfficeWeekendChart")?.optJSONArray("entries").objects()
            .mapIndexedNotNull { i, e -> e.optJSONObject("title")?.let { title(it, null, i + 1, e.optJSONObject("weekendGross")?.optJSONObject("total")?.let(::money)?.let { "$it weekend" }) } }
        ImdbList.NEW_SERIES, ImdbList.ON_NETFLIX, ImdbList.ON_PRIME, ImdbList.ON_DISNEY, ImdbList.ON_APPLE, ImdbList.ON_MAX, ImdbList.ON_PEACOCK -> searched()
    }.distinctBy { it.id }
}

/** "$42.1M" or "$860K" from IMDb's money ({amount, currency}); null when there is none. Dollars are the box office's own unit. */
internal fun money(total: JSONObject): String? {
    val amount = total.optDouble("amount", 0.0).takeIf { it > 0 } ?: return null
    val sign = when (total.text("currency")) { "USD", null -> "$"; "GBP" -> "£"; "EUR" -> "€"; else -> total.text("currency") + " " }
    return when {
        amount >= 1e9 -> "%s%.1fB".format(Locale.US, sign, amount / 1e9)
        amount >= 1e6 -> "%s%.1fM".format(Locale.US, sign, amount / 1e6)
        else -> "%s%.0fK".format(Locale.US, sign, amount / 1e3)
    }
}

private const val IMDB_LIST_SIZE = 30 // a row shows a dozen; thirty is plenty to move along, and a third less to parse and picture

/**
 * Where a video stands as the title's trailer: 0 a full-length official trailer, 1 another full-length trailer,
 * 2 a teaser, 3 a short spot (under a minute, "Final Trailer" the day before release), 9 not a trailer at all.
 */
private fun trailerRank(node: JSONObject): Int {
    val type = node.optJSONObject("contentType")?.optJSONObject("displayName")?.text("value").orEmpty()
    val name = node.optJSONObject("name")?.text("value").orEmpty()
    val seconds = node.optJSONObject("runtime")?.optInt("value", 0) ?: 0
    val trailer = type.equals("Trailer", true) || name.contains("trailer", true)
    val teaser = type.equals("Teaser", true) || name.contains("teaser", true)
    return when {
        !trailer && !teaser -> 9
        teaser && !trailer -> 2
        seconds in 1 until SPOT_SECONDS -> 3
        name.contains("official", true) -> 0
        else -> 1
    }
}

private const val SPOT_SECONDS = 60

class Meta(json: JSONObject) {
    val id: String = json.getString("id")
    val type: String = json.optString("type")
    val name: String = json.optString("name")
    val poster = json.text("poster")
    val background = json.text("background")
    /** The title as artwork (AIOMetadata and Cinemeta provide it), shown instead of plain text when present. */
    val logo = json.text("logo")
    val description = json.text("description")
    val year = json.text("releaseInfo")?.trimEnd('–', '-', ' ') // "2026–" (still running) reads as 2026 everywhere
    val runtime = json.text("runtime")?.let(::runtime)
    /** The release day (ISO), when the add-on gives one: a title not out yet says so on its page. */
    val released = json.text("released")
    val rating = json.text("imdbRating")
    val genres = json.optJSONArray("genres").strings().take(3)
    /** The IMDb id, from the id itself or the add-on's `imdb_id` field (an add-on may number titles its own way). */
    val imdbId: String? = id.takeIf { it.startsWith("tt") }?.substringBefore(':') ?: json.text("imdb_id")?.takeIf { it.startsWith("tt") }
    val cast = json.optJSONArray("cast").strings()
    /** The age rating ("PG-13", "15", "TV-MA") when the add-on gives one; IMDb is asked otherwise (imdbCertificate). */
    val certification: String? = (json.text("certification") ?: json.text("contentRating"))?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8 }
    val director = json.optJSONArray("director").strings()
    /** Trailer files an add-on gives outright (a `url` in `trailerStreams`): playable in CAS's own player. */
    val trailerFiles: List<Stream> = json.optJSONArray("trailerStreams").objects().mapNotNull { t ->
        val url = t.text("url") ?: return@mapNotNull null
        Stream(t.text("title")?.takeIf { it.isNotBlank() } ?: "Trailer", "", url, emptyMap(), 0L, null, null)
    }
    /** YouTube ids of the title's trailers: Cinemeta's `trailers` (source + type) and `trailerStreams` (ytId). */
    val trailers: List<String> = (json.optJSONArray("trailers").objects()
        .filter { it.optString("type").let { t -> t.isEmpty() || t.equals("Trailer", true) } }
        .mapNotNull { it.text("source") } +
        json.optJSONArray("trailerStreams").objects().mapNotNull { it.text("ytId") })
        .filter { it.matches(YOUTUBE_ID) }.distinct()
    val info = listOfNotNull(year, runtime, genres.joinToString(" · ").ifEmpty { null }).joinToString("   ")

    /** Episodes in play order: by season then episode, specials (season 0) last. */
    val videos: List<Video> = json.optJSONArray("videos").objects()
        .filter { it.text("id") != null } // one episode without an id shouldn't lose the whole title
        .map {
            Video(it.getString("id"), it.text("title") ?: it.text("name") ?: "", it.optInt("season"), it.optInt("episode"),
                it.text("thumbnail"), it.text("overview") ?: it.text("description"),
                it.text("released") ?: it.text("available"))
        }
        .sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
}

/** [lang] is what the addon sends: ISO 639-2 ("eng") from OpenSubtitles, sometimes a two-letter code. */
class Subtitle(val url: String, val lang: String, val addon: String) {
    val language: String get() = Languages.name(lang)
    val mimeType: String get() = url.substringBefore('?').lowercase().let {
        when {
            it.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            it.endsWith(".ass") || it.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}

class Video(val id: String, val title: String, val season: Int, val episode: Int, val thumbnail: String?,
            val overview: String?, val released: String?)

class Stream(
    val title: String, val description: String, val url: String, val headers: Map<String, String>,
    val size: Long, val filename: String?, val bingeGroup: String?,
) {
    /**
     * False for addon "actions" dressed up as streams, such as MyTrakt's "Mark Watched" (a 27 KB placeholder file).
     * They stay listed but are never picked or focused first. Unknown size counts as a video.
     */
    val isVideo get() = size == 0L || size >= 50L * 1024 * 1024

    /**
     * The addon's name for it on one line: "Torrentio (2160p)" becomes "Torrentio  ·  2160p". A debrid tag ("[RD+]")
     * becomes the service's name at the end, as the player shows it: "Torrentio  ·  2160p  ·  Real-Debrid".
     */
    val label get() = title.lines().joinToString(" ") { it.trim() }.replace(debridTag, "").trim()
        .replace(Regex("\\s*\\((.+?)\\)\\s*$"), "  ·  $1") + (debrid?.let { "  ·  ${it.name}" } ?: "")

    /** The debrid service it comes through, null for a direct link. */
    val debrid: Debrid? = debridOf(title, url)

    // What the release is, read from its file name and the addon's labels (AIOStreams writes "2160p", "DV", "HEVC"
    // into them; the file name says it for every addon). ponytail: name matching, not the file's real tracks.
    private val words = "$filename $title $description".lowercase()
    val height = when {
        Regex("2160|\\b4k\\b|\\buhd\\b").containsMatchIn(words) -> 2160
        "1080" in words -> 1080
        "720" in words -> 720
        "576" in words -> 576
        "480" in words -> 480
        else -> 0
    }
    val isHdr get() = hdr || dolbyVision
    /** Dolby Vision with no HDR10 base (profile 5, common on web releases): only a profile 5 decoder shows it right. */
    val dolbyVisionOnly get() = dolbyVision && !hdr
    val dolbyVision = Regex("\\b(dv|dovi|dolby.?vision)\\b").containsMatchIn(words)
    val hdr = Regex("\\bhdr|\\bp8\\b|hybrid").containsMatchIn(words) // P8 and hybrid DV carry an HDR10 base
    private val hevc = Regex("hevc|[xh].?265").containsMatchIn(words)
    private val av1 = Regex("\\bav1\\b").containsMatchIn(words)

    // The release type and extras, for Settings' stream preferences.
    val remux = Regex("\\bremux\\b").containsMatchIn(words)
    val bluray = !remux && Regex("blu.?ray|\\bbd.?rip|\\bbr.?rip").containsMatchIn(words)
    val web = Regex("web.?(dl|rip)|\\bweb\\b").containsMatchIn(words)
    /** Filmed in a cinema or a screener: never picked. */
    val cam = Regex("\\b(cam|camrip|hdcam|ts|hdts|telesync|tc|telecine|scr|screener|dvdscr)\\b").containsMatchIn(words)
    private val threeD = Regex("\\b3d\\b").containsMatchIn(words)
    private val hardSubs = Regex("\\bhc\\b|hardcoded|hardsub").containsMatchIn(words)
    /** 3D or subtitles burnt into the picture: picked last. */
    val awkward get() = threeD || hardSubs
    val bigAudio = Regex("atmos|truehd|dts.?hd|dts.?x\\b").containsMatchIn(words) // what a receiver gains from
    val multiAudio = Regex("\\b(multi|dual)\\b").containsMatchIn(words)
    val dubbed = Regex("\\bdub(bed)?\\b").containsMatchIn(words)
    /** The audio languages the names give, as two-letter codes; empty when they give none (see [audioLanguagesIn]). */
    val audioLanguages = audioLanguagesIn(words)
    /** Whether the names mention this language ("hindi" for "hi"). */
    fun mentions(language: String) = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH).lowercase().let { it.length > 2 && it in words }

    /** Short tags for the source list: "REMUX · DV · Atmos". */
    val tags get() = listOfNotNull(
        "REMUX".takeIf { remux }, "BluRay".takeIf { bluray }, "WEB".takeIf { web && !remux && !bluray },
        "DV".takeIf { dolbyVision }, "HDR".takeIf { hdr }, "Atmos".takeIf { "atmos" in words },
        "TrueHD".takeIf { "truehd" in words }, "DTS-HD".takeIf { Regex("dts.?hd").containsMatchIn(words) },
        "Multi audio".takeIf { multiAudio }, "Dub".takeIf { dubbed }, "3D".takeIf { threeD }, "Hard subs".takeIf { hardSubs },
    )

    /**
     * Whether this TV can decode it at all. Dolby Vision without an HDR10 base isn't left out here: many TVs report
     * their Dolby Vision decoders in ways the check misses, so it's listed last instead (StreamsActivity.rank).
     */
    fun playableOn(tv: Device): Boolean = (!hevc || tv.hevc) && (!av1 || tv.av1)

    /** How well it suits this TV, higher first: nothing above the screen's resolution, HDR only on an HDR screen. */
    fun fit(tv: Device): Int = (if (height > tv.height) 0 else 2) + (if (hdr || dolbyVision) (if (tv.hdr) 0 else -1) else 0)

    fun toJson(): JSONObject = JSONObject().put("name", title).put("description", description).put("url", url)
        .put("headers", JSONObject(headers)).put("size", size).put("filename", filename).put("binge", bingeGroup)

    companion object {
        fun from(json: JSONObject): Stream? {
            val url = json.text("url") ?: return null
            val hints = json.optJSONObject("behaviorHints")
            val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            return Stream(
                json.text("name") ?: "",
                json.text("description") ?: json.text("title") ?: "",
                url,
                headers?.keys()?.asSequence()?.associateWith { headers.getString(it) } ?: emptyMap(),
                hints?.optLong("videoSize") ?: 0L,
                hints?.text("filename"),
                hints?.text("bingeGroup"),
            )
        }

        /** Back from [toJson], for the player's list of sources to try. */
        fun read(json: JSONObject): Stream {
            val headers = json.optJSONObject("headers") ?: JSONObject()
            return Stream(json.optString("name"), json.optString("description"), json.getString("url"),
                headers.keys().asSequence().associateWith { headers.getString(it) }, json.optLong("size"),
                json.text("filename"), json.text("binge"))
        }
    }
}

fun JSONArray?.items(): List<Any> = if (this == null) emptyList() else (0 until length()).map { get(it) }

fun JSONArray?.objects(): List<JSONObject> = items().filterIsInstance<JSONObject>()

fun JSONArray?.strings(): List<String> = items().filterIsInstance<String>()

/** The string at [key], or null when it's missing, JSON null or blank (optString would return "null" for JSON null). */
/** "147 min" reads "2h 27m" as on the mock; under an hour, and anything not in minutes, stays as the addon wrote it. */
/**
 * Audio languages, as two-letter codes, from a stream's names: Torrentio's flags (🇮🇹), AIOStreams' "English | Hindi",
 * and file-name tags (ITA, HINDI, TRUEFRENCH). ponytail: names only, not the file's real tracks; most English releases
 * name no language at all, so an empty set usually means English.
 */
internal fun audioLanguagesIn(text: String): Set<String> {
    val found = mutableSetOf<String>()
    // Regional indicators: two in a row make a country's flag, read in pairs so 🇺🇸🇪🇸 isn't also "SE".
    val letters = text.codePoints().toArray().map { it - 0x1F1E6 }
    var i = 0
    while (i < letters.size - 1) {
        if (letters[i] in 0..25 && letters[i + 1] in 0..25) {
            flagLanguages["${'a' + letters[i]}${'a' + letters[i + 1]}"]?.let(found::add)
            i += 2
        } else i++
    }
    Regex("[a-z]+").findAll(text.lowercase()).forEach { m -> languageWords[m.value]?.let(found::add) }
    return found
}

private val flagLanguages = mapOf("gb" to "en", "us" to "en", "au" to "en", "it" to "it", "in" to "hi", "es" to "es",
    "mx" to "es", "fr" to "fr", "de" to "de", "pt" to "pt", "br" to "pt", "ru" to "ru", "jp" to "ja", "kr" to "ko",
    "cn" to "zh", "tw" to "zh", "hk" to "zh", "nl" to "nl", "pl" to "pl", "tr" to "tr", "se" to "sv", "dk" to "da",
    "no" to "no", "fi" to "fi", "gr" to "el", "il" to "he", "sa" to "ar", "ae" to "ar", "eg" to "ar", "th" to "th",
    "vn" to "vi", "id" to "id", "ua" to "uk", "cz" to "cs", "hu" to "hu", "ro" to "ro", "ir" to "fa", "pk" to "ur", "bd" to "bn")

// English names and the release tags that stand for a language. Short tags that are also common words ("por", "mal")
// are left out.
private val languageWords = mapOf("english" to "en", "eng" to "en", "hindi" to "hi", "hin" to "hi", "italian" to "it",
    "ita" to "it", "spanish" to "es", "spa" to "es", "esp" to "es", "latino" to "es", "castellano" to "es",
    "french" to "fr", "fre" to "fr", "fra" to "fr", "vff" to "fr", "truefrench" to "fr", "german" to "de", "ger" to "de",
    "deu" to "de", "portuguese" to "pt", "russian" to "ru", "rus" to "ru", "japanese" to "ja", "jpn" to "ja",
    "korean" to "ko", "kor" to "ko", "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh", "dutch" to "nl",
    "polish" to "pl", "turkish" to "tr", "swedish" to "sv", "danish" to "da", "norwegian" to "no", "finnish" to "fi",
    "greek" to "el", "hebrew" to "he", "arabic" to "ar", "thai" to "th", "vietnamese" to "vi", "indonesian" to "id",
    "ukrainian" to "uk", "czech" to "cs", "hungarian" to "hu", "romanian" to "ro", "persian" to "fa", "urdu" to "ur",
    "bengali" to "bn", "tamil" to "ta", "tam" to "ta", "telugu" to "te", "tel" to "te", "malayalam" to "ml",
    "kannada" to "kn", "marathi" to "mr")

/** A debrid service ([short] as add-ons tag it, "RD"); [cached]: ready to start at once, null when the add-on doesn't say. */
class Debrid(val name: String, val short: String, val cached: Boolean?) {
    /** The source list's tag: "RD ⚡" when cached. */
    val tag get() = if (cached == true) "$short ⚡" else short
}

private val debridServices = listOf(
    Triple("RD", "Real-Debrid", listOf("real-debrid", "realdebrid", "rdeb.io")),
    Triple("AD", "AllDebrid", listOf("alldebrid", "debrid.it")),
    Triple("PM", "Premiumize", listOf("premiumize", "energycdn")),
    Triple("TB", "TorBox", listOf("torbox", "tb-cdn")),
    Triple("DL", "Debrid-Link", listOf("debrid-link", "debridlink")),
    Triple("ED", "EasyDebrid", listOf("easydebrid")),
    Triple("OC", "Offcloud", listOf("offcloud")),
    Triple("PIO", "Put.io", listOf("put.io", "putio")),
)

// Add-ons' tags: Torrentio "[RD+]" (cached) and "[RD download]", AIOStreams and Comet "[RD⚡]" and "[RD⏳]",
// MediaFusion "⚡RD". The code must stand alone, so "[RDX]" or "HDR" isn't one.
private val debridCodes = debridServices.joinToString("|") { it.first }
private val debridTag = Regex("\\[($debridCodes)\\s*(\\+|⚡️?|⏳|download)?]\\s*", RegexOption.IGNORE_CASE)
private val debridFlash = Regex("(⚡️?)\\s*($debridCodes)\\b")

/**
 * The debrid service from a source's name, else from its link (Torrentio's /resolve/realdebrid/…, or the service's
 * own download host). Null for a direct link or a service it doesn't know.
 */
internal fun debridOf(name: String, url: String): Debrid? {
    fun service(code: String) = debridServices.first { it.first.equals(code, ignoreCase = true) }
    debridTag.find(name)?.let { m ->
        val mark = m.groupValues[2].lowercase()
        val cached = when { mark == "+" || mark.startsWith("⚡") -> true; mark == "⏳" || mark == "download" -> false; else -> null }
        return service(m.groupValues[1]).let { Debrid(it.second, it.first, cached) }
    }
    debridFlash.find(name)?.let { m -> return service(m.groupValues[2]).let { Debrid(it.second, it.first, true) } }
    val link = url.lowercase()
    return debridServices.firstOrNull { (_, _, hosts) -> hosts.any { it in link } }?.let { Debrid(it.second, it.first, null) }
}

internal fun runtime(text: String): String {
    // "147 min", or "2h35min" / "2h 35m" as some addons write it.
    val hm = Regex("""^(\d+)\s*h\s*(\d+)\s*m(in)?$""").find(text.trim())?.groupValues
    val minutes = hm?.let { it[1].toInt() * 60 + it[2].toInt() }
        ?: Regex("""^(\d+)\s*min""").find(text.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return text
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h %02dm".format(minutes % 60)
}

fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
