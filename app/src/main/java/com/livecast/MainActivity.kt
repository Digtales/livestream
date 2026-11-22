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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.net.InetSocketAddress
import java.net.Socket

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
    private lateinit var recordBtn: Button
    private lateinit var connectBtn: Button
    private lateinit var destinationStatus: TextView
    private lateinit var tickerPosition: RadioGroup
    private lateinit var tickerFont: Spinner
    private lateinit var tickerSize: SeekBar
    private lateinit var tickerSizeLabel: TextView
    private lateinit var tickerColor: Spinner
    private lateinit var previewCard: LinearLayout
    private lateinit var streamDot: View
    private lateinit var chatDot: View
    private lateinit var streamStatus: TextView
    private lateinit var chatStatus: TextView
    private var pendingMode = "stream"

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
        connectBtn = Button(this).apply {
            text = "CONNECT"
            setAllCaps(false)
            root.addView(this)
        }
        destinationStatus = statusText("Paste an RTMP URL to check the server")
        destinationStatus.setPadding(0, dp(4), 0, dp(4))
        root.addView(destinationStatus)
        connectBtn.setOnClickListener { checkDestination() }

        root.addView(label("Scrolling ticker"))
        ticker = et("Ticker text")
        root.addView(label("Ticker position"))
        tickerPosition = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("Top", "Bottom").forEachIndexed { index, text ->
                addView(RadioButton(context).apply {
                    this.text = text
                    id = 10 + index
                    setTextColor(Color.WHITE)
                })
            }
            check(11)
            root.addView(this)
        }
        root.addView(label("Ticker font"))
        tickerFont = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Sans", "Sans bold", "Serif", "Monospace")
            )
            root.addView(this)
        }
        root.addView(label("Ticker size"))
        tickerSizeLabel = statusText("24 px")
        root.addView(tickerSizeLabel)
        tickerSize = SeekBar(this).apply {
            max = 28
            progress = 10
            root.addView(this)
        }
        tickerSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tickerSizeLabel.text = "${progress + 14} px"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(label("Ticker color"))
        tickerColor = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("White", "Yellow", "Red", "Cyan", "Green")
            )
            root.addView(this)
        }
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
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            root.addView(this)
        }
        btn = actionButton("STREAM", "#E11D48").also { actionRow.addView(it, weightedButtonParams()) }
        recordBtn = actionButton("RECORD", "#2563EB").also { actionRow.addView(it, weightedButtonParams()) }
        btn.setOnClickListener {
            saveSettings()
            if (StreamService.streamConnected || StreamService.streamStatus == "Connecting") {
                startService(Intent(this, StreamService::class.java).setAction("STOP_STREAM"))
            } else if (!StreamService.running) {
                if (!validRtmpUrl()) return@setOnClickListener
                pendingMode = "stream"
                askPermissions()
            } else {
                startService(Intent(this, StreamService::class.java).setAction("STREAM"))
            }
        }
        recordBtn.setOnClickListener {
            saveSettings()
            if (StreamService.recording) {
                startService(Intent(this, StreamService::class.java).setAction("RECORD"))
            } else if (!StreamService.running) {
                pendingMode = "record"
                askPermissions()
            } else {
                startService(Intent(this, StreamService::class.java).setAction("RECORD"))
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

    private fun actionButton(text: String, color: String) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setAllCaps(false)
        setPadding(dp(4), dp(12), dp(4), dp(12))
        background = rounded(color)
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply { marginEnd = dp(6) }

    private fun validRtmpUrl(): Boolean {
        val value = url.text.toString().trim()
        val uri = Uri.parse(value)
        if (!listOf("rtmp", "rtmps").any { it.equals(uri.scheme, ignoreCase = true) } || uri.host.isNullOrBlank()) {
            url.error = "Enter a valid rtmp:// or rtmps:// server URL and stream key"
            return false
        }
        return true
    }

    private fun checkDestination() {
        if (!validRtmpUrl()) return
        val uri = Uri.parse(url.text.toString().trim())
        val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("rtmps", ignoreCase = true)) 443 else 1935
        val host = uri.host ?: return
        connectBtn.isEnabled = false
        destinationStatus.text = "Checking $host:$port..."
        destinationStatus.setTextColor(Color.YELLOW)
        Thread {
            val result = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 5000)
                }
                "Server reachable. Tap STREAM to start sending video."
            } catch (e: java.io.IOException) {
                "Server check failed: ${e.message ?: "connection refused"}"
            }
            runOnUiThread {
                connectBtn.isEnabled = true
                destinationStatus.text = result
                destinationStatus.setTextColor(
                    if (result.startsWith("Server reachable")) Color.GREEN else Color.RED
                )
            }
        }.start()
    }

    private fun saveSettings() {
        StreamService.apply {
            platform = when (this@MainActivity.platform.checkedRadioButtonId) {
                1 -> "yt"
                2 -> "fb"
                else -> "rtmp"
            }
            rtmpUrl = this@MainActivity.url.text.toString().trim()
            tickerText = this@MainActivity.ticker.text.toString()
            chatSource = when (this@MainActivity.platform.checkedRadioButtonId) {
                1 -> "yt"
                2 -> "fb"
                else -> ""
            }
            chatTarget = this@MainActivity.chatId.text.toString().trim()
            chatToken = this@MainActivity.token.text.toString().trim()
            landscape = this@MainActivity.orient.checkedRadioButtonId == 2
            micOn = this@MainActivity.mic.isChecked
            tickerPosition = if (this@MainActivity.tickerPosition.checkedRadioButtonId == 10) "top" else "bottom"
            tickerFont = this@MainActivity.tickerFont.selectedItem.toString()
            tickerTextSize = this@MainActivity.tickerSize.progress + 14
            tickerColor = when (this@MainActivity.tickerColor.selectedItemPosition) {
                1 -> Color.YELLOW
                2 -> Color.RED
                3 -> Color.CYAN
                4 -> Color.GREEN
                else -> Color.WHITE
            }
        }
    }

    private fun updateStatus() {
        setDot(streamDot, if (StreamService.streamConnected) Color.GREEN else Color.RED)
        streamStatus.text = StreamService.streamStatus
        btn.text = when {
            StreamService.streamConnected -> "STOP STREAM"
            StreamService.streamStatus == "Connecting" -> "CONNECTING..."
            else -> "STREAM"
        }
        recordBtn.text = if (StreamService.recording) "STOP RECORDING" else "RECORD"
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
        saveSettings()
        val i = Intent(this, StreamService::class.java).setAction("START")
            .putExtra("code", res)
            .putExtra("data", data)
            .putExtra("mode", pendingMode)
        startForegroundService(i)
    }
}
