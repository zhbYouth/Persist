package com.riheng.app

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** 桌面小组件里的习惯列表：可上下滑动，每行一个习惯。 */
class HabitWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var rows: List<WidgetRow> = emptyList()
        private var habitCount = 0

        private fun load() {
            val st = HabitWidgetProvider.computeState(context)
            habitCount = st.habitCount
            rows = if (st.rows.isNotEmpty()) st.rows else listOf(
                WidgetRow(
                    "", if (st.habitCount == 0) "添加第一个习惯" else "今天没有安排，好好休息",
                    if (st.habitCount == 0) "＋" else "☾", false, placeholder = true
                )
            )
        }

        override fun onCreate() = load()
        override fun onDataSetChanged() = load()
        override fun onDestroy() { rows = emptyList() }
        override fun getCount(): Int = rows.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 2
        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds(): Boolean = true

        override fun getViewAt(position: Int): RemoteViews {
            val row = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_habit_row)
            val v = RemoteViews(context.packageName, if (row.done) R.layout.widget_habit_row_done else R.layout.widget_habit_row)
            v.setTextViewText(R.id.rowIcon, row.icon)
            v.setTextViewText(R.id.rowName, row.name)
            if (row.placeholder) {
                v.setViewVisibility(R.id.rowCheckArea, android.view.View.GONE)
            } else {
                v.setContentDescription(R.id.rowCheckArea, (if (row.done) "取消打卡：" else "打卡：") + row.name)
                v.setOnClickFillInIntent(R.id.rowCheckArea, Intent().putExtra(HabitWidgetProvider.EXTRA_HABIT_ID, row.id))
            }
            return v
        }
    }
}
