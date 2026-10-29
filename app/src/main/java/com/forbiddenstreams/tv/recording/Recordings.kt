package com.forbiddenstreams.tv.recording

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.Http
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Program
import com.forbiddenstreams.tv.reminders.Alarms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Recording(
    val id: Long,
    val title: String,
    val channelName: String,
    val url: String,
    val start: Long,
    val end: Long,
    val status: String, // scheduled, recording, done, failed
    val file: String,
)

object Recordings {
    @Synchronized
    fun all(context: Context): List<Recording> {
        val arr = try { JSONArray(Prefs(context).recordingsJson) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Recording>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Recording(
                o.optLong("id"), o.optString("title"), o.optString("channel"), o.optString("url"),
                o.optLong("start"), o.optLong("end"), o.optString("status"), o.optString("file"),
            )
        }
        return out.sortedByDescending { it.start }
    }

    @Synchronized
    private fun save(context: Context, list: List<Recording>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channelName)
                .put("url", it.url).put("start", it.start).put("end", it.end)
                .put("status", it.status).put("file", it.file))
        }
        Prefs(context).recordingsJson = arr.toString()
    }

    @Synchronized
    fun setStatus(context: Context, id: Long, status: String) {
        save(context, all(context).map { if (it.id == id) it.copy(status = status) else it })
    }

    fun get(context: Context, id: Long) = all(context).firstOrNull { it.id == id }

    /** Records [program]; starts now if it is already on, otherwise at its start time. */
    fun schedule(context: Context, channel: LiveChannel, program: Program): Recording {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(program.start))
        val safe = program.title.replace(Regex("[^A-Za-z0-9]+"), "_").take(40)
        val rec = Recording(
            id = System.currentTimeMillis(),
            title = program.title,
            channelName = channel.name,
            url = channel.url,
            start = program.start,
            end = program.end,
            status = "scheduled",
            file = File(dir, "${safe}_$stamp.ts").absolutePath,
        )
        save(context, all(context) + rec)
        if (program.start <= System.currentTimeMillis()) {
            RecordingService.start(context, rec.id)
        } else {
            val i = Intent(context, RecordAlarmReceiver::class.java).putExtra("id", rec.id)
            val pi = PendingIntent.getBroadcast(context, rec.id.toInt(), i, Alarms.piFlags())
            Alarms.schedule(context, program.start - 30_000, pi)
        }
        return rec
    }

    /** The recording of [url] that's running right now, if any. */
    fun activeFor(context: Context, url: String): Recording? {
        val t = System.currentTimeMillis()
        return all(context).firstOrNull { it.url == url && it.status == "recording" && it.end > t }
    }

    /** Stops a running recording and keeps what was recorded so far. */
    fun stopNow(context: Context, id: Long) {
        val t = System.currentTimeMillis()
        save(context, all(context).map { if (it.id == id) it.copy(end = t, status = "done") else it })
        RecordingService.stop(context, id)
    }

    fun delete(context: Context, id: Long) {
        get(context, id)?.let { r ->
            RecordingService.stop(context, id)
            File(r.file).delete()
            val i = Intent(context, RecordAlarmReceiver::class.java).putExtra("id", id)
            Alarms.cancel(context, PendingIntent.getBroadcast(context, id.toInt(), i, Alarms.piFlags()))
        }
        save(context, all(context).filter { it.id != id })
    }
}

class RecordAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        if (id != 0L) RecordingService.start(context, id)
    }
}

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Alarms.createChannels(this)
        val n = NotificationCompat.Builder(this, Alarms.CHANNEL_RECORDINGS)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle("Forbidden Streams is recording")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 4242, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        val id = intent?.getLongExtra("id", 0) ?: 0
        val stopId = intent?.getLongExtra("stop", 0) ?: 0
        if (stopId != 0L) {
            jobs.remove(stopId)?.cancel()
            stopIfIdle()
        } else if (id != 0L && !jobs.containsKey(id)) {
            val rec = Recordings.get(this, id)
            if (rec == null) stopIfIdle() else jobs[id] = scope.launch { record(rec) }
        } else {
            stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private suspend fun record(rec: Recording) {
        Recordings.setStatus(this, rec.id, "recording")
        var ok = false
        var tries = 0
        val buf = ByteArray(64 * 1024)
        try {
            FileOutputStream(File(rec.file), true).use { out ->
                while (System.currentTimeMillis() < rec.end && tries < 8 && kotlin.coroutines.coroutineContext.isActive) {
                    try {
                        Http.client.newCall(Request.Builder().url(rec.url).build()).execute().use { r ->
                            val input = r.body?.byteStream() ?: throw java.io.IOException("empty")
                            while (System.currentTimeMillis() < rec.end && kotlin.coroutines.coroutineContext.isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                ok = true
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        tries++
                        delay(3000)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped from the player: stopNow() already saved it as done; a cancelled one was deleted.
            jobs.remove(rec.id)
            stopIfIdle()
            return
        } catch (e: Exception) { /* storage problem */ }
        Recordings.setStatus(this, rec.id, if (ok) "done" else "failed")
        jobs.remove(rec.id)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, id: Long) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).putExtra("id", id))
            } catch (e: Exception) {
                Recordings.setStatus(context, id, "failed")
            }
        }

        fun stop(context: Context, id: Long) {
            try {
                context.startService(Intent(context, RecordingService::class.java).putExtra("stop", id))
            } catch (e: Exception) { /* not running */ }
        }
    }
}
