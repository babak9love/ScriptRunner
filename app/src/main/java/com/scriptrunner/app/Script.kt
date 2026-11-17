package com.scriptrunner.app

import org.json.JSONArray
import org.json.JSONObject

/** One script block of ~/scripts.txt in the Termux home folder. */
data class Script(
    val name: String,
    val path: String,
    val port: Int = 5000,
    val interpreter: String? = null,
    val workdir: String? = null,
    /** extra command-line arguments passed to the script */
    val args: List<String> = emptyList(),
    /** path used to check that the server is up (must answer 200..399) */
    val health: String = "/",
    /** script implements /api/jobs -> show progress / done / failed notifications */
    val monitor: Boolean = false,
    /** script implements /api/unfinished -> offer to resume interrupted downloads */
    val unfinished: Boolean = false,
    /** receives links shared from other apps; e.g. "/?url={url}" */
    val shareUrl: String? = null,
    /** keep a foreground service running while this script is selected */
    val keepalive: Boolean = false,
    /** "#RRGGBB" status-bar colour; null / "auto" = take it from the page's background */
    val statusBar: String? = null,
    /** "#RRGGBB" navigation-bar colour; null / "auto" = take it from the page's background */
    val navBar: String? = null,
    /** "#RRGGBB" colour of the app's top strip; null / "auto" = take it from the page's background */
    val background: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("name", name)
        o.put("path", path)
        o.put("port", port)
        if (interpreter != null) o.put("interpreter", interpreter)
        if (workdir != null) o.put("workdir", workdir)
        if (args.isNotEmpty()) o.put("args", JSONArray(args))
        o.put("health", health)
        o.put("notify", monitor)
        o.put("unfinished", unfinished)
        if (shareUrl != null) o.put("share_url", shareUrl)
        o.put("keepalive", keepalive)
        if (statusBar != null) o.put("status_bar", statusBar)
        if (navBar != null) o.put("nav_bar", navBar)
        if (background != null) o.put("background", background)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): Script? {
            return try {
                fromObject(JSONObject(s))
            } catch (_: Exception) {
                null
            }
        }

        private fun fromObject(o: JSONObject): Script? {
            val path = o.optString("path").trim()
            if (path.isEmpty()) return null
            val name = o.optString("name").trim().ifEmpty { path.substringAfterLast('/') }
            val port = o.optInt("port", 5000)
            val interp = o.optString("interpreter").trim().ifEmpty { null }
            val wd = o.optString("workdir").trim().ifEmpty { null }
            val args = ArrayList<String>()
            o.optJSONArray("args")?.let { a ->
                for (i in 0 until a.length()) args.add(a.optString(i))
            }
            return Script(
                name, path, port, interp, wd, args,
                o.optString("health").trim().ifEmpty { "/" },
                o.optBoolean("notify", false),
                o.optBoolean("unfinished", false),
                o.optString("share_url").trim().ifEmpty { null },
                o.optBoolean("keepalive", false),
                o.optString("status_bar").trim().ifEmpty { null },
                o.optString("nav_bar").trim().ifEmpty { null },
                o.optString("background").trim().ifEmpty { null }
            )
        }

        /**
         * Parses the script list. Accepted formats:
         *
         * 1. Blocks (recommended, file ~/scripts.txt):
         *      {
         *        name = My tool
         *        path = ~/tool/app.py
         *        port = 5000
         *      },
         *      { ... }
         *    "key = value" (or "key: value"), one per line; lines starting with # are comments;
         *    commas between blocks are optional; quotes around values are optional.
         * 2. JSON: [ {"name":"X","path":"~/x/app.py","port":5000}, ... ]
         * 3. One script per line: name | path | port | interpreter
         */
        fun parseList(text: String): List<Script> {
            val t = text.trim()
            if (t.startsWith("[")) return parseJson(t)
            val blocks = parseBlocks(t)
            if (blocks.isNotEmpty()) return blocks
            if (t.startsWith("{")) return parseJson(t)
            return parsePipes(t)
        }

        private fun parseBlocks(text: String): List<Script> {
            val out = ArrayList<Script>()
            var cur: JSONObject? = null
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
                val c = cur
                if (c == null) {
                    if (line.startsWith("{")) cur = JSONObject()
                    continue
                }
                if (line.startsWith("}")) {
                    fromObject(c)?.let { out.add(it) }
                    cur = null
                    continue
                }
                val i = listOf(line.indexOf('='), line.indexOf(':')).filter { it > 0 }.minOrNull()
                    ?: continue
                val key = line.substring(0, i).trim().lowercase()
                var v = line.substring(i + 1).trim().trimEnd(',').trim()
                if (v.length >= 2 &&
                    ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
                ) {
                    v = v.substring(1, v.length - 1)
                }
                when (key) {
                    "args" -> {
                        val arr = JSONArray()
                        Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)").findAll(v).forEach {
                            arr.put(it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } })
                        }
                        c.put("args", arr)
                    }
                    "port" -> v.toIntOrNull()?.let { c.put("port", it) }
                    "notify", "unfinished", "keepalive" ->
                        c.put(key, v.lowercase() in setOf("true", "yes", "on", "1"))
                    else -> c.put(key, v)
                }
            }
            // last block without a closing brace
            cur?.let { c -> fromObject(c)?.let { out.add(it) } }
            return out
        }

        private fun parseJson(t: String): List<Script> {
            return try {
                val arr = if (t.startsWith("[")) JSONArray(t)
                else JSONObject(t).optJSONArray("scripts") ?: JSONArray()
                val out = ArrayList<Script>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    fromObject(o)?.let { out.add(it) }
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parsePipes(t: String): List<Script> {
            val out = ArrayList<Script>()
            for (raw in t.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val p = line.split("|").map { it.trim() }
                val script = if (p.size == 1) {
                    fromObject(JSONObject().put("path", p[0]))
                } else {
                    fromObject(
                        JSONObject()
                            .put("name", p[0])
                            .put("path", p[1])
                            .put("port", p.getOrNull(2)?.toIntOrNull() ?: 5000)
                            .put("interpreter", p.getOrNull(3) ?: "")
                    )
                }
                if (script != null) out.add(script)
            }
            return out
        }
    }
}
