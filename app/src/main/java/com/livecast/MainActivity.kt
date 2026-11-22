package com.livecast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var ticker: EditText
    private lateinit var chatId: EditText
    private lateinit var token: EditText
    private lateinit var platform: RadioGroup
    private lateinit var orient: RadioGroup
    private lateinit var mic: Switch
    private lateinit var btn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun et(h: String) = EditText(this).apply { hint = h; root.addView(this) }
        url = et("RTMP URL + stream key (YouTube / Facebook)")
        ticker = et("Scrolling text (bottom ticker)")
        chatId = et("YouTube liveChatId  OR  Facebook live video ID")
        token = et("OAuth access token (for live chat)")
        platform = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("YouTube", "Facebook", "No chat").forEachIndexed { i, t ->
                addView(RadioButton(context).apply { text = t; id = i + 1 })
            }
            check(1); root.addView(this)
        }
        orient = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("Portrait", "Landscape").forEachIndexed { i, t ->
                addView(RadioButton(context).apply { text = t; id = i + 1 })
            }
            check(1); root.addView(this)
        }
        mic = Switch(this).apply { text = "Mic ON/OFF"; isChecked = true; root.addView(this) }
        mic.setOnCheckedChangeListener { _, on ->
            StreamService.micOn = on
            if (StreamService.running) startService(Intent(this, StreamService::class.java).setAction("MIC"))
        }
        btn = Button(this).apply { text = "GO LIVE"; root.addView(this) }
        btn.setOnClickListener {
            if (StreamService.running) {
                startService(Intent(this, StreamService::class.java).setAction("STOP")); btn.text = "GO LIVE"
            } else askPermissions()
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+: the system dialog lets you pick "A single app" or "Entire screen"
        startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req != 2 || res != RESULT_OK || data == null) return
        StreamService.apply {
            rtmpUrl = url.text.toString().trim()
            tickerText = ticker.text.toString()
            chatSource = when (platform.checkedRadioButtonId) { 1 -> "yt"; 2 -> "fb"; else -> "" }
            chatTarget = chatId.text.toString().trim()
            chatToken = token.text.toString().trim()
            landscape = orient.checkedRadioButtonId == 2
            micOn = mic.isChecked
        }
        val i = Intent(this, StreamService::class.java).setAction("START")
            .putExtra("code", res).putExtra("data", data)
        startForegroundService(i)
        btn.text = "STOP"
    }
}
