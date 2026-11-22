package com.livecast

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.*
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpDisplay
import java.io.File
import kotlin.math.max

class StreamService : Service(), ConnectChecker {
    companion object {
        const val STATUS_ACTION = "com.livecast.STATUS"
        var running = false
        var previewEnabled = true
        var streamConnected = false
        var recording = false
        var chatConnected = false
        var streamStatus = "Disconnected"
        var platform = ""
        var rtmpUrl = ""; var tickerText = ""; var chatSource = ""; var chatTarget = ""; var chatToken = ""
        var landscape = false; var micOn = true
        var tickerPosition = "bottom"
        var tickerTextSize = 24
        var tickerFont = "Sans"
        var tickerColor = Color.WHITE
    }

    private var display: RtmpDisplay? = null
    private var filter: ImageObjectFilterRender? = null
    private val handler = Handler(Looper.getMainLooper())
    private val chat = ArrayList<String>()
    private var poller: ChatPoller? = null
    private var scrollX = 0f

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "START" -> start(i)
            "STREAM" -> startStream()
            "STOP_STREAM" -> stopStream()
            "RECORD" -> toggleRecord()
            "MIC" -> if (micOn) display?.enableAudio() else display?.disableAudio()
            "PREVIEW" -> Unit
            "STOP" -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(i: Intent) {
        if (running) return
        streamConnected = false
        streamStatus = if (i.getStringExtra("mode") == "record") "Disconnected" else "Connecting"
        broadcastStatus()
        val ch = "live"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "Live", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle(if (i.getStringExtra("mode") == "record") "Recording screen" else "Streaming live")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val w = if (landscape) 1280 else 720
        val h = if (landscape) 720 else 1280
        val d = RtmpDisplay(this, true, this)
        d.setIntentResult(
            i.getIntExtra("code", 0),
            i.getParcelableExtra("data")
        )
        check(d.prepareVideo(w, h, 30, 2_500_000, 0, resources.displayMetrics.densityDpi)) {
            "Could not prepare screen video encoder"
        }
        check(d.prepareAudio(128 * 1024, 44100, true)) { "Could not prepare microphone audio encoder" }
        if (!micOn) d.disableAudio()
        display = d
        running = true

        filter = ImageObjectFilterRender().also {
            d.glInterface.setFilter(it)
            it.setImage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
            it.setScale(100f, 100f); it.setPosition(TranslateTo.CENTER)
        }
        if (chatSource.isNotEmpty() && chatTarget.isNotEmpty()) {
            poller = ChatPoller(chatSource, chatTarget, chatToken) { line ->
                synchronized(chat) { chat.add(line); while (chat.size > 6) chat.removeAt(0) }
            }.also {
                it.onStatus = { connected ->
                    chatConnected = connected
                    broadcastStatus()
                }
                it.start()
            }
        }
        if (i.getStringExtra("mode") == "record") {
            toggleRecord()
        } else {
            startStream()
        }
        handler.post(draw(w / 2, h / 2))
    }

    private fun startStream() {
        val d = display ?: return
        if (d.isStreaming) return
        streamStatus = "Connecting"
        streamConnected = false
        broadcastStatus()
        d.startStream(rtmpUrl)
    }

    private fun stopStream() {
        val d = display
        if (d?.isStreaming == true) d.stopStream()
        streamConnected = false
        streamStatus = "Disconnected"
        broadcastStatus()
        if (d?.isRecording != true) stop()
    }

    private fun toggleRecord() {
        val d = display ?: return
        if (d.isRecording) {
            d.stopRecord()
            recording = false
            broadcastStatus()
            if (!d.isStreaming) stop()
            return
        }
        val directory = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("App video storage is unavailable")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create video storage directory")
        }
        val file = File(directory, "LiveCast_${System.currentTimeMillis()}.mp4")
        d.startRecord(file.absolutePath) { status ->
            when (status) {
                RecordController.Status.RECORDING, RecordController.Status.RESUMED -> recording = true
                RecordController.Status.STOPPED -> recording = false
                else -> Unit
            }
            broadcastStatus()
        }
        recording = true
        broadcastStatus()
    }

    // Draws overlay (chat + scrolling ticker) on a half-res transparent bitmap ~8 fps
    private fun draw(ow: Int, oh: Int) = object : Runnable {
        override fun run() {
            if (!running) return
            val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val barH = max(oh * 0.07f, tickerTextSize * 1.3f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val atTop = tickerPosition == "top"
            val barTop = if (atTop) 0f else oh - barH
            val barBottom = barTop + barH
            p.color = Color.argb(200, 0, 0, 0)
            c.drawRect(0f, barTop, ow.toFloat(), barBottom, p)
            p.color = tickerColor
            p.textSize = tickerTextSize.toFloat()
            p.typeface = when (tickerFont) {
                "Serif" -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                "Monospace" -> Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                "Sans bold" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                else -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val tw = p.measureText(tickerText + "      ")
            scrollX -= ow * 0.01f
            if (scrollX < -tw) scrollX = 0f
            var x = scrollX
            val tickerBaseline = if (atTop) barBottom - barH * 0.2f else barBottom - barH * 0.2f
            while (x < ow && tw > 0) { c.drawText(tickerText, x, tickerBaseline, p); x += tw }
            // chat lines above ticker
            p.color = Color.WHITE
            p.textSize = (tickerTextSize * 0.75f).coerceAtLeast(12f)
            p.typeface = Typeface.DEFAULT
            val lines = synchronized(chat) { chat.toList() }
            lines.forEachIndexed { idx, s ->
                val y = if (atTop) {
                    barBottom + p.textSize + 12f + idx * p.textSize * 1.4f
                } else {
                    oh - barH - 12f - (lines.size - 1 - idx) * p.textSize * 1.4f
                }
                val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
                c.drawRect(8f, y - p.textSize, 8f + p.measureText(s) + 12f, y + 8f, bg)
                c.drawText(s, 14f, y, p)
            }
            filter?.setImage(bmp)
            handler.postDelayed(this, 120)
        }
    }

    private fun stop() {
        running = false
        streamConnected = false
        recording = false
        chatConnected = false
        streamStatus = "Disconnected"
        broadcastStatus()
        handler.removeCallbacksAndMessages(null)
        poller?.stopPolling()
        display?.apply {
            if (isRecording) stopRecord()
            if (isStreaming) stopStream()
        }
        display = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(STATUS_ACTION))
    }

    override fun onConnectionStarted(url: String) {
        streamConnected = false
        streamStatus = "Connecting"
        broadcastStatus()
    }
    override fun onConnectionSuccess() {
        streamConnected = true
        streamStatus = if (platform == "yt") "YouTube Live connected" else "Live connected"
        broadcastStatus()
    }
    override fun onConnectionFailed(reason: String) {
        streamConnected = false
        streamStatus = "Connection failed"
        broadcastStatus()
        stop()
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        streamConnected = false
        streamStatus = "Disconnected"
        broadcastStatus()
    }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
