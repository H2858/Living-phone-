package com.example.livingphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlin.concurrent.thread

class ShakeService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lightSensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var mediaPlayer: MediaPlayer? = null
    private var lastShakeTimestamp: Long = 0L
    private var lastLightStateDark: Boolean = false
    private var lastProximityTriggerTime: Long = 0L
    private var powerReceiver: BroadcastReceiver? = null
    private var audioRecord: AudioRecord? = null
    private var isListeningAudio = false

    companion object {
        private const val TAG = "ShakeService"
        const val CHANNEL_ID = "living_phone"
        private const val NOTIFICATION_ID = 1001
        private const val SHAKE_THRESHOLD_G = 2.2f
        private const val COOLDOWN_MS = 3000L
        private const val LIGHT_COOLDOWN_MS = 10000L
        private const val PROXIMITY_COOLDOWN_MS = 8000L
        private const val SCREAM_COOLDOWN_MS = 8000L
        private var lastLightTransitionTimestamp: Long = 0L
        private var lastScreamTimestamp: Long = 0L

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
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        registerPowerReceiver()
        startAudioListener()
    }

    private fun startAudioListener() {
        isListeningAudio = true
        thread(start = true) {
            try {
                val sampleRate = 8000
                val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                val buffer = ByteArray(bufferSize)
                audioRecord?.startRecording()

                while (isListeningAudio) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0L
                        for (i in 0 until read step 2) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                            sum += (sample * sample).toLong()
                        }
                        val rms = sqrt((sum / (read / 2)).toDouble())
                        
                        if (rms > 25000.0) {
                            val now = System.currentTimeMillis()
                            if (now - lastScreamTimestamp >= SCREAM_COOLDOWN_MS) {
                                lastScreamTimestamp = now
                                val screamSounds = listOf(R.raw.scream, R.raw.scream_two)
                                playSound(screamSounds.random())
                                triggerHapticFeedback()
                            }
                        }
                    }
                    Thread.sleep(200)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio listener error", e)
            }
        }
    }

    private fun registerPowerReceiver() {
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        playSound(R.raw.power_connected)
                        triggerHapticFeedback()
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        val disconnectSounds = listOf(R.raw.power_disconnected, R.raw.power_disconnected_two)
                        playSound(disconnectSounds.random())
                        triggerHapticFeedback()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
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
                        playRandomShakeSound()
                        triggerHapticFeedback()
                    }
                }
            }
            Sensor.TYPE_LIGHT -> {
                val lux = event.values[0]
                val isDark = lux < 0.5f // عتبة أدق للظلام الدامس لمنع التداخل
                val now = System.currentTimeMillis()

                if (isDark != lastLightStateDark && (now - lastLightTransitionTimestamp > LIGHT_COOLDOWN_MS)) {
                    lastLightStateDark = isDark
                    lastLightTransitionTimestamp = now

                    if (isDark) {
                        playSound(R.raw.darkness)
                    } else {
                        playSound(R.raw.light)
                    }
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values[0]
                val maxRange = proximitySensor?.maximumRange ?: 5.0f
                
                // يتم تفعيل القرب فقط إذا كان هناك إضاءة محيطة كافية (لضمان أنه ليس في جيب مظلم)
                val isPhoneInPocket = lastLightStateDark
                
                if (distance < maxRange && !isPhoneInPocket) {
                    val now = System.currentTimeMillis()
                    if (now - lastProximityTriggerTime >= PROXIMITY_COOLDOWN_MS) {
                        lastProximityTriggerTime = now
                        val proxSounds = listOf(R.raw.proximity, R.raw.proximity_two)
                        playSound(proxSounds.random())
                        triggerHapticFeedback()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun playRandomShakeSound() {
        val soundResources = listOf(
            R.raw.s_one,
            R.raw.s_two,
            R.raw.s_three,
            R.raw.s_four,
            R.raw.s_five
        )
        playSound(soundResources.random())
    }

    private fun playSound(resourceId: Int) {
        releaseMediaPlayer()
        try {
            mediaPlayer = MediaPlayer.create(applicationContext, resourceId)?.apply {
                setOnCompletionListener { player ->
                    player.release()
                    if (mediaPlayer === player) mediaPlayer = null
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    if (mediaPlayer === player) mediaPlayer = null
                    true
                }
                start()
            }
        } catch (e: Exception) {
            releaseMediaPlayer()
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (e: Exception) {}
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
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Living Phone Active Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Living Phone alive and responsive"
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
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Living Phone is Alive")
            .setContentText("Living phone senses are fully separated and active...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListeningAudio = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        sensorManager?.unregisterListener(this)
        sensorManager = null
        accelerometer = null
        lightSensor = null
        proximitySensor = null

        powerReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {}
        }
        powerReceiver = null

        releaseMediaPlayer()
        _isServiceRunning.value = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
