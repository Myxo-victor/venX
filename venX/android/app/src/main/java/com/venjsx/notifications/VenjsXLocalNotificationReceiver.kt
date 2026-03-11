package com.venjsx.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.venjsx.MainActivity

class VenjsXLocalNotificationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val notificationId = intent.getIntExtra("notificationId", 0)
    val title = intent.getStringExtra("title") ?: ""
    val body = intent.getStringExtra("body") ?: ""
    val payload = intent.getStringExtra("payload") ?: "{}"

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "venjsx_default"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(channelId, "venjsX", NotificationManager.IMPORTANCE_DEFAULT)
      manager.createNotificationChannel(channel)
    }

    val tapIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      putExtra("venjsx_notification_tap", payload)
    }
    val tapPendingIntent = PendingIntent.getActivity(
      context,
      notificationId,
      tapIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    )

    val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
      .setSmallIcon(VenjsXNotificationIcon.resolveSmallIconResId(context))
      .setContentTitle(title.ifBlank { "Notification" })
      .setContentText(body)
      .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)
      .setContentIntent(tapPendingIntent)
      .build()

    manager.notify(notificationId, notification)
  }
}
