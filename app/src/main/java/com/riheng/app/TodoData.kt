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
    private const val RECENT_DONE_MS = 1200L

    class Item(
        val id: String, val title: String, val due: String, val time: String,
        val done: Boolean, val recent: Boolean
    )

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

    /** 某个分组里的待办：已确定 = 今天（含逾期）在前、未来在后，各自按日期和时间升序；待确定 = 最新的在前。 */
    fun items(c: Context, tab: String): List<Item> {
        val todos = readRoot(c).optJSONArray("todos") ?: JSONArray()
        val now = System.currentTimeMillis()
        val all = (0 until todos.length()).mapNotNull { todos.optJSONObject(it) }.filter {
            val visible = !it.optBoolean("done", false) || (now - it.optLong("doneAt", 0L) in 0..RECENT_DONE_MS)
            visible && it.optString("title").isNotBlank()
        }
        val picked = if (tab == TAB_PENDING) {
            all.filter { it.optString("due").isEmpty() }
                .sortedWith(compareByDescending<JSONObject> { it.optString("created") }.thenByDescending { it.optLong("u", 0L) })
        } else {
            all.filter { it.optString("due").isNotEmpty() }.sortedBy { sortKey(it) }
        }
        return picked.map {
            Item(
                it.optString("id"), it.optString("title"), it.optString("due"), it.optString("time"),
                it.optBoolean("done", false), it.optBoolean("done", false)
            )
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
            else { t.put("done", true); t.put("doneAt", now) }
            t.put("u", now)
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
