package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * RogerEbert.com's reviews, from the feed the site publishes for readers (its pages themselves are closed to anything
 * but a browser). The feed names the film as the site does, the critic, a one-line verdict and the day; the star
 * rating is not in it. IMDb supplies the rest: which title it is, its poster, year and rating.
 */
class EbertReview(val title: String, val critic: String?, val verdict: String?, val date: String?, val url: String) {
    /** The year in the review's address ("…-movie-review-2026"): the film came out then or a little before. */
    val year: Int? get() = Regex("-(\\d{4})/?$").find(url)?.groupValues?.get(1)?.toIntOrNull()
}

class EbertPick(val review: EbertReview, val title: ImdbPick)

internal const val EBERT_FEED = "https://www.rogerebert.com/reviews/feed"
internal const val EBERT_PAGES = 3 // ten reviews a page: the last month or so

internal fun ebertFeedUrl(page: Int) = if (page <= 1) EBERT_FEED else "$EBERT_FEED?paged=$page"

/** The reviews in one page of the feed, newest first, as plain text. */
internal fun parseEbertFeed(xml: String): List<EbertReview> = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
    val item = m.groupValues[1]
    fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::plainText)?.ifBlank { null }
    val title = tag("title") ?: return@mapNotNull null
    val link = tag("link") ?: return@mapNotNull null
    val date = tag("pubDate")?.let { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString() }.getOrNull() }
    EbertReview(title, tag("dc:creator"), tag("description"), date, link)
}.toList()

/** Text out of a feed field: CDATA and tags gone, the site's entities (&#8217; for ’, &#038; for &) back to characters. */
internal fun plainText(raw: String): String = raw
    .replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)) { it.groupValues[1] }
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
    .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
    .replace(Regex("\\s+"), " ").trim()

/**
 * The IMDb title a review is about, by name, from IMDb's search-box lookup: the same name (ignoring case and
 * punctuation) and a year in the review's year or the two before it wins; failing that the first film or series of
 * that year; a title of another year is not trusted. Null when IMDb offers nothing that fits.
 */
internal fun imdbTitleFor(name: String, year: Int?, json: JSONObject): String? {
    fun key(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val wanted = key(name)
    val titles = json.optJSONArray("d").objects().filter { it.text("qid") in setOf("movie", "tvSeries", "tvMiniSeries", "tvMovie") }
    fun yearFits(t: JSONObject) = year == null || t.optInt("y", 0).let { it in (year - 2)..year }
    return (titles.firstOrNull { key(it.text("l").orEmpty()) == wanted && yearFits(it) }
        ?: titles.firstOrNull { yearFits(it) && key(it.text("l").orEmpty()).startsWith(wanted) })?.text("id")
}

internal fun imdbSuggestUrl(name: String) =
    "https://v3.sg.media-imdb.com/suggestion/x/${URLEncoder.encode(name.lowercase().take(60), "UTF-8").replace("+", "%20")}.json"

private val ebertCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<EbertPick>>>()

/**
 * The newest reviews with their IMDb titles, posters and ratings; reviews IMDb cannot place are left out. [offline]
 * reads only what the last run kept on disk (the feed pages, each lookup, the one titles request), for the row to
 * show at once; the live run then replaces it. One request for all the titles' details, not one per review.
 */
internal fun ebertReviews(country: String, offline: Boolean = false): List<EbertPick> {
    if (!offline) ebertCache[country]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.let { return it.second }
    val headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized", "x-imdb-user-country" to country)
    fun fetch(url: String, json: String? = null): String? =
        if (offline) Http.savedAnswer(url, json)
        else if (json == null) Http.get(url, keep = true, timeoutMs = 15_000)
        else Http.post(url, json, keep = true, timeoutMs = 15_000, headers = headers)
    val reviews = (1..EBERT_PAGES).flatMap { p ->
        runCatching { fetch(ebertFeedUrl(p))?.let(::parseEbertFeed) }.getOrElse { if (p == 1) throw it else null } ?: emptyList()
    }
    val found = reviews.mapNotNull { r ->
        runCatching { fetch(imdbSuggestUrl(r.title))?.let { imdbTitleFor(r.title, r.year, JSONObject(it)) } }.getOrNull()?.let { r to it }
    }
    val ids = found.map { it.second }.distinct()
    if (ids.isEmpty()) return emptyList()
    val query = JSONObject().put("query", "query CAS_$country{titles(ids:${JSONArray(ids)}){$IMDB_TITLE_FIELDS}}").toString()
    val titles = fetch("https://api.graphql.imdb.com/", query)?.let { JSONObject(it).optJSONObject("data")?.optJSONArray("titles").objects() }
        ?.mapNotNull { imdbPick(it) }?.associateBy { it.id } ?: return emptyList()
    return found.mapNotNull { (r, id) -> titles[id]?.let { EbertPick(r, it) } }.distinctBy { it.title.id }
        .also { if (!offline) ebertCache[country] = System.currentTimeMillis() to it }
}
