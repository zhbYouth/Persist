package com.riheng.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * 待办提醒：网页把「接下来要提醒的待办」整份交给原生（id、标题、时间戳）。
 * 原生只排「最近的那一个」闹钟；到点后发出所有已到时间的通知，再排下一个。
 * 发通知前会再核对一次待办是否还没完成（比如在小组件里已经完成了）。
 */
object TodoReminders {
    const val ACTION_FIRE = "com.riheng.app.ACTION_TODO_REMINDER"
    private const val KEY = "todo_reminders"
    private const val CHANNEL = "todo_reminder"
    private const val STALE_MS = 6 * 60 * 60 * 1000L

    private fun prefs(c: Context) = c.getSharedPreferences(HabitWidgetProvider.PREFS, Context.MODE_PRIVATE)

    private fun load(c: Context): List<Triple<String, String, Long>> {
        val arr = try { JSONArray(prefs(c).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { Triple(it.optString("id"), it.optString("title"), it.optLong("at")) }
            .filter { it.first.isNotEmpty() && it.third > 0 }
            .sortedBy { it.third }
    }

    private fun save(c: Context, items: List<Triple<String, String, Long>>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.first).put("title", it.second).put("at", it.third)) }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    private fun pending(c: Context): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).setAction(ACTION_FIRE)
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 网页发来的最新提醒列表：整份替换，并重新排最近的闹钟。 */
    fun set(c: Context, json: String) {
        val arr = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        // 网页只会交来「还没到点」的提醒，整份替换前，先把之前错过的（闹钟被系统推迟或清掉）补发出来
        val missed = load(c).filter { it.third <= now + 30_000 }
        if (missed.isNotEmpty() && Reminder.canNotify(c)) missed.forEach { if (stillOpen(c, it.first)) post(c, it.first, it.second) }
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { Triple(it.optString("id"), it.optString("title").take(120), it.optLong("at")) }
            .filter { it.first.isNotEmpty() && it.third > now - STALE_MS }
            .sortedBy { it.third }.take(300)
        save(c, items)
        scheduleNext(c)
    }

    /** 小组件里新加的、带提醒时间的待办：并入现有提醒列表。 */
    fun add(c: Context, id: String, title: String, at: Long) {
        save(c, (load(c).filter { it.first != id } + Triple(id, title, at)).sortedBy { it.third }.take(300))
        scheduleNext(c)
    }

    fun scheduleNext(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(c))
        val next = load(c).firstOrNull() ?: return
        val at = maxOf(next.third, System.currentTimeMillis() + 1500)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            // 能精确就用 setAlarmClock：它不受省电 / 休眠限制，国产系统上最不容易被推迟（代价是状态栏会显示一个闹钟图标）
            if (exact) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, open(c)), pending(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
        }
    }

    private fun open(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 2, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stillOpen(c: Context, id: String): Boolean {
        return try {
            val root = JSONObject(prefs(c).getString(HabitWidgetProvider.DATA_KEY, "{}") ?: "{}")
            val todos = root.optJSONArray("todos") ?: return true
            for (i in 0 until todos.length()) {
                val t = todos.optJSONObject(i) ?: continue
                if (t.optString("id") == id) return !t.optBoolean("done", false)
            }
            false // 已被删除
        } catch (e: Exception) { true }
    }

    fun fire(c: Context) {
        val now = System.currentTimeMillis()
        val all = load(c)
        val due = all.filter { it.third <= now + 30_000 }
        save(c, all - due.toSet())
        if (due.isNotEmpty() && Reminder.canNotify(c)) due.forEach { if (stillOpen(c, it.first)) post(c, it.first, it.second) }
        scheduleNext(c)
    }

    /** 给设置页用：{count: 已排好的提醒数, next: 下一条的时间戳(没有就是 0), exact: 能否精确定时}。 */
    fun status(c: Context): JSONObject {
        val list = load(c)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return JSONObject().put("count", list.size).put("next", list.firstOrNull()?.third ?: 0L)
            .put("exact", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
    }

    /** 立刻发一条测试通知，用来确认「通知权限、通知渠道、系统拦截」这一环是通的。 */
    fun test(c: Context): Boolean {
        if (!Reminder.canNotify(c)) return false
        post(c, "test", "测试通知：看到这条就说明提醒是通的")
        return true
    }

    private fun post(c: Context, id: String, title: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到你设定的时间，提醒这条待办"
        })
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title.ifEmpty { "待办" })
            .setContentText("待办提醒")
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .build()
        nm.notify(8000 + (id.hashCode() and 0xFFFFF), n)
    }
}
