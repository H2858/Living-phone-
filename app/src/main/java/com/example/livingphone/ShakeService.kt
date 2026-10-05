package com.example.livingphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class ShakeService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val COOLDOWN_TIME = 4000L
        
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null

    private var mediaPlayer: MediaPlayer? = null
    private var currentLang = "dz"
    
    private var lastPlayTime: Long = 0
    private var lastShakeTime: Long = 0

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> playVoice("power_connected_$currentLang")
                Intent.ACTION_POWER_DISCONNECTED -> playVoice("power_disconnected_$currentLang")
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = (level * 100 / scale.toFloat()).toInt()

                    if (batteryPct == 15) {
                        playVoice("hunger_$currentLang")
                    } else if (batteryPct == 100) {
                        playVoice("burp_$currentLang")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundServiceWithNotification()
                registerSensorsAndReceivers()
                _isServiceRunning.value = true
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "living_phone_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Living Phone Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Living Phone Active")
            .setContentText("Sensors and battery monitors are running.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(1, notification)
    }

    private fun registerSensorsAndReceivers() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(powerReceiver, filter)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val acceleration = sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
                if (acceleration > 4.5) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastShakeTime > 3000) {
                        lastShakeTime = currentTime
                        playVoice("shake_$currentLang")
                    }
                }

                if (z < -8.5 && abs(x) < 3.0 && abs(y) < 3.0) {
                    playVoice("suffocation_$currentLang")
                }
            }
            Sensor.TYPE_LIGHT -> {
                val lux = event.values[0]
                if (lux < 2.0) {
                    playVoice("darkness_$currentLang")
                } else if (lux > 50.0) {
                    playVoice("light_$currentLang")
                }
            }
        }
    }

    private fun abs(value: Float): Float = if (value < 0) -value else value

    private fun playVoice(baseName: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPlayTime < COOLDOWN_TIME) return
        lastPlayTime = currentTime

        try {
            val resName = if (baseName.startsWith("power_")) "${baseName}_1" else baseName
            val resId = resources.getIdentifier(resName, "raw", packageName)
            
            if (resId != 0) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer.create(applicationContext, resId).apply {
                    start()
                    setOnCompletionListener { release() }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        try {
            unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer?.release()
        _isServiceRunning.value = false
    }
}
