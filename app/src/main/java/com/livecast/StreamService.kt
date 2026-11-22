package com.livecast

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
        var previewOnly = false
        var previewEnabled = false
        var streamRequested = false
        var streamConnected = false
        @Volatile
        var recording = false
        var recordStatus = "Not recording"
        var recordingPath = ""
        var lastRecordingPath = ""
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
    private var previewProjection: MediaProjection? = null
    private var previewVirtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    @Volatile private var recordingRequestedAtMs = 0L
    @Volatile private var recordingStartedAtMs = 0L
    @Volatile private var recordStopRequested = false
    @Volatile private var stopServiceAfterRecording = false
    private var stopServiceStatus: String? = null
    private val binder = LocalBinder()
    private val minimumRecordDurationMs = 1_000L
    private val recordStartTimeoutMs = 10_000L

    override fun onBind(i: Intent?) = binder

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (previewOnly) {
            previewOnly = false
            running = false
            previewVirtualDisplay?.release()
            previewVirtualDisplay = null
            projectionCallback?.let { previewProjection?.unregisterCallback(it) }
            projectionCallback = null
            previewProjection?.stop()
            previewProjection = null
        }
        super.onDestroy()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            when (i?.action) {
                "START" -> start(i)
                "PREVIEW_START" -> startPreviewCapture(i)
                "PREVIEW_STOP" -> stopPreviewCapture("Preview stopped")
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
        if (previewOnly) {
            updateScreenPreview(surface, width, height)
            return
        }
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

    private fun updateScreenPreview(surface: Surface?, width: Int, height: Int) {
        if (surface == null || !previewEnabled) {
            previewVirtualDisplay?.setSurface(null)
            attachedPreviewSurface = null
            return
        }
        val projection = previewProjection ?: return
        if (width <= 0 || height <= 0) return
        val densityDpi = resources.displayMetrics.densityDpi
        if (previewVirtualDisplay == null) {
            previewVirtualDisplay = projection.createVirtualDisplay(
                "LiveCast preview",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                handler
            )
        } else {
            previewVirtualDisplay?.resize(width, height, densityDpi)
            previewVirtualDisplay?.setSurface(surface)
        }
        attachedPreviewSurface = surface
    }

    private fun startPreviewCapture(i: Intent) {
        if (running) return
        val data = i.getParcelableExtra<Intent>("data")
            ?: throw IllegalArgumentException("Screen capture permission was not provided")
        val channel = "live"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channel, "LiveCast", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channel)
            .setContentTitle("Screen preview active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(i.getIntExtra("code", 0), data)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopPreviewCapture("Screen capture stopped")
            }
        }
        projection.registerCallback(callback, handler)
        projectionCallback = callback
        previewProjection = projection
        previewOnly = true
        running = true
        streamStatus = "Screen preview active"
        broadcastStatus()
    }

    private fun stopPreviewCapture(status: String) {
        previewOnly = false
        previewVirtualDisplay?.release()
        previewVirtualDisplay = null
        attachedPreviewSurface = null
        projectionCallback?.let { callback ->
            previewProjection?.unregisterCallback(callback)
        }
        projectionCallback = null
        val projection = previewProjection
        previewProjection = null
        projection?.stop()
        running = false
        streamStatus = status
        broadcastStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(1, n)
        }

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
            requestStopRecording(d)
            return
        }
        val directory = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("App video storage is unavailable")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create video storage directory")
        }
        val file = File(directory, "LiveCast_${System.currentTimeMillis()}.mp4")
        recordingPath = file.absolutePath
        recordingRequestedAtMs = SystemClock.elapsedRealtime()
        recordingStartedAtMs = 0L
        recordStopRequested = false
        stopServiceAfterRecording = false
        stopServiceStatus = null
        recordStatus = "Preparing recording..."
        recording = true
        broadcastStatus()
        d.startRecord(file.absolutePath, object : RecordController.Listener {
            override fun onStatusChange(status: RecordController.Status) {
                when (status) {
                    RecordController.Status.STARTED -> {
                        recording = true
                        recordStatus = "Waiting for the first captured frame..."
                    }
                    RecordController.Status.RECORDING, RecordController.Status.RESUMED -> {
                        if (recordingStartedAtMs == 0L) {
                            recordingStartedAtMs = SystemClock.elapsedRealtime()
                        }
                        recording = true
                        recordStatus = if (recordStopRequested) {
                            "Finalizing recording..."
                        } else {
                            "Recording: ${file.name}"
                        }
                        if (recordStopRequested) finishRecordingWhenReady(d)
                    }
                    RecordController.Status.STOPPED -> {
                        recording = false
                        recordingStartedAtMs = 0L
                        val validationError = validateRecording(file)
                        if (validationError == null && !recordStatus.startsWith("Recording failed:")) {
                            recordStatus = "Saved: ${file.name}"
                            persistRecording(file)
                        } else {
                            recordStatus = "Recording failed: ${
                                validationError ?: recordStatus.removePrefix("Recording failed:").trim()
                            }"
                        }
                        val stopService = stopServiceAfterRecording
                        val finalStatus = stopServiceStatus
                        recordStopRequested = false
                        recordingRequestedAtMs = 0L
                        stopServiceAfterRecording = false
                        stopServiceStatus = null
                        if (stopService) handler.post { stop(finalStatus) }
                    }

                    else -> Unit
                }
                broadcastStatus()
            }

            override fun onError(e: Exception) {
                recording = d.isRecording
                recordStatus = "Recording failed: ${e.message ?: "write error"}"
                if (!d.isStreaming) stopServiceAfterRecording = true
                recordStopRequested = true
                finishRecordingWhenReady(d)
                broadcastStatus()
            }
        })
        handler.postDelayed({
            if (display === d && d.isRecording && recordingStartedAtMs == 0L) {
                recordStatus = "Recording failed: no video frames arrived from screen capture."
                recordStopRequested = true
                if (!d.isStreaming) stopServiceAfterRecording = true
                broadcastStatus()
                finishRecordingWhenReady(d)
            }
        }, recordStartTimeoutMs)
    }

    private fun requestStopRecording(d: RtmpDisplay) {
        if (recordStopRequested) return
        recordStopRequested = true
        if (!d.isStreaming) stopServiceAfterRecording = true
        if (!recordStatus.startsWith("Recording failed:")) {
            recordStatus = "Finalizing recording..."
        }
        broadcastStatus()
        finishRecordingWhenReady(d)
    }

    private fun finishRecordingWhenReady(d: RtmpDisplay) {
        if (display !== d || !d.isRecording) return
        val now = SystemClock.elapsedRealtime()
        val startedAt = recordingStartedAtMs
        if (startedAt == 0L) {
            val waitMs = recordStartTimeoutMs - (now - recordingRequestedAtMs)
            if (waitMs > 0) {
                handler.postDelayed({ finishRecordingWhenReady(d) }, minOf(waitMs, 250L))
                return
            }
        } else {
            val waitMs = minimumRecordDurationMs - (now - startedAt)
            if (waitMs > 0) {
                handler.postDelayed({ finishRecordingWhenReady(d) }, waitMs)
                return
            }
        }
        handler.post {
            if (display === d && d.isRecording) d.stopRecord()
        }
    }

    private fun validateRecording(file: File): String? {
        if (!file.isFile || file.length() < 1_024L) {
            return "The MP4 is empty or too short to be playable."
        }
        val retriever = MediaMetadataRetriever()
        var validationError: String? = null
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                .equals("yes", ignoreCase = true)
            if (duration <= 0L || !hasVideo) {
                validationError = "The finalized MP4 has no readable video track."
            }
        } catch (e: IllegalArgumentException) {
            validationError = "Android could not read the finalized MP4: ${e.message ?: "invalid file"}"
        } catch (e: RuntimeException) {
            validationError = "Android could not verify the finalized MP4: ${e.message ?: "invalid file"}"
        }
        try {
            retriever.release()
        } catch (e: RuntimeException) {
            if (validationError == null) {
                validationError = "Could not release the video validator: ${e.message ?: "unknown error"}"
            }
        }
        return validationError
    }

    private fun persistRecording(file: File) {
        lastRecordingPath = file.absolutePath
        getSharedPreferences("livecast", MODE_PRIVATE).edit()
            .putString("last_recording_path", file.absolutePath)
            .apply()
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
        if (previewOnly) {
            stopPreviewCapture(errorStatus ?: "Preview stopped")
            return
        }
        val activeDisplay = display
        if (activeDisplay?.isRecording == true) {
            stopServiceAfterRecording = true
            stopServiceStatus = errorStatus
            streamRequested = false
            streamConnected = false
            if (activeDisplay.isStreaming) activeDisplay.stopStream()
            requestStopRecording(activeDisplay)
            streamStatus = errorStatus ?: "Finalizing recording..."
            broadcastStatus()
            return
        }
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
