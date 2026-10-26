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
        const val ACTION_ITEM = "com.riheng.app.ACTION_TODO_ITEM"
        const val EXTRA_TAB = "tab"
        const val EXTRA_ID = "todo_id"
        const val EXTRA_ACTION = "todo_action"
        const val EXTRA_VIEW = "todo_view"

        private fun ids(c: Context): IntArray =
            AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, TodoWidgetProvider::class.java))

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val all = ids(c)
            for (id in all) m.updateAppWidget(id, buildViews(c, id))
            if (all.isNotEmpty()) m.notifyAppWidgetViewDataChanged(all, R.id.todoList)
        }

        private fun applyStatus(c: Context, v: RemoteViews) {
            val st = TodoData.status(c)
            v.setTextViewText(R.id.todoStatus, st.text)
            v.setTextColor(R.id.todoStatus, c.getColor(st.colorRes))
            v.setImageViewResource(R.id.todoMascot, when (st.mood) {
                TodoData.Mood.PARTY -> R.drawable.mascot_party
                TodoData.Mood.CHEER -> R.drawable.mascot_cheer
                TodoData.Mood.WORRY -> R.drawable.mascot_worry
                else -> R.drawable.mascot_idle
            })
        }

        private fun applyTabs(c: Context, v: RemoteViews) {
            applyStatus(c, v)
            val tab = TodoData.getTab(c)
            v.setTextViewText(R.id.todoTabOk, "已确定")      // 件数统一放在下面那行「今天 N 件 · 未来 N 件」，标签上不再放数字
            v.setTextViewText(R.id.todoTabPending, "待确定")
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
            // ＋：进入应用里的添加面板（默认放进当前所在分组，与应用内完全一致）；↗：打开待办页
            val addView = if (TodoData.getTab(c) == TodoData.TAB_PENDING) "add:pending" else "add:today"
            val add = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PAGE, "todo").putExtra(MainActivity.EXTRA_OPEN_VIEW, addView).setData(Uri.parse("riheng://widget/todo/add/$addView"))
            v.setOnClickPendingIntent(R.id.todoAdd, PendingIntent.getActivity(c, 11, add, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PAGE, "todo").setData(Uri.parse("riheng://widget/todo/open"))
            v.setOnClickPendingIntent(R.id.todoOpen, PendingIntent.getActivity(c, 12, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val adapter = Intent(c, TodoWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.todoList, adapter)
            // 点圈 = 完成 / 撤销；点任务本身 = 打开应用里对应的分组。
            // 用「广播」而不是「Activity」：启动 Activity 会触发桌面的打开动画（小组件和壁纸跟着弹一下），广播不会。
            val template = Intent(c, TodoWidgetProvider::class.java).setAction(ACTION_ITEM).setData(Uri.parse("riheng://widget/todo/list/$widgetId"))
            v.setPendingIntentTemplate(R.id.todoList, PendingIntent.getBroadcast(c, widgetId, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            return v
        }

        /** 点圈完成：这一行先留在原位高亮，约 1 秒后再沉到最下面。 */
        fun completeAndRefresh(c: Context, id: String, pending: PendingResult? = null) { if (TodoData.complete(c, id)) settle(c, pending) else pending?.finish() }

        /** 点已完成的圈：撤销，这一行回到原来的位置并高亮一下。 */
        fun undoAndRefresh(c: Context, id: String, pending: PendingResult? = null) { if (TodoData.uncomplete(c, id)) settle(c, pending) else pending?.finish() }

        private fun settle(c: Context, pending: PendingResult?) {
            val app = c.applicationContext
            refreshLight(app)
            Handler(Looper.getMainLooper()).postDelayed({ try { refreshLight(app) } finally { pending?.finish() } }, TodoData.FLASH_MS + 80)
        }

        fun refreshLight(c: Context) {
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
            ACTION_ITEM, ACTION_DONE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                when (if (intent.action == ACTION_DONE) "done" else intent.getStringExtra(EXTRA_ACTION)) {
                    "done" -> completeAndRefresh(context, id, goAsync())
                    "undo" -> undoAndRefresh(context, id, goAsync())
                    "open" -> try {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(MainActivity.EXTRA_OPEN_PAGE, "todo")
                                .putExtra(MainActivity.EXTRA_OPEN_VIEW, intent.getStringExtra(EXTRA_VIEW) ?: "")
                                .putExtra(MainActivity.EXTRA_OPEN_ID, id)
                        )
                    } catch (e: Exception) { /* 系统不允许从后台打开时就忽略 */ }
                }
            }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> updateAll(context)
        }
    }
}
