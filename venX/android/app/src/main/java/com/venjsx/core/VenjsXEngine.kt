package com.venjsx.core

import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.venjsx.notifications.VenjsXLocalNotificationReceiver
import com.venjsx.notifications.VenjsXPushTokenStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class VenjsXEngine(
  private val context: Context,
  private val rootLayout: ViewGroup,
  private val bridge: WebView
) {
  companion object {
    private const val ANIM_SIGNATURE_TAG_KEY = 0x7F0B1001
    private const val DOUBLE_TAP_DETECTOR_TAG_KEY = 0x7F0B1002
    private const val SHAKE_SENSOR_LISTENER_TAG_KEY = 0x7F0B1003
    private const val LOCATION_PERMISSION_REQUEST_CODE = 0x5127
    private const val NOTIFICATIONS_PERMISSION_REQUEST_CODE = 0x5128
    private const val USE_RECONCILIATION = false

    @Volatile
    var activeEngine: VenjsXEngine? = null
  }

  init {
    activeEngine = this
  }

  private data class VNode(
    val tag: String,
    val props: JSONObject,
    val style: JSONObject?,
    val children: List<VNode>
  )

  private data class ResolvedMargins(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val autoHorizontal: Boolean,
    val autoVertical: Boolean
  )

  private var mountedScrollView: ScrollView? = null
  private var mountedRootView: View? = null
  private var previousTree: VNode? = null

  private data class PendingLocationRequest(
    val eventId: Int,
    val params: JSONObject
  )

  private var pendingLocationRequest: PendingLocationRequest? = null
  private var pendingNotificationsPermissionEventId: Int? = null

  private val shakeListenerEventIds = mutableSetOf<Int>()
  private var shakeSensorInstalled = false
  private var lastShakeTimestampMs = 0L

  private val notificationReceiveListenerEventIds = mutableSetOf<Int>()
  private val notificationTapListenerEventIds = mutableSetOf<Int>()
  private var pendingNotificationTapPayload: JSONObject? = null
  private val pendingNotificationReceivePayloads = mutableListOf<JSONObject>()
  private val defaultTypeface: Typeface? by lazy { loadFontByNames(listOf("myfont", "ibm_plex_sans")) }
  private val fontAwesomeTypeface: Typeface? by lazy {
    loadFontByNames(
      listOf(
        "font_awesome_6_free_solid_900",
        "font_awesome",
        "fontawesome"
      )
    )
  }

  private val imageCache: LruCache<String, Bitmap> = run {
    val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    val cacheKb = max(1024, maxKb / 8)
    object : LruCache<String, Bitmap>(cacheKb) {
      override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
  }

  @JavascriptInterface
  fun processUINode(json: String) {
    try {
      val parsed = parseNode(JSONObject(json))
      (context as Activity).runOnUiThread {
        try {
          if (!USE_RECONCILIATION) {
            mountFreshTree(parsed)
            return@runOnUiThread
          }

          val oldTree = previousTree
          val oldRoot = mountedRootView
          val scroll = mountedScrollView

          if (oldTree == null || oldRoot == null || scroll == null) {
            mountFreshTree(parsed)
            return@runOnUiThread
          }

          val reconciled = reconcileNode(
            parent = scroll,
            currentView = oldRoot,
            oldNode = oldTree,
            newNode = parsed,
            indexInParent = 0
          )

          if (reconciled !== oldRoot) {
            if (scroll.childCount > 0) {
              scroll.removeViewAt(0)
            }
            scroll.addView(reconciled, 0)
          }

          mountedRootView = reconciled
          previousTree = parsed
        } catch (e: Exception) {
          e.printStackTrace()
          mountFreshTree(parsed)
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  @JavascriptInterface
  fun deviceRequest(json: String) {
    val payload = try {
      JSONObject(json)
    } catch (_: Exception) {
      return
    }

    val action = payload.optString("action", "").trim()
    val eventId = payload.optInt("eventId", -1)
    val params = payload.optJSONObject("params") ?: JSONObject()
    if (action.isBlank()) return

    if (action == "log") {
      handleNativeLog(params)
      return
    }

    if (eventId <= 0) return

    when (action) {
      "createFile" -> handleCreateFile(eventId, params)
      "listFiles" -> handleListFiles(eventId)
      "readFile" -> handleReadFile(eventId, params)
      "writeFile" -> handleWriteFile(eventId, params)
      "getLocation" -> handleGetLocation(eventId, params)
      "startShake" -> handleStartShake(eventId)
      "stopShake" -> handleStopShake(eventId)
      "requestNotificationPermission" -> handleRequestNotificationsPermission(eventId)
      "scheduleLocalNotification" -> handleScheduleLocalNotification(eventId, params)
      "cancelLocalNotification" -> handleCancelLocalNotification(eventId, params)
      "startNotificationListener" -> handleStartNotificationListener(eventId, params)
      "stopNotificationListener" -> handleStopNotificationListener(eventId, params)
      "getPushToken" -> handleGetPushToken(eventId)
      else -> emitDeviceError(eventId, "E_ACTION", "Unknown action: $action")
    }
  }

  private fun handleNativeLog(params: JSONObject) {
    val level = params.optString("level", "log").trim().lowercase()
    val message = params.optString("message", "")
    when (level) {
      "error" -> Log.e("venjsX", message)
      "warn", "warning" -> Log.w("venjsX", message)
      "info" -> Log.i("venjsX", message)
      "debug" -> Log.d("venjsX", message)
      else -> Log.d("venjsX", message)
    }
  }

  fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
      val pending = pendingLocationRequest ?: return
      pendingLocationRequest = null

      val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
      if (!granted) {
        emitDeviceError(pending.eventId, "E_PERMISSION", "Location permission denied.")
        return
      }

      requestSingleLocationUpdate(pending.eventId, pending.params)
      return
    }

    if (requestCode == NOTIFICATIONS_PERMISSION_REQUEST_CODE) {
      val eventId = pendingNotificationsPermissionEventId ?: return
      pendingNotificationsPermissionEventId = null

      val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
      emitDeviceOk(eventId, JSONObject().apply { put("granted", granted) })
    }
  }

  private fun deviceRootDir(): File {
    val base = (context.getExternalFilesDir(null) ?: context.filesDir)
    val dir = File(base, "venjsX")
    if (!dir.exists()) {
      try {
        dir.mkdirs()
      } catch (_: Exception) {
      }
    }
    return dir
  }

  private fun sanitizeFileName(raw: String): String? {
    val name = raw.trim()
    if (name.isBlank()) return null
    if (name.contains("..")) return null
    if (name.contains("/") || name.contains("\\") || name.contains("\u0000")) return null
    return name
  }

  private fun emitDeviceOk(eventId: Int, body: JSONObject) {
    try {
      body.put("ok", true)
      body.put("platform", "android")
      body.put("timestamp", System.currentTimeMillis())
      emitEvent(eventId, body)
    } catch (_: Exception) {
    }
  }

  private fun emitDeviceError(eventId: Int, code: String, message: String) {
    try {
      val body = JSONObject().apply {
        put("ok", false)
        put("code", code)
        put("error", message)
        put("platform", "android")
        put("timestamp", System.currentTimeMillis())
      }
      emitEvent(eventId, body)
    } catch (_: Exception) {
    }
  }

  private fun handleCreateFile(eventId: Int, params: JSONObject) {
    val name = sanitizeFileName(params.optString("name", ""))
    if (name == null) {
      emitDeviceError(eventId, "E_NAME", "Invalid file name.")
      return
    }

    val content = params.optString("write", "")
    val overwrite = params.optBoolean("overwrite", true)

    thread(start = true) {
      try {
        val file = File(deviceRootDir(), name)
        if (file.exists() && !overwrite) {
          emitDeviceError(eventId, "E_EXISTS", "File already exists.")
          return@thread
        }
        file.writeText(content, Charset.forName("UTF-8"))
        emitDeviceOk(eventId, JSONObject().apply {
          put("name", name)
          put("path", file.absolutePath)
          put("size", file.length())
        })
      } catch (e: Exception) {
        emitDeviceError(eventId, "E_IO", e.message ?: "Failed to create file.")
      }
    }
  }

  private fun handleListFiles(eventId: Int) {
    thread(start = true) {
      try {
        val dir = deviceRootDir()
        val list = JSONArray()
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { file ->
          if (!file.isFile) return@forEach
          list.put(JSONObject().apply {
            put("name", file.name)
            put("path", file.absolutePath)
            put("size", file.length())
            put("lastModified", file.lastModified())
          })
        }
        emitDeviceOk(eventId, JSONObject().apply {
          put("files", list)
          put("dir", dir.absolutePath)
        })
      } catch (e: Exception) {
        emitDeviceError(eventId, "E_IO", e.message ?: "Failed to list files.")
      }
    }
  }

  private fun resolveTargetFile(params: JSONObject): File? {
    val dir = deviceRootDir()
    val name = sanitizeFileName(params.optString("name", ""))
    if (name != null) return File(dir, name)

    val rawPath = params.optString("path", "").trim()
    if (rawPath.isBlank()) return null
    val file = File(rawPath)
    return try {
      val canonicalDir = dir.canonicalFile
      val canonicalFile = file.canonicalFile
      if (!canonicalFile.path.startsWith(canonicalDir.path)) null else canonicalFile
    } catch (_: Exception) {
      null
    }
  }

  private fun handleReadFile(eventId: Int, params: JSONObject) {
    val file = resolveTargetFile(params)
    if (file == null) {
      emitDeviceError(eventId, "E_PATH", "Provide a valid { name } or { path } within the app files directory.")
      return
    }

    thread(start = true) {
      try {
        if (!file.exists() || !file.isFile) {
          emitDeviceError(eventId, "E_NOT_FOUND", "File not found.")
          return@thread
        }
        val text = file.readText(Charset.forName("UTF-8"))
        emitDeviceOk(eventId, JSONObject().apply {
          put("name", file.name)
          put("path", file.absolutePath)
          put("size", file.length())
          put("read", text)
        })
      } catch (e: Exception) {
        emitDeviceError(eventId, "E_IO", e.message ?: "Failed to read file.")
      }
    }
  }

  private fun handleWriteFile(eventId: Int, params: JSONObject) {
    val file = resolveTargetFile(params)
    if (file == null) {
      emitDeviceError(eventId, "E_PATH", "Provide a valid { name } or { path } within the app files directory.")
      return
    }

    val content = params.optString("write", "")
    val append = params.optBoolean("append", false)

    thread(start = true) {
      try {
        if (!file.exists()) {
          file.parentFile?.mkdirs()
          file.createNewFile()
        }
        if (append) {
          file.appendText(content, Charset.forName("UTF-8"))
        } else {
          file.writeText(content, Charset.forName("UTF-8"))
        }
        emitDeviceOk(eventId, JSONObject().apply {
          put("name", file.name)
          put("path", file.absolutePath)
          put("size", file.length())
        })
      } catch (e: Exception) {
        emitDeviceError(eventId, "E_IO", e.message ?: "Failed to write file.")
      }
    }
  }

  private fun handleGetLocation(eventId: Int, params: JSONObject) {
    val activity = context as? Activity ?: run {
      emitDeviceError(eventId, "E_CONTEXT", "No Activity context available for location.")
      return
    }

    val permission = Manifest.permission.ACCESS_FINE_LOCATION
    val granted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
      pendingLocationRequest = PendingLocationRequest(eventId, params)
      ActivityCompat.requestPermissions(activity, arrayOf(permission), LOCATION_PERMISSION_REQUEST_CODE)
      return
    }

    requestSingleLocationUpdate(eventId, params)
  }

  private fun requestSingleLocationUpdate(eventId: Int, params: JSONObject) {
    val activity = context as? Activity ?: run {
      emitDeviceError(eventId, "E_CONTEXT", "No Activity context available for location.")
      return
    }

    val enableHighAccuracy = params.optBoolean("enableHighAccuracy", false)
    val timeoutMs = params.optLong("timeoutMs", 15000L).coerceAtLeast(1000L)

    val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
      emitDeviceError(eventId, "E_LOCATION", "Location manager unavailable.")
      return
    }

    val criteria = Criteria().apply {
      accuracy = if (enableHighAccuracy) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
      isCostAllowed = true
    }

    val provider = locationManager.getBestProvider(criteria, true)
      ?: if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else null
      ?: if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else null

    if (provider == null) {
      emitDeviceError(eventId, "E_LOCATION", "No location provider enabled.")
      return
    }

    val handler = Handler(Looper.getMainLooper())
    var done = false

    val listener = object : LocationListener {
      override fun onLocationChanged(location: Location) {
        if (done) return
        done = true
        try {
          locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        emitDeviceOk(eventId, JSONObject().apply {
          put("provider", provider)
          put("latitude", location.latitude)
          put("longitude", location.longitude)
          put("accuracy", location.accuracy.toDouble())
          put("altitude", location.altitude)
          put("speed", location.speed.toDouble())
          put("bearing", location.bearing.toDouble())
        })
      }

      override fun onProviderDisabled(provider: String) {}
      override fun onProviderEnabled(provider: String) {}
      override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    handler.postDelayed({
      if (done) return@postDelayed
      done = true
      try {
        locationManager.removeUpdates(listener)
      } catch (_: Exception) {
      }
      emitDeviceError(eventId, "E_TIMEOUT", "Timed out getting location.")
    }, timeoutMs)

    try {
      locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    } catch (e: SecurityException) {
      emitDeviceError(eventId, "E_PERMISSION", "Location permission not granted.")
    } catch (e: Exception) {
      emitDeviceError(eventId, "E_LOCATION", e.message ?: "Failed to request location.")
    }
  }

  private fun handleStartShake(eventId: Int) {
    shakeListenerEventIds.add(eventId)
    if (!shakeSensorInstalled) {
      installShakeSensor()
    }
  }

  private fun handleStopShake(eventId: Int) {
    shakeListenerEventIds.remove(eventId)
    if (shakeListenerEventIds.isEmpty()) {
      uninstallShakeSensor()
    }
  }

  private fun installShakeSensor() {
    if (shakeSensorInstalled) return
    val activity = context as? Activity ?: return
    val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
    val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return

    lastShakeTimestampMs = 0L
    val listener = object : android.hardware.SensorEventListener {
      override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

      override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        val ev = event ?: return
        if (ev.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
        if (shakeListenerEventIds.isEmpty()) return

        val x = ev.values.getOrNull(0) ?: return
        val y = ev.values.getOrNull(1) ?: return
        val z = ev.values.getOrNull(2) ?: return

        val gX = x / android.hardware.SensorManager.GRAVITY_EARTH
        val gY = y / android.hardware.SensorManager.GRAVITY_EARTH
        val gZ = z / android.hardware.SensorManager.GRAVITY_EARTH

        val gForce = kotlin.math.sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()
        val now = System.currentTimeMillis()
        val minIntervalMs = 600L
        val threshold = 2.7f
        if (gForce > threshold && now - lastShakeTimestampMs > minIntervalMs) {
          lastShakeTimestampMs = now
          val payload = JSONObject().apply {
            put("type", "shake")
            put("platform", "android")
            put("gForce", gForce.toDouble())
            put("timestamp", now)
          }
          val targets = shakeListenerEventIds.toList()
          targets.forEach { id ->
            emitEvent(id, payload)
          }
        }
      }
    }

    shakeSensorInstalled = true
    sensorManager.registerListener(listener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_UI)

    bridge.setTag(SHAKE_SENSOR_LISTENER_TAG_KEY, listener)
  }

  private fun uninstallShakeSensor() {
    if (!shakeSensorInstalled) return
    val activity = context as? Activity ?: return
    val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
    val listener = bridge.getTag(SHAKE_SENSOR_LISTENER_TAG_KEY) as? android.hardware.SensorEventListener
    if (listener != null) {
      try {
        sensorManager.unregisterListener(listener)
      } catch (_: Exception) {
      }
    }
    bridge.setTag(SHAKE_SENSOR_LISTENER_TAG_KEY, null)
    shakeSensorInstalled = false
  }

  fun handleNotificationTapFromIntent(intent: Intent?) {
    val raw = intent?.getStringExtra("venjsx_notification_tap") ?: return
    val payload = try {
      JSONObject(raw)
    } catch (_: Exception) {
      JSONObject().apply { put("raw", raw) }
    }

    try {
      payload.put("type", "notificationTap")
      payload.put("platform", "android")
      payload.put("timestamp", System.currentTimeMillis())
    } catch (_: Exception) {
    }

    if (notificationTapListenerEventIds.isEmpty()) {
      pendingNotificationTapPayload = payload
      return
    }

    val targets = notificationTapListenerEventIds.toList()
    targets.forEach { id ->
      emitEvent(id, payload)
    }
  }

  private fun handleRequestNotificationsPermission(eventId: Int) {
    val activity = context as? Activity ?: run {
      emitDeviceError(eventId, "E_CONTEXT", "No Activity context available for notifications permission.")
      return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      emitDeviceOk(eventId, JSONObject().apply { put("granted", true) })
      return
    }

    val permission = Manifest.permission.POST_NOTIFICATIONS
    val granted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    if (granted) {
      emitDeviceOk(eventId, JSONObject().apply { put("granted", true) })
      return
    }

    pendingNotificationsPermissionEventId = eventId
    ActivityCompat.requestPermissions(activity, arrayOf(permission), NOTIFICATIONS_PERMISSION_REQUEST_CODE)
  }

  private fun handleStartNotificationListener(eventId: Int, params: JSONObject) {
    val kind = params.optString("kind", "").trim().lowercase()
    when (kind) {
      "tap" -> {
        notificationTapListenerEventIds.add(eventId)
        pendingNotificationTapPayload?.let { payload ->
          pendingNotificationTapPayload = null
          emitEvent(eventId, payload)
        }
      }
      "receive" -> {
        notificationReceiveListenerEventIds.add(eventId)
        if (pendingNotificationReceivePayloads.isNotEmpty()) {
          val queued = pendingNotificationReceivePayloads.toList()
          pendingNotificationReceivePayloads.clear()
          queued.forEach { payload -> emitEvent(eventId, payload) }
        }
      }
      else -> {}
    }
  }

  private fun handleStopNotificationListener(eventId: Int, params: JSONObject) {
    val kind = params.optString("kind", "").trim().lowercase()
    when (kind) {
      "tap" -> notificationTapListenerEventIds.remove(eventId)
      "receive" -> notificationReceiveListenerEventIds.remove(eventId)
      else -> {
        notificationTapListenerEventIds.remove(eventId)
        notificationReceiveListenerEventIds.remove(eventId)
      }
    }
  }

  private fun handleGetPushToken(eventId: Int) {
    val existing = VenjsXPushTokenStore.getToken()
    if (!existing.isNullOrBlank()) {
      emitDeviceOk(eventId, JSONObject().apply { put("token", existing) })
      return
    }

    try {
      FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (!task.isSuccessful) {
          emitDeviceError(eventId, "E_PUSH", task.exception?.message ?: "Failed to fetch FCM token.")
          return@addOnCompleteListener
        }
        val token = task.result ?: ""
        VenjsXPushTokenStore.setToken(token)
        emitDeviceOk(eventId, JSONObject().apply { put("token", token) })
      }
    } catch (e: Exception) {
      emitDeviceError(eventId, "E_PUSH", e.message ?: "Failed to fetch FCM token.")
    }
  }

  fun emitPushToken(token: String) {
    VenjsXPushTokenStore.setToken(token)
  }

  fun emitNotificationReceived(payload: JSONObject) {
    try {
      payload.put("type", "notificationReceive")
      payload.put("platform", "android")
      payload.put("timestamp", System.currentTimeMillis())
    } catch (_: Exception) {
    }

    if (notificationReceiveListenerEventIds.isEmpty()) {
      pendingNotificationReceivePayloads.add(payload)
      return
    }

    val targets = notificationReceiveListenerEventIds.toList()
    targets.forEach { id ->
      emitEvent(id, payload)
    }
  }

  private fun handleScheduleLocalNotification(eventId: Int, params: JSONObject) {
    val idRaw = params.opt("id")
    val idString = if (idRaw is String) idRaw.trim() else ""
    val notificationId = when {
      idRaw is Int && idRaw != 0 -> idRaw
      idString.isNotBlank() -> idString.hashCode()
      else -> (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    }

    val title = params.optString("title", "Notification")
    val body = params.optString("body", "")
    val delayMs = params.optLong("delayMs", 0L).coerceAtLeast(0L)
    val atMs = params.optLong("atMs", 0L)
    val triggerAtMs = if (atMs > 0) atMs else System.currentTimeMillis() + delayMs

    val tapPayload = JSONObject().apply {
      put("id", if (idString.isNotBlank()) idString else notificationId)
      put("title", title)
      put("body", body)
      if (params.has("data")) {
        put("data", params.opt("data"))
      }
    }

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    if (alarmManager == null) {
      emitDeviceError(eventId, "E_NOTIFICATIONS", "Alarm manager unavailable.")
      return
    }

    val intent = Intent(context, VenjsXLocalNotificationReceiver::class.java).apply {
      putExtra("notificationId", notificationId)
      putExtra("title", title)
      putExtra("body", body)
      putExtra("payload", tapPayload.toString())
    }

    val pending = PendingIntent.getBroadcast(
      context,
      notificationId,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    )

    fun emitOk(exact: Boolean) {
      emitDeviceOk(eventId, JSONObject().apply {
        put("id", if (idString.isNotBlank()) idString else notificationId)
        put("notificationId", notificationId)
        put("scheduledAt", triggerAtMs)
        put("exact", exact)
      })
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (alarmManager.canScheduleExactAlarms()) {
          alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
          emitOk(true)
        } else {
          alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
          emitOk(false)
        }
        return
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
        emitOk(true)
      } else {
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
        emitOk(true)
      }
    } catch (se: SecurityException) {
      // Android 12+ requires SCHEDULE_EXACT_ALARM for exact alarms; fall back to inexact.
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
        } else {
          alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
        }
        emitOk(false)
      } catch (e2: Exception) {
        emitDeviceError(eventId, "E_NOTIFICATIONS", e2.message ?: "Failed to schedule notification.")
      }
    } catch (e: Exception) {
      emitDeviceError(eventId, "E_NOTIFICATIONS", e.message ?: "Failed to schedule notification.")
    }
  }

  private fun handleCancelLocalNotification(eventId: Int, params: JSONObject) {
    val idRaw = params.opt("id")
    val idString = if (idRaw is String) idRaw.trim() else ""
    val notificationId = when {
      idRaw is Int && idRaw != 0 -> idRaw
      idString.isNotBlank() -> idString.hashCode()
      else -> params.optInt("notificationId", 0)
    }

    if (notificationId == 0) {
      emitDeviceError(eventId, "E_ID", "Provide { id } or { notificationId } to cancel.")
      return
    }

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    val intent = Intent(context, VenjsXLocalNotificationReceiver::class.java)
    val pending = PendingIntent.getBroadcast(
      context,
      notificationId,
      intent,
      PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    )
    if (alarmManager != null && pending != null) {
      try {
        alarmManager.cancel(pending)
        pending.cancel()
      } catch (_: Exception) {
      }
    }

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    try {
      manager?.cancel(notificationId)
    } catch (_: Exception) {
    }

    emitDeviceOk(eventId, JSONObject().apply {
      put("notificationId", notificationId)
    })
  }

  @JavascriptInterface
  fun openExternalURL(url: String) {
    if (url.isBlank()) return
    val normalizedUrl = if (
      url.startsWith("http://", ignoreCase = true) ||
      url.startsWith("https://", ignoreCase = true)
    ) url else "https://$url"

    val uri = try {
      Uri.parse(normalizedUrl)
    } catch (_: Exception) {
      return
    }

    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
      context.startActivity(intent)
    } catch (_: Exception) {
      // Log error or handle exception
    }
  }

  private fun mountFreshTree(tree: VNode) {
    rootLayout.removeAllViews()
    val split = splitFixedNodes(tree)
    val flowTree = split.first ?: emptyFlowRootNode()
    val fixedNodes = split.second
    val rendered = renderNode(flowTree)
    val scrollView = ScrollView(context).apply {
      setFillViewport(false)
      isSmoothScrollingEnabled = true
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      addView(rendered)
    }

    val host = FrameLayout(context).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      addView(
        scrollView,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
      )
    }

    if (fixedNodes.isNotEmpty()) {
      val overlay = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        isClickable = false
        isFocusable = false
      }

      fixedNodes
        .sortedBy { styleInt(it.style, "zIndex", 0) }
        .forEach { fixedNode ->
          val fixedView = renderNode(fixedNode)
          applyFixedLayoutParams(fixedView, fixedNode.style)
          overlay.addView(fixedView)
        }

      host.addView(
        overlay,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
      )
    }

    rootLayout.addView(host)
    mountedScrollView = scrollView
    mountedRootView = rendered
    previousTree = tree
  }

  private fun emptyFlowRootNode(): VNode {
    val props = JSONObject().apply { put("style", JSONObject()) }
    return VNode(
      tag = "div",
      props = props,
      style = JSONObject(),
      children = emptyList()
    )
  }

  private fun splitFixedNodes(node: VNode): Pair<VNode?, List<VNode>> {
    if (isFixedPosition(node.style)) {
      return Pair(null, listOf(node))
    }

    if (node.children.isEmpty()) {
      return Pair(node, emptyList())
    }

    val keptChildren = ArrayList<VNode>(node.children.size)
    val fixedNodes = ArrayList<VNode>()
    for (child in node.children) {
      val splitChild = splitFixedNodes(child)
      val flowChild = splitChild.first
      if (flowChild != null) {
        keptChildren.add(flowChild)
      }
      fixedNodes.addAll(splitChild.second)
    }

    return Pair(node.copy(children = keptChildren), fixedNodes)
  }

  private fun isFixedPosition(style: JSONObject?): Boolean {
    if (style == null) return false
    return style.optString("position", "").trim().lowercase() == "fixed"
  }

  private fun applyFixedLayoutParams(view: View, style: JSONObject?) {
    val width = if (
      style != null &&
      !style.has("width") &&
      style.has("left") &&
      style.has("right")
    ) {
      ViewGroup.LayoutParams.MATCH_PARENT
    } else {
      sizeFromStyle(style, "width", ViewGroup.LayoutParams.WRAP_CONTENT, view)
    }

    val height = if (
      style != null &&
      !style.has("height") &&
      style.has("top") &&
      style.has("bottom")
    ) {
      ViewGroup.LayoutParams.MATCH_PARENT
    } else {
      sizeFromStyle(style, "height", ViewGroup.LayoutParams.WRAP_CONTENT, view)
    }

    val lp = FrameLayout.LayoutParams(width, height)

    val hasLeft = style?.has("left") == true
    val hasRight = style?.has("right") == true
    val hasTop = style?.has("top") == true
    val hasBottom = style?.has("bottom") == true

    val horizontalGravity = when {
      hasLeft && !hasRight -> Gravity.START
      hasRight && !hasLeft -> Gravity.END
      else -> Gravity.START
    }
    val verticalGravity = when {
      hasTop && !hasBottom -> Gravity.TOP
      hasBottom && !hasTop -> Gravity.BOTTOM
      else -> Gravity.TOP
    }
    lp.gravity = horizontalGravity or verticalGravity

    lp.leftMargin = if (hasLeft) dp(styleInt(style, "left", 0)) else 0
    lp.rightMargin = if (hasRight) dp(styleInt(style, "right", 0)) else 0
    lp.topMargin = if (hasTop) dp(styleInt(style, "top", 0)) else 0
    lp.bottomMargin = if (hasBottom) dp(styleInt(style, "bottom", 0)) else 0

    view.layoutParams = lp
  }

  private fun parseNode(raw: JSONObject): VNode {
    val tag = raw.optString("tag", "div")
    val props = raw.optJSONObject("props") ?: JSONObject()
    val style = props.optJSONObject("style")
    val childrenRaw = raw.optJSONArray("children") ?: JSONArray()
    val children = ArrayList<VNode>(childrenRaw.length())
    for (i in 0 until childrenRaw.length()) {
      val child = childrenRaw.optJSONObject(i) ?: continue
      children.add(parseNode(child))
    }
    return VNode(tag, props, style, children)
  }

  private fun reconcileNode(
    parent: ViewGroup,
    currentView: View,
    oldNode: VNode,
    newNode: VNode,
    indexInParent: Int
  ): View {
    if (oldNode.tag != newNode.tag || !isViewCompatibleWithNode(currentView, oldNode)) {
      return renderNode(newNode)
    }

    val oldProps = oldNode.props.toString()
    val newProps = newNode.props.toString()
    val childrenChanged = oldNode.children.size != newNode.children.size

    // Input node keeps a TextWatcher. Recreate only when props change to avoid duplicate listeners.
    if (newNode.tag == "input" && oldProps != newProps) {
      return renderNode(newNode)
    }

    // Only skip when this is a true leaf node with identical props.
    // Parent-level shallow checks can miss deep changes (route/content updates).
    if (oldProps == newProps && !childrenChanged && oldNode.children.isEmpty() && newNode.children.isEmpty()) {
      return currentView
    }

    bindView(currentView, newNode)

    if (currentView is ViewGroup) {
      val common = min(oldNode.children.size, newNode.children.size)
      for (i in 0 until common) {
        val childView = currentView.getChildAt(i) ?: run {
          return renderNode(newNode)
        }
        val updated = reconcileNode(
          parent = currentView,
          currentView = childView,
          oldNode = oldNode.children[i],
          newNode = newNode.children[i],
          indexInParent = i
        )
        if (updated !== childView) {
          currentView.removeViewAt(i)
          currentView.addView(updated, i)
        }
      }

      if (newNode.children.size > oldNode.children.size) {
        for (i in oldNode.children.size until newNode.children.size) {
          currentView.addView(renderNode(newNode.children[i]))
        }
      } else if (newNode.children.size < oldNode.children.size) {
        for (i in oldNode.children.size - 1 downTo newNode.children.size) {
          currentView.removeViewAt(i)
        }
      }
    }

    return currentView
  }

  private fun isViewCompatibleWithNode(view: View, node: VNode): Boolean {
    return when {
      isCheckboxNode(node) -> view is CheckBox
      isIconNode(node) -> view is TextView
      else -> when (node.tag) {
      "button" -> view is Button
      "text" -> view is TextView
      "a" -> view is TextView
      "input" -> view is EditText
      "select" -> view is Spinner
      "image" -> view is ImageView
      "activityIndicator" -> view is ProgressBar
      else -> view is LinearLayout
      }
    }
  }

  private fun isCheckboxNode(node: VNode): Boolean {
    return node.tag == "checkbox" ||
      (node.tag == "input" && node.props.optString("type", "").equals("checkbox", ignoreCase = true))
  }

  private fun isIconNode(node: VNode): Boolean {
    return node.tag == "icon"
  }

  private fun renderNode(node: VNode): View {
    if (node.tag == "div" && node.style?.optString("overflowX", "") == "scroll") {
      val horizontalScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT
        )
      }
      val content = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
      }

      setupClickEvent(horizontalScroll, node.props, node.tag)
      applyBaseStyle(horizontalScroll, node.style)
      applyAnimation(horizontalScroll, node.style)

      if (node.children.isNotEmpty()) {
        for (child in node.children) {
          content.addView(renderNode(child))
        }
      }

      horizontalScroll.addView(content)
      return horizontalScroll
    }

    val view = when {
      isCheckboxNode(node) -> CheckBox(context)
      isIconNode(node) -> TextView(context)
      else -> when (node.tag) {
      "button" -> Button(context)
      "text" -> TextView(context)
      "a" -> TextView(context)
      "input" -> EditText(context)
      "select" -> Spinner(context)
      "image" -> ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
      }
      "activityIndicator" -> ProgressBar(context).apply { isIndeterminate = true }
      else -> LinearLayout(context)
      }
    }

    bindView(view, node)

    if (view is ViewGroup && node.children.isNotEmpty() && node.tag != "select") {
      if (view is LinearLayout) {
        addChildrenWithJustifySpacing(view, node)
      } else {
        for (child in node.children) {
          view.addView(renderNode(child))
        }
      }
    }

    return view
  }

  private fun bindView(view: View, node: VNode) {
    when {
      isCheckboxNode(node) -> {
        val checkbox = view as CheckBox
        checkbox.text = node.props.optString("textContent", node.props.optString("label", ""))
        val checked = when {
          node.props.has("checked") -> node.props.optBoolean("checked", false)
          else -> node.props.optString("value", "false").equals("true", ignoreCase = true)
        }
        if (checkbox.isChecked != checked) {
          checkbox.isChecked = checked
        }
        applyTextStyle(checkbox, node.style)
        setupCheckboxChangeEvent(checkbox, node.props)
        setupClickEvent(checkbox, node.props, "checkbox")
      }

      isIconNode(node) -> {
        val tv = view as TextView
        val iconName = node.props.optString("name", "").trim().lowercase()
        val faGlyph = node.props.optString("textContent", "")
        val fallbackGlyph = when (iconName) {
          "search" -> "\u2315"
          "bell" -> "\u25CE"
          "cog", "settings" -> "\u2699"
          "home" -> "\u2302"
          "user" -> "\u25CB"
          "star" -> "\u2605"
          "heart" -> "\u2665"
          "plus" -> "+"
          "minus" -> "-"
          "check" -> "\u2713"
          "close", "times" -> "\u2715"
          else -> faGlyph
        }
        tv.text = if (fontAwesomeTypeface == null) fallbackGlyph else faGlyph
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        if (fontAwesomeTypeface == null) {
          applyTextStyle(tv, node.style)
        } else {
          applyTextStyle(tv, mergedStyle(node.style, "Font Awesome 6 Free"))
        }
        setupClickEvent(tv, node.props, node.tag)
      }

      node.tag == "button" -> {
        val btn = view as Button
        btn.text = node.props.optString("textContent", "")
        btn.isAllCaps = false
        applyTextStyle(btn, node.style)
        setupClickEvent(btn, node.props, node.tag)
      }

      node.tag == "text" -> {
        val tv = view as TextView
        tv.text = node.props.optString("textContent", "")
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        applyTextStyle(tv, node.style)
        setupClickEvent(tv, node.props, node.tag)
      }

      node.tag == "a" -> {
        val tv = view as TextView
        tv.text = node.props.optString(
          "textContent",
          node.props.optString("label", node.props.optString("href", ""))
        )
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        applyTextStyle(tv, mergedStyle(node.style, "myfont").apply {
          if (!has("color")) {
            put("color", "#0B5DFF")
          }
        })
        setupClickEvent(tv, node.props, node.tag)
      }

      node.tag == "input" -> {
        val input = view as EditText
        applyInputType(input, node.props)
        val nextHint = node.props.optString("placeholder", "")
        if (input.hint?.toString() != nextHint) {
          input.hint = nextHint
        }
        val nextValue = node.props.optString("value", "")
        if (input.text?.toString() != nextValue) {
          input.setText(nextValue)
          input.setSelection(input.text?.length ?: 0)
        }
        applyTextStyle(input, node.style)
        setupChangeEvent(input, node.props, node.tag)
        setupClickEvent(input, node.props, node.tag)
      }

      node.tag == "select" -> {
        val spinner = view as Spinner
        bindSelect(spinner, node)
      }

      node.tag == "image" -> {
        val image = view as ImageView
        val src = node.props.optString("src", "")
        if ((image.tag as? String) != src) {
          image.tag = src
          loadImageSource(image, src)
        }
        setupClickEvent(image, node.props, node.tag)
      }

      node.tag == "activityIndicator" -> {
        setupClickEvent(view, node.props, node.tag)
      }

      else -> {
        val layout = view as LinearLayout
        val direction = node.style?.optString("flexDirection", "column") ?: "column"
        layout.orientation = if (direction == "row") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        setupClickEvent(layout, node.props, node.tag)
      }
    }

    applyBaseStyle(view, node.style)
    applyLayoutStyle(view, node.style)
    applyAnimation(view, node.style)
  }

  private fun bindSelect(spinner: Spinner, node: VNode) {
    val optionNodes = node.children.filter { it.tag == "option" }
    val optionValues = optionNodes.map { option ->
      val label = extractOptionText(option)
      option.props.optString("value", label)
    }
    val optionLabels = optionNodes.map { option ->
      val extracted = extractOptionText(option)
      if (extracted.isBlank()) option.props.optString("value", "") else extracted
    }

    val safeLabels = if (optionLabels.isEmpty()) listOf("") else optionLabels
    val adapter = object : ArrayAdapter<String>(
      context,
      android.R.layout.simple_spinner_item,
      safeLabels
    ) {
      override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getView(position, convertView, parent)
        if (row is TextView) {
          row.setTextColor(Color.parseColor("#111111"))
        }
        return row
      }

      override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getDropDownView(position, convertView, parent)
        if (row is TextView) {
          row.setTextColor(Color.parseColor("#111111"))
        }
        return row
      }
    }
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter

    if (optionValues.isNotEmpty()) {
      val selectedValue = node.props.optString("value", "")
      val selectedIndex = optionValues.indexOf(selectedValue).takeIf { it >= 0 } ?: 0
      if (spinner.selectedItemPosition != selectedIndex) {
        try {
          spinner.setSelection(selectedIndex, false)
        } catch (_: Exception) {
          spinner.setSelection(0, false)
        }
      }
    }

    setupSelectChangeEvent(spinner, node.props, optionValues, optionLabels)
  }

  private fun addChildrenWithJustifySpacing(layout: LinearLayout, node: VNode) {
    val children = node.children
    val justify = node.style?.optString("justifyContent", "")?.trim()?.lowercase() ?: ""
    val hasMultipleChildren = children.size > 1
    val needsSpaceBetween = hasMultipleChildren && justify == "space-between"
    val needsSpaceAround = hasMultipleChildren && justify == "space-around"
    val needsSpaceEvenly = hasMultipleChildren && justify == "space-evenly"

    if (!needsSpaceBetween && !needsSpaceAround && !needsSpaceEvenly) {
      for (child in children) {
        layout.addView(renderNode(child))
      }
      return
    }

    val edgeWeight = when {
      needsSpaceAround -> 0.5f
      needsSpaceEvenly -> 1f
      else -> 0f
    }
    val betweenWeight = 1f

    if (edgeWeight > 0f) {
      layout.addView(createFlexSpacer(layout.orientation, edgeWeight))
    }

    children.forEachIndexed { index, child ->
      layout.addView(renderNode(child))
      if (index < children.lastIndex) {
        layout.addView(createFlexSpacer(layout.orientation, betweenWeight))
      }
    }

    if (edgeWeight > 0f) {
      layout.addView(createFlexSpacer(layout.orientation, edgeWeight))
    }
  }

  private fun createFlexSpacer(orientation: Int, weight: Float): View {
    val spacer = View(context)
    spacer.layoutParams = if (orientation == LinearLayout.HORIZONTAL) {
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
    } else {
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight)
    }
    return spacer
  }

  private fun extractOptionText(node: VNode): String {
    val direct = node.props.optString("textContent", "").trim()
    if (direct.isNotEmpty()) return direct
    val label = node.props.optString("label", "").trim()
    if (label.isNotEmpty()) return label

    for (child in node.children) {
      if (child.tag == "text") {
        val value = child.props.optString("textContent", "").trim()
        if (value.isNotEmpty()) return value
      }
    }
    return node.props.optString("value", "")
  }

  private fun setupSelectChangeEvent(
    spinner: Spinner,
    props: JSONObject,
    optionValues: List<String>,
    optionLabels: List<String>
  ) {
    val events = props.optJSONObject("events")
    val changeEventId = events?.optInt("change", -1) ?: -1
    spinner.onItemSelectedListener = null
    if (changeEventId <= 0) return

    val currentValue = props.optString("value", "")
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (position < 0 || position >= optionValues.size) return
        val value = optionValues[position]
        if (value == currentValue) return
        try {
          val payload = JSONObject().apply {
            put("type", "change")
            put("tag", "select")
            put("platform", "android")
            put("value", value)
            put("label", optionLabels.getOrElse(position) { value })
            put("index", position)
            put("timestamp", System.currentTimeMillis())
          }
          emitEvent(changeEventId, payload)
        } catch (_: Exception) {
        }
      }

      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
  }

  private fun setupClickEvent(view: View, props: JSONObject, tag: String) {
    if (view is AdapterView<*>) {
      // AdapterView (e.g., Spinner) throws if setOnClickListener is used directly.
      return
    }

    val events = props.optJSONObject("events")
    val clickEventId = events?.optInt("click", -1) ?: -1
    val doubleTapEventId = events?.optInt("doubleTap", -1) ?: -1
    val href = if (tag == "a") props.optString("href", "").trim() else ""
    val shouldOpenLink = href.isNotEmpty()
    val shouldHandleClick = clickEventId > 0 || shouldOpenLink

    if (!shouldHandleClick) {
      view.setOnClickListener(null)
    } else {
      view.setOnClickListener {
        try {
          if (shouldOpenLink) {
            openUrlInChrome(href)
          }
          val payload = JSONObject().apply {
            put("type", "click")
            put("tag", tag)
            put("platform", "android")
            if (shouldOpenLink) {
              put("href", href)
            }
            put("timestamp", System.currentTimeMillis())
          }
          if (clickEventId > 0) {
            emitEvent(clickEventId, payload)
          }
        } catch (_: Exception) {
        }
      }
    }

    setupDoubleTapEvent(view, doubleTapEventId, tag)
  }

  private fun setupDoubleTapEvent(view: View, doubleTapEventId: Int, tag: String) {
    if (doubleTapEventId <= 0) return
    if (view is EditText) return

    val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
      override fun onDoubleTap(e: MotionEvent): Boolean {
        try {
          val payload = JSONObject().apply {
            put("type", "doubleTap")
            put("tag", tag)
            put("platform", "android")
            put("timestamp", System.currentTimeMillis())
          }
          emitEvent(doubleTapEventId, payload)
        } catch (_: Exception) {
        }
        return true
      }
    })

    view.setOnTouchListener { _, event ->
      detector.onTouchEvent(event)
      false
    }
    view.setTag(DOUBLE_TAP_DETECTOR_TAG_KEY, detector)
  }

  private fun openUrlInChrome(rawUrl: String) {
    if (rawUrl.isBlank()) return
    val normalizedUrl = if (
      rawUrl.startsWith("http://", ignoreCase = true) ||
      rawUrl.startsWith("https://", ignoreCase = true)
    ) rawUrl else "https://$rawUrl"

    val uri = try {
      Uri.parse(normalizedUrl)
    } catch (_: Exception) {
      return
    }

    val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
      setPackage("com.android.chrome")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
      context.startActivity(chromeIntent)
    } catch (_: ActivityNotFoundException) {
      try {
        context.startActivity(fallbackIntent)
      } catch (_: Exception) {
      }
    } catch (_: Exception) {
    }
  }

  private fun setupChangeEvent(input: EditText, props: JSONObject, tag: String) {
    val events = props.optJSONObject("events") ?: return
    if (!events.has("change")) return
    val changeEventId = events.optInt("change", -1)
    if (changeEventId <= 0) return

    input.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        try {
          val payload = JSONObject().apply {
            put("type", "change")
            put("tag", tag)
            put("platform", "android")
            put("value", s?.toString() ?: "")
            put("timestamp", System.currentTimeMillis())
          }
          emitEvent(changeEventId, payload)
        } catch (_: Exception) {
        }
      }

      override fun afterTextChanged(s: Editable?) {}
    })
  }

  private fun setupCheckboxChangeEvent(input: CheckBox, props: JSONObject) {
    val events = props.optJSONObject("events")
    val changeEventId = events?.optInt("change", -1) ?: -1
    input.setOnCheckedChangeListener(null)
    if (changeEventId <= 0) {
      return
    }

    input.setOnCheckedChangeListener { _, isChecked ->
      try {
        val payload = JSONObject().apply {
          put("type", "change")
          put("tag", "checkbox")
          put("platform", "android")
          put("checked", isChecked)
          put("value", isChecked)
          put("timestamp", System.currentTimeMillis())
        }
        emitEvent(changeEventId, payload)
      } catch (_: Exception) {
      }
    }
  }

  private fun loadImageSource(imageView: ImageView, src: String?) {
    if (src.isNullOrBlank()) return
    val source = src.trim()

    imageCache.get(source)?.let {
      imageView.setImageBitmap(it)
      return
    }

    if (source.startsWith("http://") || source.startsWith("https://")) {
      thread(start = true) {
        var connection: HttpURLConnection? = null
        try {
          val url = URL(source)
          connection = url.openConnection() as HttpURLConnection
          connection.doInput = true
          connection.connect()
          val bytes = connection.inputStream.use { it.readBytes() }
          val bitmap = decodeSampledBitmap(bytes, 1080, 720)
          if (bitmap != null) {
            imageCache.put(source, bitmap)
            (context as Activity).runOnUiThread { imageView.setImageBitmap(bitmap) }
          }
        } catch (_: Exception) {
        } finally {
          connection?.disconnect()
        }
      }
      return
    }

    thread(start = true) {
      val bitmap = loadAssetBitmap(source)
      if (bitmap != null) {
        imageCache.put(source, bitmap)
        (context as Activity).runOnUiThread { imageView.setImageBitmap(bitmap) }
      }
    }
  }

  private fun loadAssetBitmap(source: String): Bitmap? {
    val candidates = listOf(
      source,
      if (source.startsWith("./")) source.substring(2) else source,
      if (source.startsWith("images/")) "app/$source" else source,
      if (source.startsWith("./images/")) "app/${source.substring(2)}" else source,
      if (source.startsWith("app/")) source.substring(4) else source
    )

    for (candidate in candidates) {
      if (candidate.isBlank()) continue
      try {
        context.assets.open(candidate).use { input ->
          val bytes = input.readBytes()
          val bitmap = decodeSampledBitmap(bytes, 1080, 720)
          if (bitmap != null) return bitmap
        }
      } catch (_: Exception) {
      }
    }
    return null
  }

  private fun emitEvent(eventId: Int, payload: JSONObject) {
    val js = "window.__venjsDispatchNativeEvent && window.__venjsDispatchNativeEvent($eventId, $payload);"
    (context as Activity).runOnUiThread {
      bridge.evaluateJavascript(js, null)
    }
  }

  private fun applyBaseStyle(view: View, style: JSONObject?) {
    if (style == null) return

    val borderSpec = style.optString("border", "").trim()
    val borderWidthFromShorthand = parseBorderWidth(borderSpec)
    val borderColorFromShorthand = parseBorderColor(borderSpec)

    val hasRadius = style.has("borderRadius") || style.has("border-radius")
    val hasBackground = style.has("backgroundColor")
    val hasBorder = style.has("borderWidth") || style.has("borderColor") || borderSpec.isNotEmpty()

    if (hasRadius || hasBackground || hasBorder) {
      val drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(parseColor(style.optString("backgroundColor", "#00000000")))
        if (hasRadius) {
          cornerRadius = resolveBorderRadiusPx(style, view)
        }
      }

      if (hasBorder) {
        val borderWidth = if (style.has("borderWidth")) styleInt(style, "borderWidth", 0) else borderWidthFromShorthand
        val borderWidthPx = dp(borderWidth)
        val borderColor = if (style.has("borderColor")) {
          parseColor(style.optString("borderColor", "#00000000"))
        } else if (borderSpec.isNotEmpty()) {
          borderColorFromShorthand
        } else {
          parseColor("#000000")
        }
        drawable.setStroke(borderWidthPx, borderColor)
      }

      view.background = drawable

      if (hasRadius && isPercentBorderRadius(style)) {
        view.post {
          val bg = view.background as? GradientDrawable ?: return@post
          bg.cornerRadius = resolveBorderRadiusPx(style, view)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.invalidateOutline()
          }
        }
      }
    } else if (style.has("backgroundColor")) {
      view.setBackgroundColor(parseColor(style.optString("backgroundColor", "#00000000")))
    }

    val resolvedMargins = resolveMargins(style)
    val lp = LinearLayout.LayoutParams(
      sizeFromStyle(style, "width", ViewGroup.LayoutParams.MATCH_PARENT, view),
      sizeFromStyle(style, "height", ViewGroup.LayoutParams.WRAP_CONTENT, view)
    )
    lp.setMargins(
      dp(resolvedMargins.left),
      dp(resolvedMargins.top),
      dp(resolvedMargins.right),
      dp(resolvedMargins.bottom)
    )
    if (resolvedMargins.autoHorizontal && resolvedMargins.autoVertical) {
      lp.gravity = Gravity.CENTER
    } else if (resolvedMargins.autoHorizontal) {
      lp.gravity = Gravity.CENTER_HORIZONTAL
    } else if (resolvedMargins.autoVertical) {
      lp.gravity = Gravity.CENTER_VERTICAL
    }
    view.layoutParams = lp

    if ((style.has("borderRadius") || style.has("border-radius")) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
          val radiusPx = resolveBorderRadiusPx(style, v)
          outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
        }
      }
      view.clipToOutline = true
    }

    applyPadding(view, style)
  }

  private fun applyLayoutStyle(view: View, style: JSONObject?) {
    if (style == null) return

    when (style.optString("display", "").trim().lowercase()) {
      "none" -> {
        view.visibility = View.GONE
        return
      }
      "flex", "block", "" -> view.visibility = View.VISIBLE
      else -> view.visibility = View.VISIBLE
    }

    if (view is LinearLayout) {
      applyFlexContainerAlignment(view, style)
      return
    }

    if (view is TextView) {
      applyTextFlexAlignment(view, style)
    }
  }

  private fun applyFlexContainerAlignment(layout: LinearLayout, style: JSONObject) {
    val direction = style.optString("flexDirection", "column").trim().lowercase()
    val justify = style.optString("justifyContent", "").trim().lowercase()
    val align = style.optString("alignItems", "").trim().lowercase()
    val isRow = direction == "row"

    var horizontal = 0
    var vertical = 0

    if (isRow) {
      horizontal = mapHorizontalGravity(justify)
      vertical = mapVerticalGravity(align)
    } else {
      vertical = mapVerticalGravity(justify)
      horizontal = mapHorizontalGravity(align)
    }

    val resolvedGravity = horizontal or vertical
    if (resolvedGravity != 0) {
      layout.gravity = resolvedGravity
    }
  }

  private fun applyTextFlexAlignment(view: TextView, style: JSONObject) {
    val justify = style.optString("justifyContent", "").trim().lowercase()
    val align = style.optString("alignItems", "").trim().lowercase()

    val horizontal = mapHorizontalGravity(justify)
    val vertical = mapVerticalGravity(align)

    val resolvedGravity = horizontal or vertical
    if (resolvedGravity != 0) {
      view.gravity = resolvedGravity
    }
  }

  private fun mapHorizontalGravity(value: String): Int {
    return when (value) {
      "center" -> Gravity.CENTER_HORIZONTAL
      "flex-end" -> Gravity.END
      "flex-start" -> Gravity.START
      else -> 0
    }
  }

  private fun mapVerticalGravity(value: String): Int {
    return when (value) {
      "center" -> Gravity.CENTER_VERTICAL
      "flex-end" -> Gravity.BOTTOM
      "flex-start" -> Gravity.TOP
      else -> 0
    }
  }

  private fun applyTextStyle(view: TextView, style: JSONObject?) {
    val effectiveStyle = style ?: JSONObject()

    applyTypeface(view, effectiveStyle)

    if (effectiveStyle.has("fontSize")) {
      view.setTextSize(TypedValue.COMPLEX_UNIT_SP, styleInt(effectiveStyle, "fontSize", 16).toFloat())
    }
    if (effectiveStyle.has("color")) {
      view.setTextColor(parseColor(effectiveStyle.optString("color", "#111111")))
    }
    when (effectiveStyle.optString("textAlign", "")) {
      "center" -> view.gravity = Gravity.CENTER_HORIZONTAL
      "right" -> view.gravity = Gravity.END
      else -> view.gravity = Gravity.START
    }
  }

  private fun applyInputType(input: EditText, props: JSONObject) {
    val type = props.optString("type", "text").trim().lowercase()
    input.showSoftInputOnFocus = true
    input.setOnTouchListener(null)
    input.onFocusChangeListener = null
    input.isFocusable = true
    input.isFocusableInTouchMode = true
    input.isCursorVisible = true
    input.isLongClickable = true
    input.inputType = when (type) {
      "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
      "number" -> InputType.TYPE_CLASS_NUMBER
      "phone", "tel" -> InputType.TYPE_CLASS_PHONE
      "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      "multiline", "textarea" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      "select", "picker" -> {
        input.showSoftInputOnFocus = false
        input.isCursorVisible = false
        input.isLongClickable = false
        InputType.TYPE_NULL
      }
      "date" -> {
        bindDatePicker(input)
        InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
      }
      else -> InputType.TYPE_CLASS_TEXT
    }
  }

  private fun bindDatePicker(input: EditText) {
    input.showSoftInputOnFocus = false
    input.isFocusable = true
    input.isFocusableInTouchMode = true
    input.setOnTouchListener { _, event ->
      if (event.action == MotionEvent.ACTION_UP) {
        showDatePicker(input)
      }
      false
    }
    input.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
      if (hasFocus) {
        showDatePicker(input)
      }
    }
  }

  private fun showDatePicker(input: EditText) {
    val now = Calendar.getInstance()
    val dialog = DatePickerDialog(
      context,
      { _, year, month, dayOfMonth ->
        val value = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
        input.setText(value)
        input.setSelection(value.length)
      },
      now.get(Calendar.YEAR),
      now.get(Calendar.MONTH),
      now.get(Calendar.DAY_OF_MONTH)
    )
    dialog.show()
  }

  private fun applyTypeface(view: TextView, style: JSONObject) {
    val explicitFamily = style.optString("fontFamily", "").trim()
    val baseTypeface = when {
      explicitFamily.equals("font awesome", ignoreCase = true) ||
        explicitFamily.equals("font awesome 6 free", ignoreCase = true) ||
        explicitFamily.equals("fontawesome", ignoreCase = true) -> fontAwesomeTypeface
      explicitFamily.isNotBlank() -> loadFontByNames(listOf(normalizeFontResourceName(explicitFamily)))
      else -> defaultTypeface
    } ?: defaultTypeface

    val weight = parseFontWeight(style.opt("fontWeight"))
    if (weight != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.typeface = Typeface.create(baseTypeface, weight, false)
      } else {
        val legacyStyle = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
        view.setTypeface(baseTypeface ?: view.typeface, legacyStyle)
      }
      return
    }

    if (baseTypeface != null) {
      view.typeface = baseTypeface
    }
  }

  private fun parseFontWeight(raw: Any?): Int? {
    return when (raw) {
      is Number -> raw.toInt().coerceIn(100, 900)
      is String -> {
        val cleaned = raw.trim().lowercase()
        when (cleaned) {
          "", "normal", "regular" -> 400
          "bold" -> 700
          else -> cleaned.toIntOrNull()?.coerceIn(100, 900)
        }
      }
      else -> null
    }
  }

  private fun loadFontByNames(names: List<String>): Typeface? {
    for (name in names) {
      val resourceName = normalizeFontResourceName(name)
      val resourceId = context.resources.getIdentifier(resourceName, "font", context.packageName)
      if (resourceId != 0) {
        try {
          val typeface = ResourcesCompat.getFont(context, resourceId)
          if (typeface != null) {
            return typeface
          }
        } catch (_: Exception) {
        }
      }
    }
    return null
  }

  private fun normalizeFontResourceName(name: String): String {
    return name.trim()
      .lowercase()
      .replace(Regex("[^a-z0-9_]+"), "_")
      .replace(Regex("_+"), "_")
      .trim('_')
  }

  private fun mergedStyle(style: JSONObject?, fallbackFamily: String): JSONObject {
    if (style == null) {
      return JSONObject().apply {
        put("fontFamily", fallbackFamily)
      }
    }
    if (style.has("fontFamily")) {
      return style
    }
    return JSONObject(style.toString()).apply {
      put("fontFamily", fallbackFamily)
    }
  }

  private fun sizeFromStyle(style: JSONObject?, key: String, fallback: Int, view: View): Int {
    if (style == null || !style.has(key)) return fallback

    val value = style.optString(key, "")
    if (value == "match" || value == "100%") return ViewGroup.LayoutParams.MATCH_PARENT
    if (value == "wrap" || value == "auto") {
      if (key == "height") {
        view.minimumHeight = 0
      }
      return ViewGroup.LayoutParams.WRAP_CONTENT
    }

    val base = if (fallback == ViewGroup.LayoutParams.MATCH_PARENT) 0 else 44
    return dp(styleInt(style, key, base))
  }

  private fun resolveMargins(style: JSONObject): ResolvedMargins {
    val marginRaw = style.optString("margin", "").trim()
    val marginTokens = parseBoxShorthand(marginRaw)

    var top = parsePxValue(marginTokens.getOrNull(0)) ?: 0
    var right = parsePxValue(marginTokens.getOrNull(1)) ?: top
    var bottom = parsePxValue(marginTokens.getOrNull(2)) ?: top
    var left = parsePxValue(marginTokens.getOrNull(3)) ?: right

    var autoTop = marginTokens.getOrNull(0)?.equals("auto", ignoreCase = true) == true
    var autoRight = marginTokens.getOrNull(1)?.equals("auto", ignoreCase = true) == true
    var autoBottom = marginTokens.getOrNull(2)?.equals("auto", ignoreCase = true) == true
    var autoLeft = marginTokens.getOrNull(3)?.equals("auto", ignoreCase = true) == true

    if (style.has("marginTop")) {
      autoTop = isAutoValue(style.opt("marginTop"))
      top = if (autoTop) 0 else styleInt(style, "marginTop", top)
    }
    if (style.has("marginRight")) {
      autoRight = isAutoValue(style.opt("marginRight"))
      right = if (autoRight) 0 else styleInt(style, "marginRight", right)
    }
    if (style.has("marginBottom")) {
      autoBottom = isAutoValue(style.opt("marginBottom"))
      bottom = if (autoBottom) 0 else styleInt(style, "marginBottom", bottom)
    }
    if (style.has("marginLeft")) {
      autoLeft = isAutoValue(style.opt("marginLeft"))
      left = if (autoLeft) 0 else styleInt(style, "marginLeft", left)
    }

    return ResolvedMargins(
      left = left,
      top = top,
      right = right,
      bottom = bottom,
      autoHorizontal = autoLeft && autoRight,
      autoVertical = autoTop && autoBottom
    )
  }

  private fun parseBoxShorthand(value: String): List<String> {
    if (value.isBlank()) return emptyList()
    val tokens = value.split(Regex("\\s+")).filter { it.isNotBlank() }
    return when (tokens.size) {
      1 -> listOf(tokens[0], tokens[0], tokens[0], tokens[0])
      2 -> listOf(tokens[0], tokens[1], tokens[0], tokens[1])
      3 -> listOf(tokens[0], tokens[1], tokens[2], tokens[1])
      else -> listOf(tokens[0], tokens[1], tokens[2], tokens[3])
    }
  }

  private fun parsePxValue(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    if (value.equals("auto", ignoreCase = true)) return null
    val cleaned = value.replace("px", "").trim()
    return cleaned.toDoubleOrNull()?.toInt()
  }

  private fun isAutoValue(raw: Any?): Boolean {
    return raw?.toString()?.trim()?.equals("auto", ignoreCase = true) == true
  }

  private fun parseBorderWidth(border: String): Int {
    if (border.isBlank()) return 0
    val tokens = border.split(Regex("\\s+")).filter { it.isNotBlank() }
    for (token in tokens) {
      val width = parsePxValue(token)
      if (width != null) {
        return width
      }
    }
    return 0
  }

  private fun parseBorderColor(border: String): Int {
    if (border.isBlank()) return Color.TRANSPARENT
    val tokens = Regex("""(?i)(rgba?\([^)]*\)|#[0-9a-f]{3,8}|[a-z]+)""")
      .findAll(border)
      .map { it.value.trim() }
      .filter { it.isNotBlank() }
      .toList()
    for (i in tokens.indices.reversed()) {
      val token = tokens[i]
      val parsed = parseColor(token)
      if (parsed != Color.TRANSPARENT || token.equals("transparent", ignoreCase = true)) {
        return parsed
      }
    }
    return parseColor("#000000")
  }

  private fun parseColor(value: String): Int {
    val raw = value.trim()
    if (raw.isEmpty()) return Color.TRANSPARENT

    val rgbMatch = Regex("""^rgb\(\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*\)$""", RegexOption.IGNORE_CASE)
      .find(raw)
    if (rgbMatch != null) {
      val r = rgbMatch.groupValues[1].toInt().coerceIn(0, 255)
      val g = rgbMatch.groupValues[2].toInt().coerceIn(0, 255)
      val b = rgbMatch.groupValues[3].toInt().coerceIn(0, 255)
      return Color.rgb(r, g, b)
    }

    val rgbaMatch = Regex("""^rgba\(\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*,\s*([0-9]*\.?[0-9]+)\s*\)$""", RegexOption.IGNORE_CASE)
      .find(raw)
    if (rgbaMatch != null) {
      val r = rgbaMatch.groupValues[1].toInt().coerceIn(0, 255)
      val g = rgbaMatch.groupValues[2].toInt().coerceIn(0, 255)
      val b = rgbaMatch.groupValues[3].toInt().coerceIn(0, 255)
      val alphaRaw = rgbaMatch.groupValues[4].toFloatOrNull() ?: 1f
      val a = if (alphaRaw > 1f) alphaRaw.toInt().coerceIn(0, 255) else (alphaRaw * 255f).toInt().coerceIn(0, 255)
      return Color.argb(a, r, g, b)
    }

    return try {
      Color.parseColor(raw)
    } catch (_: Exception) {
      Color.TRANSPARENT
    }
  }

  private fun styleFloat(style: JSONObject?, key: String, fallback: Float): Float {
    if (style == null || !style.has(key)) return fallback
    return try {
      style.optString(key, fallback.toString())
        .replace("px", "")
        .trim()
        .toFloat()
    } catch (_: Exception) {
      fallback
    }
  }

  private fun isPercentBorderRadius(style: JSONObject?): Boolean {
    if (style == null) return false
    val raw = when {
      style.has("borderRadius") -> style.optString("borderRadius", "")
      style.has("border-radius") -> style.optString("border-radius", "")
      else -> ""
    }
    return raw.trim().endsWith("%")
  }

  private fun resolveBorderRadiusPx(style: JSONObject, view: View): Float {
    val raw = when {
      style.has("borderRadius") -> style.optString("borderRadius", "")
      style.has("border-radius") -> style.optString("border-radius", "")
      else -> ""
    }.trim()
    if (raw.isEmpty()) return 0f

    if (raw.endsWith("%")) {
      val percent = raw.removeSuffix("%").trim().toFloatOrNull() ?: return 0f
      val widthPx = view.width.toFloat()
      val heightPx = view.height.toFloat()
      val base = minOf(widthPx, heightPx)
      if (base <= 0f) return 0f
      return (base * (percent / 100f)).coerceAtLeast(0f)
    }

    val dpValue = raw
      .replace("px", "", ignoreCase = true)
      .trim()
      .toFloatOrNull() ?: return 0f
    return dpF(dpValue)
  }

  private fun styleInt(style: JSONObject?, key: String, fallback: Int): Int {
    if (style == null || !style.has(key)) return fallback
    return try {
      style.optString(key, fallback.toString())
        .replace("px", "")
        .trim()
        .toDouble()
        .toInt()
    } catch (_: Exception) {
      fallback
    }
  }

  private fun dp(value: Int): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      context.resources.displayMetrics
    ).toInt()
  }

  private fun dpF(value: Float): Float {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value,
      context.resources.displayMetrics
    )
  }

  private fun applyPadding(view: View, style: JSONObject) {
    val basePadding = styleInt(style, "padding", 0)
    val horizontal = styleInt(style, "paddingHorizontal", basePadding)
    val vertical = styleInt(style, "paddingVertical", basePadding)

    val left = dp(styleInt(style, "paddingLeft", horizontal))
    val right = dp(styleInt(style, "paddingRight", horizontal))
    val top = dp(styleInt(style, "paddingTop", vertical))
    val bottom = dp(styleInt(style, "paddingBottom", vertical))

    if (left != view.paddingLeft || top != view.paddingTop || right != view.paddingRight || bottom != view.paddingBottom) {
      view.setPadding(left, top, right, bottom)
    }
  }

  private fun applyAnimation(view: View, style: JSONObject?) {
    if (style == null) return

    val type = style.optString("animation", style.optString("animationType", "")).trim()
    val duration = styleInt(style, "animationDuration", 280).toLong().coerceAtLeast(0L)
    val delay = styleInt(style, "animationDelay", 0).toLong().coerceAtLeast(0L)
    val distance = dp(styleInt(style, "animationDistance", 18)).toFloat()
    val targetOpacity = styleFloat(style, "opacity", 1f).coerceIn(0f, 1f)

    val signature = "${type}|${duration}|${delay}|${distance}|${targetOpacity}"
    if (view.getTag(ANIM_SIGNATURE_TAG_KEY) == signature) return
    view.setTag(ANIM_SIGNATURE_TAG_KEY, signature)

    if (type.isEmpty()) {
      view.alpha = targetOpacity
      return
    }

    when (type.lowercase()) {
      "fade", "fadein" -> {
        view.alpha = 0f
        view.animate()
          .alpha(targetOpacity)
          .setDuration(duration)
          .setStartDelay(delay)
          .start()
      }

      "slideup" -> {
        view.alpha = 0f
        view.translationY = distance
        view.animate()
          .translationY(0f)
          .alpha(targetOpacity)
          .setDuration(duration)
          .setStartDelay(delay)
          .start()
      }

      "slidedown" -> {
        view.alpha = 0f
        view.translationY = -distance
        view.animate()
          .translationY(0f)
          .alpha(targetOpacity)
          .setDuration(duration)
          .setStartDelay(delay)
          .start()
      }

      "zoomin" -> {
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate()
          .alpha(targetOpacity)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(duration)
          .setStartDelay(delay)
          .start()
      }

      else -> {
        view.alpha = targetOpacity
      }
    }
  }

  private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val options = BitmapFactory.Options().apply {
      inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
      inPreferredConfig = Bitmap.Config.RGB_565
      inDither = true
      inJustDecodeBounds = false
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }

  private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    var inSampleSize = 1
    val height = options.outHeight
    val width = options.outWidth

    if (height > reqHeight || width > reqWidth) {
      val halfHeight = height / 2
      val halfWidth = width / 2
      while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
        inSampleSize *= 2
      }
    }
    return max(1, inSampleSize)
  }
}
