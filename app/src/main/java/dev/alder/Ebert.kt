package dev.alder

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

private val ebertCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<EbertPick>>>()

/** The newest reviews with their IMDb titles, posters and ratings; reviews IMDb cannot place are left out. */
internal fun ebertReviews(country: String, offline: Boolean = false): List<EbertPick> {
    if (!offline) ebertCache[country]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.let { return it.second }
    val source = Source(country, offline)
    val reviews = (1..EBERT_PAGES).flatMap { p ->
        runCatching { source.text(ebertFeedUrl(p))?.let(::parseEbertFeed) }.getOrElse { if (p == 1) throw it else null } ?: emptyList()
    }
    val found = reviews.mapNotNull { r -> source.imdbId(r.title, r.year, IMDB_KINDS_ANY)?.let { r to it } }
    val titles = source.imdbTitles(found.map { it.second })
    return found.mapNotNull { (r, id) -> titles[id]?.let { EbertPick(r, it) } }.distinctBy { it.title.id }
        .also { if (!offline && it.isNotEmpty()) ebertCache[country] = System.currentTimeMillis() to it }
}
