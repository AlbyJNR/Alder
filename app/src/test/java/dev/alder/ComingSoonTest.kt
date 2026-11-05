package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComingSoonTest {
    @Test fun imdbComingSoonIsParsedInOrderWithSmallPosters() {
        val json = JSONObject("""{"data":{"comingSoon":{"edges":[
            {"node":{"id":"tt1","titleText":{"text":"Dune: Part Three"},"releaseDate":{"day":18,"month":12,"year":2026},
             "primaryImage":{"url":"https://m.media-amazon.com/images/M/abc._V1_.jpg"},"genres":{"genres":[{"text":"Sci-Fi"},{"text":"Adventure"}]},
             "primaryVideos":{"edges":[{"node":{"id":"vi1"}}]}}},
            {"node":{"id":"tt2","titleText":{"text":"No date"},"releaseDate":{"day":0,"month":0,"year":0}}},
            {"node":{"id":"tt3","titleText":{"text":"Quiet one"},"releaseDate":{"day":1,"month":1,"year":2027},"primaryVideos":{"edges":[]}}}
        ]}}}""")
        val list = parseImdbComingSoon(json)
        assertEquals(listOf("tt1", "tt3"), list.map { it.id })
        assertEquals("2026-12-18", list[0].date)
        assertEquals("https://m.media-amazon.com/images/M/abc._V1_QL80_UX360_.jpg", list[0].poster)
        assertEquals(listOf("Sci-Fi", "Adventure"), list[0].genres)
        assertEquals(true, list[0].hasTrailer)
        assertEquals(false, list[1].hasTrailer)
        assertNull(list[1].poster)
    }

    @Test fun shortDayDropsThisYear() {
        assertEquals("20 Nov", shortDay("2026-11-20", "2026-11-05"))
        assertEquals("1 Jan 2027", shortDay("2027-01-01", "2026-11-05"))
        assertNull(shortDay("soon", "2026-11-05"))
    }

    @Test fun airsTakesAWord() {
        assertEquals("In cinemas 20 Nov", airs("2026-11-20", "2026-11-05T00:00:00.000Z", "In cinemas"))
        assertNull(airs("2026-11-01", "2026-11-05T00:00:00.000Z", "In cinemas"))
    }
}
