package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaTest {
    private fun meta(json: String) = Meta(JSONObject(json))

    @Test fun trailersComeFromBothCinemetaShapes() {
        val m = meta("""{"id":"tt1","name":"X","trailers":[{"source":"dQw4w9WgXcQ","type":"Trailer"},{"source":"aaaaaaaaaaa","type":"Clip"}],
            "trailerStreams":[{"title":"X","ytId":"bbbbbbbbbbb"},{"title":"again","ytId":"dQw4w9WgXcQ"}]}""")
        assertEquals(listOf("dQw4w9WgXcQ", "bbbbbbbbbbb"), m.trailers)
    }

    @Test fun trailersThatAreNotYouTubeIdsAreDropped() {
        // An add-on's data ends up inside a script: anything but an 11-character id is refused.
        val m = meta("""{"id":"tt1","name":"X","trailers":[{"source":"abc","type":"Trailer"},{"source":"x');alert(1);('","type":"Trailer"}]}""")
        assertEquals(emptyList<String>(), m.trailers)
    }

    @Test fun trailersAbsentIsEmpty() {
        assertEquals(emptyList<String>(), meta("""{"id":"tt1","name":"X"}""").trailers)
    }

    @Test fun castAndDirectorAreLists() {
        val m = meta("""{"id":"tt1","name":"X","cast":["A","B"],"director":["D"]}""")
        assertEquals(listOf("A", "B"), m.cast)
        assertEquals(listOf("D"), m.director)
    }

    @Test fun imdbTrailerPicksTheTrailerAndOrdersQualities() {
        val json = JSONObject("""{"data":{"title":{"primaryVideos":{"edges":[
            {"node":{"id":"vi1","name":{"value":"Clip: Opening"},"contentType":{"displayName":{"value":"Clip"}},
             "playbackURLs":[{"displayName":{"value":"720p"},"url":"https://c/clip720.mp4","videoMimeType":"MP4"}]}},
            {"node":{"id":"vi2","name":{"value":"Official Trailer"},"contentType":{"displayName":{"value":"Trailer"}},
             "playbackURLs":[{"displayName":{"value":"480p"},"url":"https://c/t480.mp4","videoMimeType":"MP4"},
                             {"displayName":{"value":"AUTO"},"url":"https://c/t.m3u8","videoMimeType":"M3U8"},
                             {"displayName":{"value":"1080p"},"url":"https://c/t1080.mp4","videoMimeType":"MP4"}]}}
        ]}}}}""")
        val streams = parseImdbTrailerStreams(json)
        assertEquals(listOf("https://c/t1080.mp4", "https://c/t.m3u8", "https://c/t480.mp4"), streams.map { it.url })
        assertEquals("Official Trailer (1080p)", streams[0].title)
        assertEquals("Official Trailer", streams[1].title)
    }

    @Test fun imdbTrailerPrefersTheFullOfficialTrailerOverASpotAndAClip() {
        // Dune: Part Two as IMDb lists it: a 31-second "Final Trailer" first, then the real trailers, then clips.
        fun video(id: String, name: String, type: String, seconds: Int, date: String) =
            """{"id":"$id","name":{"value":"$name"},"contentType":{"displayName":{"value":"$type"}},"runtime":{"value":$seconds},"createdDate":"$date",
                "playbackURLs":[{"displayName":{"value":"1080p"},"url":"https://c/$id.mp4","videoMimeType":"MP4"}]}"""
        val json = JSONObject("""{"data":{"title":{"latestTrailer":${video("spot", "Final Trailer", "Trailer", 31, "2024-02-28T00:00:00Z")},
            "primaryVideos":{"edges":[
            {"node":${video("spot", "Final Trailer", "Trailer", 31, "2024-02-28T00:00:00Z")}},
            {"node":${video("t3", "Official Trailer", "Trailer", 160, "2023-12-12T00:00:00Z")}},
            {"node":${video("t2", "Official Trailer 2", "Trailer", 184, "2023-06-29T00:00:00Z")}},
            {"node":${video("t1", "Official Trailer", "Trailer", 145, "2023-05-03T00:00:00Z")}},
            {"node":${video("clip", "Raid on the Spice Harvester - Clip", "Clip", 241, "2026-03-13T00:00:00Z")}}
        ]}}}}""")
        assertEquals(listOf("https://c/t3.mp4"), parseImdbTrailerStreams(json).map { it.url })
    }

    @Test fun imdbTrailerTakesTheLatestTrailerWhenTheFeaturedVideosAreAllClips() {
        // A series mid-season: five clips up front, the real trailer only under latestTrailer.
        val json = JSONObject("""{"data":{"title":{"latestTrailer":{"id":"vt","name":{"value":"Finale Trailer - Season 5"},"contentType":{"displayName":{"value":"Trailer"}},
            "runtime":{"value":86},"createdDate":"2025-12-30T00:00:00Z","playbackURLs":[{"displayName":{"value":"720p"},"url":"https://c/finale.mp4","videoMimeType":"MP4"}]},
            "primaryVideos":{"edges":[
            {"node":{"id":"c1","name":{"value":"Dustin's Speech"},"contentType":{"displayName":{"value":"Clip"}},"runtime":{"value":154},"createdDate":"2026-01-06T00:00:00Z",
             "playbackURLs":[{"displayName":{"value":"720p"},"url":"https://c/c1.mp4","videoMimeType":"MP4"}]}}
        ]}}}}""")
        assertEquals(listOf("https://c/finale.mp4"), parseImdbTrailerStreams(json).map { it.url })
    }

    @Test fun imdbTrailerNeverSettlesForAClip() {
        val json = JSONObject("""{"data":{"title":{"latestTrailer":null,"primaryVideos":{"edges":[
            {"node":{"id":"c1","name":{"value":"Scene: Get Out Of The Car"},"contentType":{"displayName":{"value":"Clip"}},"runtime":{"value":60},"createdDate":"2023-02-27T00:00:00Z",
             "playbackURLs":[{"displayName":{"value":"720p"},"url":"https://c/c1.mp4","videoMimeType":"MP4"}]}}
        ]}}}}""")
        assertEquals(emptyList<Stream>(), parseImdbTrailerStreams(json))
    }

    @Test fun imdbListsParseTrailersAndCharts() {
        val trending = JSONObject("""{"data":{"trendingTitles":{"titles":[
            {"id":"tt1","titleText":{"text":"Lords of War"},"titleType":{"id":"movie"},"primaryImage":null,"releaseYear":{"year":2027},"releaseDate":{"day":5,"month":2,"year":2027},
             "ratingsSummary":{"aggregateRating":null},"plot":{"plotText":{"plainText":"Arms."}},"genres":{"genres":[{"text":"Action"}]},
             "latestTrailer":{"id":"vi1","name":{"value":"Teaser"},"runtime":{"value":43},"thumbnail":{"url":"https://m/x._V1_.jpg"}}},
            {"id":"tt2","titleText":{"text":"No trailer"},"titleType":{"id":"tvSeries"},"latestTrailer":null}
        ]}}}""")
        val picks = parseImdbList(ImdbList.TRENDING, trending)
        assertEquals(listOf("tt1"), picks.map { it.id })
        assertEquals("vi1", picks[0].trailerId)
        assertEquals(43, picks[0].seconds)
        assertEquals("https://m/x._V1_QL80_UX640_.jpg", picks[0].still)
        assertEquals("2027-02-05", picks[0].date)
        assertEquals(null, picks[0].rating)

        val top = JSONObject("""{"data":{"titleChartRankings":{"edges":[
            {"node":{"item":{"id":"tt0111161","titleText":{"text":"The Shawshank Redemption"},"titleType":{"id":"movie"},"releaseYear":{"year":1994},"ratingsSummary":{"aggregateRating":9.3}}}},
            {"node":{"item":{"id":"tt0903747","titleText":{"text":"Breaking Bad"},"titleType":{"id":"tvSeries"},"releaseYear":{"year":2008},"ratingsSummary":{"aggregateRating":9.5}}}}
        ]}}}""")
        val chart = parseImdbList(ImdbList.TOP_MOVIES, top)
        assertEquals(listOf(1, 2), chart.map { it.rank })
        assertEquals(listOf("movie", "series"), chart.map { it.type })
        assertEquals("9.3", chart[0].rating)
        assertEquals("1994", chart[0].year)

        val hot = JSONObject("""{"data":{"chartTitles":{"edges":[{"currentRank":7,"node":{"id":"tt3","titleText":{"text":"Nocturne"},"titleType":{"id":"tvMiniSeries"}}}]}}}""")
        assertEquals(7, parseImdbList(ImdbList.HOT_TV, hot)[0].rank)
        val recent = JSONObject("""{"data":{"recentVideos":{"videos":[{"id":"vi9","name":{"value":"Trailer"},"runtime":{"value":130},"thumbnail":{"url":"https://m/t._V1_.jpg"},
            "primaryTitle":{"id":"tt4","titleText":{"text":"Bachelor Party"},"titleType":{"id":"movie"}}}]}}}""")
        val r = parseImdbList(ImdbList.RECENT, recent)[0]
        assertEquals("tt4" to "vi9", r.id to r.trailerId)
    }

    @Test fun ebertFeedParsesAndItsTitlesMatchOnImdb() {
        val xml = """<rss><channel><item>
            <title>Trauma or, Monsters All</title>
            <link>https://www.rogerebert.com/reviews/trauma-or-monsters-all-movie-review-2026</link>
            <dc:creator><![CDATA[Brian Tallerico]]></dc:creator>
            <pubDate>Wed, 04 Nov 2026 14:00:00 +0000</pubDate>
            <description><![CDATA[<p>A film that doesn&#8217;t flinch &#038; doesn't blink.  </p>]]></description>
            </item><item><title>No link</title></item></channel></rss>"""
        val reviews = parseEbertFeed(xml)
        assertEquals(1, reviews.size)
        assertEquals("Trauma or, Monsters All", reviews[0].title)
        assertEquals("Brian Tallerico", reviews[0].critic)
        assertEquals("A film that doesn\u2019t flinch & doesn't blink.", reviews[0].verdict)
        assertEquals("2026-11-04", reviews[0].date)
        assertEquals(2026, reviews[0].year)
        val found = JSONObject("""{"d":[{"id":"nm1","l":"Someone"},{"id":"tt0316219","l":"Manhood","y":2003,"qid":"movie"},
            {"id":"tt39637432","l":"Manhood","y":2026,"qid":"movie"},{"id":"tt1","l":"Manhood: The Series","y":2025,"qid":"tvSeries"}]}""")
        assertEquals("tt39637432", imdbTitleFor("Manhood", 2026, found))
        assertEquals("tt0316219", imdbTitleFor("Manhood", 2010, found)) // no Manhood of 2008–2010: the same name from another year (a re-release)
        assertEquals(null, imdbTitleFor("Womanhood", 2010, found))
        assertEquals("tt1", imdbTitleFor("Manhood: the series", 2026, found))
        assertEquals("https://v3.sg.media-imdb.com/suggestion/x/the%20last%20temptation.json", imdbSuggestUrl("The Last Temptation"))
        assertEquals(EBERT_FEED, ebertFeedUrl(1))
        assertEquals("$EBERT_FEED?paged=3", ebertFeedUrl(3))
        assertEquals("tt1", imdbTitleFor("Manhood", null, found, IMDB_KINDS_TV)) // no series of that exact name: the one that starts so
        assertEquals("tt0316219", imdbTitleFor("Manhood", null, found, IMDB_KINDS_FILM)) // no year: the first IMDb lists
    }

    @Test fun rottenTomatoesTitlePagesParseAndSearchMatches() {
        val search = """<search-page-media-row release-year="2015" tomatometer-score=""><a href="https://www.rottentomatoes.com/m/carface" slot="title"> Carface </a></search-page-media-row>
            <search-page-media-row release-year="2026" tomatometer-score="78"><a href="https://www.rottentomatoes.com/m/clayface" slot="title"> Clayface </a></search-page-media-row>
            <search-page-media-row endyear="" releaseyear="" startyear="2022" tomatometerscore="97"><a href="https://www.rottentomatoes.com/tv/slow_horses" slot="title">Slow Horses</a></search-page-media-row>
            <search-page-media-row release-year="1997" tomatometer-score="80"><a href="https://www.rottentomatoes.com/m/slow_horses_1997" slot="title">Slow Horses</a></search-page-media-row>"""
        assertEquals("https://www.rottentomatoes.com/m/clayface", rtSearchMatch(search, "Clayface", 2026, tv = false))
        assertEquals("https://www.rottentomatoes.com/m/clayface", rtSearchMatch(search, "clayface!", 2025, tv = false))
        assertEquals(null, rtSearchMatch(search, "Clayface", 2019, tv = false))
        assertEquals(null, rtSearchMatch(search, "Clayface", 2026, tv = true))
        assertEquals("https://www.rottentomatoes.com/tv/slow_horses", rtSearchMatch(search, "Slow Horses", 2022, tv = true))
        assertEquals("https://www.rottentomatoes.com/m/slow_horses_1997", rtSearchMatch(search, "Slow Horses", null, tv = false))
        val page = """<html><script id="media-scorecard-json" data-json="mediaScorecard" type="application/json">
            {"audienceScore":{"certified":true,"scorePercent":"94%","title":"Popcornmeter"},"criticsScore":{"averageRating":"6.60","certified":true,"scorePercent":"78%","title":"Tomatometer"}}
            </script><div id="critics-consensus" class="consensus"><rt-text context="heading">Critics Consensus</rt-text>
            <p>Carving out a unique identity, <em>Clayface</em> takes time to get going &amp; then delivers.</p><a href="/m/clayface/reviews">Read</a></div></html>"""
        val rt = parseRtScores(page, "https://www.rottentomatoes.com/m/clayface")!!
        assertEquals(78 to 94, rt.critics to rt.audience)
        assertTrue(rt.criticsCertified && rt.audienceCertified)
        assertEquals("Carving out a unique identity, Clayface takes time to get going & then delivers.", rt.consensus)
        val bare = parseRtScores("""<script id="media-scorecard-json" type="application/json">{"criticsScore":{"scorePercent":"--"}}</script>""", "u")!!
        assertEquals(null, bare.critics)
        assertEquals(null, bare.consensus)
        assertEquals(null, parseRtScores("<html></html>", "u"))
        assertEquals("https://www.rottentomatoes.com/search?search=Slow%20Horses", rtSearchUrl("Slow Horses"))
        val series = """<a href="/tv/slow_horses/s01">1</a> <a href="/tv/slow_horses/s05">5</a> <a href="/tv/slow_horses/s06">6</a> <a href="/tv/other/s09">x</a> <a href="/tv/slow_horses/s05">5 again</a>"""
        assertEquals(listOf(6, 5, 1), rtSeasonUrls(series, "https://www.rottentomatoes.com/tv/slow_horses").map { it.first })
        assertEquals("https://www.rottentomatoes.com/tv/slow_horses/s06", rtSeasonUrls(series, "https://www.rottentomatoes.com/tv/slow_horses")[0].second)
        assertEquals(5, rt.withConsensus("Fine.", 5).season)
    }

    @Test fun rottenTomatoesListsParse() {
        val json = JSONObject("""{"title":"Best Certified Fresh Movies in Theaters","grid":{"list":[
            {"title":"Clayface","type":"Movie","criticsScore":{"certified":true,"score":"78","sentiment":"positive"},"audienceScore":{"score":"94"},
             "posterUri":"https://resizing.flixster.com/x.jpg","releaseDateText":"Opened Oct 23, 2026"},
            {"title":"Nocturne","type":"TvSeries","criticsScore":{"certified":false,"score":"85"},"audienceScore":{},"releaseDateText":"Latest Episode: Oct 30"},
            {"type":"Movie"}
        ]},"pageInfo":{"startCursor":"MA==","endCursor":"Mjk=","hasNextPage":true}}""")
        val (picks, next) = parseRtList(json)
        assertEquals(listOf("Clayface", "Nocturne"), picks.map { it.title })
        assertEquals(78 to true, picks[0].critics to picks[0].certified)
        assertEquals(94, picks[0].audience)
        assertEquals(2026, picks[0].year)
        assertEquals(null, picks[1].year)
        assertEquals(null, picks[1].audience)
        assertEquals("Mjk=", next)
        assertEquals(null, parseRtList(JSONObject("""{"grid":{"list":[]},"pageInfo":{"endCursor":"Mjk=","hasNextPage":false}}""")).second)
        assertEquals("https://www.rottentomatoes.com/cnapi/browse/tv_series_browse/sort:popular?after=Mjk=", rtListUrl(RtList.POPULAR_TV, "Mjk="))
        // A finished row survives the trip to disk and back as the same titles and scores.
        val title = imdbPickOf(JSONObject("""{"id":"tt1","name":"Clayface","type":"movie","year":"2026","genres":["Horror"],"rating":"6.3","rank":3}"""))!!
        val back = RtTitle.of(RtTitle(picks[0], title).json())!!
        assertEquals("Clayface" to 78, back.rt.title to back.rt.critics)
        assertEquals(listOf("Horror") to 3, back.title.genres to back.title.rank)
        val review = EbertPick.of(EbertPick(EbertReview("Heat", "Roger Ebert", "A masterpiece.", "1995-12-15", "https://www.rogerebert.com/reviews/heat-1995"), title).json())!!
        assertEquals("Roger Ebert" to 1995, review.review.critic to review.review.year)
        val scores = RtScores.of(RtScores("u", 78, true, 94, false, "Good.", 5).json())!!
        assertEquals(listOf(78, 94, 5), listOf(scores.critics, scores.audience, scores.season))
        assertTrue(scores.criticsCertified && !scores.audienceCertified)
        assertEquals(null, RtScores.of(JSONObject("{}")))
    }

    @Test fun justWatchCountriesParseFromOneRequest() {
        val us = JW_COUNTRIES.first { it.code == "US" }
        val json = JSONObject("""{"data":{"l_nfx_film":{"edges":[
            {"node":{"objectType":"MOVIE","content":{"title":"Animals","originalTitle":"Animales","productionCountries":["FR","US"],"originalReleaseYear":2026,"shortDescription":"A kidnapping.","externalIds":{"imdbId":"tt31049299"},
             "posterUrl":"/poster/1/{profile}/animals.{format}","genres":[{"shortName":"act"},{"shortName":"eur"},{"shortName":"trl"}],"scoring":{"imdbScore":5.7}}}},
            {"node":{"objectType":"MOVIE","content":{"title":"Lupin","originalReleaseYear":2021,"externalIds":{"imdbId":null},"posterUrl":null,"genres":[],"scoring":{}}}},
            {"node":{"objectType":"MOVIE","content":{"originalReleaseYear":2020}}}
        ]},"l_amp_tv":{"edges":[]}}}""")
        val lists = parseJwCountry(json, us)
        assertEquals(us.services.size * 2, lists.size) // every row has its place, empty or not
        val films = lists.getValue("nfx/film")
        assertEquals(listOf("Animals", "Lupin"), films.map { it.title })
        assertEquals("tt31049299" to 2026, films[0].imdbId to films[0].year)
        assertEquals(listOf("Action", "Thriller") to "5.7", films[0].genres to films[0].rating)
        assertEquals("https://images.justwatch.com/poster/1/s332/animals.jpg", films[0].poster)
        assertEquals(null to null, films[1].imdbId to films[1].rating)
        assertEquals("Animales", films[0].original)
        assertEquals(countryName("FR"), films[0].countries.first())
        assertEquals("ZZ", countryName("ZZ"))
        assertEquals(emptyList<JwPick>(), lists.getValue("amp/tv"))
        assertEquals(emptyMap<String, List<JwPick>>(), parseJwCountry(JSONObject("""{"errors":[{"message":"x"}]}"""), us))
        // The one request names the country once and every row under its own field, with the service's short names.
        val query = JSONObject(jwCountryQuery(us))
        assertEquals("US", query.getJSONObject("variables").getString("country"))
        val text = query.getString("query")
        assertTrue(text.contains("l_amp_tv: popularTitles(country: \$country, first: 10, sortBy: POPULAR, filter: {packages: [\"amp\",\"prv\"], objectTypes: [SHOW]})"))
        assertTrue(text.contains("l_nfx_film: popularTitles") && text.contains("objectTypes: [MOVIE]"))
        assertEquals("Top 10 TV on Prime Video" to "amp/tv", JwList(us.services[1], tv = true).let { it.title to it.key })
        // The chips are alphabetical, each country once, and every country has its services.
        assertEquals(JW_COUNTRIES.map { it.label }.sorted(), JW_COUNTRIES.map { it.label })
        assertEquals(JW_COUNTRIES.size, JW_COUNTRIES.map { it.code }.distinct().size)
        assertTrue(JW_COUNTRIES.all { it.services.size >= 3 && it.langs.isNotEmpty() && it.demonym.isNotEmpty() })
        assertTrue(JW_COUNTRIES.all { flagColours(it.code).size in 2..3 && flagColours(it.code) !== flagColours("ALL") })
        assertEquals(2, flagColours("ALL").size)
        // IMDb's best-of-a-country request: the country, its primary languages, the kind, the floors, no variety.
        val italy = JW_COUNTRIES.first { it.code == "IT" }
        val best = JSONObject(bestQuery(italy, tv = true)).getString("query")
        assertTrue(best.contains("""originCountryConstraint:{allCountries:["IT"]}"""))
        assertTrue(best.contains("""anyPrimaryLanguages:["it","nap"]"""))
        assertTrue(best.contains("""anyTitleTypeIds:["tvSeries","tvMiniSeries"]""") && best.contains("ratingsCountRange:{min:2500}"))
        assertTrue(JSONObject(bestQuery(italy, tv = false)).getString("query").contains("""anyTitleTypeIds:["movie"]"""))
        assertTrue(best.contains("""excludeGenreIds:["Reality-TV","""))
        assertEquals("Highest rated Italian TV", italy.bestTitle(true))
        val ranked = parseBest(JSONObject("""{"data":{"advancedTitleSearch":{"edges":[
            {"node":{"title":{"id":"tt2049116","titleText":{"text":"Gomorrah"},"originalTitleText":{"text":"Gomorra"},"titleType":{"id":"tvSeries"},"releaseYear":{"year":2014},"ratingsSummary":{"aggregateRating":8.6},"genres":{"genres":[{"text":"Crime"},{"text":"Drama"}]},
             "countriesOfOrigin":{"countries":[{"text":"Italy"},{"text":"Germany"}]},"spokenLanguages":{"spokenLanguages":[{"text":"Neapolitan"},{"text":"Italian"}]}}}},
            {"node":{"title":{"id":"tt10278918","titleText":{"text":"My Brilliant Friend"},"titleType":{"id":"tvSeries"},"ratingsSummary":{"aggregateRating":8.6}}}},
            {"node":{"title":{"titleText":{"text":"nameless"}}}}
        ]}}}"""))
        assertEquals(listOf("Gomorrah" to 1, "My Brilliant Friend" to 2), ranked.map { it.name to it.rank })
        assertEquals("series" to "8.6", ranked[0].type to ranked[0].rating)
        assertEquals("Gomorra", ranked[0].original)
        assertEquals(listOf("Italy", "Germany") to listOf("Neapolitan", "Italian"), ranked[0].countries to ranked[0].languages)
        assertEquals(null, ranked[1].original) // the same title in both: nothing to repeat
        assertTrue(IMDB_TITLE_FIELDS.contains("originalTitleText{text}") && IMDB_TITLE_FIELDS.contains("spokenLanguages{spokenLanguages{text}}"))
        // A placed title has the card's shape and survives the trip to disk and back with its rank.
        val placed = jwTitle(films[0], "tt31049299", 3)
        assertEquals("movie" to 3, placed.title.type to placed.title.rank)
        val back = JwTitle.of(placed.json())!!
        assertEquals(3 to "Animals", back.rank to back.title.name)
        assertEquals(listOf("Action", "Thriller"), back.title.genres)
        assertEquals("Animales" to films[0].countries, back.title.original to back.title.countries)
    }

    @Test fun imdbBoxOfficeAndSearchListsParse() {
        val box = JSONObject("""{"data":{"boxOfficeWeekendChart":{"weekendStartDate":"2026-10-30","entries":[
            {"title":{"id":"tt1","titleText":{"text":"You Can See Everything"},"titleType":{"id":"movie"}},"weekendGross":{"total":{"amount":42134567,"currency":"USD"}}},
            {"title":{"id":"tt2","titleText":{"text":"Clayface"},"titleType":{"id":"movie"}},"weekendGross":{"total":{"amount":860000,"currency":"USD"}}}
        ]}}}""")
        val picks = parseImdbList(ImdbList.BOX_OFFICE, box)
        assertEquals(listOf(1, 2), picks.map { it.rank })
        assertEquals(listOf("$42.1M weekend", "$860K weekend"), picks.map { it.gross })
        val search = JSONObject("""{"data":{"advancedTitleSearch":{"edges":[{"node":{"title":{"id":"tt3","titleText":{"text":"Slow Horses"},"titleType":{"id":"tvSeries"},"releaseYear":{"year":2022}}}}]}}}""")
        assertEquals("tt3", parseImdbList(ImdbList.ON_APPLE, search)[0].id)
        assertEquals("series", parseImdbList(ImdbList.NEW_SERIES, search)[0].type)
    }

    @Test fun imdbListQueriesNameTheirRootsAndDates() {
        assertTrue(imdbListQuery(ImdbList.NEW_SERIES, "2026-11-05").contains("start:\\\"2026-11-01\\\",end:\\\"2026-11-30\\\""))
        assertTrue(imdbListQuery(ImdbList.ON_NETFLIX, "2026-11-05").contains("amzn1.imdb.w2w.provider.netflix"))
        assertTrue(imdbListQuery(ImdbList.BOX_OFFICE, "2026-11-05").contains("boxOfficeWeekendChart"))
        assertTrue(imdbListQuery(ImdbList.THIS_MONTH, "2026-11-05").contains("popularTitles(limit:30,queryFilter:{releaseDateRange:{start:\\\"2026-11-01\\\",end:\\\"2026-11-30\\\"}})"))
        assertTrue(imdbListQuery(ImdbList.ON_MAX, "2026-11-05").contains("provider.hbo_max"))
        assertTrue(imdbListQuery(ImdbList.ANTICIPATED, "2026-11-05").contains("start:\\\"2026-11-05\\\",end:\\\"2027-02-03\\\""))
        assertTrue(imdbListQuery(ImdbList.TOP_TV, "2026-11-05").contains("rankingsChartType:TOP_250_TV"))
        assertTrue(imdbListQuery(ImdbList.HOT_MOVIES, "2026-11-05").contains("MOST_POPULAR_MOVIES"))
        assertTrue(imdbListQuery(ImdbList.RECENT, "2026-11-05").contains("contentTypes:[TRAILER]"))
        assertTrue(imdbListQuery(ImdbList.ON_NETFLIX, "2026-11-05", "US").startsWith("{\"query\":\"query CAS_US{"))
        assertTrue(imdbListQuery(ImdbList.ON_NETFLIX, "2026-11-05", "US") != imdbListQuery(ImdbList.ON_NETFLIX, "2026-11-05", "GB"))
    }

    @Test fun imdbTrailerEmptyWhenNoVideos() {
        assertEquals(emptyList<Stream>(), parseImdbTrailerStreams(JSONObject("""{"data":{"title":{"primaryVideos":{"edges":[]}}}}""")))
    }

    @Test fun trailerFilesComeFromTrailerStreamUrls() {
        val m = meta("""{"id":"tt1","name":"X","trailerStreams":[{"title":"Teaser","url":"https://a/teaser.mp4"},{"title":"Y","ytId":"dQw4w9WgXcQ"}]}""")
        assertEquals(listOf("https://a/teaser.mp4"), m.trailerFiles.map { it.url })
        assertEquals(listOf("dQw4w9WgXcQ"), m.trailers)
    }
}
