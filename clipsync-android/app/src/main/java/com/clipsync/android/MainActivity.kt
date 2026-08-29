package com.clipsync.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var pairingCodeInput: EditText
    private lateinit var deviceNameInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val logListener: (String) -> Unit = { message ->
        runOnUiThread {
            logText.append("$message\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private val statusListener: (String) -> Unit = { status ->
        runOnUiThread { statusText.text = status }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairingCodeInput = findViewById(R.id.pairingCodeInput)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        startButton.setOnClickListener {
            val code = pairingCodeInput.text.toString()
            if (code.isBlank()) {
                pairingCodeInput.error = "Enter a pairing code"
                return@setOnClickListener
            }
            val name = deviceNameInput.text.toString().ifBlank { "this-phone" }

            val intent = Intent(this, ClipSyncService::class.java).apply {
                putExtra(ClipSyncService.EXTRA_PAIRING_CODE, code)
                putExtra(ClipSyncService.EXTRA_DEVICE_NAME, name)
            }
            ContextCompat.startForegroundService(this, intent)

            startButton.isEnabled = false
            stopButton.isEnabled = true
            pairingCodeInput.isEnabled = false
            deviceNameInput.isEnabled = false
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, ClipSyncService::class.java))
            startButton.isEnabled = true
            stopButton.isEnabled = false
            pairingCodeInput.isEnabled = true
            deviceNameInput.isEnabled = true
        }
    }

    override fun onStart() {
        super.onStart()
        ClipSyncLog.addListener(logListener)
        ClipSyncStatus.addListener(statusListener)
    }

    override fun onStop() {
        super.onStop()
        ClipSyncLog.removeListener(logListener)
        ClipSyncStatus.removeListener(statusListener)
    }
}
