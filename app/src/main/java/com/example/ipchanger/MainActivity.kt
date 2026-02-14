package com.example.ipchanger

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    
    private var isRunning = false
    private var waitingForReturn = false
    private var currentIpA: String = "Unavailable"
    
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStart)

        btnStart.setOnClickListener {
            if (isRunning) {
                stopLoop()
            } else {
                if (isAccessibilityServiceEnabled()) {
                    startCycle()
                } else {
                    Toast.makeText(this, "Please enable Accessibility Service for IP Changer", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isRunning && waitingForReturn) {
            waitingForReturn = false
            log("Returned from Settings. Checking network...")
            checkIpAndProceed()
        }
    }

    private fun startCycle() {
        isRunning = true
        btnStart.text = "Stop"
        tvStatus.text = "Status: Running"
        tvStatus.setTextColor(Color.BLACK)
        
        job = CoroutineScope(Dispatchers.Main).launch {
            // 1. Save current IP (A)
            currentIpA = getMobileIPAddress()
            log("Current IP (A): $currentIpA")

            // 2. Signal Service and Open Settings
            log("Opening Settings for Auto-Toggle...")
            val prefs = getSharedPreferences("com.example.ipchanger.PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("DO_TOGGLE", true).apply()

            waitingForReturn = true
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            startActivity(intent)
        }
    }

    private fun checkIpAndProceed() {
        job = CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                tvStatus.text = "Status: Waiting for Network..."
            }
            
            var ipB = "Unavailable"
            val maxRetries = 20 // Wait up to 20 seconds (5s toggle + reconnect time)
            for (i in 0 until maxRetries) {
                delay(1000)
                ipB = getMobileIPAddress()
                if (ipB != "Unavailable" && ipB != "0.0.0.0") {
                    break
                }
            }

            log("New IP (B): $ipB")

            withContext(Dispatchers.Main) {
                if (ipB != "Unavailable" && ipB != currentIpA) {
                    // Success
                    tvStatus.text = "Status: Success!"
                    tvStatus.setTextColor(Color.GREEN)
                    log("SUCCESS: IP Changed from $currentIpA to $ipB")
                    stopLoop() 
                } else {
                    // Fail or Same IP
                    tvStatus.text = "Status: Retrying..."
                    tvStatus.setTextColor(Color.YELLOW)
                    log("IP Unchanged. Retrying in 2s...")
                    delay(2000)
                    startCycle() // Repeat
                }
            }
        }
    }

    private fun stopLoop() {
        isRunning = false
        waitingForReturn = false
        job?.cancel()
        btnStart.text = "Start IP Changer"
        // Clear prefs just in case
        val prefs = getSharedPreferences("com.example.ipchanger.PREFS", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("DO_TOGGLE", false).apply()
        
        if (tvStatus.text != "Status: Success!") {
            tvStatus.text = "Status: Stopped"
            tvStatus.setTextColor(Color.BLACK)
        }
        log("Loop stopped.")
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = try {
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            0
        }

        if (accessibilityEnabled == 1) {
            val service = "$packageName/${AirplaneModeAccessibilityService::class.java.name}"
            val settingValue = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(settingValue)
            while (splitter.hasNext()) {
                val accessibilityService = splitter.next()
                if (accessibilityService.equals(service, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun getMobileIPAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.contains("wlan")) continue 

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                         val sAddr = addr.hostAddress
                         val isIPv4 = sAddr.indexOf(':') < 0
                         if (isIPv4) return sAddr
                    }
                }
            }
        } catch (ex: Exception) {
            // ex.printStackTrace()
        }
        return "Unavailable"
    }

    private suspend fun log(message: String) {
        withContext(Dispatchers.Main) {
            tvLog.append("\n$message")
        }
    }
}
