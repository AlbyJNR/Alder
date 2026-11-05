package dev.alder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The viewer's own lists ("AC", "Kids", "Film night"): rows on Home under Continue, filled by holding OK on any title.
 * Stremio has one library and no lists of its own, so these live on this TV, per profile, as JSON in preferences:
 * [{id, name, items: [{id, type, name, poster}]}]. Small: names and ids, no pictures.
 */
object Lists {
    class Entry(val id: String, val type: String, val name: String, val poster: String?)
    class ListDef(val id: String, var name: String, val items: MutableList<Entry>)

    private fun prefs(c: Context) = c.getSharedPreferences("lists", Context.MODE_PRIVATE)
    private fun key(c: Context) = "lists@${Profiles.active(c)?.id}"

    fun all(c: Context): List<ListDef> = runCatching {
        JSONArray(prefs(c).getString(key(c), "[]")).objects().map { l ->
            ListDef(l.getString("id"), l.optString("name"), l.optJSONArray("items").objects().map {
                Entry(it.getString("id"), it.optString("type"), it.optString("name"), it.text("poster"))
            }.toMutableList())
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, lists: List<ListDef>) {
        val json = JSONArray(lists.map { l ->
            JSONObject().put("id", l.id).put("name", l.name).put("items", JSONArray(l.items.map { e ->
                JSONObject().put("id", e.id).put("type", e.type).put("name", e.name).put("poster", e.poster ?: JSONObject.NULL)
            }))
        })
        prefs(c).edit().putString(key(c), json.toString()).apply()
    }

    fun create(c: Context, name: String): ListDef {
        val lists = all(c).toMutableList()
        val made = ListDef("l${System.currentTimeMillis()}", name.trim(), mutableListOf())
        lists += made
        save(c, lists)
        return made
    }

    fun rename(c: Context, id: String, name: String) = save(c, all(c).onEach { if (it.id == id) it.name = name.trim() })
    fun remove(c: Context, id: String) = save(c, all(c).filterNot { it.id == id })

    /** Adds [entry] to list [id], or takes it out when it's there already: one action for the hold-OK menu. */
    fun toggle(c: Context, id: String, entry: Entry): Boolean {
        val lists = all(c)
        val list = lists.firstOrNull { it.id == id } ?: return false
        val had = list.items.any { it.id == entry.id }
        if (had) list.items.removeAll { it.id == entry.id } else list.items.add(0, entry)
        save(c, lists)
        return !had
    }

    /** Puts [entryId] first in list [id]: the row's leftmost card, where the remote lands. */
    fun toFront(c: Context, id: String, entryId: String) {
        val lists = all(c)
        val list = lists.firstOrNull { it.id == id } ?: return
        val at = list.items.indexOfFirst { it.id == entryId }
        if (at <= 0) return
        list.items.add(0, list.items.removeAt(at))
        save(c, lists)
    }

    /** Changes with every edit: Home compares it to know whether its rows need redrawing. */
    fun signature(c: Context): String = prefs(c).getString(key(c), "").orEmpty().hashCode().toString()
}
