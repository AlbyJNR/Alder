package dev.alder

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

// Profile picker, Stremio sign-in, and setting up Watchly.
// The app starts here; with a single profile it goes straight on to Home.
class ProfilesActivity : Screen() {
    // The sign-in code being waited on; anything else stops its polling loop.
    private var waitingCode: String? = null
    private var signingIn = false
    private var draftName = ""
    private var draftColor = Profiles.COLORS[0]
    private var draftPicture: String? = null // the animal chosen for the profile being made, or null for its initial
    private var pickerGeneration = 0
    private var signInGeneration = 0
    private var nameInput: EditText? = null
    // Where Back goes from the profile menu's screens (PIN, rename); null elsewhere.
    private var backTo: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: `adb shell am start -n uk.albyjnr.cas/dev.alder.ProfilesActivity --es trailer <youtube id>`
        // opens the trailer box here, so it can be checked on an emulator with no Stremio account.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("trailer")?.let { id -> main.postDelayed({ showTrailer(id, "Trailer test") }, 1500) }
        // `--es imdb tt0133093` plays that title's IMDb trailer in the box, through the live IMDb call.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("imdb")?.let { id -> main.postDelayed({
                showTrailerClip("IMDb trailer test", { imdbTrailerStreams(id) }) { android.util.Log.w("CAS", "no IMDb trailer for $id") }
            }, 1500) }
        // `--ez crash true` throws after a moment, to check the crash record and the word on the next start.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("crash", false))
            main.postDelayed({ throw IllegalStateException("CAS crash test") }, 1500)
        // Likewise `--es preview <direct video url>` opens the preview box on that file.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("preview")?.let { url -> main.postDelayed({
                showPreview("Preview test") { listOf(Stream("Test", "", url, emptyMap(), 0, null, null)) }
            }, 1500) }
        // Debug builds only: `--ez marks true` shows the scores line and the age-rating marks on a plain screen, to be
        // looked at on an emulator with no account.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("marks", false)) {
            return setContentView(column(Gravity.START,
                text("Slow Horses", SERIF_TITLE, serif = true),
                text("", SMALL, color = TEXT_2).apply {
                    text = android.text.SpannableStringBuilder(certMark("15")).append("  ·  S1 E3  ·  Thriller")
                }.marginTop(12),
                scoresLine("8.4", "C97% A87%", lead = false, meta = 82).marginTop(8),
                text("", SMALL, color = TEXT_2).apply {
                    text = android.text.SpannableStringBuilder().apply { listOf("U", "PG", "12A", "15", "18", "TV-MA", "PG-13", "NR").forEach { append(certMark(it)).append("   ") } }
                }.marginTop(24),
            ).apply { setPadding(dp(84), dp(64), dp(48), 0); setBackgroundColor(0xFF2A3A44.toInt()) })
        }
        // Debug builds only: `--ez casDark true` (or false) sets the profile's mode, so CI can photograph both.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.hasExtra("casDark") && Profiles.active(this) != null)
            Settings.setLightMode(this, !intent.getBooleanExtra("casDark", false))
        // Debug builds only, and only on a TV with no profile yet: `--es testKey <Stremio auth key>` signs a test
        // profile in with no phone, so CI can photograph the signed-in screens (cas-build.yml, screens).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && Profiles.all(this).isEmpty())
            intent.getStringExtra("testKey")?.takeIf { it.isNotBlank() }?.let { key -> return signedIn("Test", key, null) }
        val profiles = Profiles.all(this)
        // Home found this profile signed out: straight to its sign-in, the profile and its settings kept.
        val again = intent.getStringExtra("signIn")?.let { id -> profiles.firstOrNull { it.id == id } }
        // Switching away from a child's profile takes the parent's PIN; Back from it returns to the child's Home.
        val leaving = Profiles.active(this)?.takeIf { intent.getBooleanExtra("pick", false) && it.kids && it.pin != null }
        when {
            again != null -> showSignIn(again.name, again)
            profiles.isEmpty() -> showName()
            profiles.size == 1 && !intent.getBooleanExtra("pick", false) -> enter(profiles[0])
            leaving != null -> askPin("Enter the PIN to leave ${leaving.name}", ::finish) { (it == leaving.pin).also { ok -> if (ok) showPicker() } }
            else -> showPicker()
        }
    }

    override fun onDestroy() {
        waitingCode = null
        super.onDestroy()
    }

    @Deprecated("Android back callback")
    override fun onBackPressed() {
        backTo?.let { return it() }
        if (signingIn) showName() else super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val input = nameInput
        if (input != null && !input.hasFocus() && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                if (input.length() > 0) input.text.delete(input.length() - 1, input.length())
                return true
            }
            val character = event.unicodeChar.takeIf { it in 32..126 }?.toChar()
            if (character != null) { input.text.append(character); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Opens a profile, asking for its PIN first when it has one (a child's PIN guards the way out, not in); a signed-out one signs in again first. */
    private fun enter(p: Profile) {
        val open = { if (Profiles.signedOut(this, p.id)) showSignIn(p.name, p) else openHome(p) }
        if (p.kids) open() else withPin(p, open)
    }

    private fun openHome(profile: Profile) {
        Profiles.setActive(this, profile.id)
        val home = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // Opened from the Google TV home's Continue Watching: Home, with that title's page on top of it.
        // A fresh task, so an app already open in the background doesn't end up with two Homes.
        if (intent.hasExtra("id")) startActivities(arrayOf(home,
            Intent(intent).setClass(this, DetailsActivity::class.java).setFlags(0)))
        else startActivity(home)
        finish()
    }

    // ---------- who's watching ----------

    private fun showPicker() {
        // Opened from inside a profile (Switch profile, Manage profiles): Back returns to it instead of leaving CAS.
        val current = Profiles.active(this)?.takeIf { intent.getBooleanExtra("pick", false) }
        backTo = current?.let { { openHome(it) } }
        nameInput = null
        val generation = ++pickerGeneration
        val profiles = Profiles.all(this)
        val art = onboardingBackdrop(false).apply { alpha = 0.42f }
        var selected = profiles.firstOrNull { it.id == Profiles.active(this)?.id } ?: profiles.first()
        // Top-aligned, so Add (no "last watched" line) sits level with the portraits; padding above for the 10% lift.
        val tiles = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; clipChildren = false; clipToPadding = false
            setPadding(0, dp(LIFT_ROOM), 0, 0)
        }
        fun showArt(p: Profile, item: org.json.JSONObject?) {
            if (generation != pickerGeneration || selected.id != p.id) return
            val url = item?.optString("_id")?.let(Images::backgroundFor) ?: return
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true) {
                val bitmap = (art.drawable as? BitmapDrawable)?.bitmap ?: return@load
                background({ Images.frost(bitmap, 160) }) { result ->
                    if (generation == pickerGeneration && selected.id == p.id && art.tag == url)
                        result.getOrNull()?.let { art.setImageBitmap(it); art.alpha = 0.5f }
                }
            }
        }
        val recent = mutableMapOf<String, org.json.JSONObject?>()
        fun focus(p: Profile) {
            selected = p
            if (p.id !in recent) { art.tag = null; art.setImageResource(R.drawable.cas_backdrop); art.alpha = 0.42f }
            else showArt(p, recent[p.id])
        }
        profiles.forEach { p ->
            val signedOut = Profiles.signedOut(this, p.id).takeIf { it }
                ?.let { text("Sign in again", SMALL, color = TUNGSTEN).apply { gravity = Gravity.CENTER } }
            tiles.addView(tile(p, signedOut) { focus(p) }.apply {
                setOnClickListener { enter(p) }
                setOnLongClickListener { showMenu(p); true }
            }, LinearLayout.LayoutParams(dp(124), -2).apply { marginStart = dp(10); marginEnd = dp(10) })
            background({
                val items = Stremio.savedLibrary(p.authKey) ?: Stremio.library(p.authKey)
                Library.continueWatching(items).firstOrNull()
                    ?: items.filter { !it.optJSONObject("state")?.optString("lastWatched").isNullOrBlank() }
                        .maxByOrNull { it.optJSONObject("state")?.optString("lastWatched").orEmpty() }
            }) { result ->
                if (generation != pickerGeneration) return@background
                val item = result.getOrNull()
                recent[p.id] = item
                showArt(p, item)
            }
        }
        if (profiles.size < Profiles.MAX) {
            tiles.addView(tile(null, null) {}.apply { setOnClickListener { showName() } },
                LinearLayout.LayoutParams(dp(124), -2).apply { marginStart = dp(10); marginEnd = dp(10) })
        }
        val content = FrameLayout(this).apply {
            addView(brand(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(PAD); topMargin = dp(31)
            })
            addView(TextClock(this@ProfilesActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"; textSize = SMALL; setTextColor(TEXT_2)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(PAD); topMargin = dp(39)
            })
            addView(text("Who's here tonight?", SERIF_HERO, serif = true).onArt().apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(118) })
            addView(tiles, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(190 - LIFT_ROOM) })
            addView(chip("Manage profiles").apply {
                setOnClickListener {
                    choose("Manage profiles", profiles.map { it.name }) { i -> showMenu(profiles[i]) }
                }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(50) }) // chip() sets its own height
            addView(keyHint("Hold OK", "Rename · PIN · Colour · Kids · Remove"),
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        }
        setWindowed(content, art)
        tiles.getChildAt(profiles.indexOf(selected)).requestFocus()
    }

    private fun tile(profile: Profile?, last: TextView?, focused: () -> Unit): View {
        val add = profile == null
        // Glyph sizes for the fixed 100 dp disc, not text sizes: they scale with the disc, not with the type scale.
        val face = text(if (add) "+" else profile?.name?.take(1)?.uppercase().orEmpty(), if (add) 42f else 48f,
            serif = !add, color = if (add) TEXT_2 else BASE).apply {
            gravity = Gravity.CENTER
            alpha = 0.6f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(profile?.color ?: GLASS)
                if (add) setStroke(dp(1), GLASS_EDGE, dp(2).toFloat(), dp(3).toFloat())
            }
        }
        val circle = FrameLayout(this).apply {
            clipChildren = false
            addView(face, FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER))
        }
        // No ring: like the top bar, a dot under the name marks the remote's portrait.
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TUNGSTEN) }
            visibility = View.INVISIBLE
        }
        val name = text(if (add) "Add" else profile?.name.orEmpty().replaceFirstChar { it.titlecase() },
            BODY, color = TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        // A locked profile says "PIN" under the name: a word, as a lock emoji draws differently on every TV.
        val locked = if (profile?.pin != null && !profile.kids) caps("PIN", TEXT_3).apply { gravity = Gravity.CENTER } else null
        // A child's profile says so under the name, quietly, in place of the lock (its PIN is for leaving).
        val kids = if (profile?.kids == true) caps("Kids").apply { gravity = Gravity.CENTER } else null
        return column(Gravity.CENTER_HORIZONTAL).apply {
            addView(circle, LinearLayout.LayoutParams(dp(112), dp(112)))
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            if (locked != null) addView(locked, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            if (kids != null) addView(kids, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            if (last != null) addView(last, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            addView(dot, LinearLayout.LayoutParams(dp(5), dp(5)).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
            isFocusable = true
            clipChildren = false
            setOnFocusChangeListener { _, has ->
                dot.visibility = if (has) View.VISIBLE else View.INVISIBLE
                circle.grow(has, 1.1f).start()
                face.alpha = if (has) 1f else 0.6f
                name.setTextColor(if (has) TEXT else TEXT_2)
                if (has) focused()
            }
        }
    }

    // ---------- edit a profile ----------

    private fun showMenu(p: Profile) {
        val items = listOfNotNull(
            "Rename" to { withPin(p) { rename(p) } },
            (if (p.pin == null) "Set a PIN" else "Change PIN") to { withPin(p) { setPin(p) } },
            "Picture" to { withPin(p) {
                val names = listOf("Initial") + Animals.all.map { Animals.label(it.first) }
                choose("${p.name}'s picture", names, checked = Animals.all.indexOfFirst { it.first == p.picture } + 1) { i ->
                    Profiles.update(this, p.id, picture = if (i == 0) "" else Animals.all[i - 1].first); showPicker()
                }
            } },
            "Colour" to { withPin(p) {
                choose("${p.name}'s portrait", listOf("Tungsten", "Clay", "Sage", "Lavender", "Honey"),
                    checked = Profiles.COLORS.indexOf(p.color)) { i ->
                    Profiles.update(this, p.id, color = Profiles.COLORS[i]); showPicker()
                }
            } },
            // A child's profile keeps its PIN: without one anybody could leave it.
            if (p.pin == null || p.kids) null else "Remove PIN" to {
                withPin(p) { Profiles.update(this, p.id, clearPin = true); showPicker() }
            },
            (if (p.kids) "Not a Kids profile" else "Make a Kids profile") to { withPin(p) { setKids(p, !p.kids) } },
            "Remove profile" to { withPin(p) { showPicker(); confirmRemove(p) } },
        )
        choose(p.name, items.map { it.first }) { i -> items[i].second() }
    }

    /**
     * Flags a profile as a child's, or back to an ordinary one. Becoming a Kids profile needs a PIN to leave by, so one
     * without a PIN chooses it first; cancelling (Back) leaves the profile as it was. Turning Kids off keeps the PIN
     * as an ordinary one.
     */
    private fun setKids(p: Profile, on: Boolean) {
        if (!on || p.pin != null) { Profiles.update(this, p.id, kids = on); return showPicker() }
        askPin("Choose the PIN that leaves ${p.name}", ::showPicker) { first ->
            askPin("Enter it again", ::showPicker, "PINs don't match. Try again.") { second ->
                (second == first).also { same ->
                    if (same) { Profiles.update(this, p.id, pin = first, kids = true); showPicker() }
                }
            }
            true
        }
    }

    /** Runs [then] straight away, or once the profile's PIN is typed, so a locked profile can't be changed around it. */
    private fun withPin(p: Profile, then: () -> Unit) {
        val pin = p.pin ?: return then()
        askPin("Enter ${p.name}'s PIN", ::showPicker) { (it == pin).also { ok -> if (ok) then() } }
    }

    private fun setPin(p: Profile) {
        askPin("Choose a PIN for ${p.name}", ::showPicker) { first ->
            askPin("Enter it again", ::showPicker, "PINs don't match. Try again.") { second ->
                (second == first).also { same ->
                    if (same) { Profiles.update(this, p.id, pin = first); showPicker() }
                }
            }
            true
        }
    }

    private fun rename(p: Profile) {
        ++pickerGeneration
        backTo = ::showPicker
        val previewName = text(p.name, BODY, bold = true).apply { gravity = Gravity.CENTER }
        val avatar = onboardingAvatar(p.name.firstOrNull()?.uppercase().orEmpty(), 100, p.color, p.picture)
        val error = text("", SMALL, color = ALERT)
        val (name, keyboard, firstKey) = nameEntry(p.name) { typed ->
            previewName.text = typed
            avatar.text = if (p.picture == null) typed.firstOrNull()?.uppercase().orEmpty() else ""
            error.text = ""
        }
        val save = onboardingButton("Save")
        val go = {
            name.text.toString().trim().takeIf { it.isNotEmpty() }
                ?.let { Profiles.update(this, p.id, name = it); showPicker() }
                ?: run { error.text = "Enter a name to save." }
        }
        name.setOnEditorActionListener { _, _, _ -> go(); true }
        save.setOnClickListener { go() }
        val copy = column(Gravity.START,
            text("Rename ${p.name}", SERIF_TITLE, serif = true),
            text("History and add-ons stay as they are.", BODY, color = TEXT_2).marginTop(4),
            name.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(16) } },
            keyboard.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } },
            error.marginTop(5),
            save.marginTop(7),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val preview = column(Gravity.CENTER, avatar, previewName.marginTop(16)).apply { gravity = Gravity.CENTER }
        setWindowed(onboardingFrame("", copy, preview, dp(65), 1.05f, 0.95f), onboardingBackdrop())
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        firstKey.requestFocus()
    }

    /**
     * Four digit boxes for a TV remote: Up/Down turn the focused digit, Left/Right move between boxes, number keys
     * type and move on. OK (or the fourth number key) hands the PIN to [check]; false shakes the boxes and clears them.
     */
    private fun askPin(title: String, back: () -> Unit, errorMessage: String = "Incorrect PIN. Try again.", check: (String) -> Boolean) {
        backTo = back
        val digits = IntArray(4) { -1 }
        var at = 0
        val boxes = List(4) {
            text("", CODE, bold = true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(76)).apply { marginStart = dp(8); marginEnd = dp(8) }
            }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; isFocusable = true; boxes.forEach { addView(it) } }
        val error = text("", BODY, color = ALERT)
        fun draw() = boxes.forEachIndexed { i, b ->
            b.text = when {
                digits[i] < 0 -> ""
                i == at -> "${digits[i]}"
                else -> "•"
            }
            b.background = glass(14, i == at)
        }
        fun submit() {
            if (digits.any { it < 0 }) return
            if (check(digits.joinToString("") { "$it" })) return
            digits.fill(-1); at = 0; draw()
            error.text = errorMessage
            row.animate().translationX(dp(16).toFloat()).setDuration(50).withEndAction {
                row.animate().translationX(-dp(16).toFloat()).setDuration(80).withEndAction {
                    row.animate().translationX(0f).setDuration(50).start()
                }.start()
            }.start()
        }
        row.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            error.text = ""
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> digits[at] = (digits[at] + 1).mod(10)
                KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = if (digits[at] <= 0) 9 else digits[at] - 1
                KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] >= 0) at = minOf(3, at + 1)
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    digits[at] = code - KeyEvent.KEYCODE_0
                    if (at < 3) at++ else { draw(); submit(); return@setOnKeyListener true }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { submit(); return@setOnKeyListener true }
                else -> return@setOnKeyListener false
            }
            draw()
            true
        }
        draw()
        val content = column(Gravity.CENTER_HORIZONTAL,
            text(title, SERIF_TITLE, bold = true, serif = true),
            row.marginTop(36),
            error.marginTop(12),
            text("▲ ▼ change a digit    ◀ ▶ move    OK to confirm", SMALL, color = TEXT_2).marginTop(28),
        ).apply { gravity = Gravity.CENTER }
        setWindowed(content, onboardingBackdrop())
        row.requestFocus()
    }

    private fun confirmRemove(p: Profile) {
        choose("Remove ${p.name}?", listOf("Cancel", "Remove"),
            "This signs ${p.name} out on this TV. Their Stremio account, history and add-ons stay as they are.") { i ->
            if (i == 0) return@choose
            Profiles.remove(this, p.id)
            background({ Http.forget() }) {}
            // Best effort; the TV forgets the profile either way. A sign-in another profile still uses stays signed in.
            if (Profiles.all(this).none { it.authKey == p.authKey }) background({ Stremio.logout(p.authKey) }) {}
            if (Profiles.all(this).isEmpty()) showName() else showPicker()
        }
    }

    // ---------- add a profile ----------

    private fun showName() {
        ++pickerGeneration
        backTo = if (Profiles.all(this).isEmpty()) null else ::showPicker
        signingIn = false
        waitingCode = null
        val first = Profiles.all(this).isEmpty()
        val previewName = text(draftName, BODY, bold = true).apply { gravity = Gravity.CENTER }
        val avatar = onboardingAvatar(draftName.firstOrNull()?.uppercase().orEmpty(), 100, draftColor, draftPicture)
        val error = text("", SMALL, color = ALERT)
        val (name, keyboard, firstKey) = nameEntry(draftName) { typed ->
            previewName.text = typed
            avatar.text = if (draftPicture == null) typed.firstOrNull()?.uppercase().orEmpty() else ""
            error.text = ""
        }
        val next = onboardingButton("Continue    →")
        val go = {
            name.text.toString().trim().takeIf { it.isNotEmpty() }?.replaceFirstChar { it.titlecase() }
                ?.let { draftName = it; showSignIn(it) }
                ?: run { error.text = "Enter a name to continue." }
        }
        name.setOnEditorActionListener { _, _, _ -> go(); true }
        next.setOnClickListener { go() }
        var redrawPictures: () -> Unit = {} // set below, once the pictures are built: a new colour redraws their discs
        val swatches = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val marks = mutableListOf<TextView>()
        Profiles.COLORS.forEachIndexed { index, color ->
            val mark = text(if (draftColor == color) "✓" else "", SMALL, bold = true, color = Tone.onColour).apply { // on a portrait colour: dark in both modes
                gravity = Gravity.CENTER
                isFocusable = true
                fun face(focused: Boolean) = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(color)
                    if (focused) setStroke(dp(2), TUNGSTEN)
                }
                background = face(false)
                setOnFocusChangeListener { view, focused -> view.background = face(focused) }
                setOnClickListener {
                    draftColor = color
                    avatar.background = portraitFace(color, draftPicture)
                    redrawPictures()
                    avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                    marks.forEachIndexed { i, view -> view.text = if (i == index) "✓" else "" }
                }
            }
            marks += mark
            swatches.addView(mark, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
        }
        val copy = column(Gravity.START,
            text(if (first) "Welcome to CAS" else "New profile", SERIF_TITLE, serif = true),
            // The first screen says what CAS is and what to do, in plain words; a later profile only needs the second half.
            text(if (first) "CAS plays films and TV from your Stremio account. Type a name for the person watching, then select Continue."
                else "Type a name for this person, choose a colour, then select Continue.", BODY, color = TEXT_2).marginTop(4),
            name.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(16) } },
            keyboard.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } },
            caps("Portrait colour").marginTop(13),
            swatches.marginTop(7),
            error.marginTop(5),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        // Continue stands on its own under the portrait, in plain sight and one press to the right of the keyboard
        // (Alan, 2026-11-12: at the foot of the left column it was past the bottom of the screen, and the way on
        // was not clear).
        // The picture: the initial, or one of twelve animals, in two rows under the portrait. OK sets it at once.
        val pictures = column(Gravity.CENTER)
        val choices = listOf<String?>(null) + Animals.all.map { it.first }
        fun drawPictures() {
            pictures.removeAllViews()
            choices.chunked(7).forEach { line ->
                pictures.addView(LinearLayout(this).apply {
                    clipChildren = false
                    line.forEach { pic ->
                        val chosen = pic == draftPicture
                        addView(text(if (pic == null) name.text.toString().trim().firstOrNull()?.uppercase() ?: "A" else "", SMALL, bold = true, color = Tone.onColour).apply {
                            gravity = Gravity.CENTER
                            isFocusable = true
                            contentDescription = pic?.let(Animals::label) ?: "Initial"
                            background = portraitFace(draftColor, pic)
                            fun rim(lit: Boolean) { foreground = if (lit || chosen) GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(if (lit) 3 else 2), if (lit) TEXT else TEXT_3) } else null }
                            rim(false)
                            setOnFocusChangeListener { v, has -> rim(has); v.grow(has, CHIP_GROW).start() }
                            setOnClickListener {
                                draftPicture = pic
                                avatar.background = portraitFace(draftColor, pic)
                                avatar.text = if (pic == null) name.text.toString().trim().firstOrNull()?.uppercase().orEmpty() else ""
                                drawPictures()
                                pictures.post { (pictures.getChildAt(choices.indexOf(pic) / 7) as? LinearLayout)?.getChildAt(choices.indexOf(pic) % 7)?.requestFocus() }
                            }
                        }, LinearLayout.LayoutParams(dp(PICTURE), dp(PICTURE)).apply { marginStart = dp(4); marginEnd = dp(4) })
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            }
        }
        drawPictures()
        redrawPictures = { drawPictures() }
        val preview = column(Gravity.CENTER,
            avatar,
            previewName.marginTop(12),
            caps("Picture").marginTop(14),
            pictures,
            next.marginTop(20),
        ).apply { gravity = Gravity.CENTER; clipChildren = false }
        val content = onboardingFrame(if (first) "step 1 of 2" else "step 1 of 3", copy, preview, dp(65), 1.05f, 0.95f)
        setWindowed(content, onboardingBackdrop())
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        firstKey.requestFocus()
    }

    /**
     * The name box and its remote keyboard: letters, a one-shot ⇧ (on while the box is empty, so names start with a
     * capital), space, ⌫ and the system keyboard. [changed] gets the text after every edit.
     */
    private fun nameEntry(initial: String, changed: (String) -> Unit): Triple<EditText, View, View> {
        val name = EditText(this).apply {
            hint = "Your name"
            textSize = BODY
            setTextColor(TEXT)
            setHintTextColor(TEXT_3)
            setPadding(dp(14), 0, dp(14), 0)
            background = glass(8)
            setOnFocusChangeListener { v, has -> v.background = glass(8, has) }
            setSingleLine()
            showSoftInputOnFocus = false
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(initial)
            setSelection(initial.length)
        }
        nameInput = name
        val keyboard = column(Gravity.START).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8)); background = glass(10)
        }
        val keys = mutableListOf<TextView>()
        // Search's keyboard: body-size letters, the word keys a step smaller, 4 dp between keys.
        fun key(label: String, row: LinearLayout, width: Int = 1, action: () -> Unit) = text(label, if (label.length > 1) SMALL else BODY, color = TEXT_2).apply {
            gravity = Gravity.CENTER
            rowFocus(6)
            setOnClickListener { action() }
            row.addView(this, LinearLayout.LayoutParams(0, -1, width.toFloat()).apply {
                if (row.childCount > 0) marginStart = dp(KEY_GAP)
            })
            keys += this
        }
        var upper = false
        val letterKeys = mutableListOf<TextView>()
        fun caps(on: Boolean) {
            upper = on
            letterKeys.forEach { it.text = if (on) it.text.toString().uppercase() else it.text.toString().lowercase() }
        }
        "abcdefghijklmnopqrstuvwxyz".chunked(6).forEach { letters ->
            keyboard.addView(LinearLayout(this).apply {
                letters.forEach { letter -> letterKeys += key(letter.toString(), this) {
                    val typed = if (upper) letter.uppercaseChar() else letter
                    if (upper) caps(false)
                    name.text.append(typed)
                } }
                // "yz" keeps the grid: empty cells fill the row instead of two keys stretched across it.
                repeat(6 - letters.length) { addView(View(context), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(KEY_GAP) }) }
            }, LinearLayout.LayoutParams(-1, dp(KEY_H)).apply { if (keyboard.childCount > 0) topMargin = dp(KEY_GAP) })
        }
        keyboard.addView(LinearLayout(this).apply {
            key("⇧", this) { caps(!upper) }
            key("SPACE", this, 3) { name.text.append(" ") }
            key("⌫", this) { if (name.length() > 0) name.text.delete(name.length() - 1, name.length()) }
            key("⌨", this) {
                name.showSoftInputOnFocus = true
                name.requestFocus()
                name.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(name, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
            }
        }, LinearLayout.LayoutParams(-1, dp(KEY_H)).apply { topMargin = dp(KEY_GAP) })
        caps(initial.isEmpty())
        name.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) caps(true)
                changed(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        return Triple(name, keyboard, keys.first())
    }

    /** Sign-in with a code: for a new profile called [name], or for [again], a profile Stremio signed out. */
    private fun showSignIn(name: String, again: Profile? = null) {
        ++pickerGeneration
        nameInput = null
        backTo = if (again != null) ::showPicker else ::showName
        signingIn = true
        val request = ++signInGeneration
        val qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val status = text("Getting a sign-in code…", SMALL, color = TEXT_2)
        val countdown = ring(1f)
        val codeTiles = (0 until 4).map {
            text("–", CODE, bold = true).apply {
                gravity = Gravity.CENTER
                background = glass(10)
            }
        }
        val codeRow = LinearLayout(this).apply {
            codeTiles.forEach { tile -> addView(tile, LinearLayout.LayoutParams(dp(52), dp(62)).apply {
                if (childCount > 0) marginStart = dp(7)
            }) }
        }
        val back = chip("← Back")
        back.setOnClickListener { waitingCode = null; if (again != null) showPicker() else showName() }
        val left = column(Gravity.CENTER,
            FrameLayout(this).apply {
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = rounded(TEXT, 12)
                addView(qr, FrameLayout.LayoutParams(-1, -1))
            }.apply { layoutParams = LinearLayout.LayoutParams(dp(236), dp(236)) },
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(onboardingAvatar(name.take(1).uppercase(), 26, again?.color ?: draftColor))
                addView(text(name.replaceFirstChar { it.titlecase() }, BODY, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
            }.marginTop(14),
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(20), dp(15), dp(20))
            background = glass(14)
        }
        val steps = column(Gravity.START,
            text(if (again != null) "Sign in again" else "Connect Stremio", SERIF_HERO, serif = true).onArt(),
            text(if (again != null) "Stremio signed $name out of this TV. Scan with $name's phone to connect again. $name's settings and PIN stay."
                else "Scan with $name's phone. Stremio opens and asks to sign in this TV.", BODY).onArt().marginTop(10),
            text("", BODY, color = TEXT_2).onArt().apply {
                val line = "Or go to link.stremio.com and enter"
                val at = line.indexOf("link.stremio.com")
                text = android.text.SpannableString(line).apply {
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + 16, 0)
                    setSpan(android.text.style.ForegroundColorSpan(TEXT), at, at + 16, 0)
                }
            }.marginTop(22),
            codeRow.marginTop(8),
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(countdown, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(status, wrap().apply { marginStart = dp(9) })
            }.marginTop(17),
            // Two people approving on one phone sign each other out: switching accounts there can end the other's sign-in.
            text("Sharing a phone? Approve each person's code on their own phone or in a private browser window. Signing out of Stremio there can sign this TV out too.",
                SMALL, color = TEXT_2).onArt().marginTop(24),
            text("New to Stremio? Make an account and add your add-ons in the Stremio app or at web.stremio.com. CAS brings them to the TV.",
                SMALL, color = TEXT_3).onArt().marginTop(10),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val first = Profiles.all(this).isEmpty()
        val content = onboardingFrame(if (again != null) "sign in again" else if (first) "step 2 of 2" else "step 2 of 3", left, steps, dp(55), 0f, 1f, dp(300))
        content.addView(back, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { // chip() sets its own height
            leftMargin = dp(PAD); bottomMargin = dp(40)
        })
        setWindowed(content, onboardingBackdrop())
        back.requestFocus()

        waitingCode = null
        background({ Stremio.createLink() }) { result ->
            if (!signingIn || request != signInGeneration) return@background
            result.onSuccess { link ->
                runCatching { qrBitmap(link.url, dp(200)) }.getOrNull()?.let(qr::setImageBitmap) // a long link: the typed code still works
                codeTiles.forEachIndexed { i, tile -> tile.text = link.code.getOrNull(i)?.toString() ?: "" }
                val started = SystemClock.elapsedRealtime()
                fun tick() {
                    if (!signingIn || request != signInGeneration || waitingCode != link.code) return
                    val leftSeconds = ((5 * 60_000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0) / 1000).toInt()
                    status.text = "Waiting for your phone · new code in ${leftSeconds / 60}:${"%02d".format(leftSeconds % 60)}"
                    countdown.progress = leftSeconds * 1000 / 300
                    main.postDelayed(::tick, 1_000)
                }
                waitingCode = link.code
                tick()
                poll(name, link.code, started, again)
            }.onFailure {
                status.text = "Can't reach Stremio. Trying again…"
                main.postDelayed({ if (signingIn && request == signInGeneration) showSignIn(name, again) }, 5_000)
            }
        }
    }

    private fun onboardingBackdrop(useLastFilm: Boolean = true) = ImageView(this).apply {
        // The backdrop is a dark picture, for the dark mode. On the light ground the screen is the ground itself: the
        // dark picture under a pale wash was a grey smudge behind the profile's disc (the launch test's picture, 1.63.0).
        if (Tone.light) setBackgroundColor(BASE) else setImageResource(R.drawable.cas_backdrop)
        scaleType = ImageView.ScaleType.CENTER_CROP
        // Dim, as the doc asks: the copy and code sit over it. A darkening filter, not alpha: the window has no
        // background, so a see-through backdrop would show the last frame instead of darkening.
        if (useLastFilm) setColorFilter(veil(if (Tone.light) 0xD9 else 0xA6), android.graphics.PorterDuff.Mode.SRC_ATOP)
        if (useLastFilm) Profiles.active(this@ProfilesActivity)?.let { profile ->
            background({ Stremio.savedLibrary(profile.authKey)?.let(Library::continueWatching)?.firstOrNull()?.optString("_id") }) { result ->
                result.getOrNull()?.let(Images::backgroundFor)?.let { url -> Images.load(url, this, resources.displayMetrics.widthPixels) }
            }
        }
    }

    private fun onboardingFrame(step: String, left: View, right: View, gap: Int, leftWeight: Float, rightWeight: Float, leftWidth: Int = 0) =
        android.widget.FrameLayout(this).apply {
            val body = LinearLayout(this@ProfilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(PAD), dp(68), dp(PAD), dp(35))
                addView(left, if (leftWidth > 0)
                    LinearLayout.LayoutParams(leftWidth, -2).apply { marginEnd = gap }
                else LinearLayout.LayoutParams(0, -1, leftWeight).apply { marginEnd = gap })
                addView(right, LinearLayout.LayoutParams(0, -1, rightWeight))
            }
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -1))
            val current = step.substringAfter("step ").substringBefore(" of ").toIntOrNull() ?: 1
            val total = step.substringAfter(" of ").toIntOrNull() ?: 2
            addView(LinearLayout(this@ProfilesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(PAD), dp(31), dp(PAD), 0)
                addView(brand(), LinearLayout.LayoutParams(0, -2, 1f))
                if (step.isEmpty()) return@apply // a profile's own screens (rename) aren't part of a setup
                addView(text((if (total > 2) "New profile · " else "Welcome · ") + step, SMALL, color = TEXT_2))
                repeat(total) { index -> addView(View(this@ProfilesActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(if (index < current) TUNGSTEN else GLASS_EDGE)
                    }
                }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginStart = dp(5) }) }
            }, android.widget.FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP))
        }

    private fun onboardingButton(label: String) = pill(label)

    // 48 and 19 are glyph sizes for the two fixed discs (100 and 26 dp), not text sizes: they go with the disc.
    private fun onboardingAvatar(initial: String, size: Int, color: Int = draftColor, picture: String? = null) = text(if (picture == null) initial else "", if (size > 50) 48f else 19f,
        serif = true, color = Tone.onColour).apply { // the initial on its portrait colour: dark in both modes
        gravity = Gravity.CENTER
        background = portraitFace(color, picture) // the animal, when the profile has one, in place of the initial
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun poll(name: String, code: String, startedAt: Long, again: Profile?) {
        main.postDelayed({
            if (waitingCode != code) return@postDelayed
            background({ Stremio.readLink(code) }) { result ->
                if (waitingCode != code) return@background
                val key = result.getOrNull()
                when {
                    key != null -> {
                        waitingCode = null
                        signingIn = false
                        approved(name, key, again)
                    }
                    // ponytail: Stremio doesn't say when a code expires (waiting and expired look the same),
                    // so a fresh code every 5 minutes. Read the expiry if their API ever exposes it.
                    SystemClock.elapsedRealtime() - startedAt > 5 * 60_000 -> showSignIn(name, again)
                    else -> poll(name, code, startedAt, again) // not approved yet, or a network blip
                }
            }
        }, 3_000)
    }

    /**
     * A code was approved. When that account is already another profile here (approved on a phone still signed in to
     * the other person), say so before going on. Each sign-in has its own key, so the accounts are compared by user id.
     */
    private fun approved(name: String, key: String, again: Profile?) {
        val others = Profiles.all(this).filter { it.id != again?.id }
        background({
            others.firstOrNull { it.authKey == key } ?: Stremio.userId(key).let { account ->
                others.firstOrNull { runCatching { Stremio.userId(it.authKey) }.getOrNull() == account }
            }
        }) { result ->
            val same = result.getOrNull() ?: return@background signedIn(name, key, again) // unknown: don't hold anyone up
            choose("This is ${same.name}'s Stremio account", listOf("Use another account", "Continue anyway"),
                "Approve the code with $name's own account: on $name's phone, or in a private browser window. " +
                    "Signing out of Stremio on the phone can sign ${same.name} out of this TV.") { i ->
                if (i == 0) showSignIn(name, again) else signedIn(name, key, again)
            }
        }
    }

    private fun signedIn(name: String, key: String, again: Profile?) {
        if (again != null) {
            Profiles.signIn(this, again.id, key)
            return openHome(Profiles.all(this).first { it.id == again.id })
        }
        val others = Profiles.all(this)
        val profile = Profiles.add(this, name, key, draftColor, draftPicture)
        draftPicture = null
        draftName = ""
        draftColor = Profiles.COLORS[0]
        // Each profile brings its own add-ons (stream add-ons are shared in Settings, never copied);
        // the others' lists only tell where Watchly lives, so the new profile can set up its own.
        if (others.isEmpty()) openHome(profile)
        else background({ others.flatMap { runCatching { Stremio.addons(it.authKey) }.getOrDefault(emptyList()) } }) {
            showWatchly(profile, it.getOrDefault(emptyList()))
        }
    }

    // ---------- recommendations ----------

    /** Watchly is per person: the new profile sets up its own, connected to its own Stremio account. */
    private fun showWatchly(profile: Profile, sourceAddons: List<Addon>) {
        backTo = { openHome(profile) }
        val watchly = sourceAddons.firstOrNull { it.name.contains("watchly", ignoreCase = true) }
            ?: return showReady(profile)
        val configure = Uri.parse(watchly.transportUrl).let { "${it.scheme}://${it.authority}/configure" }
        val qr = ImageView(this).apply { runCatching { qrBitmap(configure, dp(220)) }.getOrNull()?.let(::setImageBitmap) }
        val done = onboardingButton("Continue to CAS").apply { setOnClickListener { openHome(profile) } }
        val steps = column(Gravity.START,
            text("Recommendations for ${profile.name}", SERIF_TITLE, bold = true, serif = true),
            text("1.  Scan with ${profile.name}'s phone to open Watchly\n" +
                "2.  Connect ${profile.name}'s Stremio account there\n" +
                "3.  Install it to ${profile.name}'s Stremio account\n\n" +
                "${profile.name}'s own \"Top Picks\" then show up on Home. You can finish this later.", BODY, color = TEXT_2).marginTop(16), // the screen's instructions, not a footnote
            done.marginTop(32),
        )
        setWindowed(onboardingFrame("step 3 of 3", column(Gravity.CENTER,
            qr.apply { background = rounded(TEXT, 12) },
            text(profile.name, BODY, bold = true).marginTop(12)
        ).apply { gravity = Gravity.CENTER; setPadding(dp(22), dp(22), dp(22), dp(22)); background = glass(14) },
            steps, dp(52), 0.9f, 1.1f), onboardingBackdrop())
        done.requestFocus()
    }

    private fun showReady(profile: Profile) {
        backTo = { openHome(profile) }
        val done = onboardingButton("Continue to CAS").apply { setOnClickListener { openHome(profile) } }
        setWindowed(onboardingFrame("step 3 of 3", column(Gravity.CENTER,
            onboardingAvatar(profile.name.take(1).uppercase(), 100, profile.color, profile.picture),
            text(profile.name, SERIF_TITLE, serif = true).marginTop(12)
        ).apply { gravity = Gravity.CENTER }, column(Gravity.START,
            text("You're all set", SERIF_HERO, serif = true),
            text("${profile.name}'s profile is ready. You can add more add-ons later in Settings.", BODY, color = TEXT_2).marginTop(12),
            done.marginTop(24)
        ).apply { gravity = Gravity.CENTER_VERTICAL }, dp(55), 0.9f, 1.1f), onboardingBackdrop())
        done.requestFocus()
    }

    /** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
    private fun qrBitmap(text: String, maxPx: Int): Bitmap {
        val m = Qr.encode(text)
        val n = m.size + 8
        val px = maxOf(1, maxPx / n)
        val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
        bmp.eraseColor(TEXT)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Tone.onColour } // the code's dark squares, on white: the same in both modes, or a phone cannot read it
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
        }
        return bmp
    }

    private companion object {
        const val PICTURE = 34 // dp, a picture to choose on the name screen
        const val LIFT_ROOM = 8 // dp above the portraits for the focused one's 10% lift and ring
        const val KEY_H = 30 // dp a key row on the name step: Search's keyboard, so the two feel like one
        const val KEY_GAP = 4 // dp between keys and rows, as on Search
    }
}
