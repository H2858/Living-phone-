// app/src/main/java/com/example/livingphone/ShakeService.kt
package com.example.livingphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class ShakeService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var mediaPlayer: MediaPlayer? = null
    private var lastShakeTimestamp: Long = 0L

    companion object {
        private const val TAG = "ShakeService"
        const val CHANNEL_ID = "living_phone"
        private const val NOTIFICATION_ID = 1001
        private const val SHAKE_THRESHOLD_G = 2.5f
        private const val COOLDOWN_MS = 3000L

        const val ACTION_START = "com.example.livingphone.ACTION_START"
        const val ACTION_STOP = "com.example.livingphone.ACTION_STOP"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true

        createNotificationChannel()
        val notification = buildPersistentNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        accelerometer?.let { sensor ->
            sensorManager?.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_UI
            )
            Log.d(TAG, "Accelerometer listener registered successfully.")
        } ?: run {
            Log.w(TAG, "Device does not have an accelerometer.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH

        val gForce = sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

        if (gForce > SHAKE_THRESHOLD_G) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTimestamp >= COOLDOWN_MS) {
                lastShakeTimestamp = now
                Log.d(TAG, "Shake detected! G-force: $gForce. Playing audio...")
                playRandomSound()
                triggerHapticFeedback()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun playRandomSound() {
        releaseMediaPlayer()

        val soundResources = listOf(
            R.raw.shake_1,
            R.raw.shake_2,
            R.raw.shake_3
        )
        val selectedSound = soundResources.random()

        try {
            mediaPlayer = MediaPlayer.create(applicationContext, selectedSound)?.apply {
                setOnCompletionListener { player ->
                    try {
                        player.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing completed MediaPlayer", e)
                    }
                    if (mediaPlayer === player) {
                        mediaPlayer = null
                    }
                }
                setOnErrorListener { player, _, _ ->
                    try {
                        player.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing failed MediaPlayer", e)
                    }
                    if (mediaPlayer === player) {
                        mediaPlayer = null
                    }
                    true
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize or play sound with MediaPlayer", e)
            releaseMediaPlayer()
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaPlayer", e)
            }
            try {
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaPlayer", e)
            }
        }
        mediaPlayer = null
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(150L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(150L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Living Phone Active Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Living Phone listening for shakes in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildPersistentNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Living Phone is Awake")
            .setContentText("Listening to accelerometer movements...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        sensorManager = null
        accelerometer = null

        releaseMediaPlayer()
        _isServiceRunning.value = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.d(TAG, "ShakeService destroyed and cleaned up.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
