package dev.alder

import android.content.Context
import android.app.ActivityManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Choices kept on this TV. Most are shared by every profile (they're about the TV and the sound system); taste (release
 * type, dubs and audio language) is kept per profile under "key@profileId".
 */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun maxHeight(c: Context) = prefs(c).getInt("maxHeight", 0) // 0: no limit
    fun maxSizeGb(c: Context) = prefs(c).getInt("maxSizeGb", 0) // 0: no limit
    /** "": as the addon orders them, "dv" or "hdr10": that first, "off": plain releases only. */
    fun hdrPref(c: Context): String {
        val p = prefs(c)
        if (!p.contains("hdrPref")) p.edit().putString("hdrPref", if (p.getBoolean("hdr", true)) "" else "off").remove("hdr").apply() // was On/Off
        return p.getString("hdrPref", "").orEmpty()
    }
    fun hdr(c: Context) = hdrPref(c) != "off"
    /** "": no preference, "remux", "encode" (smaller BluRay and web encodes) or "web". */
    fun release(c: Context) = prefs(c).getString(profileKey(c, "release"), "").orEmpty()
    /** "": no preference, "original": dubs last, "mine": the audio language first. */
    fun dub(c: Context) = prefs(c).getString(profileKey(c, "dub"), "").orEmpty()
    fun profileKey(c: Context, key: String) = "$key@${Profiles.active(c)?.id}"
    /** Settings › Title page: each extra on a title's page is on unless turned off. Keys: TITLE_* in SettingsActivity. */
    fun titlePage(c: Context, key: String) = prefs(c).getBoolean(key, true)

    /**
     * Whether this profile sees the whole of CAS. Off, the default (Alan, 2026-11-10: CAS is for his parents first):
     * Home, TV, Movies and Search, short pages, a handful of settings. On: every page, every row, every setting.
     */
    fun everything(c: Context) = prefs(c).getBoolean(profileKey(c, "everything"), false)
    /** Light (the default) or dark, each profile's own. */
    fun lightMode(c: Context) = prefs(c).getBoolean(profileKey(c, "light"), true)
    fun setLightMode(c: Context, light: Boolean) = prefs(c).edit().putBoolean(profileKey(c, "light"), light).apply()
    /** The profile's text size, a multiple of the TV's own: 1 standard, [LARGE_TEXT] large. */
    fun textScale(c: Context) = prefs(c).getFloat(profileKey(c, "textScale"), 1f)
    fun setTextScale(c: Context, scale: Float) = prefs(c).edit().putFloat(profileKey(c, "textScale"), scale).apply()
    const val LARGE_TEXT = 1.15f
    fun setEverything(c: Context, on: Boolean) = prefs(c).edit().putBoolean(profileKey(c, "everything"), on).apply()
    /** The Foreign page's country, by ISO code (JW_COUNTRIES); the last one chosen on its chips. */
    fun foreignCountry(c: Context): String = prefs(c).getString("foreignCountry", null) ?: "ALL"
    /** Chosen: kept, and (a country, not the World) put first among the three most recent for the chips. */
    fun setForeignCountry(c: Context, code: String) {
        val recent = if (code == "ALL") foreignRecent(c) else (listOf(code) + foreignRecent(c).filter { it != code }).take(3)
        prefs(c).edit().putString("foreignCountry", code).putString("foreignRecent", recent.joinToString(",")).apply()
    }
    fun foreignRecent(c: Context): List<String> = prefs(c).getString("foreignRecent", "").orEmpty().split(',').filter { it.isNotEmpty() }
    /** Settings › Picture & sound › Screensaver: Home's cinema after IDLE without a key. */
    fun homeCinema(c: Context) = prefs(c).getBoolean("homeCinema", true)
    /** Where a trailer plays: "browser" (Silk or whatever browser), "app" (the YouTube app) or "box". Fire TV's engine plays sound but no picture in the box, however the window is arranged, so Fire TV starts on Silk. */
    fun trailerPlayer(c: Context): String = prefs(c).getString(SettingsActivity.TRAILER_PLAYER, null) ?: defaultTrailerPlayer()
    fun defaultTrailerPlayer() = if (android.os.Build.MANUFACTURER.equals("Amazon", true)) "browser" else "box"
    fun setTrailerPlayer(c: Context, value: String) = prefs(c).edit().putString(SettingsActivity.TRAILER_PLAYER, value).apply()
    /**
     * Titles this profile hid from Continue Watching on this TV: id to {name, timeOffset}, so Settings can put them
     * back. ponytail: this TV only; titles hidden in Stremio elsewhere aren't listed.
     */
    fun hidden(c: Context) = runCatching { org.json.JSONObject(prefs(c).getString(profileKey(c, "hidden"), null)!!) }
        .getOrDefault(org.json.JSONObject())
    fun setHidden(c: Context, all: org.json.JSONObject) = prefs(c).edit().putString(profileKey(c, "hidden"), all.toString()).apply()
    /** A removed profile's taste goes with it, so a new profile in its place starts with none. */
    fun forget(c: Context, profileId: String) = prefs(c).edit().apply {
        listOf("release", "dub", "audioLang", "subLang", "hidden", "hiddenRows").forEach { remove("$it@$profileId") }
        prefs(c).all.keys.filter { it.startsWith("title:") && it.endsWith("@$profileId") }.forEach(::remove)
    }.apply()

    /**
     * What the viewer picked in the player's Audio & subtitles for one title (a series' episodes share it), so the next
     * episode and a resume start the same way. Language codes; subtitles "" is Off; null where nothing was picked.
     */
    class TitleChoice(val audio: String?, val subtitles: String?)

    fun titleChoice(c: Context, metaId: String): TitleChoice? = prefs(c).getString(profileKey(c, "title:$metaId"), null)
        ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        ?.let { TitleChoice(it.text("audio"), if (it.has("subtitles")) it.optString("subtitles") else null) }

    fun pickedInPlayer(c: Context, metaId: String, audio: String? = null, subtitles: String? = null) {
        val key = profileKey(c, "title:$metaId")
        val json = prefs(c).getString(key, null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()
        audio?.let { json.put("audio", it) }
        subtitles?.let { json.put("subtitles", it) }
        prefs(c).edit().putString(key, json.toString()).apply()
    }
    fun alwaysList(c: Context) = prefs(c).getBoolean("alwaysList", false)
    fun matchFrameRate(c: Context) = prefs(c).getBoolean("matchFrameRate", true)
    fun passthrough(c: Context) = prefs(c).getBoolean("passthrough", false)
    /** When subtitles start with the video: "foreign" (the sound is not in your language), "always" or "off". */
    fun subtitleMode(c: Context): String {
        val p = prefs(c)
        p.getString("subtitlesWhen", null)?.let { return it }
        // Before 1.37.0 the setting was on or off: off stays off, and on becomes the new default, which follows the sound.
        val mode = if (p.contains("subtitles") && !p.getBoolean("subtitles", true)) "off" else "foreign"
        p.edit().putString("subtitlesWhen", mode).apply()
        return mode
    }
    /** Two-letter code of the dub to start with: the TV's language unless one is picked. */
    fun audioLanguage(c: Context): String = prefs(c).getString(profileKey(c, "audioLang"), prefs(c).getString("audioLang", ""))
        .orEmpty().ifEmpty { Languages.code(Locale.getDefault().toLanguageTag()) ?: "en" }
    /** Two-letter code of the subtitles to start with: the TV's language unless one is picked. */
    fun subtitleLanguage(c: Context): String = prefs(c).getString(profileKey(c, "subLang"), "")
        .orEmpty().ifEmpty { Languages.code(Locale.getDefault().toLanguageTag()) ?: "en" }
    fun subtitleSize(c: Context) = prefs(c).getInt("subSize", 100) // percent of Media3's default size
    fun subtitleBackground(c: Context) = prefs(c).getBoolean("subBackground", true)
    /** Voices lifted above music and effects in the player (DialogueAudioProcessor); off with a receiver (passthrough). */
    fun clearDialogue(c: Context) = prefs(c).getBoolean("clearDialogue", false)
    fun setClearDialogue(c: Context, on: Boolean) = prefs(c).edit().putBoolean("clearDialogue", on).apply()
    /** Rows the viewer hid (hold OK on a title → Hide row), as "<page>|<row name>", per profile. */
    fun hiddenRows(c: Context): Set<String> = runCatching { org.json.JSONArray(prefs(c).getString(profileKey(c, "hiddenRows"), "[]")).strings().toSet() }.getOrDefault(emptySet())
    fun setHiddenRows(c: Context, rows: Set<String>) = prefs(c).edit().putString(profileKey(c, "hiddenRows"), org.json.JSONArray(rows.toList()).toString()).apply()
    /** The viewer's own order of a page's rows, by row name (hold OK › Move row up/down); rows not named follow, in their own order. */
    fun rowOrder(c: Context, page: String): List<String> = runCatching { org.json.JSONArray(prefs(c).getString(profileKey(c, "rowOrder:$page"), "[]")).strings() }.getOrDefault(emptyList())
    fun setRowOrder(c: Context, page: String, order: List<String>) = prefs(c).edit().putString(profileKey(c, "rowOrder:$page"), org.json.JSONArray(order).toString()).apply()
    /** A source that keeps stalling: the player switches to a lighter one by itself ("auto"), or asks first ("ask"). */
    fun stallSwitch(c: Context): String = prefs(c).getString("stallSwitch", "auto") ?: "auto"
    /** New versions installed by CAS itself while the TV sits on the screensaver, with nothing to press. */
    fun autoUpdate(c: Context) = prefs(c).getBoolean("autoUpdate", true)
    /** Episode stills of unwatched episodes blurred in the guide, against spoilers. */
    fun blurThumbnails(c: Context) = prefs(c).getBoolean("blurThumbnails", false)
}

/**
 * Every source writes languages its own way: the TV "es", a file's track "spa" or "es-MX", OpenSubtitles "spa", "ger"
 * or "pob", some add-ons "Spanish". [code] turns any of them into one code, so all comparisons go through it: two
 * letters, except Brazilian Portuguese ("pt-BR"), which OpenSubtitles keeps apart from Portugal's.
 */
object Languages {
    const val BRAZIL = "pt-BR"
    // ISO 639-2/B codes (OpenSubtitles sends these) where they differ from 639-2/T, OpenSubtitles' own, and Java's old ones.
    private val aliases = mapOf("alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
        "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
        "may" to "ms", "per" to "fa", "spl" to "es", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy", "pom" to "pt",
        "scc" to "sr", "scr" to "hr", "ze" to "zh", "zht" to "zh", "zhe" to "zh", "iw" to "he", "in" to "id", "ji" to "yi")
    private val brazilian = setOf("pob", "pb", "ptbr", "brazilian", "brazilian portuguese", "portuguese (brazil)",
        "portuguese (brazilian)", "portuguese (br)")
    private val iso by lazy { Locale.getISOLanguages().map { aliases[it] ?: it }.distinct() }
    private val threeLetter by lazy { iso.associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) } }
    private val englishNames by lazy { iso.associateBy { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase() } }
    private val common = listOf("en", "es", "fr", "de", "pt", BRAZIL, "it", "hi", "ar", "ja", "ko", "zh", "ru")

    /** The code ("es", "pt-BR"), or null for none or unknown ("und"). */
    fun code(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.replace('_', '-')?.ifEmpty { null } ?: return null
        if (s in brazilian) return BRAZIL
        val parts = s.split('-')
        val base = parts[0].let { aliases[it] ?: when (it.length) {
            2 -> it.takeIf { it in iso }
            3 -> threeLetter[it]
            else -> englishNames[it]
        } } ?: return null
        return if (base == "pt" && parts.getOrNull(1) == "br") BRAZIL else base
    }
    /** "pt-BR" -> "pt". */
    fun base(code: String) = code.substringBefore('-')
    fun same(a: String?, b: String?) = code(a).let { it != null && it == code(b) }
    /** 2 for the same language, 1 for the same language from another country (Portugal's for Brazil's), else 0. */
    fun match(a: String?, b: String?): Int {
        val x = code(a) ?: return 0
        val y = code(b) ?: return 0
        return if (x == y) 2 else if (base(x) == base(y)) 1 else 0
    }
    /** "Spanish", "Portuguese (Brazil)", in the TV's language. */
    fun name(raw: String): String = (code(raw) ?: raw).let { c ->
        val l = Locale.forLanguageTag(c)
        (if (l.country.isEmpty()) l.getDisplayLanguage(Locale.getDefault()) else l.getDisplayName(Locale.getDefault()))
            .ifBlank { c }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    /** Every language, the common ones first, the rest by name. */
    val all: List<String> by lazy { common + iso.filter { it !in common && name(it) != it }.sortedBy(::name) }
}

internal fun nextSettingIndex(current: Int, size: Int, direction: Int): Int {
    require(size > 0 && direction in -1..1 && direction != 0)
    return if (current < 0) if (direction < 0) size - 1 else 0 else (current + direction + size) % size
}

/** Whether a stream add-on's address carries a Real-Debrid key (Torrentio and its kin write "realdebrid=…" into it). */
internal fun carriesRealDebrid(transportUrl: String) = transportUrl.lowercase().let { "realdebrid" in it || "real-debrid" in it }

class SettingsActivity : Screen() {
    private class Choice(val key: String, val label: String, val hint: String, val values: List<Pair<Any, String>>, val perProfile: Boolean = false) {
        init { require(values.isNotEmpty() && values.all { it.first.javaClass == values[0].first.javaClass }) }
    }
    private enum class Group(val label: String, val icon: Int = 0, val glyph: String = "") {
        STREAMS("Streams", R.drawable.ic_play),
        PICTURE("Picture & sound", R.drawable.ic_tv),
        SUBTITLES("Subtitles", R.drawable.ic_subtitles),
        TITLE("Title page", R.drawable.ic_article),
        LISTS("Lists", R.drawable.ic_list),
        ADDONS("Add-ons", R.drawable.ic_layers),
        PROFILE("Profile", R.drawable.ic_person),
        LANGUAGE("Language", R.drawable.ic_language),
        STORAGE("Storage", R.drawable.ic_storage),
        ABOUT("About", R.drawable.ic_info),
    }

    private val streaming = listOf(
        Choice("alwaysList", "When I press Play", "What Play and Resume do. Holding OK always shows every source.",
            listOf(false to "Best source", true to "Show sources")),
        Choice("maxHeight", "Highest quality", "Releases above this are skipped when picking a source.",
            listOf(0 to "No limit", 2160 to "4K", 1080 to "1080p", 720 to "720p")),
        Choice("maxSizeGb", "Largest file", "For slow connections: big files take longer to start.",
            listOf(0 to "No limit", 40 to "40 GB", 20 to "20 GB", 10 to "10 GB", 5 to "5 GB")),
        Choice("release", "Release type", "Which kind of file is tried first for this profile. REMUX is the full Blu-ray.",
            listOf("" to "No preference", "remux" to "Full Blu-ray (REMUX)", "encode" to "Smaller encodes", "web" to "Web releases"), true),
    )
    private val onOff = listOf(true to "On", false to "Off")
    private val titlePage = listOf(
        Choice(TITLE_TRAILER, "Trailer button", "The title's trailer, in a box over the page.", onOff),
        Choice(TITLE_PREVIEW, "Preview button", "Plays a minute of the title itself in a box over the page, from a little way in, through the same sources as Play.", onOff),
        Choice(TITLE_CAST, "Cast", "The leads under the description. Select a name to find more of their films and shows.", onOff),
        Choice(TITLE_DIRECTOR, "Director", "The director under the cast, for films.", onOff),
        Choice(TRAILER_ART, "Art behind the trailer", "Keeps the title's art, dimmed, behind the trailer box. Off plays the trailer over black.", onOff),
        Choice(TITLE_RATING, "Content rating", "The age rating (PG, 15, TV-MA) beside the year, when it is known.", onOff),
        Choice(TITLE_TOMATOES, "Rotten Tomatoes", "The critics' (C) and audience (A) percentages beside the IMDb rating, on the title page and under a title's name on Home, TV, Movies and Content, and the critics' one-line consensus on the title page.", onOff),
        Choice("blurThumbnails", "Blur unwatched episodes", "Pictures of episodes you have not watched are blurred in the episode guide, against spoilers.", listOf(false to "Off", true to "On")),
    )
    private val pictureAndSound = listOf(
        Choice("homeCinema", "Screensaver", "After five minutes without a key on Home, your Continue and New episodes titles fill the screen in turn, with a clock, for ten minutes; then the TV's own screensaver or sleep follows. Any key returns.", listOf(true to "On", false to "Off")),
        Choice("hdrPref", "HDR and Dolby Vision", "Which goes first when a title has both. Off picks plain releases.",
            listOf("" to "On", "dv" to "Dolby Vision first", "hdr10" to "HDR10 first", "off" to "Off")),
        Choice("matchFrameRate", "Match frame rate", "The TV switches to the film's rate so pans don't judder.",
            listOf(true to "On", false to "Off")),
        Choice("passthrough", "Audio output", "Receiver sends Dolby and DTS untouched to a soundbar or receiver. TV speakers decodes the sound on the TV, which always works.",
            listOf(false to "TV speakers", true to "Receiver")),
        Choice("stallSwitch", "When a source keeps stalling", "A lighter file of the same title takes over from the same second. Ask me shows the choice instead.",
            listOf("auto" to "Switch by itself", "ask" to "Ask me")),
        Choice("clearDialogue", "Clear dialogue", "Lifts voices above music and effects, for TV speakers. Also in the player under Audio & subtitles. Does nothing with Audio output set to Receiver.",
            listOf(false to "Off", true to "On")),
    )
    private val dubbed = listOf(
        Choice("dub", "Dubbed releases", "Original first puts dubbed releases last; My language first uses this profile's audio language.",
            listOf("" to "No preference", "original" to "Original first", "mine" to "My language first"), true),
    )
    private val subtitles = listOf(
        Choice("subtitlesWhen", "Subtitles by default", "With foreign sound: subtitles in your subtitle language start when the sound is not in your language, as Netflix does. Always: with every video that has them. A choice made while watching a title is kept for that title.",
            listOf("foreign" to "With foreign sound", "always" to "Always", "off" to "Off")),
    )
    private val subtitleLooks = listOf(
        Choice("subSize", "Subtitle size", "How big subtitles are on screen.",
            listOf(100 to "Medium", 130 to "Large", 160 to "Extra large", 80 to "Small")),
        Choice("subBackground", "Subtitle background", "A dark box behind subtitles keeps them readable in bright scenes.",
            listOf(true to "On", false to "Off")),
    )

    private lateinit var groups: LinearLayout
    private lateinit var detailRows: LinearLayout
    private lateinit var rowsScroll: ScrollView
    private lateinit var detailTitle: TextView
    private lateinit var hint: TextView
    private lateinit var profileName: TextView
    private lateinit var avatar: TextView
    private val groupViews = mutableMapOf<Group, View>()
    private val groupLabels = mutableMapOf<Group, TextView>()
    private val choiceRows = mutableSetOf<View>()
    private val refresh = mutableListOf<() -> Unit>()
    private var selectedGroup: Group? = null
    private var firstDetail: View? = null
    private var addonValue: TextView? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var latestChecked: Result<Updates.Release?>? = null // the About group's update check, kept for this visit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.hdrPref(this) // migrate the old HDR switch before any row reads it
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(BASE); clipChildren = false; clipToPadding = false }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        avatar = text(Profiles.active(this)?.name?.take(1)?.uppercase() ?: "A", BODY, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            background = glass(15)
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
        header.addView(text("Settings", SERIF_TITLE, serif = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        profileName = text("", SMALL, color = TEXT_3).apply { setPadding(dp(14), dp(8), 0, 0) }
        header.addView(profileName)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(android.widget.TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = SMALL
            setTextColor(TEXT_2)
            typeface = text("", SMALL).typeface
        })
        root.addView(header, FrameLayout.LayoutParams(-1, dp(40), Gravity.TOP).apply {
            leftMargin = dp(48); rightMargin = dp(48); topMargin = dp(24)
        })

        groups = column(Gravity.START).apply {
            focusByLight()
            setPadding(dp(7), dp(9), dp(7), dp(9))
            background = glassOver(this, 14)
        }
        shownGroups().forEach { group ->
            if (group == Group.STORAGE) groups.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) })
            addGroup(group)
        }
        root.addView(groups, FrameLayout.LayoutParams(dp(200), dp((height - 115).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(48); topMargin = dp(88)
        })

        // Tracked capitals, so the heading reads apart from the row labels, which are the same bold body size.
        detailTitle = caps("", TEXT_3).apply { setPadding(dp(12), 0, 0, 0) }
        detailRows = column(Gravity.START).apply { focusByLight() }
        rowsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(detailRows)
        }
        // About 70 characters a line: at the panel's full width the help ran to a hundred and was hard to follow.
        hint = text("", SMALL, color = TEXT_2).onArt().apply { setPadding(dp(12), 0, dp(20), 0); maxWidth = dp(520) }
        // The help follows the rows, so a long group (Streams has six) never runs under it.
        val detail = column(Gravity.START).apply {
            clipChildren = false
            addView(detailTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3); bottomMargin = dp(12) })
            addView(rowsScroll, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        root.addView(detail, FrameLayout.LayoutParams(dp((width - 328).toInt()), dp((height - 110).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(280); topMargin = dp(88)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)
        profileName.text = Profiles.active(this)?.name?.let { "Profile " + it } ?: ""
        showGroup(shownGroups().first())
        groupViews[Group.STREAMS]?.post { groupViews[Group.STREAMS]?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (::profileName.isInitialized) {
            val name = Profiles.active(this)?.name
            profileName.text = name?.let { "Profile " + it } ?: ""
            avatar.text = name?.take(1)?.uppercase() ?: "A"
            refresh.forEach { it() }
        }
        val key = authKey ?: return
        background({ Stremio.addons(key).size }) { result ->
            addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Unavailable") + "  ›"
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                groupViews.values.any { it.hasFocus() }) {
                if (firstDetail?.requestFocus() == true) return true
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                currentFocus?.let { isInside(it, detailRows) && it !in choiceRows } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
            // The rows end where they end: Down past the last one stays put instead of falling into the group list.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && detailRows.childCount > 0 &&
                currentFocus === detailRows.getChildAt(detailRows.childCount - 1)) return true
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                currentFocus?.let { isInside(it, detailRows) } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun addGroup(group: Group) {
        // The open section is the filled, brighter row; a dot as well was a third mark for one thing.
        val icon = if (group.icon != 0) ImageView(this).apply {
            setImageResource(group.icon)
            imageTintList = ColorStateList.valueOf(TEXT_3)
        } else text(group.glyph, BODY, color = TEXT_3).apply { gravity = Gravity.CENTER }
        val label = text(group.label, BODY, bold = true, color = TEXT_2)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36) // a least height, not a fixed one, so a larger TV font still fits
            addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
            addView(label, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(10) })
            setPadding(dp(2), 0, dp(4), 0)
            rowFocus(8, rest = { if (selectedGroup == group) tile(8) else null }) { has ->
                if (has) showGroup(group)
            }
            setOnClickListener { showGroup(group); firstDetail?.requestFocus() }
        }
        groupViews[group] = row
        groupLabels[group] = label
        groups.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(1) })
    }

    /** The groups this profile's Settings has: all of them with Show everything on, else the three a viewer needs. */
    private fun shownGroups() = if (Settings.everything(this)) Group.entries.toList() else listOf(Group.SUBTITLES, Group.PROFILE, Group.ABOUT)

    private fun showGroup(group: Group) {
        if (selectedGroup == group && detailRows.childCount > 0) return
        selectedGroup = group
        groupViews.forEach { (item, view) ->
            groupLabels[item]?.setTextColor(if (item == group) TEXT else if (item == Group.STORAGE || item == Group.ABOUT) TEXT_3 else TEXT_2)
            if (!view.hasFocus()) view.background = if (item == group) tile(8) else null
        }
        detailTitle.text = group.label // caps() only uppercases the words it was made with
        detailRows.removeAllViews()
        rowsScroll.scrollTo(0, 0)
        choiceRows.clear()
        refresh.clear()
        firstDetail = null
        addonValue = null
        hint.text = when (group) {
            Group.STREAMS -> "Choose how CAS finds and starts a source."
            Group.PICTURE -> "Choose the picture and sound this TV can play."
            Group.SUBTITLES -> "Set how subtitles start and appear."
            Group.TITLE -> "Choose what a title's page shows."
            Group.LISTS -> "Your own rows on Home, under Continue. Hold OK on any title to add it to one."
            Group.ADDONS -> "Manage the add-ons connected to this profile."
            Group.PROFILE -> "Profile choices stay with the person watching."
            Group.LANGUAGE -> "CAS follows the TV's language."
            Group.STORAGE -> "Storage on this TV only."
            Group.ABOUT -> "About CAS."
        }
        when (group) {
            Group.STREAMS -> {
                presetRow()
                streaming.forEach(::choiceRow)
                Profiles.active(this)?.let { me ->
                    lateinit var value: TextView
                    value = actionRow("Share my streaming sources", if (Profiles.sharesStreams(this, me.id)) "On" else "Off",
                        "Other profiles on this TV can find sources with this profile's stream add-ons. Your add-on's provider may see what they play.") {
                        val on = !Profiles.sharesStreams(this, me.id)
                        Profiles.setSharesStreams(this, me.id, on)
                        value.text = (if (on) "On" else "Off") + "  ›"
                    }
                }
                // Real-Debrid has no sign-in of its own in CAS: the key lives in the address of a stream add-on on the
                // Stremio account. Signing out is taking those add-ons off the account; the key is never shown.
                Profiles.active(this)?.let { me ->
                    var all: List<Addon>? = null
                    var carriers: List<Addon> = emptyList()
                    lateinit var value: TextView
                    value = actionRow("Real-Debrid", "…", "Sign out of Real-Debrid on this profile. CAS removes the stream add-ons that carry your Real-Debrid key.") {
                        val list = all ?: return@actionRow
                        if (carriers.isEmpty()) return@actionRow choose("Real-Debrid", listOf("OK"),
                            "No add-on on this profile shows a Real-Debrid key. An add-on that keeps its key hidden can be removed in Add-ons.") {}
                        val names = carriers.joinToString(", ") { addonShortName(it.name) }
                        choose("Sign out of Real-Debrid?", listOf("Cancel", "Sign out"),
                            "This removes $names from this profile's Stremio account, on all its devices. Your Real-Debrid account stays as it is. To sign in again, add the add-on again.") { n ->
                            if (n == 1) urgent({ Stremio.setAddons(me.authKey, list - carriers.toSet()) }) { r ->
                                if (r.isSuccess) showGroup(Group.STREAMS)
                                else choose("Couldn't sign out", listOf("OK"), "Stremio did not save the change. Check the TV's connection and try again.") {}
                            }
                        }
                    }
                    background({ Stremio.addons(me.authKey, refresh = true) }) { r ->
                        all = r.getOrNull()
                        carriers = all.orEmpty().filter { !it.locked && carriesRealDebrid(it.transportUrl) }
                        value.text = (when { all == null -> "Unavailable"; carriers.isEmpty() -> "Not signed in"; else -> "Signed in" }) + "  ›"
                    }
                }
                Profiles.streamLenders(this).forEach { lender ->
                    val value = actionRow("Shared by ${lender.name}", "…",
                        "${lender.name} shares these stream add-ons with every profile on this TV. Your history stays yours.") {}
                        .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END } // many add-ons: one line, not a wrapped row
                    background({ (Stremio.savedAddons(lender.authKey) ?: Stremio.addons(lender.authKey)).filter { it.shareable } }) { result ->
                        value.text = result.getOrNull()?.map { addonShortName(it.name) }?.ifEmpty { null }?.joinToString(", ")
                            ?: if (result.isSuccess) "No stream add-ons" else "Couldn't load"
                    }
                }
                actionRow("More filters", "", "Release groups, codecs and more are set in your streaming add-on.") {
                    choose("More filters", listOf("OK"),
                        "Release groups, codecs, languages and more are set in your streaming add-on on your phone or at web.stremio.com. This TV follows its order.") {}
                }
            }
            Group.PICTURE -> {
                pictureAndSound.forEach(::choiceRow)
                languageRow("audioLang", "Audio language", "The dub to start with when a file has several. Changeable while watching.")
                dubbed.forEach(::choiceRow)
            }
            Group.SUBTITLES -> {
                subtitles.forEach(::choiceRow)
                languageRow("subLang", "Subtitle language", "Which subtitles start with the video, and which come first in the list while watching.")
                subtitleLooks.forEach(::choiceRow)
            }
            Group.TITLE -> titlePage.forEach(::choiceRow)
            Group.LISTS -> {
                Lists.all(this).forEach { l ->
                    val count = l.items.size
                    actionRow(l.name, if (count == 1) "1 title" else "$count titles", "Hold OK on a title on Home, TV, Movies or Library to add it here.") {
                        choose(l.name, listOf("Rename", "Remove list", "Keep")) { picked ->
                            when (picked) {
                                0 -> askName("Rename ${l.name}", l.name) { name -> Lists.rename(this, l.id, name); showGroup(Group.LISTS) }
                                1 -> { Lists.remove(this, l.id); showGroup(Group.LISTS) }
                            }
                        }
                    }
                }
                actionRow("New list", "", "A name of your own: a person, a mood, a night of the week.") {
                    askName("Name the list", hint = "Kids, AC, Film night…") { name -> Lists.create(this, name); showGroup(Group.LISTS) }
                }
            }
            Group.ADDONS -> {
                actionRow("My add-ons", "…", "Add by URL, reorder or remove this profile's add-ons.") {
                    if (authKey != null) startActivity(Intent(this, AddonsActivity::class.java))
                }.also { addonValue = it }
                actionRow("Reload add-ons from Stremio", "", "Fetch this profile's add-ons after installing some elsewhere.") {
                    val key = authKey ?: return@actionRow
                    addonValue?.text = "Reloading…"
                    background({ Stremio.addons(key, refresh = true).size }) { result ->
                        addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Reload failed") + "  ›"
                    }
                }
                val key = authKey
                if (key != null) background({ Stremio.addons(key).size }) { result ->
                    addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Unavailable") + "  ›"
                }
            }
            Group.PROFILE -> {
                Settings.everything(this).let { on ->
                    actionRow("Show everything", if (on) "On" else "Off",
                        "Off: Home, TV, Movies and Search, with short pages and few settings. On: every page, every row and every setting.") {
                        Settings.setEverything(this, !on)
                        recreate()
                    }
                }
                Settings.lightMode(this).let { light ->
                    actionRow("Appearance", if (light) "Light" else "Dark", "Light: dark words on a soft off-white. Dark: light words on charcoal. The player is dark in both.") {
                        Settings.setLightMode(this, !light)
                        recreate()
                    }
                }
                (Settings.textScale(this) > 1f).let { large ->
                    actionRow("Text size", if (large) "Large" else "Standard", "Large makes every word in CAS bigger, for this profile.") {
                        Settings.setTextScale(this, if (large) 1f else Settings.LARGE_TEXT)
                        recreate()
                    }
                }
                // One row: switching and managing both open the picker, where Hold OK does the managing.
                actionRow("Profiles", "", "Choose who is watching. Rename profiles, change PINs or add another person.") {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                val hiddenRows = Settings.hiddenRows(this).sorted()
                actionRow("Hidden rows", if (hiddenRows.isEmpty()) "None" else "${hiddenRows.size}", "Rows you hid by holding OK on a title. Choose one to show it again.") {
                    if (hiddenRows.isEmpty()) return@actionRow
                    val names = hiddenRows.map { "${it.substringAfter('|')}  ·  ${it.substringBefore('|')}" }
                    choose("Show again", names + "Keep") { i ->
                        if (i < hiddenRows.size) { Settings.setHiddenRows(this, Settings.hiddenRows(this) - hiddenRows[i]); showGroup(Group.PROFILE) }
                    }
                }
                var hiddenValue: TextView? = null
                fun count() { hiddenValue?.text = "${Settings.hidden(this).length()}  ›" }
                hiddenValue = actionRow("Removed from Continue Watching", "", "Titles you took off the row. They are not blocked: play one and it comes back. Select one here to put it back now.") {
                    unhideMenu(::count)
                }
                count()
            }
            Group.LANGUAGE -> actionRow("App language", Locale.getDefault().displayLanguage,
                "The app follows this TV's language setting.") {
                // Per-app language pages exist from Android 13; older TVs get the system language page.
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT < 33) throw android.content.ActivityNotFoundException()
                    startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName")))
                }
                    .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)) }
                    .onFailure { choose("Language settings are unavailable on this TV.", listOf("OK")) {} }
            }
            Group.STORAGE -> actionRow("Clear local storage", "", "Removes profiles, settings and saved data from this TV. Stremio accounts stay as they are.") {
                choose("Clear local storage?", listOf("Cancel", "Clear"),
                    "This removes saved profiles, settings and cached data from this TV. It does not cancel your Stremio account.") { choice ->
                    if (choice == 1) (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }
            }
            Group.ABOUT -> {
                detailRows.addView(brand(SERIF_TITLE), LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = dp(12); bottomMargin = dp(14)
                })
                choiceRow(Choice("autoUpdate", "Updates", "Overnight: a new version installs by itself while the screensaver is on, and is there next time. Ask me offers it on Home instead.",
                    listOf(true to "Overnight", false to "Ask me")))
                actionRow("CAS", "Version " + Updates.mine(this),
                    "A quieter way to watch. CAS connects your Stremio account to your TV.") {
                    choose("CAS", listOf("OK"), "A TV interface for your Stremio account.") {}
                }
                actionRow("What's new", "", "The changes in the version on this TV.") { whatsNew() } // the version is on the row above
                Crash.last(this)?.let { trace ->
                    actionRow("Last crash", Crash.lastWhen(this) ?: "", "What went wrong the last time CAS closed on its own. Photograph it to report it.") {
                        choose("Last crash", listOf("Keep", "Clear"), trace.take(1_400)) { if (it == 1) { Crash.clear(this); showGroup(Group.ABOUT) } }
                    }
                }
                var found: Updates.Release? = null
                val value = actionRow("Check for updates", "Checking…", "New versions come from CAS's releases on GitHub.") {
                    found?.let(::installUpdate) ?: run { latestChecked = null; showGroup(Group.ABOUT) }
                }
                // One check per visit to Settings: the group is rebuilt on every focus, and GitHub allows 60 anonymous
                // calls an hour. "Try again" asks afresh.
                fun gotLatest(r: Result<Updates.Release?>) {
                    val release = r.getOrNull()
                    found = release?.takeIf { newerVersion(it.version, Updates.mine(this)) }
                    value.text = when {
                        found != null -> "Install ${found!!.version}  ›"
                        release == null -> "Couldn't check · Try again  ›"
                        else -> "Up to date"
                    }
                }
                latestChecked?.let(::gotLatest) ?: background({ Updates.latest(applicationContext) }) { r -> latestChecked = r; gotLatest(r) }
            }
        }
    }

    private fun settingRow(label: String, value: View, help: String, perProfile: Boolean = false,
                           focus: (Boolean) -> Unit = {}, click: () -> Unit): View {
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // A least height, not a fixed one: a value that wraps makes the row taller. On the line, not the frame
            // around it: a frame with one match-parent child doesn't pass its own least height down.
            minimumHeight = dp(44)
            // The label takes what the value leaves and ends in an ellipsis, so the two never run into each other.
            addView(text(label, BODY, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (perProfile) addView(text("THIS PROFILE", BADGE, color = TUNGSTEN).apply {
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = glass(6)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            setPadding(dp(12), 0, dp(12), 0)
        }
        val row = FrameLayout(this).apply {
            addView(line, FrameLayout.LayoutParams(-1, -2))
            rowFocus(9) { has ->
                if (has) hint.text = help
                elevation = if (has) dp(8).toFloat() else 0f
                if (has && android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = TUNGSTEN_GLOW
                focus(has)
            }
            setOnClickListener { click() }
        }
        detailRows.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (firstDetail == null) firstDetail = row
        return row
    }

    /** The titles hidden from Continue Watching; OK on one puts it back with its resume point, here and in Stremio. */
    private fun unhideMenu(done: () -> Unit) {
        val all = Settings.hidden(this)
        val ids = all.keys().asSequence().toList()
        if (ids.isEmpty()) return choose("Nothing removed", listOf("OK"), "Titles you remove from Continue Watching show here until you play them again.") {}
        val key = authKey ?: return
        choose("Removed from Continue Watching", ids.map { all.getJSONObject(it).optString("name", it) }, "Select a title to put it back.") { i ->
            val id = ids[i]
            background({
                val item = Stremio.library(key, listOf(id)).firstOrNull() ?: error("not in the library")
                Library.unhide(item, all.getJSONObject(id).optLong("timeOffset"))
                Stremio.saveLibrary(key, listOf(item))
            }) { result ->
                result.fold({
                    all.remove(id)
                    Settings.setHidden(this, all)
                    done()
                }) { choose("Couldn't put it back", listOf("OK"), "Check the connection and try again.") {} }
            }
        }
    }

    private fun actionRow(label: String, value: String, help: String, click: () -> Unit): TextView {
        // Capped, so a long value (a list of add-ons) wraps or ellipsizes instead of squeezing the label out.
        val display = text(if (value.isEmpty()) "›" else "$value  ›", BODY, color = TEXT_2).apply { maxWidth = dp(VALUE_W) }
        settingRow(label, display, help, focus = { display.setTextColor(if (it) TUNGSTEN else TEXT_2) }, click = click)
        return display
    }

    private fun key(c: Choice) = if (c.perProfile) Settings.profileKey(this, c.key) else c.key
    private fun stored(c: Choice): Any = when (val default = c.values[0].first) {
        is Boolean -> prefs.getBoolean(key(c), default)
        is String -> prefs.getString(key(c), default).orEmpty()
        else -> prefs.getInt(key(c), default as Int)
    }
    private fun store(c: Choice, value: Any) = prefs.edit().apply {
        when (value) {
            is Boolean -> putBoolean(key(c), value)
            is String -> putString(key(c), value)
            else -> putInt(key(c), value as Int)
        }
    }.apply()
    private fun choice(key: String) = (streaming + pictureAndSound).first { it.key == key }

    private fun choiceRow(c: Choice) {
        fun current() = c.values.indexOfFirst { it.first == stored(c) }.coerceAtLeast(0)
        // A switch only where the choice is On or Off; "Best source / Show sources" is a choice with words.
        val isSwitch = c.values.map { it.second } == listOf("On", "Off")
        val words = text("", BODY, color = TEXT_2)
        val track = FrameLayout(this).apply { background = rounded(GLASS_EDGE, 9) }
        val knob = View(this)
        if (isSwitch) track.addView(knob)
        val value = if (isSwitch) LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(words, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(track, LinearLayout.LayoutParams(dp(32), dp(17)))
        } else words
        var focused = false
        fun draw() {
            val index = current()
            val on = isSwitch && c.values[index].first == true
            words.setTextColor(if (focused) TEXT else TEXT_2)
            words.text = if (isSwitch) c.values[index].second
                else if (focused) "‹  " + c.values[index].second + "  ›" else c.values[index].second
            if (isSwitch) {
                track.background = rounded(if (on) TUNGSTEN else GLASS_EDGE, 9)
                knob.background = rounded(if (on) BASE else TEXT_2, 7)
                knob.layoutParams = FrameLayout.LayoutParams(dp(13), dp(13),
                    Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply {
                    leftMargin = dp(2); rightMargin = dp(2)
                }
            }
        }
        fun shift(by: Int) {
            val next = nextSettingIndex(current(), c.values.size, by)
            store(c, c.values[next].first)
            refresh.forEach { it() }
        }
        val row = settingRow(c.label, value, c.hint, c.perProfile, focus = { has -> focused = has; draw() }) { shift(1) }
        choiceRows += row
        row.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                shift(if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            } else false
        }
        refresh += ::draw
        draw()
    }

    /** A language for this profile, picked from a list (too many to step through with Left and Right). */
    private fun languageRow(key: String, label: String, help: String) {
        val codes = listOf("") + Languages.all
        // Audio language was once shared by every profile: that pick stands until this profile makes its own.
        fun current() = prefs.getString(Settings.profileKey(this, key), if (key == "audioLang") prefs.getString(key, "") else "").orEmpty()
        fun name(code: String) = if (code.isEmpty()) "TV language" else Languages.name(code)
        val value = text("", BODY, color = TEXT_2)
        fun draw() { value.text = name(current()) + "  ›" }
        settingRow(label, value, help, perProfile = true, focus = { value.setTextColor(if (it) TUNGSTEN else TEXT_2) }) {
            choose(label, codes.map(::name), checked = codes.indexOf(current()).coerceAtLeast(0)) { i ->
                prefs.edit().putString(Settings.profileKey(this, key), codes[i]).apply()
                refresh.forEach { it() }
            }
        }
        refresh += ::draw
        draw()
    }

    private fun presetRow() {
        val value = text("", BODY, color = TEXT_2)
        fun current() = PRESETS.indexOfFirst { (_, rows) -> rows.all { (key, v) -> stored(choice(key)) == v } }
        var focused = false
        fun draw() { value.text = (if (focused) "‹  " else "") + (PRESETS.getOrNull(current())?.first ?: "Custom") + (if (focused) "  ›" else "") }
        fun shift(by: Int) {
            val next = nextSettingIndex(current(), PRESETS.size, by)
            PRESETS[next].second.forEach { (key, v) -> store(choice(key), v) }
            refresh.forEach { it() }
        }
        val row = settingRow("Source choice", value,
            "Best picture: full Blu-ray and Dolby Vision first. Balanced: smaller files. Fast start: web releases. Add-on's order: follow the add-on.",
            focus = { has -> focused = has; draw() }) { shift(1) }
        choiceRows += row
        row.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                shift(if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            } else false
        }
        refresh += ::draw
        draw()
    }

    companion object {
        const val TITLE_TRAILER = "titleTrailer"
        const val TITLE_PREVIEW = "titlePreview"
        const val TITLE_CAST = "titleCast"
        const val TITLE_DIRECTOR = "titleDirector"
        const val TRAILER_ART = "trailerArt"
        const val TITLE_RATING = "titleRating"
        const val TITLE_TOMATOES = "titleTomatoes"
        const val TRAILER_PLAYER = "trailerPlayer" // "browser", "app" or "box"; unset: the box, except on Fire TV (Silk), where the box draws no picture
        private const val VALUE_W = 300 // dp a row's value may take, about half the panel; the label keeps the rest
        val PRESETS = listOf(
            "Add-on's order" to mapOf("release" to "", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 0),
            "Best picture" to mapOf("release" to "remux", "hdrPref" to "dv", "maxHeight" to 0, "maxSizeGb" to 0),
            "Balanced" to mapOf("release" to "encode", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 20),
            "Fast start" to mapOf("release" to "web", "hdrPref" to "", "maxHeight" to 1080, "maxSizeGb" to 10),
        )
    }
}
