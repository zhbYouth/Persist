package com.riheng.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews

/** 待办小组件：已确定 / 待确定两个分组，可上下滑动，点圈完成，＋ 弹出快速添加小窗。 */
class TodoWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_TAB = "com.riheng.app.ACTION_TODO_TAB"
        const val ACTION_DONE = "com.riheng.app.ACTION_TODO_DONE"
        const val EXTRA_TAB = "tab"
        const val EXTRA_ID = "todo_id"

        private fun ids(c: Context): IntArray =
            AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, TodoWidgetProvider::class.java))

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val all = ids(c)
            for (id in all) m.updateAppWidget(id, buildViews(c, id))
            if (all.isNotEmpty()) m.notifyAppWidgetViewDataChanged(all, R.id.todoList)
        }

        private fun applyTabs(c: Context, v: RemoteViews) {
            val tab = TodoData.getTab(c)
            val (ok, pending) = TodoData.counts(c)
            v.setTextViewText(R.id.todoTabOk, "已确定" + if (ok > 0) " $ok" else "")
            v.setTextViewText(R.id.todoTabPending, "待确定" + if (pending > 0) " $pending" else "")
            val on = c.getColor(R.color.widget_ink); val off = c.getColor(R.color.widget_muted)
            v.setInt(R.id.todoTabOk, "setBackgroundResource", if (tab == TodoData.TAB_OK) R.drawable.widget_tab_on else 0)
            v.setInt(R.id.todoTabPending, "setBackgroundResource", if (tab == TodoData.TAB_PENDING) R.drawable.widget_tab_on else 0)
            v.setTextColor(R.id.todoTabOk, if (tab == TodoData.TAB_OK) on else off)
            v.setTextColor(R.id.todoTabPending, if (tab == TodoData.TAB_PENDING) on else off)
        }

        private fun tabIntent(c: Context, tab: String): PendingIntent {
            val i = Intent(c, TodoWidgetProvider::class.java).setAction(ACTION_TAB).putExtra(EXTRA_TAB, tab)
                .setData(Uri.parse("riheng://widget/todo/tab/$tab"))
            return PendingIntent.getBroadcast(c, 10, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun buildViews(c: Context, widgetId: Int): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_todo_root)
            applyTabs(c, v)
            v.setOnClickPendingIntent(R.id.todoTabOk, tabIntent(c, TodoData.TAB_OK))
            v.setOnClickPendingIntent(R.id.todoTabPending, tabIntent(c, TodoData.TAB_PENDING))
            // ＋：弹出快速添加小窗（默认放进当前所在分组）；↗：打开应用
            val add = Intent(c, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .setData(Uri.parse("riheng://widget/todo/add/$widgetId"))
            v.setOnClickPendingIntent(R.id.todoAdd, PendingIntent.getActivity(c, 11, add, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            v.setOnClickPendingIntent(R.id.todoOpen, PendingIntent.getActivity(c, 12, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val adapter = Intent(c, TodoWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.todoList, adapter)
            val template = Intent(c, TodoWidgetProvider::class.java).setAction(ACTION_DONE)
                .setData(Uri.parse("riheng://widget/todo/list/$widgetId"))
            v.setPendingIntentTemplate(R.id.todoList, PendingIntent.getBroadcast(c, widgetId, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            return v
        }

        /** 只更新分组标签和列表数据：列表滑动位置不动，不闪。 */
        private fun refreshLight(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val all = ids(c)
            if (all.isEmpty()) return
            val partial = RemoteViews(c.packageName, R.layout.widget_todo_root)
            applyTabs(c, partial)
            for (id in all) m.partiallyUpdateAppWidget(id, partial)
            m.notifyAppWidgetViewDataChanged(all, R.id.todoList)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) manager.updateAppWidget(id, buildViews(context, id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TAB -> {
                TodoData.setTab(context, intent.getStringExtra(EXTRA_TAB) ?: TodoData.TAB_OK)
                refreshLight(context)
            }
            ACTION_DONE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                if (!TodoData.complete(context, id)) return
                // 先让这一行变成「已勾选」（打勾 + 删除线），稍后再让它从列表里消失，比直接消失更顺
                refreshLight(context)
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try { refreshLight(context) } finally { pending.finish() }
                }, 650)
            }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> updateAll(context)
        }
    }
}
