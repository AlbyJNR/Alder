package dev.alder

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun lowerSourceHeight(height: Int): Int? = listOf(1080, 720, 480).firstOrNull { it < height }
internal fun upNextWindow(durationMs: Long) = minOf(60_000L, maxOf(30_000L, durationMs / 25))
internal fun plausibleEpisode(durationMs: Long) = durationMs >= 2 * 60_000L

// ExoPlayer with our own controls (buildControls) and audio/subtitle panel (openOptions). Resumes where Stremio says you stopped and
// writes progress back to the account, so Continue Watching matches on every device.
// It gets a short list of sources: one that fails, or hasn't shown a picture after START_WAIT, gives way to the next
// at the same second, and when the list runs out the sources page opens. Network blips are retried first, twice
// after 1.5 s (NuvioTV's rule), so a dropped connection doesn't cost the source.
class PlayerActivity : Screen() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val videoId by lazy { intent.getStringExtra("videoId")!! }

    // The title's library item. Null when it couldn't be loaded: then nothing is saved, rather than
    // overwriting real history with a blank item.
    private var item: JSONObject? = null
    private var lastPosition = 0L
    private var ticks = 0

    private val sources by lazy { JSONArray(intent.getStringExtra("sources") ?: "[]").objects().map(Stream::read) }
    private var at = 0 // the source playing
    private var retries = 0 // network retries on this source
    private var asHls = false // tried as HLS: live and IPTV links often hide the playlist behind a plain URL
    private var started = false // a picture has shown
    private var startMessage: String? = null
    private var watched = false // any source showed a picture this time: only then is progress saved
    private var startAt = 0L
    private var prepared = false
    private lateinit var status: android.widget.TextView
    private lateinit var loading: FrameLayout
    private lateinit var loadingBar: SeekBar
    private lateinit var loadingStart: TextView
    private lateinit var loadingLeft: TextView
    private lateinit var stallPanel: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var pausedShade: View
    // Subtitles from addons (OpenSubtitles): offered in Playback options after the file's own. Picking one reloads the
    // source from the same second with that file attached, then selects it once its track shows up.
    private var addonSubs: List<Subtitle> = emptyList()
    private var chosenSub: Subtitle? = null
    private var selectAddonSub = false
    // The languages to start with: what was picked in Audio & subtitles for this title before (the next episode, a
    // resume), else the profile's Settings. Picks in the menu change them, are kept for the title, and hold when the
    // player moves to another source.
    private var wantAudio = ""
    private var wantSub = ""
    private var subsOn = true
    private var subsIfForeign = false // decided once the tracks are known: on when the sound is not in the viewer's language
    // The title's own language as the rows knew it ("Italy · Italian" from Foreign), for sound the file leaves untagged.
    private val titleLanguage by lazy { intent.getStringExtra("origin")?.substringAfterLast('·')?.trim()?.let(Languages::code) }
    private var viewerAddonSub = false // the viewer chose the addon file, over the file's own in the same language
    // Timing for an addon subtitle file that runs early or late: the file is fetched, its times moved, and attached
    // from the app's cache instead of its URL. ponytail: SRT and VTT only; ASS and the file's own subtitles can't move.
    private var subDelay = 0L
    private var subFile: java.io.File? = null

    // A series' episodes (for the next one and Stremio's watched ticks), from the meta addon's saved answer when there is one.
    private var meta: Meta? = null
    private var tickedWatched = false
    // "Up next": offered during credits; the current episode keeps playing until the viewer chooses the next one.
    private lateinit var upNextPanel: FrameLayout
    private var upNextDismissed = false
    // The pill's other use: a source that keeps stalling (three times in a minute) is offered a lower quality once.
    private var offeringLower = false
    private val stalls = ArrayDeque<Long>()
    private var seekedAt = 0L
    // Silent failover (Settings › Picture & sound › When a source keeps stalling): a source that stops three times in a
    // minute, or sits buffering for LONG_STALL, gives way to a lighter file by itself. The count rides the intent through
    // the sources page, which restarts the player, so one bad evening can't cascade past AUTO_SWITCHES.
    private val autoSwitches get() = intent.getIntExtra("autoSwitches", 0)
    private var pendingNote: String? = null // shown once the next picture is up ("Switched to a lighter source · 720p")
    private var whyNote: String? = null // why Play chose this source ("Best match · 1080p · played last time"), for its first picture only
    private lateinit var note: TextView
    private val hideNote = Runnable { note.visibility = View.GONE }
    private val longStall = Runnable {
        if (isDestroyed || !started || !player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) return@Runnable
        if (System.currentTimeMillis() - seekedAt < 2_000) return@Runnable
        weakSource("buffered for ${LONG_STALL / 1000} s")
    }
    private val loadingTask = object : Runnable {
        override fun run() {
            if (loading.visibility != View.VISIBLE || player.playbackState != Player.STATE_BUFFERING) return
            showLoading(if (started) "Loading" else startMessage ?: "Starting · ${sources[at].label}")
        }
    }
    private val startTimeout = Runnable {
        if (started) return@Runnable
        android.util.Log.w("CAS", "source ${at + 1}/${sources.size} timed out, state ${player.playbackState}")
        next()
    }

    // The title page's tint, half way back to near-black: the shades lean to the film's colour without fighting
    // whatever scene is paused under them (the poster's amber over a teal night street read as mud at full strength).
    private val room by lazy { mix(intent.getIntExtra("tint", BASE), BASE, 0.5) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        if (sources.isEmpty()) return finish()
        // Audio is decoded to PCM here unless Settings says a receiver takes Dolby and DTS: TVs report passthrough
        // (DTS-HD, TrueHD) they then can't open, and playback dies. A track that fails is swapped (onPlayerError).
        passthrough = Settings.passthrough(this)
        dialogueProcessor.enabled = !passthrough && Settings.clearDialogue(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                if (passthrough) DefaultAudioSink.Builder(context).build() else
                // Clear dialogue sits in the PCM chain; off, it passes the sound through untouched.
                object : ForwardingAudioSink(DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(dialogueProcessor)).build()) {
                    override fun getFormatSupport(format: Format): Int =
                        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

                    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
                }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // ffmpeg after the TV's own decoders
            // A decoder that won't open (a Fire TV's first HEVC decoder is busy, or refuses the profile) gives way to the
            // TV's next one for the format before the source counts as failed.
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers).build()
        val remembered = Settings.titleChoice(this, metaId)
        wantAudio = remembered?.audio ?: Settings.audioLanguage(this)
        // A choice made for this title while watching stands; else Settings: always, never, or (Netflix's way) only
        // when the sound turns out not to be in the viewer's language, decided once the file's tracks are known.
        val subMode = Settings.subtitleMode(this)
        subsOn = remembered?.subtitles?.isNotEmpty() ?: (subMode == "always")
        subsIfForeign = remembered?.subtitles == null && subMode == "foreign"
        wantSub = remembered?.subtitles?.ifEmpty { null } ?: Settings.subtitleLanguage(this)
        // Release files carry many dubs; start in the chosen language (the TV's unless Settings says) instead of the first.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(wantAudio).setPreferredTextLanguage(wantSub).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Settings' size and background, in the app's own face. With the box on, it carries the words and
            // nothing is drawn under them; with it off, a soft shadow keeps white words readable on a bright scene.
            val boxed = Settings.subtitleBackground(context)
            subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.subtitleSize(context) / 100f)
            subtitleView?.setStyle(CaptionStyleCompat(Color.WHITE,
                if (boxed) 0xB3000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
                if (boxed) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                if (boxed) Color.TRANSPARENT else 0xCC000000.toInt(), text("", SMALL).typeface))
            setBackgroundColor(Color.BLACK)
        }
        status = text("", BODY).onArt().apply {
            gravity = Gravity.CENTER
            maxWidth = dp(440)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        pausedShade = View(this).apply { setBackgroundColor(0x4D000000); visibility = View.GONE }
        upNextPanel = FrameLayout(this).apply { visibility = View.GONE }
        controls = buildControls()
        loading = buildLoading()
        stallPanel = FrameLayout(this).apply { visibility = View.GONE }
        skip = text("", BODY, bold = true).onArt().apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = glass(10)
            visibility = View.GONE
        }
        // A quiet word after an automatic switch; no buttons, gone by itself.
        note = text("", SMALL, color = TEXT_2).onArt().apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = glass(10)
            visibility = View.GONE
        }
        fetchSegments()
        options = FrameLayout(this).apply { visibility = View.GONE }
        screen = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(pausedShade, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(options, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(-1, -1))
            addView(stallPanel, FrameLayout.LayoutParams(-1, -1))
            addView(upNextPanel, FrameLayout.LayoutParams(-1, -1))
            addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(PAD), dp(PAD + 40)) })
            addView(note, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(PAD), 0, 0, dp(PAD + 40)) })
        }
        intent.getStringExtra("switchedNote")?.let { pendingNote = it; intent.removeExtra("switchedNote") }
        intent.getStringExtra("whyNote")?.let { whyNote = it; intent.removeExtra("whyNote") }
        setContentView(screen)
        background({
            Stremio.addons(key).let { it.savedMeta(type, metaId) ?: it.meta(type, metaId) }
        }) { r ->
            meta = r.getOrNull()
            val m = meta ?: return@background
            val episode = m.videos.firstOrNull { it.id == videoId }
            pauseTitle.text = episode?.title?.takeIf { it.isNotBlank() } ?: m.name
            subtitle.text = episode?.let { "S${it.season} E${it.episode}" } ?: m.year.orEmpty()
            Images.load(m.logo ?: Images.logoFor(m.id), playerLogo, dp(300), urgent = true, failed = {
                hasLogo = false; showBrand()
            }) {
                hasLogo = true; showBrand()
            }
            nextCell.visibility = if (nextVideo() == null) View.GONE else View.VISIBLE
        }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // A bad audio track (ffmpeg rejects a DTS-HD packet in The Raid around 5:14) shouldn't end the movie:
                // drop that track and carry on from the same second on the next best one.
                val failed = (e as? ExoPlaybackException)?.rendererFormat
                if (failed?.sampleMimeType?.let(MimeTypes::isAudio) == true && failedAudio.add(failed.id ?: "")) {
                    val next = bestAudio(audioGroups)
                    if (next != null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(next.mediaTrackGroup, (0 until next.length).toList())).build()
                        player.prepare()
                        android.util.Log.i("CAS", "Audio track changed after decoder error")
                        return
                    }
                }
                // A link that isn't a file ExoPlayer knows may be an HLS playlist without .m3u8 in its name.
                if (!asHls && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    asHls = true
                    return load(position())
                }
                if (retryable(e) && retries < RETRIES) {
                    retries++
                    // ExoPlayer's own retries already took about 6 s: each of ours gets a fresh START_WAIT, or the
                    // start timeout would move on to the next source before the second retry ran.
                    main.removeCallbacks(startTimeout)
                    main.postDelayed(startTimeout, RETRY_WAIT + START_WAIT)
                    main.postDelayed({ if (!isFinishing) player.prepare() }, RETRY_WAIT)
                    return
                }
                android.util.Log.w("CAS", "source ${at + 1}/${sources.size} failed: ${e.errorCodeName}", e)
                next(e.errorCodeName)
            }

            override fun onRenderedFirstFrame() {
                if (started) return
                started = true
                watched = true
                retries = 0
                hideLoading()
                main.removeCallbacks(startTimeout)
                (pendingNote ?: whyNote)?.let { pendingNote = null; whyNote = null; showNote(it) }
                player.videoFormat?.frameRate?.let(::matchFrameRate)
                // Next time: the same release for this video, and the same group (release and quality) for the next episode.
                val s = sources[at]
                getSharedPreferences("sources", MODE_PRIVATE).edit()
                    .putString("file:$videoId", s.filename).putString("binge:$metaId", s.bingeGroup).apply()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // At the real end the next episode starts, unless the viewer chose to watch the credits.
                if (state == Player.STATE_ENDED) {
                    // shortcut: sub-two-minute series streams are treated as addon placeholders; revisit for short-form shows.
                    if (type == "series" && !plausibleEpisode(player.duration)) {
                        started = false // retry from the original resume point, not the placeholder's short ending
                        next("source ended as a short placeholder")
                    } else if (nextVideo() != null && !upNextDismissed) playNext() else finish()
                }
                if (state == Player.STATE_BUFFERING) showLoading(if (started) "Loading" else startMessage ?: "Starting · ${sources[at].label}")
                if (state == Player.STATE_READY && started) hideLoading()
                // A seek buffers too; only stalls in plain playback count.
                main.removeCallbacks(longStall)
                if (state == Player.STATE_BUFFERING && started && player.playWhenReady && System.currentTimeMillis() - seekedAt > 2_000) {
                    stalled()
                    main.postDelayed(longStall, LONG_STALL) // one stall that never ends counts as much as three short ones
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playPause.foreground = centred(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play, 25, BASE)
                buttonLabels[playPause]?.text = if (playWhenReady) "Pause" else "Play"
                pausedShade.visibility = if (playWhenReady) View.GONE else View.VISIBLE
                pauseInfo.visibility = if (playWhenReady) View.GONE else View.VISIBLE
                showBrand()
                if (playWhenReady) { if (shown) showControls(null) }
                else if (options.visibility != View.VISIBLE && upNextPanel.visibility != View.VISIBLE &&
                    stallPanel.visibility != View.VISIBLE) showControls(playPause)
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekedAt = System.currentTimeMillis()
                    skipped.clear() // back into an intro: offer it again
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) { updateBadges() }

            override fun onTracksChanged(tracks: Tracks) {
                trackStatus.text = trackStatus(tracks)
                updateBadges()
                if (selectAddonSub) tracks.groups.firstOrNull(::isAddonSub)?.let { g ->
                    selectAddonSub = false
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                }
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty()) audioGroups = audio
                val best = bestAudio(audio)
                // Releases flag foreign subtitles as forced or default (Lanterns came up with Russian "Forced" under
                // English audio): start with subtitles only in the profile's subtitle language, whatever the sound, else
                // none. The file's own come first, then the addon file attached at the start, then the file's own from
                // another country (Portugal's for Brazil's). Once per video, like audio.
                if (!subsPicked && best != null && subsIfForeign) {
                    subsIfForeign = false
                    // The sound's language from its track (most releases tag it), else what the rows knew of the title;
                    // untagged sound in a title of unknown language counts as the viewer's own.
                    val sound = Languages.code(best.getTrackFormat(0).language) ?: titleLanguage
                    val mine = wantAudio.ifEmpty { Locale.getDefault().language }
                    subsOn = sound != null && Languages.match(sound, mine) == 0
                    if (subsOn) android.util.Log.i("CAS", "Sound in $sound, not $mine: subtitles on")
                }
                if (!subsPicked && best != null) {
                    subsPicked = true
                    val texts = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
                    fun inFile(score: Int) = texts.filterNot(::isAddonSub).filter { Languages.match(it.getTrackFormat(0).language, wantSub) == score }
                        .maxByOrNull { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED == 0 } // full, not forced-only
                    val addon = texts.firstOrNull { isAddonSub(it) && Languages.same(chosenSub?.lang, wantSub) }
                    val pick = if (!subsOn) null
                        else (if (viewerAddonSub) addon else null) ?: inFile(2) ?: addon ?: inFile(1)
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        if (pick == null) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        else setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(pick.mediaTrackGroup, 0))
                    }.build()
                }
                if (audioPicked || best == null) return
                audioPicked = true // once per video; picks made later in the settings menu stand
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(best.mediaTrackGroup, (0 until best.length).toList())).build()
            }
        })

        // "Start Over" plays from the beginning; a switch to a lower quality carries its own second.
        val known = if (intent.getBooleanExtra("startOver", false)) 0L else intent.getLongExtra("resumeAt", -1L).takeIf { it >= 0 }
        // The addon's subtitles are waited for (SUB_WAIT at most) so a file in the profile's language is attached from
        // the start: attaching one later means loading the source again. The latest resume point asked for wins.
        var subsAnswered = !(subsOn || subsIfForeign) // with foreign sound the files are fetched ahead too, in case
        var waiting: Long? = null
        fun begin(resume: Long) {
            if (prepared) return
            if (!subsAnswered) { waiting = resume; return }
            load(resume)
            lastPosition = resume
            prepared = true
            main.postDelayed(tickTask, TICK)
            main.post(skipTask)
        }
        val stopWaiting = Runnable { subsAnswered = true; waiting?.let(::begin) }
        main.postDelayed(stopWaiting, SUB_WAIT)
        background({
            // Every language: the profile's subtitle language and English first, three files each in the addon's order,
            // then one file for each other language by name, so the list stays short enough for a remote.
            val all = Stremio.addons(key).filter { it.serves("subtitles", type, videoId) }
                .flatMap { a -> runCatching { a.subtitles(type, videoId) }
                    .onFailure { android.util.Log.w("CAS", "subtitles from ${a.name} failed", it) }.getOrDefault(emptyList()) }
            all.groupBy { Languages.code(it.lang) ?: it.lang }.entries
                .sortedWith(compareBy({ it.key != wantSub }, { it.key != "en" }, { Languages.name(it.key) }))
                .flatMap { (code, files) -> files.take(if (code == wantSub || code == "en") 3 else 1) }
        }) { r ->
            addonSubs = r.getOrDefault(emptyList())
            android.util.Log.i("CAS", "addon subtitles: ${addonSubs.size}", r.exceptionOrNull())
            if (subsAnswered) return@background // too late: playing already, offered in the list
            main.removeCallbacks(stopWaiting)
            if (chosenSub == null) chosenSub = addonSubs.firstOrNull { Languages.same(it.lang, wantSub) } // attached, picked above
            stopWaiting.run()
        }
        // The stream starts at once from what's known here (the intent, or the library saved on the TV) instead of
        // waiting a round trip to Stremio for the resume point; Stremio's answer corrects it if it moved elsewhere.
        if (known != null) begin(known)
        else background({ Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId } }) { r ->
            r.getOrNull()?.let { begin(Library.resumeAt(it, videoId)) }
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            item = result.fold(
                { it ?: Library.newItem(metaId, type, intent.getStringExtra("name") ?: "", intent.getStringExtra("poster")) },
                { null },
            )
            val resume = known ?: item?.let { Library.resumeAt(it, videoId) } ?: 0L
            if (!prepared) begin(resume)
            // Watched further on the phone since the TV saved its copy: go there, unless you've already moved on.
            else if (known == null && abs(resume - startAt) > RESUME_SLACK && position() < startAt + RESUME_SLACK) {
                startAt = resume
                player.seekTo(resume)
                lastPosition = resume
            }
        }
    }

    private fun buildLoading() = FrameLayout(this).apply {
        visibility = View.GONE
        addView(View(context).apply { setBackgroundColor(withAlpha(room, 0x80)) }, FrameLayout.LayoutParams(-1, -1))
        val center = column(Gravity.CENTER_HORIZONTAL).apply {
            addView(ring(), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        loadingBar = SeekBar(this@PlayerActivity).apply { isFocusable = false }
        loadingStart = text("", SMALL, color = TEXT_2).onArt()
        loadingLeft = text("", SMALL, color = TEXT_2).onArt()
        addView(loadingBar, FrameLayout.LayoutParams(-1, dp(12), Gravity.TOP).apply {
            leftMargin = dp(PAD + 60); rightMargin = dp(PAD + 60); topMargin = dp(32) // room for "1:23:45" either side
        })
        addView(loadingStart, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(PAD); topMargin = dp(29)
        })
        addView(loadingLeft, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(PAD); topMargin = dp(29)
        })
    }

    private fun showLoading(message: String) {
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0)
        val buffered = player.bufferedPosition.coerceAtLeast(position)
        loadingBar.set(position, buffered, duration)
        loadingStart.text = if (duration > 0) clock(position) else ""
        loadingLeft.text = if (duration > 0) "−${clock((duration - position).coerceAtLeast(0))}" else ""
        status.text = if (message == "Loading" && player.bufferedPercentage in 1..99)
            "Loading · ${player.bufferedPercentage}%" else message
        loading.visibility = View.VISIBLE
        main.removeCallbacks(loadingTask)
        if (player.playbackState == Player.STATE_BUFFERING) main.postDelayed(loadingTask, 500)
    }

    private fun hideLoading() {
        main.removeCallbacks(loadingTask)
        loading.visibility = View.GONE
        status.text = ""
    }

    /** A subtitle pick in the menu: the language ("" for Off) for this title's next episode and resume, and for the
     *  next source if this one fails. A track with no language turns subtitles on without naming one. */
    private fun remember(language: String?, addonFile: Boolean) {
        subsOn = language != ""
        viewerAddonSub = addonFile
        val code = if (language == "") "" else Languages.code(language) ?: return
        if (code.isNotEmpty()) wantSub = code
        Settings.pickedInPlayer(this, metaId, subtitles = code)
    }

    /** Plays the current source from [position], with its own request headers (debrid proxies need them). */
    private fun load(position: Long, message: String? = null) {
        val s = sources[at]
        startAt = position
        started = false
        toldUnseekable = false
        startMessage = message ?: "Starting · ${s.label}"
        showLoading(startMessage!!)
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(s.headers)
        val item = MediaItem.Builder().setUri(s.url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(listOfNotNull(chosenSub?.let {
                MediaItem.SubtitleConfiguration.Builder(subFile?.let(android.net.Uri::fromFile) ?: android.net.Uri.parse(it.url)).setMimeType(it.mimeType)
                    .setLanguage(it.lang).setLabel(it.addon).setId(ADDON_SUB).build()
            })).build()
        // Files with no index (.ts, raw audio, some .avi) seek by their bitrate instead of not at all: ExoPlayer turns
        // every seek in a file it can't seek into a jump to 0:00, so scrubbing restarted the movie.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true).setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors).createMediaSource(item), position) // files too: shifted subtitles
        player.prepare()
        player.playWhenReady = true
        main.removeCallbacks(startTimeout)
        main.postDelayed(startTimeout, START_WAIT)
    }

    private fun position() = if (started) player.currentPosition else startAt

    /** This source failed: the next one from the same second or, with none left, the list to choose from. */
    private fun next(reason: String = "didn't start") {
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(longStall)
        val position = position()
        if (at + 1 < sources.size) {
            at++
            stalls.clear()
            retries = 0
            asHls = false
            audioPicked = false
            subsPicked = false
            failedAudio.clear()
            whyNote = null // the reason was for the source that failed
            android.util.Log.w("CAS", "Source failed: $reason; trying another")
            return load(position, "That source failed · trying the next one")
        }
        if (intent.getBooleanExtra("picked", false)) {
            showSourceError()
            player.stop()
            showLoading("That source failed · choose another source")
            return
        }
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).putExtra("pick", true)
            .putExtra("message", "${sources.size} sources didn't play"))
        finish()
    }

    /** Worth trying the same link again: a network or server hiccup, not a link that's gone (4xx) or a bad file. */
    private fun retryable(e: PlaybackException): Boolean {
        if (e.errorCode !in 2000..2999) return false
        val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return true
        return code !in 400..499 || code == 408 || code == 429
    }

    private var audioPicked = false
    private var subsPicked = false
    private var audioGroups: List<Tracks.Group> = emptyList()
    private val failedAudio = mutableSetOf<String>() // Format ids of audio tracks that failed to decode

    /**
     * The start track: never a commentary (releases only say so in the track name, which ExoPlayer ignores), then the
     * TV's language, then the release's default track, then the most channels. ExoPlayer alone would favour whatever
     * the TV decodes in hardware, which on The Raid was a stereo commentary.
     */
    private fun bestAudio(groups: List<Tracks.Group>): Tracks.Group? {
        val language = wantAudio
        return groups
            .filter { it.isTrackSupported(0) && (it.getTrackFormat(0).id ?: "") !in failedAudio }
            .filter { it.getTrackFormat(0).label?.contains("commentary", ignoreCase = true) != true }
            .maxWithOrNull(compareBy<Tracks.Group>(
                { Languages.match(it.getTrackFormat(0).language, language) },
                { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
                { it.getTrackFormat(0).channelCount },
            ))
    }

    private val tickTask = Runnable { tick() }

    // Progress every 10 s, pushed to Stremio every minute (Stremio's own apps push every 3 minutes).
    private fun tick() {
        record()
        if (++ticks % 6 == 0) push()
        main.postDelayed(tickTask, TICK)
    }

    override fun onStart() {
        super.onStart()
        if (prepared) {
            lastPosition = player.currentPosition
            main.postDelayed(tickTask, TICK)
            main.post(skipTask)
            if (loading.visibility == View.VISIBLE && player.playbackState == Player.STATE_BUFFERING)
                main.post(loadingTask)
        }
    }

    private fun record() {
        // Nothing is saved until a picture shows: a source that never starts would otherwise reset the resume point.
        if (!started || player.isCurrentMediaItemLive) return // live TV has no progress to keep
        if (type == "series" && !plausibleEpisode(player.duration)) return
        val item = item ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: return
        // Only real viewing counts as watched time: a seek moves the position by more than a tick allows.
        val played = (position - lastPosition).takeIf { it in 1..TICK * 2 } ?: 0
        lastPosition = position
        Library.record(item, videoId, position, duration, played)
        val videos = meta?.videos
        if (!tickedWatched && videos != null && item.optJSONObject("state")?.optInt("flaggedWatched") == 1) {
            tickedWatched = true
            Library.markWatched(item, videos, videoId)
        }
        // Keep the current episode full screen; offer the next one only near its actual end.
        if (!upNextDismissed && upNextPanel.visibility != View.VISIBLE && duration - position < upNextWindow(duration)) showUpNext()
    }

    /** The episode after this one, or null for a film, the last episode, or before the episode list is in. */
    private fun nextVideo(): Video? {
        val videos = meta?.videos ?: return null
        val at = videos.indexOfFirst { it.id == videoId }.takeIf { it >= 0 } ?: return null
        return videos.getOrNull(at + 1)?.takeIf { it.season != 0 || videos[at].season == 0 } // not from the finale into specials
    }

    private fun stalled() {
        val now = System.currentTimeMillis()
        stalls.addLast(now)
        while (stalls.first() < now - 60_000) stalls.removeFirst()
        if (stalls.size == 3) weakSource("stopped 3 times in a minute") // the third only: the panel, once declined, stays away
    }

    /**
     * The source can't keep up. Set to switch by itself, the player goes to a lighter file from the same second (the
     * sources page picks it, as the panel's button does), or failing that to the next source in its list; set to ask,
     * or after AUTO_SWITCHES, it shows the panel as before. Nothing while Up next or the panel is up.
     */
    private fun weakSource(reason: String) {
        if (isDestroyed || offeringLower || upNextPanel.visibility == View.VISIBLE) return
        val height = sources[at].height
        val lower = lowerSourceHeight(height)
        val auto = Settings.stallSwitch(this) == "auto"
        when {
            auto && lower != null && autoSwitches < AUTO_SWITCHES -> {
                android.util.Log.i("CAS", "Source $reason: switching from ${height}p to ${lower}p by itself (${autoSwitches + 1} of $AUTO_SWITCHES)")
                lowerQuality(auto = true)
            }
            auto && lower == null && at + 1 < sources.size -> {
                android.util.Log.i("CAS", "Source $reason at ${height}p, nothing lighter: the next source")
                pendingNote = "Switched to another source"
                next("kept stalling")
            }
            lower != null -> showStall(height)
        }
    }

    private fun showNote(message: String) {
        note.text = message
        note.visibility = View.VISIBLE
        main.removeCallbacks(hideNote)
        main.postDelayed(hideNote, NOTE_SHOWN)
    }

    private fun showStall(height: Int) {
        val lower = lowerSourceHeight(height) ?: return
        offeringLower = true
        stallPanel.removeAllViews()
        val card = column(Gravity.START).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = glass(14)
            addView(caps("Keeps buffering", TUNGSTEN))
            addView(text("Switch to ${lower}p?", SERIF_TITLE, serif = true),
                wrap().apply { topMargin = dp(4) })
            addView(text("This ${height}p source stopped 3 times in a minute. A smaller file may play more smoothly from ${clock(position())}.",
                SMALL, color = TEXT_2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val actions = LinearLayout(this@PlayerActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val lowerButton = pill("Switch to ${lower}p").apply { setOnClickListener { lowerQuality() } }
            actions.addView(lowerButton, wrap())
            actions.addView(chip("Keep ${height}p").apply { setOnClickListener { dismissStall() } },
                wrap().apply { marginStart = dp(8) })
            actions.addView(chip("Other sources").apply { setOnClickListener { changeStream() } },
                wrap().apply { marginStart = dp(8) })
            addView(actions, wrap().apply { topMargin = dp(12) })
            lowerButton.post { lowerButton.requestFocus() }
        }
        stallPanel.addView(card, FrameLayout.LayoutParams(dp(460), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
    }

    private fun dismissStall() {
        offeringLower = false
        stallPanel.visibility = View.GONE
        stallPanel.removeAllViews()
    }

    private fun showSourceError() {
        stallPanel.removeAllViews()
        val card = column(Gravity.START,
            caps("Source failed", TUNGSTEN),
            text("Try another source", SERIF_TITLE, serif = true).marginTop(6),
            pill("Other sources").apply { setOnClickListener { changeStream() } }.marginTop(12)
        ).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = glass(14)
        }
        stallPanel.addView(card, FrameLayout.LayoutParams(dp(310), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
        card.getChildAt(2).requestFocus()
    }

    /** Continue at the same second with one quality step lower; [auto] when the player decided, not the viewer. */
    private fun lowerQuality(auto: Boolean = false) {
        val height = sources[at].height
        val lower = lowerSourceHeight(height) ?: return
        main.removeCallbacks(longStall)
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("pick"); removeExtra("switchedNote")
            if (auto) putExtra("autoSwitches", autoSwitches + 1).putExtra("switchedNote", "Switched to a lighter source · ${lower}p")
        }.putExtra("maxHeight", lower).putExtra("resumeAt", player.currentPosition))
        finish()
    }

    private fun showUpNext() {
        if (type == "series" && !plausibleEpisode(player.duration)) return
        val next = nextVideo() ?: return
        if (offeringLower) dismissStall()
        main.removeCallbacks(longStall)
        if (shown) hideControls(force = true)
        options.visibility = View.GONE
        hideLoading()
        upNextPanel.removeAllViews()
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val playNow = pill("Play now", R.drawable.ic_play).apply { setOnClickListener { playNext() } }
        actions.addView(playNow, wrap())
        actions.addView(glassButton("Watch the credits") { dismissUpNext() }, wrap().apply { marginStart = dp(8) })
        val content = column(Gravity.START).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            // Video can't be frosted: a dark base under the glass keeps it readable over white end cards.
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(BASE and 0xD9FFFFFF.toInt(), 14), glass(14)))
            addView(caps("Up next", TUNGSTEN).onArt())
            // Where it sits in the season first, then its name: a long name stops at two lines rather than filling the card.
            addView(text("S${next.season} E${next.episode}", SMALL, color = TEXT_2).onArt(), wrap().apply { topMargin = dp(5) })
            addView(text(next.title.ifBlank { "Episode ${next.episode}" }, SERIF_HERO, serif = true).onArt().apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(actions, wrap().apply { topMargin = dp(16) })
        }
        upNextPanel.addView(content, FrameLayout.LayoutParams(dp(500), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        upNextPanel.visibility = View.VISIBLE
        playNow.requestFocus()
    }

    private fun dismissUpNext() {
        upNextDismissed = true
        upNextPanel.visibility = View.GONE
    }

    /** A worded glass control beside a pill, as tall as it; focus lights it white as a chip's does. */
    private fun glassButton(label: String, action: () -> Unit) = text(label, SMALL, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(BUTTON_H)
        setPadding(dp(16), 0, dp(16), 0)
        lightFocus(10)
        setOnClickListener { action() }
    }

    // The title's logo arrived (or didn't): the name stands in for it.
    private var hasLogo = false

    /** The logo or name at the top, unless the pause title is up: both say the same words, so one steps aside. */
    private fun showBrand() {
        val paused = ::pauseInfo.isInitialized && pauseInfo.visibility == View.VISIBLE
        playerLogo.visibility = if (hasLogo && !paused) View.VISIBLE else View.GONE
        topName.visibility = if (!hasLogo && !paused) View.VISIBLE else View.GONE
    }

    /** Hands the next episode to the sources page, which starts it (preferring this release's group) and finishes here. */
    private fun playNext() {
        val next = nextVideo() ?: return finish()
        val videos = meta!!.videos
        val after = videos.getOrNull(videos.indexOf(next) + 1)?.takeIf { it.season != 0 }
        val name = intent.getStringExtra("name") ?: ""
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", name)
            .putExtra("poster", intent.getStringExtra("poster")).putExtra("background", intent.getStringExtra("background"))
            .putExtra("videoId", next.id).putExtra("nextVideoId", after?.id)
            .putExtra("label", "$name  ·  S${next.season} E${next.episode}")
            .putExtra("logo", meta?.logo).putExtra("still", next.thumbnail).putExtra("runtime", meta?.runtime)
            .putExtra("episode", "S${next.season} E${next.episode}  ·  ${next.title}"))
        finish()
    }

    /**
     * Switches the TV to a refresh rate the film divides into (24 Hz for 24 fps, 50 Hz for 25), as Just Player does, so
     * pans don't judder. Same resolution only; the window gives the mode back when the player closes.
     */
    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || !Settings.matchFrameRate(this)) return
        val display = window.decorView.display ?: return
        val now = display.mode
        fun fits(m: Display.Mode) = (m.refreshRate / fps).let { r -> r.roundToInt() >= 1 && abs(r - r.roundToInt()) < 0.0005f }
        if (fits(now)) return
        val best = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight && fits(it) }
            .minByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun push() {
        if (!watched || player.isCurrentMediaItemLive) return
        val key = authKey ?: return
        val snapshot = item?.let { JSONObject(it.toString()) } ?: return
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (upNextPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    wakeKey = KeyEvent.KEYCODE_BACK
                    dismissUpNext()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (stallPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) dismissStall()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) { if (player.playWhenReady) player.pause() else player.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { showControls(bar); scrub(SKIP) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { showControls(bar); scrub(-SKIP) }; return true }
        }
        if (options.visibility == View.VISIBLE) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (!down) closeOptions()
            return true
        }
        // The OK that woke the controls: its repeats and release would otherwise press the button it focused.
        // Skip intro: OK jumps past it while the pill shows over the picture (the controls down).
        if (skip.visibility == View.VISIBLE && !shown && down && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            (skip.tag as? Segment)?.takeIf { canSeek() }?.let { player.seekTo(it.end) }
            skip.visibility = View.GONE
            wakeKey = event.keyCode
            return true
        }
        if (wakeKey != 0 && event.keyCode == wakeKey) {
            if (!down) wakeKey = 0
            return true
        }
        if (!shown && down) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(bar); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                wakeKey = event.keyCode
                if (player.playWhenReady) player.pause() else player.play()
                showControls(playPause)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
        }
        if (shown) showControls(null) // any key keeps them up a while longer
        return super.dispatchKeyEvent(event)
    }

    // ---------- skip intro, as NuvioTV does it ----------
    // IntroDB (api.introdb.app, community timings by IMDb id, season and episode; no key, nothing personal sent) says where
    // an episode's intro, recap and credits are. Inside an intro or recap a "Skip intro" pill shows for 10 s, and OK
    // jumps to its end. The credits start, when known, is when Up next appears, rather than the last 4% guess.
    // ponytail: series with IMDb ids only; anime (AniSkip by MAL id) and films' credits aren't asked for.
    private class Segment(val label: String, val start: Long, val end: Long)
    private lateinit var skip: TextView
    private var segments = emptyList<Segment>()
    private var outroAt = 0L
    private var skipShownAt = 0L
    private val skipped = mutableSetOf<Segment>() // shown once and left alone, or skipped: not offered again this time
    private val skipTask = Runnable { checkSegments() }

    private fun fetchSegments() {
        val parts = videoId.split(":")
        if (type != "series" || !metaId.startsWith("tt") || parts.size < 3) return
        background({
            val json = JSONObject(Http.get("https://api.introdb.app/segments?imdb_id=${parts[0]}&season=${parts[1]}&episode=${parts[2]}"))
            fun part(name: String) = json.optJSONObject(name)?.let { it.optDouble("start_sec", -1.0) to it.optDouble("end_sec", -1.0) }
                ?.takeIf { (a, b) -> a >= 0 && b > a }?.let { (a, b) -> (a * 1000).toLong() to (b * 1000).toLong() }
            Triple(part("intro"), part("recap"), part("outro"))
        }) { r ->
            val (intro, recap, outro) = r.getOrNull() ?: return@background
            segments = listOfNotNull(recap?.let { Segment("Skip recap", it.first, it.second) }, intro?.let { Segment("Skip intro", it.first, it.second) })
            outroAt = outro?.first ?: 0L
        }
    }

    private fun checkSegments() {
        main.removeCallbacks(skipTask)
        if (!started || player.isCurrentMediaItemLive) return run { main.postDelayed(skipTask, 500) }
        val position = player.currentPosition
        val now = segments.firstOrNull { position >= it.start && position < it.end - 1_000 && it !in skipped }
        if (now == null || shown) skip.visibility = View.GONE
        else if (skip.tag !== now || skip.visibility != View.VISIBLE) {
            if (skip.tag !== now) skipShownAt = System.currentTimeMillis()
            skip.tag = now
            skip.text = "${now.label}   OK"
            skip.visibility = View.VISIBLE
        }
        if (now != null && skip.tag === now && System.currentTimeMillis() - skipShownAt > SKIP_SHOWN) { skipped += now; skip.visibility = View.GONE }
        if (outroAt > 0 && position >= outroAt && player.duration - position < 60_000 &&
            !upNextDismissed && upNextPanel.visibility != View.VISIBLE && player.duration > 0) showUpNext()
        main.postDelayed(skipTask, 500)
    }

    // ---------- sound ----------
    private val dialogueProcessor = DialogueAudioProcessor()
    private var passthrough = false // a receiver takes Dolby and DTS untouched: no PCM chain, no Clear dialogue

    // ---------- player controls ----------
    private lateinit var controls: FrameLayout
    private lateinit var badges: LinearLayout // "4K · Dolby Vision · Atmos 5.1 · English · Real-Debrid" under the title
    private lateinit var bar: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private lateinit var playPause: TextView
    private lateinit var audioButton: TextView
    private lateinit var trackStatus: TextView // "English  ·  Spanish subtitles", under the bar whenever the controls are up
    private lateinit var nextCell: FrameLayout
    private lateinit var playerLogo: ImageView
    private lateinit var topName: TextView
    private lateinit var pauseTitle: TextView
    private lateinit var pauseInfo: LinearLayout
    private lateinit var pauseFacts: TextView
    private lateinit var subtitle: TextView // the year or S5 E2
    private val buttonLabels = mutableMapOf<View, TextView>()
    private var shown = false
    private var wakeKey = 0
    private var scrubTo = -1L
    private val hideTask = Runnable { hideControls() }
    private val progressTask = Runnable { updateProgress() }
    // "Ends 9:40 PM" is worked out twice a second while the controls are up: one format and one date, reused.
    private val endClock = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val endDate = Date()
    private val seekTask = Runnable { if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 } }

    private fun buildControls(): FrameLayout {
        val name = intent.getStringExtra("name").orEmpty()
        playerLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        topName = text(name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pauseTitle = text(intent.getStringExtra("episode")?.substringAfter("  ·  ", "")?.takeIf { it.isNotBlank() }
            ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        // What's playing, as a quiet line of small capsules under the name: the format, the sound, the words, the service.
        badges = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; alpha = 0.85f }
        subtitle = text(intent.getStringExtra("episode")?.substringBefore("  ·  ").orEmpty(), SMALL, color = TEXT_2).onArt()
        pauseFacts = text("", SMALL, color = TEXT_2).onArt()
        pauseInfo = column(Gravity.START).apply {
            addView(caps("Paused", TUNGSTEN).onArt())
            addView(pauseTitle, LinearLayout.LayoutParams(dp(480), -2).apply { topMargin = dp(6) })
            addView(pauseFacts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }

        trackStatus = text("", SMALL, color = TEXT_2).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        elapsed = text("", SMALL).onArt()
        length = text("", SMALL).onArt()
        bar = SeekBar(this).apply {
            id = View.generateViewId()
            isFocusable = true
            contentDescription = "Seek"
            setOnFocusChangeListener { _, _ -> invalidate() }
            setOnClickListener { if (player.playWhenReady) player.pause() else player.play() }
            setOnKeyListener { _, code, event ->
                val by = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_DOWN)
                    scrub(by * when { event.repeatCount < 10 -> SKIP; event.repeatCount < 30 -> 30_000L; else -> 60_000L })
                true
            }
        }

        audioButton = button("Audio & subtitles", R.drawable.ic_subtitles) { openOptions() }
        val streamButton = button("Change stream", R.drawable.ic_layers) { changeStream() }
        playPause = button("Pause", R.drawable.ic_pause, primary = true) {
            if (player.playWhenReady) player.pause() else player.play()
        }
        val nextButton = button("Next episode", R.drawable.ic_next_episode) { playNext() }
        val infoButton = button("Info", R.drawable.ic_info) {
            startActivity(Intent(this, DetailsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("type", type).putExtra("id", metaId)
                .putExtra("name", intent.getStringExtra("name"))
                .putExtra("poster", intent.getStringExtra("poster"))
                .putExtra("background", intent.getStringExtra("background")))
            finish()
        }
        nextCell = buttonCell(nextButton).apply { visibility = View.GONE }
        val buttons = LinearLayout(this).apply {
            clipChildren = false // a focused button's label is wider than its cell
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            addView(buttonCell(audioButton))
            addView(buttonCell(streamButton))
            addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(9); marginEnd = dp(10) })
            addView(buttonCell(playPause, true))
            addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(9); marginStart = dp(10) })
            addView(nextCell)
            addView(buttonCell(infoButton))
        }
        for (button in listOf(audioButton, streamButton, playPause, nextButton, infoButton))
            button.nextFocusUpId = bar.id
        playPause.id = View.generateViewId()
        bar.nextFocusDownId = playPause.id

        return FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(withAlpha(room, 0x99), withAlpha(room, 0)))
            }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(withAlpha(room, 0xE6), withAlpha(room, 0xA6), withAlpha(room, 0)))
            }, FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM))
            addView(playerLogo, FrameLayout.LayoutParams(dp(150), dp(50)).apply {
                leftMargin = dp(PAD); topMargin = dp(26)
            })
            addView(topName, FrameLayout.LayoutParams(dp(250), -2).apply {
                leftMargin = dp(PAD); topMargin = dp(31)
            })
            addView(badges, FrameLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(PAD); topMargin = dp(84)
            })
            addView(TextClock(this@PlayerActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                textSize = SMALL; setTextColor(TEXT_2); typeface = text("", SMALL).typeface
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
            // The bottom group sits clear of a TV's overscan: the buttons 24 dp up, everything above them keeping its distance.
            addView(pauseInfo, FrameLayout.LayoutParams(dp(520), -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(161)
            })
            addView(bar, FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM).apply {
                leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(124)
            })
            addView(elapsed, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(104)
            })
            addView(length, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(104)
            })
            addView(trackStatus, FrameLayout.LayoutParams(dp(420), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(104)
            })
            trackStatus.gravity = Gravity.CENTER
            addView(buttons, FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM).apply {
                bottomMargin = dp(24)
            })
            addView(column(Gravity.END, keyHint("OK", "Play / pause"), keyHint("◀ ▶", "Seek on the bar")), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(48)
            })
        }
    }

    private fun button(label: String, res: Int, primary: Boolean = false, onClick: () -> Unit): TextView {
        val words = text(label, SMALL, color = TEXT_2).onArt().apply {
            gravity = Gravity.CENTER; visibility = View.INVISIBLE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val radius = if (primary) 25 else 20
        val control = text("", BODY).apply {
            gravity = Gravity.CENTER
            contentDescription = label
            isFocusable = true
            foreground = centred(res, if (primary) 25 else 20, if (primary) BASE else TEXT)
            if (primary) {
                fun face(focused: Boolean) = GradientDrawable().apply {
                    setColor(TEXT); cornerRadius = dp(radius).toFloat()
                }
                background = face(false)
                setOnFocusChangeListener { view, focused ->
                    view.background = face(focused)
                    view.grow(focused).start()
                    words.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                }
            } else {
                // Lit as a chip is: a white face and a dark glyph. The row fill (0x38 white) read dimmer than the glass beside it.
                background = glass(radius)
                setOnFocusChangeListener { view, focused ->
                    view.background = if (focused) rounded(TEXT, radius) else glass(radius)
                    view.foreground = centred(res, 20, if (focused) BASE else TEXT)
                    words.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                }
            }
            setOnClickListener { onClick() }
        }
        buttonLabels[control] = words
        return control
    }

    private fun buttonCell(control: TextView, primary: Boolean = false) = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        addView(control, FrameLayout.LayoutParams(dp(if (primary) 50 else 40), dp(if (primary) 50 else 40),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(buttonLabels.getValue(control), FrameLayout.LayoutParams(dp(160), dp(18),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(if (primary) 53 else 48) })
        layoutParams = LinearLayout.LayoutParams(dp(if (primary) 70 else 62), dp(76))
    }

    /** Brings the controls up with [focus] focused (null: leaves focus where it is) and restarts the time to hide. */
    private fun showControls(focus: View? = playPause) {
        main.removeCallbacks(hideTask)
        if (!shown) {
            shown = true
            view.subtitleView?.setBottomPaddingFraction(0.28f) // keep captions above the seek bar and buttons
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(150).start()
            updateProgress()
            updateBadges()
            (focus ?: playPause).requestFocus()
        } else focus?.requestFocus()
        if (player.playWhenReady) main.postDelayed(hideTask, HIDE)
    }

    private fun hideControls(force: Boolean = false) {
        if (!force && !player.playWhenReady) return
        shown = false
        main.removeCallbacks(hideTask)
        main.removeCallbacks(progressTask)
        controls.animate().alpha(0f).setDuration(250).withEndAction {
            if (!shown) {
                controls.visibility = View.GONE
                view.subtitleView?.setBottomPaddingFraction(0.08f)
            }
        }.start()
    }

    private fun updateProgress() {
        main.removeCallbacks(progressTask)
        if (!shown && options.visibility != View.VISIBLE) return
        val live = player.isCurrentMediaItemLive
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (scrubTo >= 0) scrubTo else player.currentPosition
        if (shown) {
            bar.set(position, player.bufferedPosition, if (live) 0L else duration)
            elapsed.text = if (live) "Live" else clock(position)
            length.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
        }
        if (options.visibility == View.VISIBLE) {
            optionsBar.set(position, player.bufferedPosition, if (live) 0L else duration)
            optionsElapsed.text = if (live) "Live" else clock(position)
            optionsLeft.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
        }
        val remaining = (duration - position).coerceAtLeast(0)
        endDate.time = System.currentTimeMillis() + remaining
        val end = endClock.format(endDate)
        pauseFacts.text = listOfNotNull(subtitle.text.toString().takeIf { it.isNotBlank() },
            if (live || duration <= 0) null else runtime("${(remaining / 60_000).coerceAtLeast(1)} min") + " left",
            if (live || duration <= 0) null else "Ends $end").joinToString(DOT)
        main.postDelayed(progressTask, 500)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Moves the playing point by [by] ms; the seek itself waits until the presses stop (SEEK_SETTLE). */
    private fun scrub(by: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (player.isCurrentMediaItemLive || !canSeek()) return
        scrubTo = ((if (scrubTo >= 0) scrubTo else player.currentPosition) + by).coerceIn(0, maxOf(0, duration - 1_000))
        updateProgress()
        main.removeCallbacks(seekTask)
        main.postDelayed(seekTask, SEEK_SETTLE)
    }

    /** Whether this file can jump to another second. A seek in one that can't (no index, even by bitrate) lands on
     *  0:00, so it's refused with a word instead; once per source. */
    private fun canSeek(): Boolean {
        if (player.isCurrentMediaItemSeekable) return true
        if (!toldUnseekable) {
            toldUnseekable = true
            android.widget.Toast.makeText(this, "This source can't skip ahead · Change stream to try another", android.widget.Toast.LENGTH_LONG).show()
        }
        return false
    }
    private var toldUnseekable = false

    /** The source list for this video, from this second; the player closes, and a pick starts a new one. */
    private fun changeStream() {
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("maxHeight")
            putExtra("pick", true)
            if (!player.isCurrentMediaItemLive) putExtra("resumeAt", player.currentPosition)
        })
        finish()
    }

    // ---------- playback options, after design/player-options-cinematic-minimal-glass.png ----------
    // A panel down the left over a darkening: the audio tracks, the subtitles (Off first), and Change stream with the
    // source playing. The chosen track has a tick; OK on another switches to it there and then. Back closes it.
    private lateinit var options: FrameLayout
    private lateinit var optionsBar: SeekBar
    private lateinit var optionsElapsed: TextView
    private lateinit var optionsLeft: TextView

    private fun openOptions() {
        hideControls(force = true)
        controls.visibility = View.GONE
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; focusByLight() }
        list.addView(text("Audio & subtitles", SERIF_TITLE, serif = true), wrap())
        list.addView(text(listOfNotNull(pauseTitle.text.toString().takeIf { it.isNotBlank() },
            subtitle.text.toString().takeIf { it.isNotBlank() }).joinToString(DOT),
            SMALL, color = TEXT_2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, wrap().apply { topMargin = dp(5) })

        val audio = audioGroups.filter { it.isTrackSupported(0) }
        if (audio.isNotEmpty()) section(list, "Audio", audio.map { trackName(it.getTrackFormat(0), true) }, audio.indexOfFirst { it.isSelected }, closes = true) { i ->
            audioPicked = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(audio[i].mediaTrackGroup, (0 until audio[i].length).toList())).build()
            Languages.code(audio[i].getTrackFormat(0).language)?.let { wantAudio = it; Settings.pickedInPlayer(this, metaId, audio = it) }
        }
        val texts = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
        val subs = texts.filterNot(::isAddonSub)
        val loaded = texts.firstOrNull(::isAddonSub) // the addon file attached now, if any
        val chosen = when {
            C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> 0
            loaded?.isSelected == true -> 1 + subs.size + addonSubs.indexOf(chosenSub)
            else -> subs.indexOfFirst { it.isSelected } + 1
        }
        val subsFirst = section(list, "Subtitles", listOf("Off") + subs.map { trackName(it.getTrackFormat(0), false) + "  ·  in the file" } +
            addonSubs.map { "${it.language}  ·  ${it.addon}" }, chosen, closes = true) { i ->
            val p = player.trackSelectionParameters.buildUpon()
            val sub = addonSubs.getOrNull(i - 1 - subs.size)
            remember(if (i == 0) "" else sub?.lang ?: subs[i - 1].getTrackFormat(0).language, addonFile = sub != null)
            player.trackSelectionParameters = when {
                i == 0 -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                sub == null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(subs[i - 1].mediaTrackGroup, 0)).build()
                sub == chosenSub && loaded != null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(loaded.mediaTrackGroup, 0)).build()
                else -> { chosenSub = sub; subDelay = 0; subFile = null; selectAddonSub = true; load(position()); return@section }
            }
        }
        val shiftable = chosenSub?.takeIf { loaded?.isSelected == true && it.mimeType != MimeTypes.TEXT_SSA }
        if (shiftable != null) {
            val steps = listOf(-2_000L, -1_000L, -500L, 0L, 500L, 1_000L, 2_000L)
            section(list, "Subtitle timing", steps.map { if (it == 0L) "On time" else "%+.1f s".format(it / 1000f) + if (it < 0) "  earlier" else "  later" },
                steps.indexOf(subDelay)) { i -> shiftSubtitles(shiftable, steps[i]) }
        }
        // Clear dialogue, where the sound is chosen; not offered with a receiver, which gets the sound undecoded.
        if (!passthrough) section(list, "Sound", listOf("Normal", "Clear dialogue  ·  voices lifted"), if (dialogueProcessor.enabled) 1 else 0, closes = true) { i ->
            dialogueProcessor.enabled = i == 1
            Settings.setClearDialogue(this, i == 1)
        }
        list.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(18), 0, dp(10)) })
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val head = text("Change stream  →", BODY, bold = true).apply { setPadding(dp(8), dp(5), dp(8), dp(5)) }
            addView(head, wrap())
            // "2160p · Torrentio" as the mock reads it: what you're watching first, where it comes from last.
            val label = sources[at].label.split(DOT.trim()).map { it.trim() }.filter { it.isNotEmpty() }
            addView(text((label.drop(1) + label.take(1)).joinToString(DOT), SMALL, color = TEXT_3).apply { setPadding(dp(8), 0, 0, 0) }, wrap())
            setPadding(0, 0, 0, dp(6))
            rowFocus(8)
            setOnClickListener { changeStream() }
        }, LinearLayout.LayoutParams(-1, -2))

        options.removeAllViews()
        options.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
            setPadding(dp(22), dp(20), dp(22), dp(22))
            clipToPadding = false
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(withAlpha(room, 0xF0), 14), glass(14)))
        }, FrameLayout.LayoutParams(dp(310), resources.displayMetrics.heightPixels - dp(64)).apply {
            leftMargin = dp(32); topMargin = dp(32)
        })
        optionsBar = SeekBar(this).apply { isFocusable = false }
        optionsElapsed = text("", SMALL, color = TEXT_2).onArt()
        optionsLeft = text("", SMALL, color = TEXT_2).onArt()
        options.addView(optionsBar, FrameLayout.LayoutParams(dp(280), dp(12), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        options.addView(optionsElapsed, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD + 246); bottomMargin = dp(32)
        })
        options.addView(optionsLeft, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(32)
        })
        options.visibility = View.VISIBLE
        updateProgress()
        subsFirst.requestFocus()
    }

    /** Rows stay on clear glass; a tungsten rim and tick carry focus and selection. */
    /** [closes]: a choice made here is the end of the visit; the panel goes once the dot has moved. */
    private fun section(list: LinearLayout, heading: String, labels: List<String>, chosen: Int, closes: Boolean = false, pick: (Int) -> Unit): View {
        // The heading names the choice in use ("SUBTITLES · SPANISH"), so it reads before any scrolling.
        fun headingFor(index: Int) = (listOf(heading) + listOfNotNull(labels.getOrNull(index)?.substringBefore("  ·  "))).joinToString(" · ")
        val title = caps(headingFor(chosen), TUNGSTEN)
        list.addView(title, wrap().apply {
            topMargin = dp(18); bottomMargin = dp(5)
        })
        val checks = mutableListOf<View>()
        val names = mutableListOf<TextView>()
        val rows = labels.mapIndexed { index, label ->
            val name = text(label.substringBefore("  ·  "), BODY, color = TEXT_2).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            names += name
            val tag = text(label.substringAfter("  ·  ", ""), SMALL, color = TEXT_3).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            // The choice in use: the tungsten dot before its name, as on chips and filters, and its name at full strength.
            val check = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TUNGSTEN) }
                visibility = if (index == chosen) View.VISIBLE else View.INVISIBLE
            }
            checks += check
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), 0, dp(9), 0)
                addView(check, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
                addView(name)
                if (tag.text.isNotEmpty()) addView(tag, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(7)
                })
                else addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                rowFocus(8) { has ->
                    name.setTextColor(if (has || check.visibility == View.VISIBLE) TEXT else TEXT_2)
                }
                setOnClickListener {
                    pick(index)
                    checks.forEachIndexed { i, icon -> icon.visibility = if (i == index) View.VISIBLE else View.INVISIBLE }
                    title.text = headingFor(index).uppercase()
                    names.forEachIndexed { i, word -> word.setTextColor(if (i == index) TEXT else TEXT_2) }
                    if (closes) main.postDelayed({ if (options.visibility == View.VISIBLE) closeOptions() }, PICK_SEEN)
                }
            }.also { list.addView(it, LinearLayout.LayoutParams(-1, dp(32))) }
        }
        return rows[chosen.coerceIn(0, rows.lastIndex)]
    }

    private fun closeOptions() {
        options.visibility = View.GONE
        showControls(audioButton)
    }

    /** What's playing, in a few words: "English  ·  Spanish subtitles", or "English  ·  Subtitles off". */
    private fun trackStatus(tracks: Tracks): String {
        fun name(language: String?) = Languages.code(language)?.let(Languages::name)
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)?.language
        val text = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val subtitles = when {
            text == null || C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> "Subtitles off"
            else -> name(if (isAddonSub(text)) chosenSub?.lang else text.getTrackFormat(0).language)?.let { "$it subtitles" } ?: "Subtitles on"
        }
        return listOfNotNull(name(audio), subtitles).joinToString(DOT)
    }

    /** "English · Dolby Digital 5.1": the language, then the release's own name for the track or, for audio, its format. */
    /** Re-attaches [sub] with every time moved by [delay] ms (later when positive), from the same second. */
    private fun shiftSubtitles(sub: Subtitle, delay: Long) {
        subDelay = delay
        background({
            if (delay == 0L) return@background null
            val times = Regex("""(\d{2}):(\d{2}):(\d{2})([,.])(\d{3})""")
            val moved = times.replace(Http.get(sub.url)) { m ->
                val (h, mi, se, sep, ms) = m.destructured
                val t = maxOf(0L, ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + ms.toLong() + delay)
                "%02d:%02d:%02d%s%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
            }
            java.io.File(cacheDir, "subtitle." + if (sub.mimeType == MimeTypes.TEXT_VTT) "vtt" else "srt").apply { writeText(moved) }
        }) { r ->
            subFile = r.getOrNull()
            selectAddonSub = true
            // Attaching the file reloads the source: a title the viewer paused meanwhile stays paused (upstream fix).
            val playing = player.playWhenReady
            load(position())
            player.playWhenReady = playing
        }
    }

    /** The capsules under the name, from the tracks playing now and the source's own words (Dolby Vision, the debrid). */
    private fun updateBadges() {
        if (!::badges.isInitialized) return
        val tracks = player.currentTracks
        val video = player.videoFormat
        val audio = player.audioFormat
        val subOff = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        val sub = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.takeIf { !subOff }
        val subName = sub?.let { g -> if (isAddonSub(g)) chosenSub?.language else Languages.code(g.getTrackFormat(0).language)?.let(Languages::name) }
        val source = sources.getOrNull(at)
        val wanted = playerBadges(video?.height ?: 0, audio?.sampleMimeType, audio?.channelCount ?: 0,
            Languages.code(audio?.language)?.let(Languages::name), source?.dolbyVision == true, source?.hdr == true, subName, source?.debrid?.name)
        if (wanted == badges.tag) return
        badges.tag = wanted
        badges.removeAllViews()
        wanted.forEachIndexed { i, label ->
            badges.addView(caps(label, TEXT_2).onArt().apply {
                background = glass(7)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
        }
    }

    private fun isAddonSub(g: Tracks.Group) = g.type == C.TRACK_TYPE_TEXT && g.getTrackFormat(0).id?.contains(ADDON_SUB) == true

    private fun trackName(f: Format, audio: Boolean): String {
        val language = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }?.ifBlank { null }
        val format = if (!audio) null else listOfNotNull(when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            else -> null
        }, when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE -> null; else -> "${f.channelCount} ch" })
            .joinToString(" ").ifEmpty { null }
        val label = f.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
        return listOfNotNull(language, label ?: format).joinToString("  ·  ").ifEmpty { "Track ${f.id ?: ""}".trim() }
    }

    override fun onStop() {
        super.onStop()
        if (!::status.isInitialized) return
        main.removeCallbacks(tickTask)
        main.removeCallbacks(progressTask)
        main.removeCallbacks(skipTask)
        main.removeCallbacks(loadingTask)
        player.pause()
        if (!watched || player.isCurrentMediaItemLive || type == "series" && !plausibleEpisode(player.duration)) return
        record()
        // Finished (past the credits) but not ticked yet, because the episode list came late or the viewer skipped
        // through: the tick goes in now, so the guide, the blur and Home's NEW count agree with what was watched.
        item?.let { it -> meta?.videos?.takeIf { v -> !tickedWatched && v.isNotEmpty() && Library.pastCredits(it) }?.let { v -> tickedWatched = true; Library.markWatched(it, v, videoId) } }
        // The player's own episode list first: Home's Resume passes no next episode when the title's details weren't in
        // yet, and a finished episode with no next one drops the title out of Continue Watching.
        item?.let { Library.stopped(it, nextVideo()?.id ?: intent.getStringExtra("nextVideoId")) }
        push()
    }

    override fun onDestroy() {
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(loadingTask)
        main.removeCallbacks(longStall)
        main.removeCallbacks(hideNote)
        if (::status.isInitialized) player.release()
        super.onDestroy()
    }

    private companion object {
        const val TICK = 10_000L
        const val RESUME_SLACK = 10_000L // how far apart the saved and fresh resume points may be before it jumps
        const val SKIP = 10_000L // ms a Left/Right press on the bar moves
        const val SEEK_SETTLE = 600L // ms after the last skip before seeking
        const val SKIP_SHOWN = 10_000L // ms the Skip intro pill stays, as in NuvioTV
        const val HIDE = 5_000L // ms the controls stay up while playing
        const val PICK_SEEN = 320L // ms the moved dot shows before the Audio & subtitles panel closes on its own
        const val START_WAIT = 12_000L // ms for a source to show a picture before the next is tried (4K DV from debrid took 6 s)
        const val ADDON_SUB = "addon-subtitle" // the id an addon subtitle file is attached under
        const val SUB_WAIT = 2_000L // ms the start waits for the addon's subtitles
        const val RETRIES = 2
        const val RETRY_WAIT = 1_500L
        const val LONG_STALL = 20_000L // ms of buffering, mid-play, that counts as a source failing
        const val AUTO_SWITCHES = 2 // lighter files the player goes to by itself before it asks
        const val NOTE_SHOWN = 3_000L // ms the "Switched to…" word stays
    }
}

/** Tungsten playback progress over a dark track, with a warm-white knob on focus. */
private class SeekBar(context: Context) : View(context) {
    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun set(position: Long, buffered: Long, duration: Long) {
        this.position = position
        this.buffered = buffered
        this.duration = duration
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val knob = (if (isFocused) 9 else 6) * d
        val line = (if (isFocused) 4 else 3) * d
        val y = height / 2f
        fun x(t: Long) = knob + (width - 2 * knob) * (if (duration > 0) t.coerceIn(0, duration) / duration.toFloat() else 0f)
        fun stretch(to: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(knob, y - line / 2, to, y + line / 2, line / 2, line / 2, paint)
        }
        // A light track: the dark one vanished over a dark picture.
        stretch(width - knob, 0x40F5F5F7)
        stretch(x(buffered), 0x73F5F5F7)
        paint.setShadowLayer(4 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
        stretch(x(position), Screen.TUNGSTEN)
        paint.clearShadowLayer()
        paint.color = Screen.TEXT
        if (duration > 0) canvas.drawCircle(x(position), y, knob, paint)
    }
}

/**
 * The player's format badges, in reading order: picture (4K, Dolby Vision or HDR), sound (codec and layout), the
 * audio language, the subtitles on, and the debrid service the file comes through. Each only when known; a plain
 * stereo AAC web file in English with nothing else reads "1080p · AAC · English".
 */
internal fun playerBadges(
    height: Int, audioMime: String?, channels: Int, audioLanguage: String?,
    dolbyVision: Boolean, hdr: Boolean, subtitles: String?, debrid: String?,
): List<String> {
    val out = mutableListOf<String>()
    when {
        height >= 2160 -> out += "4K"
        height >= 1440 -> out += "1440p"
        height >= 1080 -> out += "1080p"
        height >= 720 -> out += "720p"
        height >= 480 -> out += "${height}p"
    }
    if (dolbyVision) out += "Dolby Vision" else if (hdr) out += "HDR"
    val codec = when (audioMime) {
        "audio/eac3-joc" -> "Atmos"
        "audio/true-hd" -> "TrueHD"
        "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/vnd.dts" -> "DTS"
        "audio/eac3" -> "DD+"
        "audio/ac3" -> "DD"
        "audio/mp4a-latm" -> "AAC"
        else -> null
    }
    val layout = when (channels) { 6 -> "5.1"; 8 -> "7.1"; else -> null }
    listOfNotNull(codec, layout).joinToString(" ").takeIf { it.isNotEmpty() }?.let { out += it }
    audioLanguage?.takeIf { it.isNotBlank() }?.let { out += it }
    subtitles?.takeIf { it.isNotBlank() }?.let { out += "$it subtitles" }
    debrid?.takeIf { it.isNotBlank() }?.let { out += it }
    return out
}
