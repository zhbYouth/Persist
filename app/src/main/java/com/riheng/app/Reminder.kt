package com.riheng.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/** 每天一次的汇总提醒：到点时如果今天还有没完成的习惯，就推一条通知。 */
object Reminder {
    const val ACTION_FIRE = "com.riheng.app.ACTION_DAILY_REMINDER"
    private const val CHANNEL = "daily_reminder"
    private const val PREF_ENABLED = "reminder_enabled"
    private const val PREF_MINUTES = "reminder_minutes"
    private const val DEFAULT_MINUTES = 21 * 60
    private const val NOTIFY_ID = 7101

    private fun prefs(c: Context) = c.getSharedPreferences(HabitWidgetProvider.PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean(PREF_ENABLED, false)
    fun minutes(c: Context) = prefs(c).getInt(PREF_MINUTES, DEFAULT_MINUTES).coerceIn(0, 1439)

    fun canNotify(c: Context): Boolean =
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    fun set(c: Context, enabled: Boolean, minutes: Int) {
        prefs(c).edit().putBoolean(PREF_ENABLED, enabled).putInt(PREF_MINUTES, minutes.coerceIn(0, 1439)).apply()
        schedule(c)
    }

    private fun pending(c: Context): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).setAction(ACTION_FIRE)
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 按当前设置重新安排下一次提醒（先取消旧的）。 */
    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(c))
        if (!enabled(c)) return
        val m = minutes(c)
        var t = LocalDateTime.now().withHour(m / 60).withMinute(m % 60).withSecond(0).withNano(0)
        if (!t.isAfter(LocalDateTime.now().plusSeconds(5))) t = t.plusDays(1)
        val at = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 不需要「精确闹钟」权限；系统可能在省电时延后几分钟，对每日提醒足够
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
    }

    fun fire(c: Context) {
        if (enabled(c) && canNotify(c)) {
            val st = HabitWidgetProvider.computeState(c)
            val left = st.scheduled - st.completed
            if (st.scheduled > 0 && left > 0) post(c, left)
        }
        schedule(c)
    }

    private fun post(c: Context, left: Int) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "每日提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "每天提醒你还剩多少习惯没有完成"
        })
        val open = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = android.app.Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("Persist")
            .setContentText("今天还剩 $left 项没完成")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminder.ACTION_FIRE -> Reminder.fire(context)
            // 重启、改时间/时区、应用更新后，闹钟会丢失或错位，重新排一次
            else -> Reminder.schedule(context)
        }
    }
}
