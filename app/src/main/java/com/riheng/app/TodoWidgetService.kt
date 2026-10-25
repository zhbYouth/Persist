package com.riheng.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate

/** 待办小组件里的列表：每行 = 完成圈 + 标题 + 日期/时间。 */
class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val c: Context) : RemoteViewsFactory {
        private var rows: List<TodoData.Item> = emptyList()
        private var tab = TodoData.TAB_OK

        private fun load() { tab = TodoData.getTab(c); rows = TodoData.items(c, tab) }

        override fun onCreate() = load()
        override fun onDataSetChanged() = load()
        override fun onDestroy() { rows = emptyList() }
        override fun getCount(): Int = if (rows.isEmpty()) 1 else rows.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 2
        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: -1L
        override fun hasStableIds(): Boolean = true

        private fun meta(t: TodoData.Item): String {
            if (t.due.isEmpty()) return ""
            val today = LocalDate.now()
            val d = try { LocalDate.parse(t.due) } catch (e: Exception) { return t.time }
            return when {
                d.isBefore(today) -> "逾期 ${d.monthValue}/${d.dayOfMonth}" + if (t.time.isNotEmpty()) " ${t.time}" else ""
                d == today -> t.time
                else -> "${d.monthValue}月${d.dayOfMonth}日" + if (t.time.isNotEmpty()) " ${t.time}" else ""
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            if (rows.isEmpty()) {
                val v = RemoteViews(c.packageName, R.layout.widget_todo_row)
                v.setViewVisibility(R.id.rowCheckArea, View.GONE)
                v.setViewVisibility(R.id.rowMeta, View.GONE)
                v.setTextViewText(R.id.rowName, if (tab == TodoData.TAB_OK) "没有已确定的待办，点右上角＋添加" else "没有待确定的想法，点右上角＋记一条")
                return v
            }
            val t = rows[position]
            val v = RemoteViews(c.packageName, if (t.done) R.layout.widget_todo_row_done else R.layout.widget_todo_row)
            if (t.done) {
                val s = SpannableString(t.title); s.setSpan(StrikethroughSpan(), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                v.setTextViewText(R.id.rowName, s)
            } else v.setTextViewText(R.id.rowName, t.title)
            val m = meta(t)
            v.setTextViewText(R.id.rowMeta, m)
            v.setViewVisibility(R.id.rowMeta, if (m.isEmpty()) View.GONE else View.VISIBLE)
            v.setTextColor(R.id.rowMeta, if (!t.done && m.startsWith("逾期")) 0xFFE8506B.toInt() else c.getColor(R.color.widget_muted))
            if (!t.done) {
                v.setContentDescription(R.id.rowCheckArea, "完成：" + t.title)
                v.setOnClickFillInIntent(R.id.rowCheckArea, Intent().putExtra(TodoWidgetProvider.EXTRA_ID, t.id))
            }
            return v
        }
    }
}
