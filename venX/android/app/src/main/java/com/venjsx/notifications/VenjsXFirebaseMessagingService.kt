package com.venjsx.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.venjsx.MainActivity
import com.venjsx.core.VenjsXEngine
import org.json.JSONObject

class VenjsXFirebaseMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    VenjsXPushTokenStore.setToken(token)
    VenjsXEngine.activeEngine?.emitPushToken(token)
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val title = message.notification?.title ?: message.data["title"] ?: "Notification"
    val body = message.notification?.body ?: message.data["body"] ?: ""

    val data = JSONObject()
    message.data.forEach { (k, v) ->
      data.put(k, v)
    }

    val payload = JSONObject().apply {
      put("id", message.messageId ?: "")
      put("title", title)
      put("body", body)
      put("data", data)
    }

    VenjsXEngine.activeEngine?.emitNotificationReceived(payload)
    showSystemNotification(title, body, payload.toString())
  }

  private fun showSystemNotification(title: String, body: String, payload: String) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "venjsx_default"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(channelId, "venjsX", NotificationManager.IMPORTANCE_DEFAULT)
      manager.createNotificationChannel(channel)
    }

    val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    val tapIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      putExtra("venjsx_notification_tap", payload)
    }
    val tapPendingIntent = PendingIntent.getActivity(
      this,
      notificationId,
      tapIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    )

    val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
      .setSmallIcon(VenjsXNotificationIcon.resolveSmallIconResId(this))
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)
      .setContentIntent(tapPendingIntent)
      .build()

    manager.notify(notificationId, notification)
  }
}
