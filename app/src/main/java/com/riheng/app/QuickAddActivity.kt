package com.riheng.app

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

/**
 * 小组件「＋」弹出的快速添加窗口：和应用里的添加一样可以选「待确定 / 今天 / 未来」，
 * 今天和未来可以再设提醒时间、重复；未来必须选日期。进入时不会自动弹出键盘，点输入框才弹出。
 */
class QuickAddActivity : Activity() {
    private var mode = 0                 // 0 待确定  1 今天  2 未来
    private var futureDate: LocalDate? = null
    private var time = ""
    private var repeatIdx = 0
    private val repeats = listOf("" to "不重复", "daily" to "每天", "weekdays" to "工作日", "weekly" to "每周", "monthly" to "每月")

    private lateinit var titleV: EditText
    private lateinit var chips: List<TextView>
    private lateinit var more: View
    private lateinit var date: TextView
    private lateinit var timeV: TextView
    private lateinit var repeatV: TextView
    private lateinit var card: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_add)
        titleV = findViewById(R.id.qaTitle)
        card = findViewById(R.id.qaCard)
        more = findViewById(R.id.qaMore)
        date = findViewById(R.id.qaDate); timeV = findViewById(R.id.qaTime); repeatV = findViewById(R.id.qaRepeat)
        chips = listOf(R.id.qaPending, R.id.qaToday, R.id.qaFuture).map { findViewById<TextView>(it) }
        mode = if (TodoData.getTab(this) == TodoData.TAB_PENDING) 0 else 1
        chips.forEachIndexed { i, v -> v.setOnClickListener { setMode(i, true) } }
        findViewById<View>(R.id.qaRoot).setOnClickListener { finishQuiet() }
        findViewById<View>(R.id.qaClose).setOnClickListener { finishQuiet() }
        card.setOnClickListener { }
        date.setOnClickListener { pickDate() }
        timeV.setOnClickListener { pickTime() }
        repeatV.setOnClickListener { repeatIdx = (repeatIdx + 1) % repeats.size; renderMore(); bounce(repeatV) }
        findViewById<View>(R.id.qaSave).setOnClickListener { bounce(it); save() }
        titleV.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { save(); true } else false }
        render()
        // 弹簧式升起：从下方带一点过冲地弹到位
        card.alpha = 0f
        card.translationY = 120f * resources.displayMetrics.density
        card.post {
            card.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(OvershootInterpolator(0.9f)).start()
        }
        // 默认不让输入框拿到焦点：键盘不会自己弹出
        findViewById<View>(R.id.qaCard).requestFocus()
    }

    private fun setMode(m: Int, animate: Boolean) {
        mode = m
        if (m == 2 && futureDate == null) pickDate()
        render()
        if (animate) bounce(chips[m])
    }

    private fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 0.88f; v.scaleY = 0.88f
        v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun render() {
        val on = getColor(R.color.widget_accent); val off = getColor(R.color.widget_muted)
        chips.forEachIndexed { i, v ->
            v.setBackgroundResource(if (i == mode) R.drawable.qa_chip_on else R.drawable.qa_chip_off)
            v.setTextColor(if (i == mode) on else off)
        }
        more.visibility = if (mode == 0) View.GONE else View.VISIBLE
        renderMore()
    }

    private fun renderMore() {
        date.visibility = if (mode == 2) View.VISIBLE else View.GONE
        date.text = futureDate?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: "选择日期"
        timeV.text = if (time.isEmpty()) "提醒时间" else time
        repeatV.text = repeats[repeatIdx].second
        val on = getColor(R.color.widget_accent); val off = getColor(R.color.widget_muted)
        fun mark(v: TextView, active: Boolean) { v.setBackgroundResource(if (active) R.drawable.qa_chip_on else R.drawable.qa_chip_off); v.setTextColor(if (active) on else off) }
        mark(date, futureDate != null); mark(timeV, time.isNotEmpty()); mark(repeatV, repeatIdx != 0)
    }

    private fun pickDate() {
        val start = futureDate ?: LocalDate.now().plusDays(1)
        val dlg = DatePickerDialog(this, { _, y, m, d -> futureDate = LocalDate.of(y, m + 1, d); renderMore(); bounce(date) }, start.year, start.monthValue - 1, start.dayOfMonth)
        dlg.datePicker.minDate = LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        dlg.setOnCancelListener { if (futureDate == null && mode == 2) { mode = 1; render() } }
        dlg.show()
    }

    private fun pickTime() {
        val now = java.time.LocalTime.now()
        TimePickerDialog(this, { _, h, m -> time = "%02d:%02d".format(h, m); renderMore(); bounce(timeV) }, now.hour, now.minute, true).show()
    }

    private fun save() {
        val text = titleV.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "先写下要做什么", Toast.LENGTH_SHORT).show(); return }
        val due = when (mode) {
            0 -> ""
            1 -> TodoData.todayKey()
            else -> futureDate?.toString() ?: run { Toast.makeText(this, "未来的待办要选一个日期", Toast.LENGTH_SHORT).show(); pickDate(); return }
        }
        TodoData.add(this, text, due, if (mode == 0) "" else time, if (mode == 0) "" else repeats[repeatIdx].first)
        TodoData.setTab(this, if (mode == 0) TodoData.TAB_PENDING else TodoData.TAB_OK)
        TodoWidgetProvider.updateAll(this)
        Toast.makeText(this, if (mode == 0) "已放进「待确定」" else if (mode == 1) "已放进「已确定 · 今天」" else "已放进「已确定 · 未来」", Toast.LENGTH_SHORT).show()
        finishQuiet()
    }

    private fun finishQuiet() {
        try { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(titleV.windowToken, 0) } catch (e: Exception) {}
        finish()
        overridePendingTransition(0, 0)
    }
}
