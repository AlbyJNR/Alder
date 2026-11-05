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
        assertTrue(imdbListQuery(ImdbList.THIS_MONTH, "2026-11-05").contains("popularTitles(limit:50,queryFilter:{releaseDateRange:{start:\\\"2026-11-01\\\",end:\\\"2026-11-30\\\"}})"))
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
