package com.riheng.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 小组件 / 快速添加窗口读写待办的地方。数据和网页是同一份 JSON（DATA_KEY 里的 todos），
 * 字段含义与网页一致；应用回到前台时网页会读到这里的改动。
 * 分组规则也与网页一致：没完成且没日期 = 待确定；没完成且有日期 = 已确定（≤ 今天算「今天」，含逾期；> 今天算「未来」）。
 */
object TodoData {
    const val TAB_OK = "ok"
    const val TAB_PENDING = "pending"
    private const val TAB_KEY = "todo_tab"
    private const val FLASH_KEY = "todo_flash"
    const val FLASH_MS = 1000L

    /** flash：刚完成 / 刚撤销的那一行，短时间内高亮；刚完成的那行先留在原位，之后再沉到底部。 */
    class Item(
        val id: String, val title: String, val due: String, val time: String,
        val done: Boolean, val flash: String
    )

    enum class Mood { IDLE, CHEER, PARTY, WORRY }
    class Status(val mood: Mood, val text: String, val colorRes: Int)

    private fun setFlash(c: Context, id: String, kind: String) {
        prefs(c).edit().putString(FLASH_KEY, "$id|$kind|${System.currentTimeMillis()}").apply()
    }

    private fun flashOf(c: Context): Triple<String, String, Long>? {
        val p = (prefs(c).getString(FLASH_KEY, null) ?: return null).split("|")
        if (p.size != 3) return null
        val at = p[2].toLongOrNull() ?: return null
        return if (System.currentTimeMillis() - at in 0..FLASH_MS) Triple(p[0], p[1], at) else null
    }

    private fun prefs(c: Context) = c.getSharedPreferences(HabitWidgetProvider.PREFS, Context.MODE_PRIVATE)

    fun getTab(c: Context): String = prefs(c).getString(TAB_KEY, TAB_OK) ?: TAB_OK
    fun setTab(c: Context, tab: String) { prefs(c).edit().putString(TAB_KEY, tab).apply() }

    fun todayKey(): String = LocalDate.now().toString()

    private fun readRoot(c: Context): JSONObject {
        val raw = prefs(c).getString(HabitWidgetProvider.DATA_KEY, null)
        val root = try { if (raw == null) JSONObject() else JSONObject(raw) } catch (e: Exception) { JSONObject() }
        if (!root.has("habits")) root.put("habits", JSONArray())
        if (!root.has("checks")) root.put("checks", JSONObject())
        if (root.optJSONArray("todos") == null) root.put("todos", JSONArray())
        return root
    }

    private fun writeRoot(c: Context, root: JSONObject) {
        // commit()：广播接收器随时会结束，改动必须马上落盘
        prefs(c).edit().putString(HabitWidgetProvider.DATA_KEY, root.toString()).commit()
    }

    private fun sortKey(o: JSONObject): String =
        o.optString("due", "9999-99-99").ifEmpty { "9999-99-99" } + " " + o.optString("time", "").ifEmpty { "99:99" }

    /**
     * 某个分组里的待办：没完成的按日期、时间从早到晚；已完成的沉到最下面（也是从早到晚）。
     * 已确定 = 有日期；待确定 = 没日期（最新的在前）。
     */
    fun items(c: Context, tab: String): List<Item> {
        val todos = readRoot(c).optJSONArray("todos") ?: JSONArray()
        val fl = flashOf(c)
        val all = (0 until todos.length()).mapNotNull { todos.optJSONObject(it) }.filter { it.optString("title").isNotBlank() }
        val inTab = all.filter { (it.optString("due").isEmpty()) == (tab == TAB_PENDING) }
        fun isFlashDone(o: JSONObject) = fl != null && fl.second == "done" && fl.first == o.optString("id")
        val open = inTab.filter { !it.optBoolean("done", false) || isFlashDone(it) }
        val openSorted = if (tab == TAB_PENDING)
            open.sortedWith(compareByDescending<JSONObject> { it.optString("created") }.thenByDescending { it.optLong("u", 0L) })
        else open.sortedBy { sortKey(it) }
        val done = inTab.filter { it.optBoolean("done", false) && !isFlashDone(it) }
            .sortedBy { it.optLong("doneAt", 0L) }.takeLast(40)
        return (openSorted + done).map {
            val id = it.optString("id")
            val flash = if (fl != null && fl.first == id) fl.second else ""
            Item(id, it.optString("title"), it.optString("due"), it.optString("time"), it.optBoolean("done", false), flash)
        }
    }

    /** 小组件顶部的状态：文字、颜色、小成的表情，会随完成度变化。 */
    fun status(c: Context): Status {
        val todos = readRoot(c).optJSONArray("todos") ?: JSONArray()
        val today = todayKey()
        val zone = ZoneId.systemDefault()
        var overdue = 0; var todayN = 0; var future = 0; var open = 0; var doneToday = 0
        for (i in 0 until todos.length()) {
            val t = todos.optJSONObject(i) ?: continue
            if (t.optString("title").isBlank()) continue
            val due = t.optString("due")
            if (t.optBoolean("done", false)) {
                val at = t.optLong("doneAt", 0L)
                if (at > 0 && java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString() == today) doneToday++
            } else if (due.isNotEmpty()) {            // 待确定的不算在「今天 / 未来」里
                open++
                if (due < today) overdue++ else if (due == today) todayN++ else future++
            }
        }
        val fut = if (future > 0) " · 未来 $future 件" else ""
        return when {
            overdue > 0 -> Status(Mood.WORRY, "逾期 $overdue 件 · 今天 $todayN 件$fut", R.color.widget_warn)
            open == 0 && doneToday > 0 -> Status(Mood.PARTY, "所有任务都完成啦！", R.color.widget_good)
            todayN == 0 && doneToday > 0 -> Status(Mood.PARTY, "今天的都完成啦$fut", R.color.widget_good)
            doneToday > 0 -> Status(Mood.CHEER, "今天 $todayN 件，已完成 $doneToday$fut", R.color.widget_accent)
            todayN > 0 -> Status(Mood.IDLE, "今天 $todayN 件$fut", R.color.widget_muted)
            future > 0 -> Status(Mood.IDLE, "今天没有安排 · 未来 $future 件", R.color.widget_muted)
            else -> Status(Mood.IDLE, "今天没有安排，轻松一下", R.color.widget_muted)
        }
    }

    fun counts(c: Context): Pair<Int, Int> {
        val todos = readRoot(c).optJSONArray("todos") ?: JSONArray()
        var ok = 0; var pending = 0
        for (i in 0 until todos.length()) {
            val t = todos.optJSONObject(i) ?: continue
            if (t.optBoolean("done", false) || t.optString("title").isBlank()) continue
            if (t.optString("due").isEmpty()) pending++ else ok++
        }
        return Pair(ok, pending)
    }

    /** 重复待办完成后的下一个日期：从原日期往后数，直到晚于今天。 */
    private fun nextDue(due: String, repeat: String): String {
        val today = LocalDate.now()
        val start = try { LocalDate.parse(due) } catch (e: Exception) { today }
        var d = start
        var i = 0
        do {
            i++
            d = when (repeat) {
                "daily" -> d.plusDays(1)
                "weekdays" -> { var n = d.plusDays(1); while (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) n = n.plusDays(1); n }
                "weekly" -> d.plusDays(7)
                "monthly" -> start.plusMonths(i.toLong())
                else -> d.plusDays(1)
            }
        } while (!d.isAfter(today) && i < 4000)
        return d.toString()
    }

    /** 点圈完成。重复待办不会进「已完成」，而是顺延到下一个日期。返回是否真的改了。 */
    fun complete(c: Context, id: String): Boolean {
        val root = readRoot(c)
        val todos = root.optJSONArray("todos") ?: return false
        for (i in 0 until todos.length()) {
            val t = todos.optJSONObject(i) ?: continue
            if (t.optString("id") != id || t.optBoolean("done", false)) continue
            val now = System.currentTimeMillis()
            val repeat = t.optString("repeat")
            if (repeat.isNotEmpty() && t.optString("due").isNotEmpty()) t.put("due", nextDue(t.optString("due"), repeat))
            else { t.put("done", true); t.put("doneAt", now); setFlash(c, id, "done") }
            t.put("u", now)
            writeRoot(c, root)
            return true
        }
        return false
    }

    /** 点已完成的圈：撤销完成，回到原来的分组。 */
    fun uncomplete(c: Context, id: String): Boolean {
        val root = readRoot(c)
        val todos = root.optJSONArray("todos") ?: return false
        for (i in 0 until todos.length()) {
            val t = todos.optJSONObject(i) ?: continue
            if (t.optString("id") != id || !t.optBoolean("done", false)) continue
            t.put("done", false); t.put("doneAt", 0); t.put("u", System.currentTimeMillis())
            setFlash(c, id, "undo")
            writeRoot(c, root)
            return true
        }
        return false
    }

    /** 新增一条待办。due 为空 = 待确定。返回新待办的 id。 */
    fun add(c: Context, title: String, due: String, time: String, repeat: String): String {
        val root = readRoot(c)
        val todos = root.optJSONArray("todos") ?: JSONArray().also { root.put("todos", it) }
        val now = System.currentTimeMillis()
        val id = "t_" + java.lang.Long.toString(now, 36) + java.lang.Integer.toString((Math.random() * 46656).toInt(), 36)
        todos.put(
            JSONObject().put("id", id).put("title", title.trim().take(120)).put("note", "")
                .put("due", due).put("time", if (due.isEmpty()) "" else time).put("prio", 0).put("list", "")
                .put("done", false).put("doneAt", 0).put("subs", JSONArray())
                .put("repeat", if (due.isEmpty()) "" else repeat).put("created", todayKey()).put("u", now)
        )
        writeRoot(c, root)
        if (due.isNotEmpty() && time.isNotEmpty()) {
            try {
                val at = LocalDate.parse(due).atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at > now) TodoReminders.add(c, id, title.trim().take(120), at)
            } catch (e: Exception) { /* 时间格式异常就不提醒 */ }
        }
        return id
    }
}
