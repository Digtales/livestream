package com.livecast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var streamKey: EditText
    private lateinit var ticker: EditText
    private lateinit var chatId: EditText
    private lateinit var token: EditText
    private lateinit var platform: RadioGroup
    private lateinit var orient: RadioGroup
    private lateinit var mic: Switch
    private lateinit var preview: Switch
    private lateinit var btn: Button
    private lateinit var recordBtn: Button
    private lateinit var recordingStatus: TextView
    private lateinit var connectBtn: Button
    private lateinit var destinationStatus: TextView
    private lateinit var destinationDot: View
    private lateinit var tickerPosition: RadioGroup
    private lateinit var tickerFont: Spinner
    private lateinit var tickerSize: SeekBar
    private lateinit var tickerSizeLabel: TextView
    private lateinit var tickerColor: Spinner
    private lateinit var previewCard: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewView: SurfaceView
    private lateinit var overlayPreview: TextView
    private lateinit var previewHint: TextView
    private lateinit var streamDot: View
    private lateinit var chatDot: View
    private lateinit var streamStatus: TextView
    private lateinit var chatStatus: TextView
    private var pendingMode = "stream"
    private var streamService: StreamService? = null
    private var serviceBound = false
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            streamService = (binder as? StreamService.LocalBinder)?.getService()
            serviceBound = streamService != null
            attachPreview()
            updateStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
            serviceBound = false
        }
    }

    private val previewCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            previewSurface = holder.surface
            attachPreview()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            previewSurface = holder.surface
            previewWidth = width
            previewHeight = height
            attachPreview()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            previewSurface = null
            streamService?.updatePreview(null, 0, 0)
        }
    }

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
            background = rounded("#0B0F16")
            previewFrame = FrameLayout(this@MainActivity)
            previewView = SurfaceView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(210)
                )
                holder.addCallback(previewCallback)
            }
            previewFrame.addView(previewView)
            overlayPreview = TextView(this@MainActivity).apply {
                text = "Ticker preview"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(210, 0, 0, 0))
                textSize = 24f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            previewFrame.addView(
                overlayPreview,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
            addView(previewFrame)
            previewHint = TextView(this@MainActivity).apply {
                text = "Turn Preview on to select a screen or app."
                setTextColor(Color.parseColor("#DDE4EE"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            addView(previewHint)
            root.addView(this)
        }

        root.addView(label("Destination"))
        url = et("RTMP server URL (e.g. rtmp://a.rtmp.youtube.com/live2)")
        streamKey = et("Stream key (optional if already included in URL)")
        streamKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(TextView(this).apply {
            text = "For YouTube, copy your private stream key from YouTube Studio > Live Control Room > Stream settings. Keep it secret."
            setTextColor(Color.parseColor("#AAB3C2"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        connectBtn = Button(this).apply {
            text = "CONNECT"
            setAllCaps(false)
            root.addView(this)
        }
        val destinationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        destinationDot = statusDot(Color.RED)
        destinationStatus = statusText("Server not checked")
        destinationStatus.setPadding(0, dp(4), 0, dp(4))
        destinationRow.addView(destinationDot)
        destinationRow.addView(destinationStatus)
        root.addView(destinationRow)
        connectBtn.setOnClickListener { checkDestination() }

        root.addView(label("Scrolling ticker"))
        ticker = et("Ticker text")
        ticker.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateOverlayPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
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
            setOnCheckedChangeListener { _, _ -> updateOverlayPreview() }
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
        tickerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateOverlayPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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
                updateOverlayPreview()
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
        tickerColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateOverlayPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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
            if (on) {
                saveSettings()
                if (StreamService.running) {
                    previewHint.text = "Starting preview..."
                    attachPreview()
                } else {
                    pendingMode = "preview"
                    previewHint.text = "Choose a screen or app to preview."
                    requestScreenCapture()
                }
            } else if (StreamService.previewOnly) {
                startService(Intent(this, StreamService::class.java).setAction("PREVIEW_STOP"))
                previewHint.text = "Preview stopped."
            } else {
                streamService?.updatePreview(null, 0, 0)
                previewHint.text = "Preview off."
            }
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            root.addView(this)
        }
        btn = actionButton("STREAM", "#E11D48").also { actionRow.addView(it, weightedButtonParams()) }
        recordBtn = actionButton("RECORD", "#2563EB").also { actionRow.addView(it, weightedButtonParams()) }
        recordingStatus = statusText(StreamService.recordStatus)
        recordingStatus.setPadding(0, dp(6), 0, dp(6))
        root.addView(recordingStatus)
        btn.setOnClickListener {
            saveSettings()
            if (StreamService.streamConnected || StreamService.streamStatus == "Connecting") {
                startService(Intent(this, StreamService::class.java).setAction("STOP_STREAM"))
            } else if (StreamService.previewOnly) {
                startService(Intent(this, StreamService::class.java).setAction("PREVIEW_STOP"))
                if (!validRtmpUrl()) return@setOnClickListener
                pendingMode = "stream"
                askPermissions()
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
            } else if (StreamService.previewOnly) {
                startService(Intent(this, StreamService::class.java).setAction("PREVIEW_STOP"))
                pendingMode = "record"
                askPermissions()
            } else if (!StreamService.running) {
                pendingMode = "record"
                askPermissions()
            } else {
                startService(Intent(this, StreamService::class.java).setAction("RECORD"))
            }
        }
        val openRecordingButton = actionButton("OPEN LAST RECORDING", "#334155")
        root.addView(openRecordingButton)
        openRecordingButton.setOnClickListener { openLastRecording() }
        setContentView(ScrollView(this).apply { addView(root) })
        updateOverlayPreview()
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
        val value = effectiveRtmpUrl()
        val uri = Uri.parse(value)
        val pathSegments = uri.pathSegments.filter { it.isNotBlank() }
        if (!listOf("rtmp", "rtmps").any { it.equals(uri.scheme, ignoreCase = true) } ||
            uri.host.isNullOrBlank() ||
            pathSegments.size < 2
        ) {
            url.error = "Use an RTMP(S) server URL with app name and stream key"
            return false
        }
        return true
    }

    private fun effectiveRtmpUrl(): String {
        val serverUrl = url.text.toString().trim().trimEnd('/')
        val key = streamKey.text.toString().trim().trim('/')
        return if (key.isEmpty()) serverUrl else "$serverUrl/$key"
    }

    private fun checkDestination() {
        if (!validRtmpUrl()) return
        val uri = Uri.parse(effectiveRtmpUrl())
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
                "RTMP server reachable. Tap STREAM to start the live broadcast."
            } catch (e: java.io.IOException) {
                "Server check failed: ${e.message ?: "connection refused"}"
            }
            runOnUiThread {
                connectBtn.isEnabled = true
                destinationStatus.text = result
                val reachable = result.startsWith("RTMP server reachable")
                destinationStatus.setTextColor(if (reachable) Color.GREEN else Color.RED)
                setDot(destinationDot, if (reachable) Color.GREEN else Color.RED)
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
            rtmpUrl = this@MainActivity.effectiveRtmpUrl()
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

    private fun updateOverlayPreview() {
        if (!::overlayPreview.isInitialized ||
            !::ticker.isInitialized ||
            !::tickerPosition.isInitialized ||
            !::tickerFont.isInitialized ||
            !::tickerSize.isInitialized ||
            !::tickerColor.isInitialized
        ) return
        val text = ticker.text.toString()
        StreamService.tickerText = text
        StreamService.tickerPosition =
            if (tickerPosition.checkedRadioButtonId == 10) "top" else "bottom"
        StreamService.tickerFont = tickerFont.selectedItem?.toString() ?: "Sans"
        StreamService.tickerTextSize = tickerSize.progress + 14
        StreamService.tickerColor = when (tickerColor.selectedItemPosition) {
            1 -> Color.YELLOW
            2 -> Color.RED
            3 -> Color.CYAN
            4 -> Color.GREEN
            else -> Color.WHITE
        }
        overlayPreview.text = text.ifBlank { "Ticker preview" }
        overlayPreview.setTextColor(StreamService.tickerColor)
        overlayPreview.textSize = StreamService.tickerTextSize.toFloat()
        overlayPreview.typeface = when (StreamService.tickerFont) {
            "Serif" -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            "Monospace" -> Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            "Sans bold" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val gravity = if (StreamService.tickerPosition == "top") Gravity.TOP else Gravity.BOTTOM
        (overlayPreview.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = gravity
            overlayPreview.layoutParams = params
        }
    }

    private fun openLastRecording() {
        val path = getSharedPreferences("livecast", MODE_PRIVATE)
            .getString("last_recording_path", StreamService.lastRecordingPath)
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "No saved recording yet.", Toast.LENGTH_LONG).show()
            return
        }
        val file = File(path)
        if (!file.isFile) {
            Toast.makeText(this, "Recording file not found.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No video player is installed.", Toast.LENGTH_LONG).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Cannot open recording: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        setDot(
            streamDot,
            when {
                StreamService.streamConnected -> Color.GREEN
                StreamService.streamStatus == "Connecting" -> Color.YELLOW
                else -> Color.RED
            }
        )
        streamStatus.text = StreamService.streamStatus
        btn.text = when {
            StreamService.streamConnected -> "STOP STREAM"
            StreamService.streamStatus == "Connecting" -> "CANCEL STREAM"
            else -> "STREAM"
        }
        recordBtn.text = if (StreamService.recording) "STOP RECORDING" else "RECORD"
        recordingStatus.text = StreamService.recordStatus
        val chatEnabled = chatId.text.toString().trim().isNotEmpty() &&
            platform.checkedRadioButtonId != 3
        setDot(chatDot, if (StreamService.chatConnected) Color.GREEN else if (chatEnabled) Color.RED else Color.GRAY)
        chatStatus.text = if (StreamService.chatConnected) "Chat connected" else if (chatEnabled) "Chat not connected" else "Chat optional"
    }

    private fun setDot(dot: View, color: Int) {
        (dot.background as? GradientDrawable)?.setColor(color)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateStatus()
            attachPreview()
        }
    }

    private fun askPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            requestScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c != 1) return
        if (r.isEmpty() || r[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            StreamService.streamStatus = "Microphone permission denied"
            updateStatus()
            Toast.makeText(this, "Allow microphone permission to start capture.", Toast.LENGTH_LONG).show()
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
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
        serviceBound = bindService(
            Intent(this, StreamService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        updateStatus()
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: IllegalArgumentException) {}
        if (serviceBound) {
            streamService?.updatePreview(null, 0, 0)
            unbindService(serviceConnection)
            serviceBound = false
            streamService = null
        }
        super.onStop()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 2) return
        if (res != RESULT_OK || data == null) {
            StreamService.streamStatus = if (pendingMode == "preview") {
                "Preview permission cancelled"
            } else {
                "Screen capture permission cancelled"
            }
            if (pendingMode == "preview") preview.isChecked = false
            updateStatus()
            return
        }
        saveSettings()
        val isPreview = pendingMode == "preview"
        val i = Intent(this, StreamService::class.java)
            .setAction(if (isPreview) "PREVIEW_START" else "START")
            .putExtra("code", res)
            .putExtra("data", data)
            .putExtra("mode", pendingMode)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: RuntimeException) {
            StreamService.streamStatus = "Could not start service: ${e.message ?: "check permissions"}"
            updateStatus()
        }
    }

    private fun attachPreview() {
        if (!preview.isChecked || previewSurface == null || previewWidth <= 0 || previewHeight <= 0) return
        val service = streamService ?: return
        try {
            service.updatePreview(previewSurface, previewWidth, previewHeight)
            previewHint.text = when {
                StreamService.previewOnly ->
                    "Previewing the selected screen or app. Protected apps may show black by design."
                StreamService.running ->
                    "Live capture preview. Android may hide content from apps that block screen capture."
                else -> "Preview will appear after capture starts."
            }
        } catch (e: RuntimeException) {
            previewHint.text = "Preview error: ${e.message ?: "surface is unavailable"}"
            StreamService.streamStatus = "Preview error: ${e.message ?: "surface is unavailable"}"
            updateStatus()
        }
    }
}
