package dev.alder

import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray

/**
 * A whole row at once ("See all"): the row's name, how many titles it has, and every one of them as a poster with
 * its name under it, seven across, to look over forty titles in the time a row shows eight. Opened by pressing on
 * past the end of a row, or from the menu a held OK gives. OK on a poster opens that title; Back returns to the row.
 */
class GridActivity : Screen() {
    private val tiles = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = runCatching { JSONArray(intent.getStringExtra("items") ?: "[]").objects() }.getOrDefault(emptyList())
        if (items.isEmpty()) return finish()
        val grid = column(Gravity.START).apply { clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(8), dp(6), dp(40)) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) }
        val head = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(text(intent.getStringExtra("title").orEmpty(), SERIF_TITLE, serif = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(if (items.size == 1) "1 title" else "${items.size} titles", SMALL, color = TEXT_3), wrap())
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BASE)
            addView(column(Gravity.START).apply {
                addView(head, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(6); marginEnd = dp(6) })
                // A pane of its own round the posters, which clips: the rows that have scrolled up stay under the name.
                addView(FrameLayout(context).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
            }, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(PAD - 6); rightMargin = dp(PAD - 6); topMargin = dp(TOP) })
        })
        items.forEachIndexed { index, item ->
            val row = (grid.getChildAt(grid.childCount - 1) as? LinearLayout)?.takeIf { index % COLUMNS != 0 }
                ?: LinearLayout(this).apply { clipChildren = false; clipToPadding = false }.also { grid.addView(it, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) }) }
            row.addView(tile(item.optString("id"), item.optString("type"), item.optString("name"), item.optString("poster").ifEmpty { null }, item.optString("year")),
                LinearLayout.LayoutParams(-2, -2).apply { if (row.childCount > 0) marginStart = dp(TILE_GAP) })
        }
        tiles.firstOrNull()?.requestFocus()
    }

    private fun tile(id: String, type: String, name: String, poster: String?, year: String): View {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(GLASS)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
            }
            clipToOutline = true
        }
        val rim = View(this)
        val title = text(name, SMALL, color = TEXT_2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val tile = column(Gravity.START).apply {
            isFocusable = true
            defaultFocusHighlightEnabled = false
            clipChildren = false
            contentDescription = name
            addView(FrameLayout(context).apply {
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(rim, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_W * 3 / 2)))
            addView(title, LinearLayout.LayoutParams(dp(TILE_W), -2).apply { topMargin = dp(7) })
            if (year.isNotEmpty()) addView(text(year, SMALL, color = TEXT_3).apply { maxLines = 1 }, LinearLayout.LayoutParams(dp(TILE_W), -2))
            setOnFocusChangeListener { v, has ->
                rim.background = if (has) GradientDrawable().apply { setStroke(dp(FOCUS_RIM), TEXT); cornerRadius = dp(8).toFloat() } else null
                title.setTextColor(if (has) TEXT else TEXT_2)
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(if (has) FOCUS_IN_MS else FOCUS_OUT_MS).start()
            }
            setOnClickListener {
                startActivity(Intent(this@GridActivity, DetailsActivity::class.java)
                    .putExtra("type", type).putExtra("id", id).putExtra("name", name).putExtra("poster", poster))
            }
        }
        tiles += tile
        Images.load(poster, image, dp(TILE_W))
        return tile
    }

    private companion object {
        const val TOP = 40
        const val COLUMNS = 7
        const val TILE_W = 104
        const val TILE_GAP = 20
    }
}
