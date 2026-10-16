package com.riheng.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.content.Intent
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
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, buildViews(context, id)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
            toggleHabit(context, habitId)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HabitWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        }
    }

    private fun readData(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DATA_KEY, null)
            ?: return JSONObject().put("habits", JSONArray()).put("checks", JSONObject())
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("habits", JSONArray()).put("checks", JSONObject()) }
    }

    private fun todayKey(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private fun dayNumber(date: LocalDate): Int = date.dayOfWeek.value % 7 // JS convention: Sunday=0

    private fun isScheduled(habit: JSONObject, key: String): Boolean {
        return try {
            val date = LocalDate.parse(key)
            if (key < habit.optString("created", "0000-00-00")) return false
            val days = habit.optJSONArray("days") ?: JSONArray().apply { for (i in 0..6) put(i) }
            (0 until days.length()).any { days.optInt(it, -1) == dayNumber(date) }
        } catch (_: Exception) { false }
    }

    private fun isDone(checks: JSONObject, id: String, key: String): Boolean {
        val list = checks.optJSONArray(id) ?: return false
        return (0 until list.length()).any { list.optString(it) == key }
    }

    private fun toggleHabit(context: Context, habitId: String) {
        val root = readData(context)
        val habits = root.optJSONArray("habits") ?: JSONArray()
        val habit = (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }.firstOrNull { it.optString("id") == habitId } ?: return
        val today = todayKey()
        if (!isScheduled(habit, today)) return
        val checks = root.optJSONObject("checks") ?: JSONObject().also { root.put("checks", it) }
        val list = checks.optJSONArray(habitId) ?: JSONArray().also { checks.put(habitId, it) }
        val existing = (0 until list.length()).map { list.optString(it) }
        val updated = JSONArray()
        (existing.filter { it != today } + if (today in existing) emptyList() else listOf(today)).distinct().sorted().forEach { updated.put(it) }
        checks.put(habitId, updated)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DATA_KEY, root.toString()).apply()
    }

    private fun managerHeight(context: Context, widgetId: Int): Int {
        return try {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            minHeight.takeIf { it > 0 } ?: maxHeight.takeIf { it > 0 } ?: 220
        } catch (_: Exception) { 220 }
    }

    private fun buildViews(context: Context, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_root)
        val root = readData(context)
        val habits = root.optJSONArray("habits") ?: JSONArray()
        val checks = root.optJSONObject("checks") ?: JSONObject()
        val today = todayKey()
        val scheduled = (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }
            .filter { isScheduled(it, today) }
        val completed = scheduled.count { isDone(checks, it.optString("id"), today) }
        val allocatedHeight = managerHeight(context, widgetId)
        val maxRows = when {
            allocatedHeight < 190 -> 2
            allocatedHeight < 230 -> 3
            allocatedHeight < 270 -> 4
            else -> 5
        }
        views.setTextViewText(R.id.widgetDate, LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日")))
        views.setTextViewText(R.id.widgetProgress, "已完成 $completed / ${scheduled.size} 项")
        views.removeAllViews(R.id.widgetRows)
        if (scheduled.isEmpty()) {
            val row = RemoteViews(context.packageName, R.layout.widget_habit_row)
            row.setTextViewText(R.id.rowIcon, "＋")
            row.setTextViewText(R.id.rowName, "添加第一个习惯")
            row.setTextViewText(R.id.rowCheck, "›")
            row.setOnClickPendingIntent(R.id.rowName, openAppPending(context))
            views.addView(R.id.widgetRows, row)
        } else {
            scheduled.take(maxRows).forEachIndexed { index, habit ->
                val row = RemoteViews(context.packageName, R.layout.widget_habit_row)
                val id = habit.optString("id")
                val done = isDone(checks, id, today)
                val icon = habit.optString("emoji", "🌿").let { if (it.startsWith("data:image/")) "🌿" else it }
                row.setTextViewText(R.id.rowIcon, icon)
                row.setTextViewText(R.id.rowName, habit.optString("name", "习惯").take(28))
                row.setTextViewText(R.id.rowCheck, if (done) "✓" else "○")
                row.setTextColor(R.id.rowName, if (done) 0xFF7C897F.toInt() else 0xFF25352B.toInt())
                row.setInt(R.id.rowCheck, "setBackgroundColor", if (done) 0xFFD8EBDD.toInt() else 0xFFE8F0E6.toInt())
                val action = Intent(context, HabitWidgetProvider::class.java).apply {
                    this.action = ACTION_TOGGLE
                    putExtra(EXTRA_HABIT_ID, id)
                    data = android.net.Uri.parse("riheng://widget/toggle/$widgetId/$index/$id")
                }
                val pi = PendingIntent.getBroadcast(context, (widgetId * 100) + index + 1, action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                row.setOnClickPendingIntent(R.id.rowCheck, pi)
                row.setOnClickPendingIntent(R.id.rowName, openAppPending(context))
                views.addView(R.id.widgetRows, row)
            }
        }
        views.setOnClickPendingIntent(R.id.widgetOpen, openAppPending(context))
        views.setOnClickPendingIntent(R.id.widgetRoot, openAppPending(context))
        return views
    }

    private fun openAppPending(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
