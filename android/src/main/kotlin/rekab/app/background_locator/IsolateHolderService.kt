package rekab.app.background_locator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import rekab.app.background_locator.provider.AndroidLocationProviderClient
import rekab.app.background_locator.provider.BLLocationProvider
import rekab.app.background_locator.provider.GoogleLocationProviderClient
import rekab.app.background_locator.provider.LocationClient
import rekab.app.background_locator.provider.LocationUpdateListener
import java.util.*

class IsolateHolderService : MethodChannel.MethodCallHandler, LocationUpdateListener, EventChannel.StreamHandler, Service() {
    companion object {
        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"

        @JvmStatic
        val ACTION_START = "START"

        @JvmStatic
        val ACTION_UPDATE_NOTIFICATION = "UPDATE_NOTIFICATION"

        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"

        @JvmStatic
        var backgroundEngine: FlutterEngine? = null

        @JvmStatic
        private val notificationId = 1
    }

    private var notificationChannelName = "Flutter Locator Plugin"
    private var notificationTitle = "Start Location Tracking"
    private var notificationMsg = "Track location in background"
    private var notificationBigMsg =
            "Background location is on to keep the app up-tp-date with your location. This is required for main features to work properly when the app is not running."
    private var notificationButtonMsg = "Button1"
    private var hasNotificationButtons = true
    private var notificationIconColor = 0
    private var icon = 0
    private var wakeLockTime = 60 * 60 * 1000L // 1 hour default wake lock time
    private var locatorClient: BLLocationProvider? = null
    internal lateinit var backgroundChannel: MethodChannel
    internal lateinit var loggerChannel: EventChannel

    internal lateinit var context: Context
    private var sink: EventChannel.EventSink? = null
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startLocatorService(this)
        startForeground(notificationId, getNotification())
        loggerChannel.setStreamHandler(this)
        log("isolateHandler", "onCreate")
    }

    private fun start() {
        log("isolateHandler", "start")
        if (PreferencesManager.isServiceRunning(this)) {
            log("isolateHandler", "isAlready Running")
            return
        }

        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(wakeLockTime)
                log("isolateHandler", "Wake lock acquired")
            }
        }

        // Starting Service as foreground with a notification prevent service from closing
        val notification = getNotification()
        log("isolateHandler", "Built notification")
        startForeground(notificationId, notification)
        log("isolateHandler", "Starting foreground")

        PreferencesManager.setServiceRunning(this, true)
    }

    private fun getNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Notification channel is available in Android O and up
            val channel = NotificationChannel(Keys.CHANNEL_ID, notificationChannelName,
                    NotificationManager.IMPORTANCE_LOW)

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
        }

        val intent = Intent(this, getMainActivityClass(this))
        intent.action = Keys.NOTIFICATION_ACTION

        val actionButton1 = Intent(this, getMainActivityClass(this))
        actionButton1.action = Keys.NOTIFICATION_ACTION_BUTTON_1

        val pendingIntent: PendingIntent = PendingIntent.getActivity(this,
                1, intent, PendingIntent.FLAG_UPDATE_CURRENT)

        val actionButtonIntent1: PendingIntent = PendingIntent.getActivity(this,
                1,
                actionButton1,
                PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, Keys.CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setContentText(notificationMsg)
                .setStyle(NotificationCompat.BigTextStyle()
                        .bigText(notificationBigMsg))
                .setSmallIcon(icon)
                .setColor(notificationIconColor)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true) // so when data is updated don't make sound and alert in android 8.0+
                .setOngoing(true)

        if (hasNotificationButtons) {
            notification.addAction(0, notificationButtonMsg, actionButtonIntent1)
        }

        return notification.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return super.onStartCommand(intent, flags, startId)
        }
        log("isolateHandler", "On start comm")
        when {
            ACTION_SHUTDOWN == intent.action -> {
                shutdownHolderService()
                log("isolateHandler", "On start comm - shutdown")
            }
            ACTION_START == intent.action -> {
                startHolderService(intent)
                log("isolateHandler", "On start comm - start")
            }
            ACTION_UPDATE_NOTIFICATION == intent.action -> {
                if (PreferencesManager.isServiceRunning(this)) {
                    updateNotification(intent)
                    log("isolateHandler", "On start comm - update")
                }
            }
        }

        return START_STICKY
    }

    private fun startHolderService(intent: Intent) {
        notificationChannelName = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME).toString()
        notificationTitle = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        notificationMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        notificationBigMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        notificationButtonMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BUTTON_MSG).toString()
        hasNotificationButtons = intent.getBooleanExtra(Keys.SETTINGS_ANDROID_HAS_NOTIFICATION_BUTTONS, false)
        val iconNameDefault = "ic_stat_name"
        var iconName = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON)
        if (iconName == null || iconName.isEmpty()) {
            iconName = iconNameDefault
        }
        icon = resources.getIdentifier(iconName, "drawable", packageName)
        notificationIconColor = intent.getLongExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR, 0).toInt()
        wakeLockTime = intent.getIntExtra(Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME, 60) * 60 * 1000L

        locatorClient = getLocationClient(context)
        locatorClient?.requestLocationUpdates(getLocationRequest(intent))

        start()
    }

    private fun shutdownHolderService() {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                if (isHeld) {
                    release()
                }
            }
        }

        locatorClient?.removeLocationUpdates()
        PreferencesManager.setServiceRunning(this, false)
        stopForeground(true)
        stopSelf()
    }

    private fun updateNotification(intent: Intent) {
        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE)) {
            notificationTitle = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG)) {
            notificationMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG)) {
            notificationBigMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BUTTON_MSG)) {
            notificationButtonMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BUTTON_MSG).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_HAS_NOTIFICATION_BUTTONS)) {
            hasNotificationButtons = intent.getBooleanExtra(Keys.SETTINGS_ANDROID_HAS_NOTIFICATION_BUTTONS, false)
        }

        val notification = getNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun getMainActivityClass(context: Context): Class<*>? {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent?.component?.className ?: return null

        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            null
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            Keys.METHOD_SERVICE_INITIALIZED -> {
                PreferencesManager.setServiceRunning(this, true)
            }
            else -> result.notImplemented()
        }

        result.success(null)
    }

    override fun onDestroy() {
        PreferencesManager.setServiceRunning(this, false)
        super.onDestroy()
    }


    private fun getLocationClient(context: Context): BLLocationProvider {
        return when (PreferencesManager.getLocationClient(context)) {
            LocationClient.Google -> {
                log("isolateHandler", "Location client - Google")
                GoogleLocationProviderClient(context, this)
            }
            LocationClient.Android -> {
                log("isolateHandler", "Location client - Android")
                AndroidLocationProviderClient(context, this)
            }
        }
    }

    override fun onLocationUpdated(location: HashMap<Any, Any>?) {
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(context, null)

        //https://github.com/flutter/plugins/pull/1641
        //https://github.com/flutter/flutter/issues/36059
        //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
        // new version of flutter can not invoke method from background thread
        if (location != null) {
            val callback = BackgroundLocatorPlugin.getCallbackHandle(context, Keys.CALLBACK_HANDLE_KEY) as Long

            val result: HashMap<Any, Any> =
                    hashMapOf(Keys.ARG_CALLBACK to callback,
                            Keys.ARG_LOCATION to location)
            log("isolateHandler", "New location")
            sendLocationEvent(result)
        }
    }

    private fun sendLocationEvent(result: HashMap<Any, Any>) {
        //https://github.com/flutter/plugins/pull/1641
        //https://github.com/flutter/flutter/issues/36059
        //https://github.com/flutter/plugins/pull/1641/commits/4358fbba3327f1fa75bc40df503ca5341fdbb77d
        // new version of flutter can not invoke method from background thread

        if (backgroundEngine != null) {
            val backgroundChannel =
                    MethodChannel(backgroundEngine?.dartExecutor?.binaryMessenger, Keys.BACKGROUND_CHANNEL_ID)
            log("isolateHandler", "Send location")
            Handler(context.mainLooper)
                    .post {
                        Log.d("plugin", "sendLocationEvent $result")
                        backgroundChannel.invokeMethod(Keys.BCM_SEND_LOCATION, result)
                    }
        }
    }

    private fun log(key: String, message: String) {
        Handler(context.mainLooper)
                .post {
                    sink?.success(mapOf("key" to key, "value" to message))
                }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

}