package com.livecast

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.*
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.library.view.GlStreamInterface
import java.io.File
import kotlin.math.max

class StreamService : Service(), ConnectChecker {
    inner class LocalBinder : Binder() {
        fun getService() = this@StreamService
    }

    companion object {
        const val STATUS_ACTION = "com.livecast.STATUS"
        var running = false
        var previewEnabled = true
        var streamRequested = false
        var streamConnected = false
        var recording = false
        var recordStatus = "Not recording"
        var recordingPath = ""
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
    private var attachedPreviewSurface: Surface? = null
    private val binder = LocalBinder()

    override fun onBind(i: Intent?) = binder

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            when (i?.action) {
                "START" -> start(i)
                "STREAM" -> startStream()
                "STOP_STREAM" -> stopStream()
                "RECORD" -> toggleRecord()
                "MIC" -> if (micOn) display?.enableAudio() else display?.disableAudio()
                "STOP" -> stop()
            }
        } catch (e: java.io.IOException) {
            val message = e.message ?: "could not access recording storage"
            if (i?.action == "RECORD" || i?.getStringExtra("mode") == "record") {
                recordStatus = "Recording failed: $message"
                stop()
            } else {
                stop("Error: $message")
            }
        } catch (e: RuntimeException) {
            val message = e.message ?: "could not start capture"
            if (i?.action == "RECORD" || i?.getStringExtra("mode") == "record") {
                recordStatus = "Recording failed: $message"
                stop()
            } else {
                stop("Error: $message")
            }
        }
        return START_NOT_STICKY
    }

    fun updatePreview(surface: Surface?, width: Int, height: Int) {
        val d = display ?: return
        val gl = d.glInterface as? GlStreamInterface ?: return
        if (!previewEnabled || surface == null) {
            gl.deAttachPreview()
            attachedPreviewSurface = null
            return
        }
        if (width <= 0 || height <= 0) return
        try {
            gl.setPreviewResolution(width, height)
            if (attachedPreviewSurface !== surface) {
                gl.attachPreview(surface)
                attachedPreviewSurface = surface
            }
        } catch (e: RuntimeException) {
            streamStatus = "Preview error: ${e.message ?: "surface is unavailable"}"
            broadcastStatus()
            throw e
        }
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
        if (!d.prepareVideo(w, h, 30, 2_500_000, 0, resources.displayMetrics.densityDpi)) {
            throw IllegalStateException("Could not prepare screen video encoder")
        }
        if (!d.prepareAudio(128 * 1024, 44100, true)) {
            throw IllegalStateException("Could not prepare microphone audio encoder. Check microphone permission.")
        }
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
        streamRequested = true
        streamStatus = "Connecting"
        streamConnected = false
        broadcastStatus()
        d.startStream(rtmpUrl)
    }

    private fun stopStream() {
        val d = display
        streamRequested = false
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
            recordStatus = "Saved in Android/data/com.livecast/files/Movies: ${File(recordingPath).name}"
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
        recordingPath = file.absolutePath
        recordStatus = "Starting recording..."
        d.startRecord(file.absolutePath, object : RecordController.Listener {
            override fun onStatusChange(status: RecordController.Status) {
                when (status) {
                    RecordController.Status.RECORDING, RecordController.Status.RESUMED -> {
                        recording = true
                        recordStatus = "Recording: ${file.name}"
                    }
                    RecordController.Status.STOPPED -> {
                        recording = false
                        recordStatus = "Saved in Android/data/com.livecast/files/Movies: ${file.name}"
                    }
                    else -> Unit
                }
                broadcastStatus()
            }

            override fun onError(e: Exception) {
                recording = false
                recordStatus = "Recording failed: ${e.message ?: "write error"}"
                broadcastStatus()
            }
        })
        recording = true
        recordStatus = "Recording: ${file.name}"
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

    private fun stop(errorStatus: String? = null) {
        running = false
        streamRequested = false
        streamConnected = false
        recording = false
        chatConnected = false
        streamStatus = errorStatus ?: "Disconnected"
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
        if (!streamRequested) return
        streamConnected = false
        streamStatus = "Connecting"
        broadcastStatus()
    }
    override fun onConnectionSuccess() {
        if (!streamRequested) return
        streamConnected = true
        streamStatus = if (platform == "yt") "YouTube Live connected" else "Live connected"
        broadcastStatus()
    }
    override fun onConnectionFailed(reason: String) {
        if (!streamRequested) return
        streamRequested = false
        streamConnected = false
        streamStatus = if (reason.contains("EOF", ignoreCase = true)) {
            "RTMP handshake closed. Check server/app/key URL and RTMP 1935 vs RTMPS 443."
        } else {
            "Connection failed: $reason"
        }
        val errorStatus = streamStatus
        val d = display
        if (d?.isStreaming == true) d.stopStream()
        broadcastStatus()
        if (d?.isRecording != true) stop(errorStatus)
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        if (!running || !streamRequested) return
        streamConnected = false
        streamStatus = "Disconnected"
        broadcastStatus()
    }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
