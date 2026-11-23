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
import com.pedro.library.multiple.MultiDisplay
import com.pedro.library.multiple.MultiType
import com.pedro.library.view.GlStreamInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

class StreamService : Service() {
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
        var previewReady = false
        @Volatile
        var recording = false
        var audioPrepared = false
        var recordStatus = "Not recording"
        var recordingPath = ""
        var lastRecordingPath = ""
        var chatConnected = false
        var streamStatus = "Disconnected"
        var youtubeStreamStatus = "Not selected"
        var facebookStreamStatus = "Not selected"
        var youtubeConnected = false
        var facebookConnected = false
        var youtubeSelected = true
        var facebookSelected = false
        var outputAspectRatio = 9f / 16f
        var platform = ""
        var youtubeRtmpUrl = ""; var facebookRtmpUrl = ""
        var tickerText = ""; var chatSource = ""; var chatTarget = ""; var chatToken = ""
        var landscape = false; var micOn = true
        var tickerPosition = "bottom"
        var tickerTextSize = 24
        var tickerFont = "Sans"
        var tickerColor = Color.WHITE
    }

    private var display: MultiDisplay? = null
    private var filter: ImageObjectFilterRender? = null
    private val handler = Handler(Looper.getMainLooper())
    private val chat = ArrayList<String>()
    private var poller: ChatPoller? = null
    private var scrollX = 0f
    private var attachedPreviewSurface: Surface? = null
    private var requestedPreviewSurface: Surface? = null
    private var previewAttachRunnable: Runnable? = null
    private var previewProjection: MediaProjection? = null
    private var previewVirtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    @Volatile private var recordingRequestedAtMs = 0L
    @Volatile private var recordingStartedAtMs = 0L
    @Volatile private var recordStopRequested = false
    @Volatile private var stopServiceAfterRecording = false
    private var youtubeStreamAttempted = false
    private var facebookStreamAttempted = false
    private var stopServiceStatus: String? = null
    private val binder = LocalBinder()
    private val minimumRecordDurationMs = 1_000L
    private val recordStartTimeoutMs = 10_000L
    private val youtubeConnectChecker = destinationChecker("YouTube")
    private val facebookConnectChecker = destinationChecker("Facebook")

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
            previewAttachRunnable?.let(handler::removeCallbacks)
            previewAttachRunnable = null
            requestedPreviewSurface = null
            gl.deAttachPreview()
            attachedPreviewSurface = null
            previewReady = false
            return
        }
        if (width <= 0 || height <= 0) return
        requestedPreviewSurface = surface
        gl.setPreviewResolution(width, height)
        if (attachedPreviewSurface === surface && gl.isRunning()) {
            previewReady = true
            return
        }
        if (gl.isRunning()) attachPreviewToGl(gl, surface)
        else schedulePreviewAttach(gl, surface)
    }

    private fun updateScreenPreview(surface: Surface?, width: Int, height: Int) {
        if (surface == null || !previewEnabled) {
            previewVirtualDisplay?.setSurface(null)
            attachedPreviewSurface = null
            previewReady = false
            return
        }
        val projection = previewProjection ?: run {
            previewReady = false
            return
        }
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
        previewReady = true
    }

    private fun schedulePreviewAttach(
        gl: GlStreamInterface,
        surface: Surface
    ) {
        previewAttachRunnable?.let(handler::removeCallbacks)
        var attempts = 0
        val attachTask = object : Runnable {
            override fun run() {
                if (!running || !previewEnabled || requestedPreviewSurface !== surface || !surface.isValid) {
                    previewAttachRunnable = null
                    return
                }
                if (gl.isRunning()) {
                    attachPreviewToGl(gl, surface)
                    previewAttachRunnable = null
                } else if (++attempts < 40) {
                    handler.postDelayed(this, 50)
                } else {
                    previewReady = false
                    streamStatus = "Preview unavailable: video renderer did not start"
                    previewAttachRunnable = null
                    broadcastStatus()
                }
            }
        }
        previewAttachRunnable = attachTask
        handler.post(attachTask)
    }

    private fun attachPreviewToGl(gl: GlStreamInterface, surface: Surface) {
        try {
            gl.attachPreview(surface)
            attachedPreviewSurface = surface
            previewReady = true
            broadcastStatus()
        } catch (e: RuntimeException) {
            previewReady = false
            streamStatus = "Preview error: ${e.message ?: "surface is unavailable"}"
            broadcastStatus()
        }
    }

    private fun startPreviewCapture(i: Intent) {
        if (running) return
        previewReady = false
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
        audioPrepared = false
        previewReady = false
        previewVirtualDisplay?.release()
        previewVirtualDisplay = null
        attachedPreviewSurface = null
        requestedPreviewSurface = null
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
        audioPrepared = false
        previewReady = false
        streamRequested = false
        streamConnected = false
        youtubeConnected = false
        facebookConnected = false
        youtubeStreamStatus = if (youtubeSelected) "Disconnected" else "Not selected"
        facebookStreamStatus = if (facebookSelected) "Disconnected" else "Not selected"
        attachedPreviewSurface = null
        requestedPreviewSurface = null
        previewAttachRunnable?.let(handler::removeCallbacks)
        previewAttachRunnable = null
        val microphonePermissionGranted =
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!microphonePermissionGranted) micOn = false
        streamConnected = false
        streamStatus = when (i.getStringExtra("mode")) {
            "record" -> "Disconnected"
            "preview" -> "Screen preview starting"
            else -> "Connecting"
        }
        broadcastStatus()
        val ch = "live"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "Live", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle(
                when (i.getStringExtra("mode")) {
                    "record" -> "Recording screen"
                    "preview" -> "Screen preview active"
                    else -> "Streaming live"
                }
            )
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                (if (microphonePermissionGranted) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                })
            startForeground(
                1,
                n,
                foregroundTypes
            )
        } else {
            startForeground(1, n)
        }

        val metrics = resources.displayMetrics
        val screenLongSide = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val screenShortSide = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val encoderWidth = 1280
        val encoderHeight = (
            encoderWidth.toDouble() * screenShortSide / screenLongSide
            ).toInt().coerceIn(2, encoderWidth) / 2 * 2
        val rotation = if (landscape) 0 else 90
        val outputWidth = if (landscape) encoderWidth else encoderHeight
        val outputHeight = if (landscape) encoderHeight else encoderWidth
        outputAspectRatio = outputWidth.toFloat() / outputHeight
        val d = MultiDisplay(
            this,
            true,
            arrayOf(youtubeConnectChecker, facebookConnectChecker),
            null,
            null,
            null
        )
        d.setIntentResult(
            i.getIntExtra("code", 0),
            i.getParcelableExtra("data")
        )
        if (!d.prepareVideo(
                encoderWidth,
                encoderHeight,
                30,
                2_500_000,
                rotation,
                metrics.densityDpi
            )
        ) {
            throw IllegalStateException("Could not prepare screen video encoder")
        }
        if (microphonePermissionGranted) {
            if (!d.prepareAudio(128 * 1024, 44100, true)) {
                throw IllegalStateException("Could not prepare microphone audio encoder.")
            }
            audioPrepared = true
            if (!micOn) d.disableAudio()
        }
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
        handler.post(draw(outputWidth / 2, outputHeight / 2))
    }

    private fun startStream() {
        val d = display ?: return
        if (streamRequested) return
        val destinations = mutableListOf<Triple<String, Int, String>>()
        if (youtubeSelected) destinations.add(Triple("YouTube", 0, youtubeRtmpUrl))
        if (facebookSelected) destinations.add(Triple("Facebook", 1, facebookRtmpUrl))
        if (destinations.isEmpty()) {
            streamRequested = false
            streamConnected = false
            streamStatus = "Select YouTube, Facebook, or both before streaming."
            broadcastStatus()
            return
        }
        streamRequested = true
        streamConnected = false
        youtubeConnected = false
        facebookConnected = false
        youtubeStreamAttempted = youtubeSelected
        facebookStreamAttempted = facebookSelected
        youtubeStreamStatus = if (youtubeSelected) "Connecting" else "Not selected"
        facebookStreamStatus = if (facebookSelected) "Connecting" else "Not selected"
        updateCombinedStreamStatus()
        destinations.forEach { (name, index, endpoint) ->
            try {
                d.startStream(MultiType.RTMP, index, endpoint)
            } catch (e: RuntimeException) {
                updateDestinationStatus(name, "Failed: ${e.message ?: "could not connect"}", false)
            }
        }
    }

    private fun stopStream() {
        val d = display
        streamRequested = false
        streamConnected = false
        youtubeConnected = false
        facebookConnected = false
        youtubeStreamStatus = if (youtubeSelected) "Disconnected" else "Not selected"
        facebookStreamStatus = if (facebookSelected) "Disconnected" else "Not selected"
        if (d?.isRecording == true) {
            if (d != null) stopDestinationStreams(d)
            updateCombinedStreamStatus()
        } else {
            stop()
        }
    }

    private fun stopDestinationStreams(d: MultiDisplay) {
        if (youtubeStreamAttempted) d.stopStream(MultiType.RTMP, 0)
        if (facebookStreamAttempted) d.stopStream(MultiType.RTMP, 1)
        youtubeStreamAttempted = false
        facebookStreamAttempted = false
    }

    private fun updateCombinedStreamStatus() {
        streamConnected = (youtubeSelected && youtubeConnected) ||
            (facebookSelected && facebookConnected)
        val statuses = mutableListOf<String>()
        if (youtubeSelected) statuses.add("YouTube: $youtubeStreamStatus")
        if (facebookSelected) statuses.add("Facebook: $facebookStreamStatus")
        streamStatus = statuses.joinToString(" | ").ifEmpty { "No stream destination selected" }
        broadcastStatus()
    }

    private fun updateDestinationStatus(name: String, status: String, connected: Boolean) {
        if (name == "YouTube") {
            youtubeStreamStatus = status
            youtubeConnected = connected
        } else {
            facebookStreamStatus = status
            facebookConnected = connected
        }
        updateCombinedStreamStatus()

        val hasPendingConnection = (youtubeSelected && youtubeStreamStatus == "Connecting") ||
            (facebookSelected && facebookStreamStatus == "Connecting")
        if (streamRequested && !streamConnected && !hasPendingConnection) {
            streamRequested = false
            val failureStatus = streamStatus
            val activeDisplay = display
            if (activeDisplay != null) stopDestinationStreams(activeDisplay)
            if (activeDisplay?.isRecording == true) {
                streamStatus = failureStatus
                broadcastStatus()
            } else {
                stop(failureStatus)
            }
        }
    }

    private fun destinationChecker(name: String) = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            if (streamRequested) updateDestinationStatus(name, "Connecting", false)
        }

        override fun onConnectionSuccess() {
            if (streamRequested) updateDestinationStatus(name, "Connected", true)
        }

        override fun onConnectionFailed(reason: String) {
            if (!streamRequested) return
            val status = if (reason.contains("EOF", ignoreCase = true)) {
                "Failed: RTMP handshake closed; check server URL and stream key"
            } else {
                "Failed: $reason"
            }
            updateDestinationStatus(name, status, false)
        }

        override fun onNewBitrate(bitrate: Long) {}

        override fun onDisconnect() {
            if (streamRequested) updateDestinationStatus(name, "Disconnected", false)
        }

        override fun onAuthError() {
            if (streamRequested) updateDestinationStatus(name, "Authentication failed", false)
        }

        override fun onAuthSuccess() {}
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
                if (!streamRequested) stopServiceAfterRecording = true
                recordStopRequested = true
                finishRecordingWhenReady(d)
                broadcastStatus()
            }
        })
        handler.postDelayed({
            if (display === d && d.isRecording && recordingStartedAtMs == 0L) {
                recordStatus = "Recording failed: no video frames arrived from screen capture."
                recordStopRequested = true
                if (!streamRequested) stopServiceAfterRecording = true
                broadcastStatus()
                finishRecordingWhenReady(d)
            }
        }, recordStartTimeoutMs)
    }

    private fun requestStopRecording(d: MultiDisplay) {
        if (recordStopRequested) return
        recordStopRequested = true
        if (!streamRequested) stopServiceAfterRecording = true
        if (!recordStatus.startsWith("Recording failed:")) {
            recordStatus = "Finalizing recording..."
        }
        broadcastStatus()
        finishRecordingWhenReady(d)
    }

    private fun finishRecordingWhenReady(d: MultiDisplay) {
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
            if (errorStatus == null) {
                youtubeConnected = false
                facebookConnected = false
                youtubeStreamStatus = if (youtubeSelected) "Disconnected" else "Not selected"
                facebookStreamStatus = if (facebookSelected) "Disconnected" else "Not selected"
            }
            stopDestinationStreams(activeDisplay)
            requestStopRecording(activeDisplay)
            streamStatus = errorStatus ?: "Finalizing recording..."
            broadcastStatus()
            return
        }
        running = false
        streamRequested = false
        streamConnected = false
        if (errorStatus == null) {
            youtubeConnected = false
            facebookConnected = false
            youtubeStreamStatus = if (youtubeSelected) "Disconnected" else "Not selected"
            facebookStreamStatus = if (facebookSelected) "Disconnected" else "Not selected"
        }
        recording = false
        audioPrepared = false
        previewReady = false
        previewAttachRunnable?.let(handler::removeCallbacks)
        previewAttachRunnable = null
        attachedPreviewSurface = null
        requestedPreviewSurface = null
        chatConnected = false
        streamStatus = errorStatus ?: "Disconnected"
        broadcastStatus()
        handler.removeCallbacksAndMessages(null)
        poller?.stopPolling()
        display?.let { activeDisplay ->
            stopDestinationStreams(activeDisplay)
            activeDisplay.apply {
                if (isRecording) stopRecord()
            }
        }
        display = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(STATUS_ACTION))
    }

}
