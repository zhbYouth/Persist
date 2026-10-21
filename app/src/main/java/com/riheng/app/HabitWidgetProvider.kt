package com.riheng.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WidgetRow(val id: String, val name: String, val icon: String, val done: Boolean, val placeholder: Boolean = false)
class WidgetState(val rows: List<WidgetRow>, val habitCount: Int, val scheduled: Int, val completed: Int, val today: LocalDate)

class HabitWidgetProvider : AppWidgetProvider() {
    companion object {
        const val PREFS = "riheng_widget_store"
        const val DATA_KEY = "widget_data"
        const val ACTION_TOGGLE = "com.riheng.app.ACTION_TOGGLE_HABIT"
        const val EXTRA_HABIT_ID = "habit_id"

        private val WEEKDAYS = "日一二三四五六"

        /** 刷新桌面上所有 Persist 小组件。 */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HabitWidgetProvider::class.java))
            for (id in ids) manager.updateAppWidget(id, buildViews(context, id))
            if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetList)
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

        private fun openAppPending(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** 今天要做的习惯：没完成的在上，完成的沉到下面（各自保持习惯原有顺序），与应用内一致。 */
        fun computeState(context: Context): WidgetState {
            val root = readData(context)
            val habits = root.optJSONArray("habits") ?: JSONArray()
            val checks = root.optJSONObject("checks") ?: JSONObject()
            val today = LocalDate.now()
            val todayKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val list = (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }.filter { isScheduled(it, todayKey) }
            val (finished, pending) = list.partition { isDone(checks, it.optString("id"), todayKey) }
            val rows = (pending + finished).map { habit ->
                val id = habit.optString("id")
                // 页面同步时会带上 widgetIcon（图标库图标对应的 emoji）；旧数据没有这个字段，就用原来的 emoji 字段
                val icon = habit.optString("widgetIcon", "").ifEmpty { habit.optString("emoji", "🌿") }
                    .let { if (it.startsWith("data:image/") || it.startsWith("i:")) "🌿" else it }
                WidgetRow(id, habit.optString("name", "习惯"), icon, isDone(checks, id, todayKey))
            }
            return WidgetState(rows, habits.length(), rows.size, finished.size, today)
        }

        private fun applyHeader(views: RemoteViews, st: WidgetState) {
            views.setTextViewText(R.id.widgetDate, st.today.format(DateTimeFormatter.ofPattern("M月d日")) + " · 周" + WEEKDAYS[dayNumber(st.today)])
            views.setTextViewText(
                R.id.widgetProgress,
                when {
                    st.habitCount == 0 -> "还没有习惯"
                    st.scheduled == 0 -> "今天没有安排"
                    st.completed == st.scheduled -> "今天的计划完成了"
                    else -> "已完成 ${st.completed} / ${st.scheduled} 项"
                }
            )
        }

        private fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_root)
            val openApp = openAppPending(context)
            applyHeader(views, computeState(context))
            // 习惯列表是可以上下滑动的列表（由 HabitWidgetService 提供每一行）
            val adapter = Intent(context, HabitWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widgetList, adapter)
            // 点某一行的打卡圈：行里的 fill-in intent 带上习惯 id，合并进这个模板
            val template = Intent(context, HabitWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
                data = Uri.parse("riheng://widget/list/$widgetId")
            }
            views.setPendingIntentTemplate(
                R.id.widgetList,
                PendingIntent.getBroadcast(context, widgetId, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            )
            views.setOnClickPendingIntent(R.id.widgetHeader, openApp)
            views.setOnClickPendingIntent(R.id.widgetProgress, openApp)
            views.setOnClickPendingIntent(R.id.widgetOpen, openApp)
            return views
        }

        /** 打卡之后：只更新标题进度并让列表重新取数，列表滑动位置不动。 */
        private fun refreshAfterToggle(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HabitWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val partial = RemoteViews(context.packageName, R.layout.widget_root)
            applyHeader(partial, computeState(context))
            for (id in ids) manager.partiallyUpdateAppWidget(id, partial)
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetList)
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
        for (id in appWidgetIds) manager.updateAppWidget(id, buildViews(context, id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
                toggleHabit(context, habitId)
                refreshAfterToggle(context)
            }
            // 跨天 / 改时间 / 改时区：重新按「今天」渲染
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> updateAll(context)
        }
    }
}
