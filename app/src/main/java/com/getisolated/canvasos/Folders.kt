package com.getisolated.canvasos

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** A folder on the home grid: a name and the grid keys of its apps, in the order they show inside it. */
data class Folder(val id: String, val name: String, val apps: List<String>)

/**
 * The home grid's folders, kept in [Prefs.folders] as a JSON array
 * (`[{"id":"k3x9qa","name":"Streaming","apps":["com.netflix.ninja", …]}]`). A folder is a grid entry
 * of its own, keyed [key], that takes part in [Prefs.order] like an app. Pure: every edit returns a
 * new list, an app is in one folder at most, and an id that does not exist leaves the list as it was.
 */
object Folders {
    const val PREFIX = "folder:"
    const val DEFAULT_NAME = "Dossier"
    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val ID_LENGTH = 6
    private val ID = Regex("[A-Za-z0-9_-]{1,32}")

    fun key(id: String) = PREFIX + id
    fun isFolder(key: String) = key.startsWith(PREFIX)
    fun idOf(key: String): String? = if (isFolder(key)) key.removePrefix(PREFIX) else null

    /** A name as kept: trimmed, at most [Labels.MAX] characters, [DEFAULT_NAME] when blank. */
    fun cleanName(name: String?): String = name?.trim()?.take(Labels.MAX)?.takeIf { it.isNotEmpty() } ?: DEFAULT_NAME

    /** What can go in a folder: any grid key but a folder's (no folders in folders). */
    private fun canHold(app: String) = app.isNotBlank() && !isFolder(app)

    /**
     * Folders from [json]; broken JSON is no folders. A repeated id keeps its first folder, and an
     * app listed twice stays in the first folder that has it, so whatever was saved, the invariants hold.
     */
    fun decode(json: String): List<Folder> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val ids = HashSet<String>()
        val taken = HashSet<String>()
        val out = ArrayList<Folder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!ID.matches(id) || !ids.add(id)) continue
            val apps = ArrayList<String>()
            o.optJSONArray("apps")?.let { a ->
                for (j in 0 until a.length()) {
                    val app = a.optString(j).trim()
                    if (canHold(app) && taken.add(app)) apps += app
                }
            }
            out += Folder(id, cleanName(o.optString("name")), apps)
        }
        return out
    }

    fun encode(list: List<Folder>): String = JSONArray().apply {
        for (f in list) put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps)))
    }.toString()

    /** A short random id that no folder of [taken] has. */
    fun newId(taken: Collection<String>, random: Random = Random.Default): String {
        while (true) {
            val id = String(CharArray(ID_LENGTH) { ID_CHARS[random.nextInt(ID_CHARS.length)] })
            if (id !in taken) return id
        }
    }

    fun byId(list: List<Folder>, id: String): Folder? = list.firstOrNull { it.id == id }

    /** The folder [app] is in, or null when it is on the grid itself. */
    fun folderOf(list: List<Folder>, app: String): Folder? = list.firstOrNull { app in it.apps }

    /** Every app that is in a folder. */
    fun members(list: List<Folder>): Set<String> = list.flatMapTo(HashSet()) { it.apps }

    /**
     * [list] with a new folder [id] named [name] holding [apps], at the end; the apps leave any other
     * folder. An id already in use changes nothing.
     */
    fun create(list: List<Folder>, id: String, name: String?, apps: List<String>): List<Folder> {
        if (!ID.matches(id) || byId(list, id) != null) return list
        val held = apps.filter { canHold(it) }.distinct()
        return list.map { f -> f.copy(apps = f.apps - held.toSet()) } + Folder(id, cleanName(name), held)
    }

    fun rename(list: List<Folder>, id: String, name: String?): List<Folder> =
        list.map { if (it.id == id) it.copy(name = cleanName(name)) else it }

    /** [list] without folder [id]; its apps are on no folder any more (the caller puts them back on the grid). */
    fun delete(list: List<Folder>, id: String): List<Folder> = list.filter { it.id != id }

    /**
     * [app] added at the end of folder [id], out of whichever folder held it before (moving it from
     * one folder to another). Unchanged when [id] does not exist or [app] is already there.
     */
    fun add(list: List<Folder>, id: String, app: String): List<Folder> {
        if (!canHold(app) || byId(list, id) == null) return list
        if (app in byId(list, id)!!.apps) return list
        return list.map { f ->
            when {
                f.id == id -> f.copy(apps = f.apps + app)
                app in f.apps -> f.copy(apps = f.apps - app)
                else -> f
            }
        }
    }

    /** [app] taken out of its folder, which stays even when empty. */
    fun remove(list: List<Folder>, app: String): List<Folder> =
        list.map { if (app in it.apps) it.copy(apps = it.apps - app) else it }

    /*
     * Grid order. [grid] is what the home grid shows now, in order; [saved] is Prefs.order. The grid
     * comes first so that every entry it shows keeps its place; the saved order follows for entries
     * that are not shown (hidden apps, apps in folders), which keep their rank for when they come back.
     */

    /** The order with [old] (at its place on the grid) replaced by [new]: a folder made from an app, or a deleted folder's apps. */
    fun orderReplacing(grid: List<String>, saved: List<String>, old: String, new: List<String>): List<String> =
        (grid.flatMap { if (it == old) new else listOf(it) } + new + saved).distinct().filter { it != old || old in new }

    /** The order with [key] after everything the grid shows: an app that leaves a folder lands at the end. */
    fun orderAtEnd(grid: List<String>, saved: List<String>, key: String): List<String> =
        ((grid - key) + key + saved).distinct()
}
