package dev.alder

import android.util.Base64
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.zip.InflaterInputStream
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Stremio library items (each title's watch history), following stremio-core's player rules so progress made
 * here shows up in Stremio on other devices. Items stay raw JSON so fields this app doesn't use survive the trip.
 */
object Library {
    private const val WATCHED = 0.7 // stremio-core WATCHED_THRESHOLD_COEF: watched this much, it counts as seen
    private const val CREDITS = 0.9 // CREDITS_THRESHOLD_COEF: stopped past this, it's finished

    /**
     * Continue Watching: what's in progress, plus the series in My List with a new episode out ([news], from
     * [NewEpisodes]), each placed by its most recent moment: the last time it was watched, or the day its newest
     * episode aired, whichever is later. So the film started tonight comes first, last night's new episode next.
     */
    fun continueWatching(items: List<JSONObject>, news: Map<String, NewEpisodes.News> = emptyMap()): List<JSONObject> {
        val inProgress = items.filter { it.optString("type") != "other" && (!it.optBoolean("removed") || it.optBoolean("temp")) && it.state.optLong("timeOffset") > 0 }
        val going = inProgress.map { it.optString("_id") }.toSet()
        val withNews = items.filter { it.optString("_id") in news && it.optString("_id") !in going && !it.optBoolean("removed") }
        return (inProgress + withNews).sortedByDescending { maxOf(it.state.text("lastWatched") ?: "", news[it.optString("_id")]?.latest ?: "") }
    }

    /** The series in My List with a new episode out, the newest news first: Home's New episodes row. */
    fun withNewEpisodes(items: List<JSONObject>, news: Map<String, NewEpisodes.News>): List<JSONObject> =
        items.filter { it.optString("_id") in news && !it.optBoolean("removed") }
            .sortedByDescending { news.getValue(it.optString("_id")).latest }

    /** A title that isn't in the library yet: Stremio keeps those as temporary, hidden items. */
    fun newItem(id: String, type: String, name: String, poster: String?): JSONObject {
        val now = now()
        return JSONObject().put("_id", id).put("name", name).put("type", type).put("poster", poster ?: "")
            .put("posterShape", "poster").put("removed", true).put("temp", true).put("_ctime", now).put("_mtime", now)
            .put("behaviorHints", JSONObject())
            .put("state", JSONObject().put("lastWatched", now).put("timeWatched", 0).put("timeOffset", 0)
                .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0).put("duration", 0)
                .put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL).put("noNotif", false))
    }

    /** In the library ("My List") rather than only remembered for Continue Watching. */
    fun inList(item: JSONObject?) = item != null && !item.optBoolean("removed")

    /** Adds the title to the library or takes it out; its watch history stays either way. */
    fun setInList(item: JSONObject, on: Boolean) {
        item.put("removed", !on).put("temp", false).put("_mtime", now())
    }

    /** Marks the current title complete without discarding its watch history. */
    fun markTitleWatched(item: JSONObject, videos: List<Video> = emptyList()) {
        val s = item.state
        s.text("video_id")?.let { id -> if (videos.any { it.id == id }) markWatched(item, videos, id) }
        s.put("timesWatched", maxOf(1, s.optInt("timesWatched")))
        s.put("flaggedWatched", 1).put("timeOffset", 0)
        item.put("_mtime", now())
    }

    /**
     * Off Continue Watching, as Stremio's own dismiss does (stremio-core's RewindLibraryItem): the resume point goes;
     * watch history, the episode reached and My List stay, and watching it again brings it back. It syncs, so the
     * phone and the web hide it too. Returns the resume point it had, so [unhide] can put it back.
     */
    fun hideFromContinueWatching(item: JSONObject): Long {
        val offset = item.state.optLong("timeOffset")
        item.state.put("timeOffset", 0)
        item.put("_mtime", now())
        return offset
    }

    /** Back on Continue Watching at [offset]; left alone when it was watched again since (it's back already). */
    fun unhide(item: JSONObject, offset: Long) {
        if (item.state.optLong("timeOffset") > 0) return
        item.state.put("timeOffset", offset)
        item.put("_mtime", now())
    }

    /** Stopped past the credits: finished, whatever the watched-time count says. */
    fun pastCredits(item: JSONObject): Boolean = item.state.let { s -> s.optLong("duration") > 0 && s.optLong("timeOffset") > s.optLong("duration") * CREDITS }

    /** Where to resume [videoId] in milliseconds, 0 to start from the beginning. */
    fun resumeAt(item: JSONObject, videoId: String): Long {
        val s = item.state
        val offset = s.optLong("timeOffset")
        val duration = s.optLong("duration")
        val unfinished = duration == 0L || offset < duration * CREDITS
        return if (s.text("video_id") == videoId && offset > 0 && unfinished) offset else 0
    }

    /**
     * The episodes Stremio has ticked as watched, read from stremio-core's watched bitfield (`state.watched`):
     * `{anchor video id}:{anchor length}:{base64 zlib bits}`, one bit per episode in season-then-episode order, shifted
     * so the anchor (the last one watched) lines up even after new episodes come out.
     */
    fun watched(item: JSONObject?, videos: List<Video>): Set<String> {
        val field = item?.optJSONObject("state")?.text("watched") ?: return emptySet()
        val parts = field.split(":")
        if (parts.size < 3) return emptySet()
        return runCatching {
            val bits = InflaterInputStream(Base64.decode(parts.last(), Base64.DEFAULT).inputStream()).readBytes()
            val anchorLength = parts[parts.size - 2].toInt()
            val anchor = parts.dropLast(2).joinToString(":") // ids may hold ':' themselves
            val ids = videos.sortedWith(compareBy({ it.season }, { it.episode })).map { it.id }
            val anchorAt = ids.indexOf(anchor)
            if (anchorAt < 0) return emptySet() // stremio-core: anchor gone, nothing counts as watched
            val offset = anchorLength - anchorAt - 1
            ids.filterIndexed { i, _ ->
                val bit = i + offset
                bit >= 0 && bit < ids.size && bit / 8 < bits.size && (bits[bit / 8].toInt() shr (bit % 8)) and 1 == 1
            }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Ticks [videoId] as watched in that bitfield, as stremio-core's `set_video` does, so other devices show the ✓. */
    fun markWatched(item: JSONObject, videos: List<Video>, videoId: String) = setWatched(item, videos, watched(item, videos) + videoId)

    /** Ticks every id in [ids] (a season, or all the episodes before one). */
    fun markWatchedMany(item: JSONObject, videos: List<Video>, ids: Collection<String>) = setWatched(item, videos, watched(item, videos) + ids)

    /** Takes the tick off [videoId]; with none left, the bitfield goes (stremio-core reads a missing one as nothing watched). */
    fun markUnwatched(item: JSONObject, videos: List<Video>, videoId: String) = setWatched(item, videos, watched(item, videos) - videoId)

    /** Takes the tick off every id in [ids]. */
    fun markUnwatchedMany(item: JSONObject, videos: List<Video>, ids: Collection<String>) = setWatched(item, videos, watched(item, videos) - ids.toSet())

    private fun setWatched(item: JSONObject, videos: List<Video>, seen: Set<String>) {
        val ids = videos.sortedWith(compareBy({ it.season }, { it.episode })).map { it.id }
        val last = ids.indexOfLast { it in seen }
        if (last < 0) { item.state.put("watched", JSONObject.NULL); return }
        val bits = ByteArray((ids.size + 7) / 8)
        ids.forEachIndexed { i, id -> if (id in seen) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte() }
        val zipped = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out, java.util.zip.Deflater(6)).use { it.write(bits) }
        }.toByteArray()
        item.state.put("watched", "${ids[last]}:${last + 1}:${Base64.encodeToString(zipped, Base64.NO_WRAP)}")
    }

    /** Playback progress, called every few seconds. [playedMs] is time actually watched since the last call. */
    fun record(item: JSONObject, videoId: String, positionMs: Long, durationMs: Long, playedMs: Long) {
        val s = item.state
        val now = now()
        s.put("lastWatched", now)
        if (s.text("video_id") != videoId) {
            s.put("video_id", videoId)
            s.put("overallTimeWatched", s.optLong("overallTimeWatched") + s.optLong("timeWatched"))
            s.put("timeWatched", 0).put("flaggedWatched", 0)
        }
        s.put("timeWatched", s.optLong("timeWatched") + playedMs)
        s.put("overallTimeWatched", s.optLong("overallTimeWatched") + playedMs)
        s.put("timeOffset", positionMs)
        if (durationMs > 0) s.put("duration", durationMs)
        val duration = s.optLong("duration")
        if (s.optInt("flaggedWatched") == 0 && duration > 0 && s.optLong("timeWatched") > duration * WATCHED) {
            s.put("flaggedWatched", 1)
            s.put("timesWatched", s.optInt("timesWatched") + 1)
            // The episode's own tick (the watched bitfield) is set by the player here, which has the episode list.
        }
        if (item.optBoolean("temp") && s.optInt("timesWatched") == 0) item.put("removed", true)
        if (item.optBoolean("removed")) item.put("temp", true)
        item.put("_mtime", now)
    }

    /** When playback stops: past the credits it's finished, and a series moves on to [nextVideoId]. */
    fun stopped(item: JSONObject, nextVideoId: String?) {
        val s = item.state
        if (s.optLong("duration") == 0L || s.optLong("timeOffset") <= s.optLong("duration") * CREDITS) return
        s.put("timeOffset", 0)
        if (nextVideoId != null) {
            s.put("video_id", nextVideoId)
            s.put("overallTimeWatched", s.optLong("overallTimeWatched") + s.optLong("timeWatched"))
            s.put("timeWatched", 0).put("flaggedWatched", 0)
            s.put("timeOffset", 1) // stremio-core's marker for "start the next episode", which keeps it in Continue Watching
        }
        item.put("_mtime", now())
    }

    private val JSONObject.state: JSONObject get() = optJSONObject("state") ?: JSONObject().also { put("state", it) }

    /** Now as Stremio writes times: ISO 8601 in UTC with milliseconds. */
    fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
