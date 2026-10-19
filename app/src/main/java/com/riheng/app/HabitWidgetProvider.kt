package com.riheng.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HabitWidgetProvider : AppWidgetProvider() {
    companion object {
        const val PREFS = "riheng_widget_store"
        const val DATA_KEY = "widget_data"
        const val ACTION_TOGGLE = "com.riheng.app.ACTION_TOGGLE_HABIT"
        const val EXTRA_HABIT_ID = "habit_id"

        // 下面三个数字必须与 res/layout/widget_root.xml、widget_habit_row*.xml 里写死的 dp 一致：
        // 内边距 12+12、标题行 24、进度行 18+4、底部入口 20 = 90；每行 38。
        private const val CHROME_HEIGHT_DP = 90
        private const val ROW_HEIGHT_DP = 38
        private const val SAFETY_DP = 6
        private const val MAX_ROWS = 8
        private const val DEFAULT_HEIGHT_DP = 250

        private val WEEKDAYS = "日一二三四五六"

        /** 刷新桌面上所有 Persist 小组件。 */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HabitWidgetProvider::class.java))
            for (id in ids) manager.updateAppWidget(id, buildViews(context, manager, id))
        }

        private fun readData(context: Context): JSONObject {
            val empty = { JSONObject().put("habits", JSONArray()).put("checks", JSONObject()) }
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DATA_KEY, null) ?: return empty()
            return try { JSONObject(raw) } catch (e: Exception) { empty() }
        }

        private fun dayNumber(date: LocalDate): Int = date.dayOfWeek.value % 7 // 与 JS 一致：周日 = 0

        private fun isScheduled(habit: JSONObject, key: String): Boolean {
            return try {
                val date = LocalDate.parse(key)
                if (key < habit.optString("created", "0000-00-00")) return false
                val days = habit.optJSONArray("days") ?: JSONArray().apply { for (i in 0..6) put(i) }
                (0 until days.length()).any { days.optInt(it, -1) == dayNumber(date) }
            } catch (e: Exception) { false }
        }

        private fun isDone(checks: JSONObject, id: String, key: String): Boolean {
            val list = checks.optJSONArray(id) ?: return false
            return (0 until list.length()).any { list.optString(it) == key }
        }

        /** 桌面分配给小组件的高度（dp）。竖屏取最大高度，横屏取最小高度；取不到时用默认值。 */
        private fun allocatedHeightDp(context: Context, manager: AppWidgetManager, widgetId: Int): Int {
            val options: Bundle = manager.getAppWidgetOptions(widgetId) ?: return DEFAULT_HEIGHT_DP
            val min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val preferred = if (landscape) min else max
            return when {
                preferred > 0 -> preferred
                min > 0 -> min
                max > 0 -> max
                else -> DEFAULT_HEIGHT_DP
            }
        }

        private fun rowsFor(heightDp: Int): Int =
            ((heightDp - CHROME_HEIGHT_DP - SAFETY_DP) / ROW_HEIGHT_DP).coerceIn(1, MAX_ROWS)

        private fun openAppPending(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun togglePending(context: Context, widgetId: Int, habitId: String): PendingIntent {
            val intent = Intent(context, HabitWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
                putExtra(EXTRA_HABIT_ID, habitId)
                // data 让每个（小组件，习惯）组合拥有独立的 PendingIntent，互不覆盖
                data = Uri.parse("riheng://widget/toggle/$widgetId/${Uri.encode(habitId)}")
            }
            return PendingIntent.getBroadcast(
                context, habitId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun buildViews(context: Context, manager: AppWidgetManager, widgetId: Int): RemoteViews {
            val pkg = context.packageName
            val views = RemoteViews(pkg, R.layout.widget_root)
            val root = readData(context)
            val habits = root.optJSONArray("habits") ?: JSONArray()
            val checks = root.optJSONObject("checks") ?: JSONObject()
            val today = LocalDate.now()
            val todayKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val scheduled = (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }
                .filter { isScheduled(it, todayKey) }
            val completed = scheduled.count { isDone(checks, it.optString("id"), todayKey) }
            val maxRows = rowsFor(allocatedHeightDp(context, manager, widgetId))
            val openApp = openAppPending(context)

            views.setTextViewText(
                R.id.widgetDate,
                today.format(DateTimeFormatter.ofPattern("M月d日")) + " · 周" + WEEKDAYS[dayNumber(today)]
            )
            views.setTextViewText(
                R.id.widgetProgress,
                when {
                    habits.length() == 0 -> "还没有习惯"
                    scheduled.isEmpty() -> "今天没有安排"
                    completed == scheduled.size -> "今天的计划完成了"
                    else -> "已完成 $completed / ${scheduled.size} 项"
                }
            )
            views.removeAllViews(R.id.widgetRows)

            var shown = 0
            if (scheduled.isEmpty()) {
                val row = RemoteViews(pkg, R.layout.widget_habit_row)
                row.setTextViewText(R.id.rowIcon, if (habits.length() == 0) "＋" else "☾")
                row.setTextViewText(R.id.rowName, if (habits.length() == 0) "添加第一个习惯" else "今天没有安排，好好休息")
                row.setViewVisibility(R.id.rowCheckArea, View.GONE)
                row.setOnClickPendingIntent(R.id.rowRoot, openApp)
                views.addView(R.id.widgetRows, row)
            } else {
                scheduled.take(maxRows).forEach { habit ->
                    val id = habit.optString("id")
                    val name = habit.optString("name", "习惯")
                    val done = isDone(checks, id, todayKey)
                    val row = RemoteViews(pkg, if (done) R.layout.widget_habit_row_done else R.layout.widget_habit_row)
                    // 页面同步时会带上 widgetIcon（图标库图标对应的 emoji）；旧数据没有这个字段，就用原来的 emoji 字段
                    val icon = habit.optString("widgetIcon", "").ifEmpty { habit.optString("emoji", "🌿") }
                        .let { if (it.startsWith("data:image/") || it.startsWith("i:")) "🌿" else it }
                    row.setTextViewText(R.id.rowIcon, icon)
                    row.setTextViewText(R.id.rowName, name)
                    row.setContentDescription(R.id.rowCheckArea, (if (done) "取消打卡：" else "打卡：") + name)
                    row.setOnClickPendingIntent(R.id.rowCheckArea, togglePending(context, widgetId, id))
                    row.setOnClickPendingIntent(R.id.rowRoot, openApp)
                    views.addView(R.id.widgetRows, row)
                    shown++
                }
            }

            val hidden = scheduled.size - shown
            views.setTextViewText(R.id.widgetOpen, if (hidden > 0) "还有 $hidden 项 · 打开 Persist ↗" else "打开 Persist ↗")
            views.setOnClickPendingIntent(R.id.widgetOpen, openApp)
            views.setOnClickPendingIntent(R.id.widgetRoot, openApp)
            return views
        }

        private fun toggleHabit(context: Context, habitId: String) {
            val root = readData(context)
            val habits = root.optJSONArray("habits") ?: JSONArray()
            val habit = (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }
                .firstOrNull { it.optString("id") == habitId } ?: return
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            if (!isScheduled(habit, today)) return
            val checks = root.optJSONObject("checks") ?: JSONObject().also { root.put("checks", it) }
            val list = checks.optJSONArray(habitId) ?: JSONArray()
            val existing = (0 until list.length()).map { list.optString(it) }
            val updated = if (today in existing) existing.filter { it != today } else existing + today
            val sorted = JSONArray()
            updated.distinct().sorted().forEach { sorted.put(it) }
            checks.put(habitId, sorted)
            // 用 commit()：广播接收器随时可能结束，必须保证这次打卡已经落盘
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DATA_KEY, root.toString()).commit()
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) manager.updateAppWidget(id, buildViews(context, manager, id))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        manager.updateAppWidget(appWidgetId, buildViews(context, manager, appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
                toggleHabit(context, habitId)
                updateAll(context)
            }
            // 跨天 / 改时间 / 改时区：重新按「今天」渲染
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> updateAll(context)
        }
    }
}
