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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.IBinder
import android.util.Base64
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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_CAPTURE_PERMISSIONS = 1
        private const val REQUEST_SCREEN_CAPTURE = 2
        private const val REQUEST_STARTUP_PERMISSIONS = 3
        private const val REQUEST_MICROPHONE_TOGGLE = 4

        private var startupPermissionFlowStarted = false
        private var pendingProjectionCode: Int? = null
        private var pendingProjectionData: Intent? = null
    }

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
    private lateinit var previewFrame: AspectRatioFrameLayout
    private lateinit var previewView: SurfaceView
    private lateinit var overlayPreview: TextView
    private lateinit var previewHint: TextView
    private lateinit var streamDot: View
    private lateinit var chatDot: View
    private lateinit var streamStatus: TextView
    private lateinit var chatStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var settingsPage: ScrollView
    private lateinit var livePage: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var liveTabButton: Button
    private lateinit var settingsTabButton: Button
    private var pendingMode = "stream"
    private var streamService: StreamService? = null
    private var serviceBound = false
    private var suppressPreviewChange = false
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var cachedStreamKey: String? = null
    private var cachedEncryptedStreamKey: String? = null
    private val settingsPreferences by lazy { getSharedPreferences("livecast_settings", MODE_PRIVATE) }

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

    private class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {
        var aspectRatio: Float = 9f / 16f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = View.MeasureSpec.getSize(widthMeasureSpec)
            val maxHeight = View.MeasureSpec.getSize(heightMeasureSpec)
            if (maxWidth <= 0 || maxHeight <= 0 || aspectRatio <= 0f) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }

            var width = maxWidth
            var height = (width / aspectRatio).toInt()
            if (height > maxHeight) {
                height = maxHeight
                width = (height * aspectRatio).toInt()
            }
            setMeasuredDimension(width, height)
            val exactWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val exactHeight = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            measureChildren(exactWidth, exactHeight)
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
        pendingMode = b?.getString("pending_mode") ?: "stream"
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
        val appTitle = TextView(this).apply {
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

        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#0B0F16")
            previewFrame = AspectRatioFrameLayout(this@MainActivity)
            previewView = SurfaceView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
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
            addView(
                previewFrame,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0,
                    1f
                ).apply { gravity = Gravity.CENTER }
            )
            previewHint = TextView(this@MainActivity).apply {
                text = "Microphone and notification permissions are checked at launch. Android requires screen-share consent for each capture session; camera and broad storage access are not used."
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
        streamKey = et("YouTube stream key (actual secret value)")
        streamKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(TextView(this).apply {
            text = "In YouTube Studio > Live Control Room > Stream settings, copy the key value. A custom key name (for example, TradeLive) is only a label, not the key."
            setTextColor(Color.parseColor("#AAB3C2"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        connectBtn = Button(this).apply {
            text = "CHECK SERVER"
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
            setOnCheckedChangeListener { _, _ ->
                updateOverlayPreview()
                persistSettings()
            }
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
                persistSettings()
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
                persistSettings()
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
                persistSettings()
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
            setOnCheckedChangeListener { _, _ -> persistSettings() }
            root.addView(this)
        }
        root.addView(label("Live chat (optional)"))
        chatId = et("Live chat ID or Facebook video ID")
        token = et("OAuth access token")
        root.addView(label("Video orientation"))
        orient = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("Portrait", "Landscape", "Auto").forEachIndexed { i, text ->
                addView(RadioButton(context).apply {
                    this.text = text
                    id = i + 1
                    setTextColor(Color.WHITE)
                })
            }
            check(3)
            setOnCheckedChangeListener { _, _ ->
                updatePreviewAspectRatio()
                persistSettings()
            }
            root.addView(this)
        }
        root.addView(TextView(this).apply {
            text = "Auto uses the tablet's current orientation when capture starts. Stop and restart capture after rotating to change the stream format."
            setTextColor(Color.parseColor("#AAB3C2"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        mic = Switch(this).apply {
            text = "Microphone"
            setTextColor(Color.WHITE)
            isChecked = true
            root.addView(this)
        }
        mic.setOnCheckedChangeListener { _, on ->
            if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                mic.isChecked = false
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_MICROPHONE_TOGGLE
                )
                return@setOnCheckedChangeListener
            }
            StreamService.micOn = on
            persistSettings()
            if (StreamService.running && StreamService.audioPrepared) {
                startService(Intent(this, StreamService::class.java).setAction("MIC"))
            } else if (on && StreamService.running) {
                Toast.makeText(this, "Restart capture to enable microphone audio.", Toast.LENGTH_LONG).show()
            }
        }
        preview = Switch(this).apply {
            text = "Preview"
            setTextColor(Color.WHITE)
            isChecked = StreamService.previewEnabled
            root.addView(this)
        }
        preview.setOnCheckedChangeListener { _, on ->
            StreamService.previewEnabled = on
            if (suppressPreviewChange) return@setOnCheckedChangeListener
            if (on) {
                saveSettings()
                if (StreamService.running) {
                    previewHint.text = "Starting preview..."
                    attachPreview()
                } else {
                    pendingMode = "preview"
                    previewHint.text = "Choose a screen or app to preview."
                    askPermissions()
                }
            } else if (StreamService.previewOnly) {
                startService(Intent(this, StreamService::class.java).setAction("PREVIEW_STOP"))
                previewHint.text = "Preview stopped."
            } else {
                streamService?.updatePreview(null, 0, 0)
                previewHint.text = "Preview off."
            }
        }
        permissionStatus = statusText("Permissions: checking at startup")
        permissionStatus.textSize = 10f
        root.addView(permissionStatus)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            root.addView(this)
        }
        btn = actionButton("GO LIVE", "#E11D48").also { actionRow.addView(it, weightedButtonParams()) }
        recordBtn = actionButton("RECORD", "#2563EB").also { actionRow.addView(it, weightedButtonParams()) }
        recordingStatus = statusText(StreamService.recordStatus)
        recordingStatus.setPadding(0, dp(6), 0, dp(6))
        root.addView(recordingStatus)
        btn.setOnClickListener {
            saveSettings()
            if (StreamService.streamConnected || StreamService.streamStatus == "Connecting") {
                startService(Intent(this, StreamService::class.java).setAction("STOP_STREAM"))
            } else {
                showPage(false)
                enablePreviewForCapture()
                if (StreamService.previewOnly) {
                    startService(Intent(this, StreamService::class.java).setAction("PREVIEW_STOP"))
                    if (!validRtmpUrl()) return@setOnClickListener
                    pendingMode = "stream"
                    StreamService.streamStatus = "Waiting for screen capture permission"
                    updateStatus()
                    askPermissions()
                } else if (!StreamService.running) {
                    if (!validRtmpUrl()) return@setOnClickListener
                    pendingMode = "stream"
                    StreamService.streamStatus = "Waiting for screen capture permission"
                    updateStatus()
                    askPermissions()
                } else {
                    startService(Intent(this, StreamService::class.java).setAction("STREAM"))
                }
            }
        }
        recordBtn.setOnClickListener {
            saveSettings()
            if (StreamService.recording) {
                startService(Intent(this, StreamService::class.java).setAction("RECORD"))
            } else {
                showPage(false)
                enablePreviewForCapture()
                if (StreamService.previewOnly) {
                    startService(Intent(this, StreamService::class.java).setAction("PREVIEW_STOP"))
                    pendingMode = "record"
                    StreamService.recordStatus = "Waiting for screen capture permission..."
                    updateStatus()
                    askPermissions()
                } else if (!StreamService.running) {
                    pendingMode = "record"
                    StreamService.recordStatus = "Waiting for screen capture permission..."
                    updateStatus()
                    askPermissions()
                } else {
                    startService(Intent(this, StreamService::class.java).setAction("RECORD"))
                }
            }
        }
        val openRecordingButton = actionButton("OPEN LAST RECORDING", "#334155")
        root.addView(openRecordingButton)
        openRecordingButton.setOnClickListener { openLastRecording() }
        buildResponsivePages(
            root,
            appTitle,
            statusRow,
            previewCard,
            actionRow,
            openRecordingButton
        )
        restoreSettings()
        bindSettingsPersistence()
        saveSettings()
        setContentView(root)
        showPage(false)
        updateOverlayPreview()
        updateStatus()
        beginStartupPermissionSetup()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_mode", pendingMode)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildResponsivePages(
        root: LinearLayout,
        appTitle: TextView,
        statusRow: LinearLayout,
        previewCard: LinearLayout,
        actionRow: LinearLayout,
        openRecordingButton: Button
    ) {
        val settingsViews = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filterNot {
                it === appTitle ||
                    it === statusRow ||
                    it === previewCard ||
                    it === preview ||
                    it === permissionStatus ||
                    it === actionRow ||
                    it === recordingStatus ||
                    it === openRecordingButton
            }
        root.removeAllViews()

        livePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(
                previewCard,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
            addView(
                permissionStatus,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
            addView(
                openRecordingButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
        }

        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
            settingsViews.forEach { addView(it) }
        }
        settingsPage = ScrollView(this).apply {
            isFillViewport = true
            visibility = View.GONE
            addView(
                settingsContent,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        pageHost = FrameLayout(this)
        pageHost.addView(
            livePage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        pageHost.addView(
            settingsPage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        liveTabButton = actionButton("LIVE PREVIEW", "#2563EB")
        settingsTabButton = actionButton("SETTINGS", "#334155")
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(liveTabButton, weightedButtonParams())
            addView(settingsTabButton, weightedButtonParams())
        }
        liveTabButton.setOnClickListener { showPage(false) }
        settingsTabButton.setOnClickListener { showPage(true) }

        root.addView(appTitle)
        root.addView(statusRow)
        root.addView(actionRow)
        root.addView(recordingStatus)
        root.addView(
            pageHost,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(tabBar)
    }

    private fun showPage(showSettings: Boolean) {
        if (!::settingsPage.isInitialized || !::livePage.isInitialized) return
        livePage.visibility = if (showSettings) View.GONE else View.VISIBLE
        settingsPage.visibility = if (showSettings) View.VISIBLE else View.GONE
        if (::liveTabButton.isInitialized && ::settingsTabButton.isInitialized) {
            liveTabButton.alpha = if (showSettings) 0.65f else 1f
            settingsTabButton.alpha = if (showSettings) 1f else 0.65f
        }
    }

    private fun restoreSettings() {
        val prefs = settingsPreferences
        url.setText(prefs.getString("server_url", "rtmp://a.rtmp.youtube.com/live2"))
        cachedEncryptedStreamKey = prefs.getString("stream_key", "")
        cachedStreamKey = decryptStreamKey(cachedEncryptedStreamKey.orEmpty())
        streamKey.setText(cachedStreamKey)
        ticker.setText(prefs.getString("ticker_text", ""))
        tickerPosition.check(prefs.getInt("ticker_position", 11))
        tickerFont.setSelection(prefs.getInt("ticker_font", 0).coerceIn(0, 3))
        tickerSize.progress = (prefs.getInt("ticker_size", 24) - 14).coerceIn(0, 28)
        tickerColor.setSelection(prefs.getInt("ticker_color", 0).coerceIn(0, 4))
        platform.check(prefs.getInt("platform", 1).coerceIn(1, 3))
        chatId.setText(prefs.getString("chat_id", ""))
        orient.check(
            prefs.getInt(
                "orientation",
                3
            ).coerceIn(1, 3)
        )
        mic.isChecked = prefs.getBoolean("microphone", true)
        preview.isChecked = StreamService.previewEnabled
        updatePreviewAspectRatio()
    }

    private fun bindSettingsPersistence() {
        listOf(url, streamKey, ticker, chatId).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    persistSettings()
                }
            })
        }
    }

    private fun persistSettings() {
        if (!::url.isInitialized ||
            !::streamKey.isInitialized ||
            !::ticker.isInitialized ||
            !::tickerPosition.isInitialized ||
            !::tickerFont.isInitialized ||
            !::tickerSize.isInitialized ||
            !::tickerColor.isInitialized ||
            !::platform.isInitialized ||
            !::chatId.isInitialized ||
            !::orient.isInitialized ||
            !::mic.isInitialized
        ) return
        val currentStreamKey = streamKey.text.toString()
        val encryptedStreamKey = if (currentStreamKey == cachedStreamKey &&
            cachedEncryptedStreamKey != null
        ) {
            cachedEncryptedStreamKey.orEmpty()
        } else {
            try {
                encryptStreamKey(currentStreamKey)
            } catch (e: GeneralSecurityException) {
                streamKey.error = "Unable to securely save stream key: ${e.message ?: "Keystore error"}"
                return
            }
        }
        streamKey.error = null
        cachedStreamKey = currentStreamKey
        cachedEncryptedStreamKey = encryptedStreamKey
        settingsPreferences.edit()
            .putString("server_url", url.text.toString())
            .putString("stream_key", encryptedStreamKey)
            .putString("ticker_text", ticker.text.toString())
            .putInt("ticker_position", tickerPosition.checkedRadioButtonId)
            .putInt("ticker_font", tickerFont.selectedItemPosition)
            .putInt("ticker_size", tickerSize.progress + 14)
            .putInt("ticker_color", tickerColor.selectedItemPosition)
            .putInt("platform", platform.checkedRadioButtonId)
            .putString("chat_id", chatId.text.toString())
            .putInt("orientation", orient.checkedRadioButtonId)
            .putBoolean("microphone", mic.isChecked)
            .apply()
    }

    private fun decryptStreamKey(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            if (encrypted.size <= 12) throw GeneralSecurityException("Saved key data is invalid")
            val iv = encrypted.copyOfRange(0, 12)
            val ciphertext = encrypted.copyOfRange(12, encrypted.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, streamKeyEncryptionKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            clearSavedStreamKey()
            Toast.makeText(this, "Saved stream key is invalid. Enter it again.", Toast.LENGTH_LONG).show()
            ""
        } catch (e: GeneralSecurityException) {
            clearSavedStreamKey()
            Toast.makeText(this, "Saved stream key could not be unlocked. Enter it again.", Toast.LENGTH_LONG).show()
            ""
        }
    }

    private fun encryptStreamKey(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, streamKeyEncryptionKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun streamKeyEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        try {
            keyStore.load(null)
        } catch (e: IOException) {
            throw GeneralSecurityException("Could not access Android Keystore", e)
        } catch (e: GeneralSecurityException) {
            throw GeneralSecurityException("Could not access Android Keystore", e)
        }
        val existing = keyStore.getKey("livecast_stream_key", null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                "livecast_stream_key",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun clearSavedStreamKey() {
        settingsPreferences.edit().remove("stream_key").apply()
        cachedStreamKey = ""
        cachedEncryptedStreamKey = ""
    }

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
        val pressedColor = if (color == "#E11D48") "#BE123C" else "#1D4ED8"
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressedColor))
            addState(intArrayOf(), rounded(color))
        }
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply { marginEnd = dp(6) }

    private fun enablePreviewForCapture() {
        if (preview.isChecked) return
        suppressPreviewChange = true
        preview.isChecked = true
        suppressPreviewChange = false
        StreamService.previewEnabled = true
        if (StreamService.running) attachPreview()
    }

    private fun updatePreviewAspectRatio() {
        if (!::previewFrame.isInitialized || !::orient.isInitialized) return
        if (StreamService.running && !StreamService.previewOnly) {
            previewFrame.aspectRatio = StreamService.outputAspectRatio
            previewFrame.requestLayout()
            return
        }
        val metrics = resources.displayMetrics
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val landscapeOutput = when (orient.checkedRadioButtonId) {
            1 -> false
            2 -> true
            else -> resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        previewFrame.aspectRatio = if (landscapeOutput) {
            longSide.toFloat() / shortSide
        } else {
            shortSide.toFloat() / longSide
        }
        previewFrame.requestLayout()
    }

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
        url.error = null
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
                destinationStatus.text = if (result.startsWith("RTMP server reachable")) {
                    "TCP reachable only; tap STREAM to publish. This does not verify your stream key."
                } else {
                    result
                }
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
            landscape = when (this@MainActivity.orient.checkedRadioButtonId) {
                1 -> false
                2 -> true
                else -> resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
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
        persistSettings()
        updatePreviewAspectRatio()
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
        persistSettings()
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
                StreamService.streamStatus == "Connecting" ||
                    StreamService.streamStatus.startsWith("Waiting") ||
                    StreamService.streamStatus.startsWith("Checking") ||
                    StreamService.streamStatus.startsWith("Allow screen") ||
                    StreamService.streamStatus.startsWith("Permissions ready") -> Color.YELLOW
                else -> Color.RED
            }
        )
        streamStatus.text = StreamService.streamStatus
        btn.text = when {
            StreamService.streamConnected -> "STOP STREAM"
            StreamService.streamStatus == "Connecting" -> "CANCEL STREAM"
            StreamService.streamStatus.startsWith("Waiting") -> "PLEASE WAIT..."
            else -> "GO LIVE"
        }
        recordBtn.text = when {
            StreamService.recording -> "STOP RECORDING"
            StreamService.recordStatus.startsWith("Waiting") ||
                StreamService.recordStatus.startsWith("Preparing") -> "STARTING..."
            StreamService.recordStatus.startsWith("Finalizing") -> "FINALIZING..."
            else -> "RECORD"
        }
        recordBtn.isEnabled = !StreamService.recordStatus.startsWith("Finalizing")
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

    private fun beginStartupPermissionSetup() {
        if (startupPermissionFlowStarted || StreamService.running) return
        startupPermissionFlowStarted = true
        pendingMode = "startup"
        StreamService.streamStatus = "Checking required permissions"
        updatePermissionHint("not requested")
        updateStatus()

        val missingPermissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missingPermissions.isEmpty()) {
            requestStartupScreenCapture()
        } else {
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_STARTUP_PERMISSIONS)
        }
    }

    private fun requestStartupScreenCapture() {
        pendingMode = "startup"
        StreamService.streamStatus = "Allow screen sharing when prompted"
        updatePermissionHint("awaiting approval")
        updateStatus()
        requestScreenCapture()
    }

    private fun askPermissions() {
        if (preview.isChecked && pendingMode == "preview") {
            StreamService.previewEnabled = true
        }
        if (mic.isChecked &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_CAPTURE_PERMISSIONS
            )
        } else {
            startCaptureWithAvailableConsent()
        }
    }

    private fun startCaptureWithAvailableConsent() {
        val code = pendingProjectionCode
        val data = pendingProjectionData
        if (code == null || data == null) {
            StreamService.streamStatus = "Waiting for screen sharing permission"
            updatePermissionHint("awaiting approval")
            updateStatus()
            requestScreenCapture()
            return
        }
        pendingProjectionCode = null
        pendingProjectionData = null
        startCaptureService(pendingMode, code, data)
    }

    private fun updatePermissionHint(screenShare: String) {
        val microphone = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            "granted"
        } else {
            "not granted (video-only)"
        }
        val notifications = if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            "granted"
        } else {
            "not granted (optional)"
        }
        permissionStatus.text =
            "Microphone: $microphone | Notifications: $notifications | Screen share: $screenShare"
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    private fun startCaptureService(mode: String, resultCode: Int, data: Intent) {
        saveSettings()
        val intent = Intent(this, StreamService::class.java)
            .setAction(if (mode == "preview") "PREVIEW_START" else "START")
            .putExtra("code", resultCode)
            .putExtra("data", data)
            .putExtra("mode", mode)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: RuntimeException) {
            val message = e.message ?: "check permissions"
            if (mode == "record") {
                StreamService.recordStatus = "Recording failed: $message"
            } else {
                StreamService.streamStatus = "Could not start capture: $message"
            }
            updateStatus()
        }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        when (c) {
            REQUEST_STARTUP_PERMISSIONS -> {
                val microphoneGranted =
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                mic.isChecked = mic.isChecked && microphoneGranted
                StreamService.micOn = mic.isChecked
                val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                previewHint.text = if (notificationsGranted) {
                    "Microphone permission checked. Screen sharing consent is requested next."
                } else {
                    "Notifications are optional; screen capture and streaming can continue without them."
                }
                requestStartupScreenCapture()
            }
            REQUEST_MICROPHONE_TOGGLE -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    mic.isChecked = true
                } else {
                    Toast.makeText(this, "Microphone permission was not granted.", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_CAPTURE_PERMISSIONS -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    mic.isChecked = false
                    StreamService.micOn = false
                    Toast.makeText(
                        this,
                        "Microphone denied. Continuing without microphone audio.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                startCaptureWithAvailableConsent()
            }
        }
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
        persistSettings()
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
        if (req != REQUEST_SCREEN_CAPTURE) return
        if (pendingMode == "startup") {
            if (res == RESULT_OK && data != null) {
                pendingProjectionCode = res
                pendingProjectionData = data
                StreamService.streamStatus = "Permissions ready. Start live or recording when ready."
                updatePermissionHint("ready; Android may ask again for a new session")
            } else {
                StreamService.streamStatus =
                    "Screen sharing not allowed. Tap GO LIVE or RECORD to try again."
                updatePermissionHint("denied; required for capture")
            }
            updateStatus()
            return
        }
        if (res != RESULT_OK || data == null) {
            if (pendingMode == "record") {
                StreamService.recordStatus = "Recording cancelled"
            }
            StreamService.streamStatus = if (pendingMode == "preview") {
                "Preview permission cancelled"
            } else {
                "Screen capture permission cancelled"
            }
            updatePermissionHint("not allowed")
            if (pendingMode == "preview") preview.isChecked = false
            updateStatus()
            return
        }
        startCaptureService(pendingMode, res, data)
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
