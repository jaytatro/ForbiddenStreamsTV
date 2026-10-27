package com.forbiddenstreams.tv.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.forbiddenstreams.tv.MainActivity
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Program
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Long,
    val channelName: String,
    val channelUrl: String,
    val title: String,
    val start: Long,
)

object Alarms {
    fun piFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    fun schedule(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            when {
                Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() ->
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Build.VERSION.SDK_INT >= 23 -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else -> am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, pi: PendingIntent) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
    }

    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_RECORDINGS = "recordings"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDINGS, "Recordings", NotificationManager.IMPORTANCE_LOW))
    }
}

object Reminders {
    fun all(context: Context): List<Reminder> {
        val arr = try { JSONArray(Prefs(context).remindersJson) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Reminder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Reminder(o.optLong("id"), o.optString("channel"), o.optString("url"), o.optString("title"), o.optLong("start"))
        }
        return out.sortedBy { it.start }
    }

    private fun save(context: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("channel", it.channelName).put("url", it.channelUrl)
                .put("title", it.title).put("start", it.start))
        }
        Prefs(context).remindersJson = arr.toString()
    }

    fun add(context: Context, channel: LiveChannel, program: Program): Reminder {
        val r = Reminder(System.currentTimeMillis(), channel.name, channel.url, program.title, program.start)
        save(context, all(context).filter { !(it.title == r.title && it.start == r.start) } + r)
        Alarms.schedule(context, (program.start - 60_000).coerceAtLeast(System.currentTimeMillis() + 1000), pending(context, r))
        return r
    }

    fun remove(context: Context, id: Long) {
        all(context).firstOrNull { it.id == id }?.let { Alarms.cancel(context, pending(context, it)) }
        save(context, all(context).filter { it.id != id })
    }

    /** Reminders due now (from 1 minute before the start to 10 minutes after). */
    fun due(context: Context, now: Long = System.currentTimeMillis()): List<Reminder> =
        all(context).filter { now >= it.start - 60_000 && now < it.start + 10 * 60_000 }

    fun prune(context: Context) {
        val now = System.currentTimeMillis()
        save(context, all(context).filter { it.start + 10 * 60_000 > now })
    }

    private fun pending(context: Context, r: Reminder): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .putExtra("id", r.id).putExtra("title", r.title).putExtra("channel", r.channelName)
        return PendingIntent.getBroadcast(context, r.id.toInt(), i, Alarms.piFlags())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val channel = intent.getStringExtra("channel") ?: ""
        Alarms.createChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Alarms.piFlags(),
        )
        val n = NotificationCompat.Builder(context, Alarms.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle("Starting now: $title")
            .setContentText("On $channel. Open Forbidden Streams to watch.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(intent.getLongExtra("id", 0).toInt(), n)
        } catch (e: SecurityException) { /* notifications not allowed */ }
    }
}
