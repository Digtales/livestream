package com.livecast

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.*
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpDisplay

class StreamService : Service(), ConnectChecker {
    companion object {
        var running = false
        var rtmpUrl = ""; var tickerText = ""; var chatSource = ""; var chatTarget = ""; var chatToken = ""
        var landscape = false; var micOn = true
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
            "MIC" -> if (micOn) display?.enableAudio() else display?.disableAudio()
            "STOP" -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(i: Intent) {
        val ch = "live"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "Live", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("Streaming live")
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
        d.prepareVideo(w, h, 30, 2_500_000, 0, resources.displayMetrics.densityDpi)
        d.prepareAudio(128 * 1024, 44100, true)
        d.startStream(rtmpUrl)
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
            }.also { it.start() }
        }
        handler.post(draw(w / 2, h / 2))
    }

    // Draws overlay (chat + scrolling ticker) on a half-res transparent bitmap ~8 fps
    private fun draw(ow: Int, oh: Int) = object : Runnable {
        override fun run() {
            if (!running) return
            val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val barH = oh * 0.07f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            // ticker bar
            p.color = Color.argb(200, 0, 0, 0); c.drawRect(0f, oh - barH, ow.toFloat(), oh.toFloat(), p)
            p.color = Color.WHITE; p.textSize = barH * 0.6f
            val tw = p.measureText(tickerText + "      ")
            scrollX -= ow * 0.01f
            if (scrollX < -tw) scrollX = 0f
            var x = scrollX
            while (x < ow && tw > 0) { c.drawText(tickerText, x, oh - barH * 0.25f, p); x += tw }
            // chat lines above ticker
            p.textSize = barH * 0.5f
            val lines = synchronized(chat) { chat.toList() }
            lines.forEachIndexed { idx, s ->
                val y = oh - barH - 12f - (lines.size - 1 - idx) * p.textSize * 1.4f
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
        handler.removeCallbacksAndMessages(null)
        poller?.stopPolling()
        display?.apply { if (isStreaming) stopStream() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onConnectionFailed(reason: String) { stop() }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
