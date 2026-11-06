package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class NewEpisodesTest {
    private val today: LocalDate = LocalDate.parse("2026-10-31")

    private fun series(id: String, lastWatched: String?, timeOffset: Long = 0, removed: Boolean = false) = JSONObject()
        .put("_id", id).put("type", "series").put("name", id).put("removed", removed)
        .put("state", JSONObject().put("timeOffset", timeOffset).put("video_id", "$id:1:1").also { if (lastWatched != null) it.put("lastWatched", lastWatched) })

    private fun show(vararg episodes: Pair<String, String?>) = Meta(JSONObject().put("id", "tt1").put("name", "Show").put("type", "series")
        .put("videos", org.json.JSONArray(episodes.map { (id, released) ->
            val parts = id.split(":")
            JSONObject().put("id", id).put("season", parts[1].toInt()).put("episode", parts[2].toInt()).also { if (released != null) it.put("released", released) }
        })))

    @Test fun episodesAiredSinceTheLastWatchAreNews() {
        val item = series("tt1", "2026-10-20T21:00:00.000Z")
        val news = NewEpisodes.find(item, show("tt1:1:1" to "2026-10-13T00:00:00.000Z", "tt1:1:2" to "2026-10-27T00:00:00.000Z", "tt1:1:3" to "2026-10-30T00:00:00.000Z"), today)!!
        assertEquals(2, news.count)
        assertEquals("2026-10-30", news.latest)
        assertEquals("tt1:1:2", news.videoId) // the first of them is the one to watch next
    }

    @Test fun nothingNewWhenUpToDate() {
        val item = series("tt1", "2026-10-30T21:00:00.000Z")
        assertNull(NewEpisodes.find(item, show("tt1:1:1" to "2026-10-30T00:00:00.000Z"), today))
    }

    @Test fun oldFutureAndSpecialEpisodesDoNotCount() {
        val item = series("tt1", "2026-01-01T00:00:00.000Z")
        val m = show("tt1:1:1" to "2026-09-01T00:00:00.000Z", // aired more than a month ago: no longer news
            "tt1:1:2" to "2026-11-07T00:00:00.000Z", // not out yet
            "tt1:0:1" to "2026-10-30T00:00:00.000Z", // a special
            "tt1:1:3" to null) // no date
        assertNull(NewEpisodes.find(item, m, today))
    }

    @Test fun aSeriesNeverWatchedHasNoNews() {
        assertNull(NewEpisodes.find(series("tt1", null), show("tt1:1:1" to "2026-10-30T00:00:00.000Z"), today))
        assertNull(NewEpisodes.find(series("tt1", "1970-01-01T00:00:00.000Z"), show("tt1:1:1" to "2026-10-30T00:00:00.000Z"), today))
    }

    @Test fun candidatesAreSeriesInMyListOnly() {
        val items = listOf(series("a", "2026-10-01T00:00:00.000Z"), series("b", "2026-10-02T00:00:00.000Z", removed = true),
            series("c", "2026-10-03T00:00:00.000Z").put("type", "movie"), series("d", "2026-10-04T00:00:00.000Z"))
        assertEquals(listOf("d", "a"), NewEpisodes.candidates(items).map { it.optString("_id") })
    }

    @Test fun continueIsInTheOrderThingsHappened() {
        val film = series("film", "2026-10-31T20:00:00.000Z", timeOffset = 600_000).put("type", "movie")
        val queued = series("queued", "2026-10-29T20:00:00.000Z", timeOffset = 1) // next episode lined up, nothing new
        val caughtUp = series("caught", "2026-10-25T20:00:00.000Z") // finished its last episode; a new one aired on the 30th
        val news = mapOf("caught" to NewEpisodes.News(1, "2026-10-30", "caught:2:1"))
        val cw = Library.continueWatching(listOf(queued, caughtUp, film), news)
        assertEquals(listOf("film", "caught", "queued"), cw.map { it.optString("_id") })
    }

    @Test fun aSeriesOutOfMyListDoesNotComeBackForNews() {
        val gone = series("gone", "2026-10-25T20:00:00.000Z", removed = true)
        val cw = Library.continueWatching(listOf(gone), mapOf("gone" to NewEpisodes.News(1, "2026-10-30", "gone:1:2")))
        assertEquals(emptyList<String>(), cw.map { it.optString("_id") })
    }

    @Test fun theNewEpisodesRowListsTheNewestNewsFirstAndOnlySeriesStillInMyList() {
        val older = series("tt1", "2026-10-20T21:00:00.000Z")
        val newer = series("tt2", "2026-10-25T21:00:00.000Z")
        val gone = series("tt3", "2026-10-25T21:00:00.000Z", removed = true)
        val quiet = series("tt4", "2026-10-25T21:00:00.000Z")
        val news = mapOf("tt1" to NewEpisodes.News(1, "2026-10-30", "tt1:1:3"), "tt2" to NewEpisodes.News(2, "2026-10-28", "tt2:1:2"),
            "tt3" to NewEpisodes.News(1, "2026-10-31", "tt3:1:1"))
        assertEquals(listOf("tt1", "tt2"), Library.withNewEpisodes(listOf(newer, older, gone, quiet), news).map { it.optString("_id") })
    }
}
