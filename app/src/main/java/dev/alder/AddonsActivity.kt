package dev.alder

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject

// The account's add-on order decides which add-on answers first; changes save to Stremio at once.
class AddonsActivity : Screen() {
    private lateinit var list: LinearLayout
    private lateinit var help: LinearLayout
    private lateinit var helpName: TextView
    private lateinit var helpBody: TextView
    private var addons: List<Addon> = emptyList()

    private val scene by lazy { ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.75f
    } }
    override val backdrop get() = scene

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (authKey == null) return finish()
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels / metrics.density).toInt()
        val height = (metrics.heightPixels / metrics.density).toInt()
        val panelWidth = (width * PANEL_W).toInt()
        list = column(Gravity.START).apply { setPadding(dp(16), dp(8), dp(16), dp(20)) }
        helpName = caps("").onArt()
        helpBody = text("", BODY, color = TEXT_2).onArt()
        // A shaded pane so the note stays readable over bright art.
        help = column(Gravity.START, helpName, helpBody.marginTop(8)).apply {
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = glassOver(this, 14, shade = 0.35f)
            visibility = View.INVISIBLE
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        // No footer hint: the focused row shows the OK key itself.
        val panel = FrameLayout(this).apply {
            background = glassOver(this, 14)
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        setWindowed(FrameLayout(this).apply {
            addView(panel, FrameLayout.LayoutParams(dp(panelWidth), dp(height - 54), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(PAD); topMargin = dp(PANEL_TOP)
            })
            addView(TextClock(this@AddonsActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                textSize = SMALL; setTextColor(TEXT_2)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(PAD); topMargin = dp(35)
            })
            // Level with "My add-ons": the panel's top, then the list's padding and the title's own margin.
            addView(help, FrameLayout.LayoutParams(dp((width - panelWidth - 2 * PAD - 64).coerceAtLeast(160)), -2,
                Gravity.TOP or Gravity.END).apply { rightMargin = dp(PAD); topMargin = dp(PANEL_TOP + 8 + 8) })
        }, scene)
        val art = Profiles.active(this)?.let { getSharedPreferences("home", MODE_PRIVATE).getString("art:" + it.id, null) }
        if (art != null) Images.load(art, scene, metrics.widthPixels, urgent = true,
            failed = { refrost() }) { refrost() }
        show(loading = true)
        load()
    }

    private fun load() {
        val key = authKey ?: return
        background({ Stremio.addons(key, refresh = true) }) { result ->
            result.onSuccess { addons = it; show() }
                .onFailure { show(message = "Couldn't reach Stremio. Check the TV's internet.", retry = ::load) }
        }
    }

    /** Redraws the list, focusing [focus] (an add-on URL), or its first row. */
    private fun show(focus: String? = null, message: String? = null, retry: (() -> Unit)? = null, loading: Boolean = false) {
        list.removeAllViews()
        list.addView(text("My add-ons", SERIF_TITLE, serif = true).onArt(), LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(8); topMargin = dp(8)
        })
        val guidance = "The first add-on that has something answers first. Changes apply to Stremio on all your devices."
        list.addView(text(guidance, SMALL, color = TEXT_2).onArt().apply {
            text = android.text.SpannableString(guidance).apply {
                setSpan(android.text.style.ForegroundColorSpan(TUNGSTEN), guidance.indexOf("Changes apply"), guidance.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(8); rightMargin = dp(12); topMargin = dp(4); bottomMargin = dp(16)
        })
        if (loading) list.addView(ring(), LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            leftMargin = dp(16); topMargin = dp(16)
        })
        if (message != null && retry != null) {
            val card = failed(message, retry)
            list.addView(card, LinearLayout.LayoutParams(-1, -2))
            if (addons.isEmpty()) {
                card.getChildAt(card.childCount - 1).post { card.getChildAt(card.childCount - 1).requestFocus() }
                return
            }
        }
        else if (message != null) list.addView(text(message, SMALL, color = ALERT).onArt(),
            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(16); bottomMargin = dp(8) })
        if (!loading && addons.isEmpty() && message == null) list.addView(
            text("No add-ons yet. Add them in the Stremio app, then reload here.", BODY, color = TEXT_2).onArt(),
            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(16); topMargin = dp(12) })
        var focused: View? = null
        addons.forEachIndexed { index, addon ->
            val row = row(index, addon)
            list.addView(row, LinearLayout.LayoutParams(-1, dp(ROW_H2)))
            if (addon.transportUrl == focus || (focus == null && focused == null)) focused = row
        }
        val add = row(addons.size, null)
        list.addView(add, LinearLayout.LayoutParams(-1, dp(ROW_H2)).apply { topMargin = dp(8) })
        if (focus == ADD_URL || focused == null) focused = add
        if (!loading) focused?.post { focused?.requestFocus() }
    }

    private fun row(index: Int, addon: Addon?) = FrameLayout(this).apply {
        val name = addon?.let { addonShortName(it.name) } ?: "Add by URL"
        val role = addon?.let(::addonRole) ?: "Install from a manifest address"
        val host = addon?.let(::addonHost).orEmpty()
        val line = LinearLayout(this@AddonsActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            addView(text(if (addon == null) "+" else "${index + 1}", SMALL, color = TEXT_3),
                LinearLayout.LayoutParams(dp(24), -2))
            addView(column(Gravity.CENTER_VERTICAL,
                tight(text(name, BODY, bold = true)),
                tight(text(role, SMALL, color = TEXT_3)).marginTop(2)),
                LinearLayout.LayoutParams(0, -2, 1f))
            if (host.isNotEmpty()) addView(tag(host), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
        // OK opens move up / move down / remove, so the hint names OK rather than arrows.
        val move = if (addon == null) null else keyHint("OK", "Move").apply { visibility = View.GONE }
            .also { line.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) }) }
        addView(line, FrameLayout.LayoutParams(-1, -1))
        addView(divider(), FrameLayout.LayoutParams(-1, dp(1), Gravity.BOTTOM))
        rowFocus(8, also = { has ->
            move?.visibility = if (has) View.VISIBLE else View.GONE
            if (has) {
                help.visibility = if (addon != null) View.VISIBLE else View.INVISIBLE
                if (addon != null) { helpName.text = name; helpBody.text = addonHelp(addon, index) }
            }
        })
        contentDescription = if (addon == null) name else "${index + 1}. $name. $role. $host. Move or remove"
        setOnClickListener { if (addon == null) askUrl() else edit(addon) }
    }

    private fun tight(view: TextView) = view.apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        setLineSpacing(0f, 1f)
    }

    private fun edit(a: Addon) {
        val i = addons.indexOf(a)
        val actions = listOfNotNull(
            if (i > 0) "Move up" to { save(addons.toMutableList().apply { add(i - 1, removeAt(i)) }, a.transportUrl) } else null,
            if (i < addons.size - 1) "Move down" to { save(addons.toMutableList().apply { add(i + 1, removeAt(i)) }, a.transportUrl) } else null,
            if (!a.locked) "Remove" to {
                choose("Remove ${addonShortName(a.name)}?", listOf("Cancel", "Remove"),
                    "This changes Stremio on all your devices.") { n ->
                    if (n == 1) save(addons - a, addons.getOrNull(i + 1)?.transportUrl)
                }
                Unit
            } else null,
        )
        if (actions.isNotEmpty()) choose(addonShortName(a.name), actions.map { it.first }) { n -> actions[n].second() }
    }

    private fun askUrl() {
        val dialog = Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val field = EditText(this).apply {
            hint = "https://…/manifest.json"
            setHintTextColor(TEXT_3)
            setTextColor(TEXT)
            textSize = BODY
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = glass(8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        dialog.setContentView(column(Gravity.START).apply {
            setPadding(dp(DIALOG_PAD), dp(DIALOG_PAD), dp(DIALOG_PAD), dp(DIALOG_PAD))
            background = glassOver(this, 14)
            addView(text("Add an add-on", BODY, bold = true))
            addView(field, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(14) })
            addView(LinearLayout(this@AddonsActivity).apply {
                gravity = Gravity.END
                addView(chip("Cancel").apply { setOnClickListener { dialog.dismiss() } })
                addView(pill("Add").apply {
                    setOnClickListener { val url = field.text.toString().trim(); dialog.dismiss(); install(url) }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        })
        dialog.show()
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(0)); setLayout(dp(DIALOG_W), -2); setDimAmount(DIALOG_DIM) }
        field.setOnEditorActionListener { _, _, _ ->
            val url = field.text.toString().trim(); dialog.dismiss(); install(url); true
        }
        field.requestFocus()
    }

    // Stremio's install links are stremio://host/…/manifest.json; a bare addon address gets /manifest.json added.
    private fun install(typed: String) {
        if (typed.isEmpty()) return show(ADD_URL)
        val url = typed.replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
            .let { if (it.contains("://")) it else "https://$it" }
            .let { if (it.endsWith("/manifest.json")) it else it.trimEnd('/') + "/manifest.json" }
        if (addons.any { it.transportUrl == url }) return show(url, "Already installed.")
        background({
            val manifest = JSONObject(Http.get(url))
            require(manifest.has("id") && manifest.has("resources")) { "That address isn't a Stremio addon." }
            Addon(JSONObject().put("transportUrl", url).put("manifest", manifest)
                .put("flags", JSONObject().put("official", false).put("protected", false)))
        }) { result ->
            result.onSuccess { save(addons + it, url) }
                .onFailure { show(ADD_URL, "Couldn't add the add-on. Check its address and try again.") }
        }
    }

    private fun save(next: List<Addon>, focus: String?) {
        val key = authKey ?: return
        urgent({ Stremio.setAddons(key, next) }) { result ->
            result.onSuccess { addons = next; show(focus) }
                .onFailure { show(focus, "Couldn't save the change to Stremio.", retry = { save(next, focus) }) }
        }
    }

    private companion object {
        const val ADD_URL = "add-url"
        const val PANEL_TOP = 27 // dp from the top to the list panel; the side note lines up with the title inside it
    }
}

internal fun addonShortName(name: String) = name.substringBefore('|').substringBefore('(').trim()
    .replace(Regex("(?i)^OpenSubtitles v3$"), "OpenSubtitles")
    .replaceFirstChar { it.uppercase() }

internal fun addonRole(addon: Addon): String {
    val resources = addon.descriptor.optJSONObject("manifest")?.optJSONArray("resources")
    val names = (0 until (resources?.length() ?: 0)).map { resources?.opt(it) }.mapNotNull {
        when (it) { is String -> it; is JSONObject -> it.optString("name"); else -> null }
    }.toSet()
    return addonRole(addonShortName(addon.name), addon.id, names)
}

internal fun addonRole(name: String, id: String, names: Set<String>): String {
    if (name.startsWith("Local Files", true)) return "Files on this network"
    if (id.contains("watchly", true)) return "Recommendations · Top Picks on Home"
    if (id.contains("trakt", true)) return "Watch history to Trakt"
    if (name.equals("Cinemeta", true)) return "Titles and details"
    return listOfNotNull(
        "stream".takeIf { it in names }?.let { "Streams" },
        "subtitles".takeIf { it in names }?.let { "Subtitles" },
        "catalog".takeIf { it in names }?.let { "Catalogs" },
        "meta".takeIf { it in names }?.let { "Titles and details" },
    ).take(2).joinToString(" · ").ifEmpty { "Add-on" }
}

internal fun addonHost(addon: Addon): String {
    if (addon.locked) return "Built-in"
    val named = addon.name.substringAfter('|', "").trim()
    if (named.isNotEmpty()) return named
    val host = android.net.Uri.parse(addon.transportUrl).host.orEmpty()
    return when {
        addon.id.contains("opensubtitles", true) &&
            (addon.name.contains("v3", true) || host.contains("v3", true)) -> "v3"
        host.contains("elfhosted", true) -> "ElfHosted"
        else -> "" // a bare server address says nothing useful
    }
}

// The row already names the add-on's role, so the note says only what the row doesn't: its turn, and for Watchly, how it works.
internal fun addonHelp(addon: Addon, index: Int): String = when {
    addon.id.contains("watchly", true) -> "Makes your own Top Picks from what you watch. It answers ${ordinal(index + 1)}."
    else -> "It answers ${ordinal(index + 1)}."
}

internal fun ordinal(n: Int) = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
    "tenth").getOrNull(n - 1) ?: when { n % 100 in 11..13 -> "${n}th"; n % 10 == 1 -> "${n}st"; n % 10 == 2 -> "${n}nd"
    n % 10 == 3 -> "${n}rd"; else -> "${n}th" }
