package com.livecast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var ticker: EditText
    private lateinit var chatId: EditText
    private lateinit var token: EditText
    private lateinit var platform: RadioGroup
    private lateinit var orient: RadioGroup
    private lateinit var mic: Switch
    private lateinit var preview: Switch
    private lateinit var btn: Button
    private lateinit var previewCard: LinearLayout
    private lateinit var streamDot: View
    private lateinit var chatDot: View
    private lateinit var streamStatus: TextView
    private lateinit var chatStatus: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.parseColor("#10151F"))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#AAB3C2"))
            textSize = 12f
            setPadding(0, dp(8), 0, dp(5))
        }
        fun et(hintText: String) = EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#667085"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded("#1D2635")
            root.addView(this)
        }
        TextView(this).apply {
            text = "LIVE CAST"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            root.addView(this)
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
        }
        streamDot = statusDot(Color.RED)
        chatDot = statusDot(Color.RED)
        streamStatus = statusText("Stream disconnected")
        chatStatus = statusText("Chat optional")
        statusRow.addView(statusItem(streamDot, streamStatus))
        statusRow.addView(statusItem(chatDot, chatStatus))
        root.addView(statusRow)

        previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(36), dp(12), dp(36))
            minimumHeight = dp(150)
            background = rounded("#0B0F16")
            addView(TextView(this@MainActivity).apply {
                text = "SCREEN PREVIEW"
                setTextColor(Color.parseColor("#DDE4EE"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            root.addView(this)
        }

        root.addView(label("Destination"))
        url = et("RTMP URL + stream key")
        root.addView(label("Scrolling ticker"))
        ticker = et("Ticker text")
        root.addView(label("Platform"))
        platform = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("YouTube", "Facebook", "RTMP only").forEachIndexed { i, text ->
                addView(RadioButton(context).apply {
                    this.text = text
                    id = i + 1
                    setTextColor(Color.WHITE)
                })
            }
            check(1)
            root.addView(this)
        }
        root.addView(label("Live chat (optional)"))
        chatId = et("Live chat ID or Facebook video ID")
        token = et("OAuth access token")
        root.addView(label("Orientation"))
        orient = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("Portrait", "Landscape").forEachIndexed { i, text ->
                addView(RadioButton(context).apply {
                    this.text = text
                    id = i + 1
                    setTextColor(Color.WHITE)
                })
            }
            check(1)
            root.addView(this)
        }
        mic = Switch(this).apply {
            text = "Microphone"
            setTextColor(Color.WHITE)
            isChecked = true
            root.addView(this)
        }
        mic.setOnCheckedChangeListener { _, on ->
            StreamService.micOn = on
            if (StreamService.running) startService(Intent(this, StreamService::class.java).setAction("MIC"))
        }
        preview = Switch(this).apply {
            text = "Preview"
            setTextColor(Color.WHITE)
            isChecked = StreamService.previewEnabled
            root.addView(this)
        }
        preview.setOnCheckedChangeListener { _, on ->
            StreamService.previewEnabled = on
            previewCard.visibility = if (on) View.VISIBLE else View.GONE
            if (StreamService.running) {
                startService(Intent(this, StreamService::class.java).setAction("PREVIEW"))
            }
        }
        btn = Button(this).apply {
            text = "GO LIVE"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setAllCaps(false)
            setPadding(0, dp(12), 0, dp(12))
            background = rounded("#E11D48")
            root.addView(this)
        }
        btn.setOnClickListener {
            if (StreamService.running) {
                startService(Intent(this, StreamService::class.java).setAction("STOP"))
                btn.text = "GO LIVE"
            } else if (url.text.trim().isEmpty()) {
                url.error = "RTMP URL + stream key required"
            } else {
                askPermissions()
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
        updateStatus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor(color))
    }

    private fun statusDot(color: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
            marginEnd = dp(7)
            gravity = Gravity.CENTER_VERTICAL
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun statusText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 12f
    }

    private fun statusItem(dot: View, text: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(dot)
        addView(text)
    }

    private fun updateStatus() {
        setDot(streamDot, if (StreamService.streamConnected) Color.GREEN else Color.RED)
        streamStatus.text = StreamService.streamStatus
        val chatEnabled = chatId.text.toString().trim().isNotEmpty() &&
            platform.checkedRadioButtonId != 3
        setDot(chatDot, if (StreamService.chatConnected) Color.GREEN else if (chatEnabled) Color.RED else Color.GRAY)
        chatStatus.text = if (StreamService.chatConnected) "Chat connected" else if (chatEnabled) "Chat not connected" else "Chat optional"
    }

    private fun setDot(dot: View, color: Int) {
        (dot.background as? GradientDrawable)?.setColor(color)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateStatus()
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r.any { it != android.content.pm.PackageManager.PERMISSION_GRANTED }) return
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(StreamService.STATUS_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        updateStatus()
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: IllegalArgumentException) {}
        super.onStop()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req != 2 || res != RESULT_OK || data == null) return
        StreamService.apply {
            platform = when (this@MainActivity.platform.checkedRadioButtonId) {
                1 -> "yt"
                2 -> "fb"
                else -> "rtmp"
            }
            rtmpUrl = url.text.toString().trim()
            tickerText = ticker.text.toString()
            chatSource = when (this@MainActivity.platform.checkedRadioButtonId) { 1 -> "yt"; 2 -> "fb"; else -> "" }
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
