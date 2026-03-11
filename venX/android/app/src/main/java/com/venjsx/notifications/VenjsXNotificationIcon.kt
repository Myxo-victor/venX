package com.venjsx.notifications

import android.content.Context
import android.content.pm.PackageManager
import com.venjsx.R

object VenjsXNotificationIcon {
  private const val META_SMALL_ICON = "venjsx_notification_small_icon"

  fun resolveSmallIconResId(context: Context): Int {
    try {
      val appInfo = context.packageManager.getApplicationInfo(
        context.packageName,
        PackageManager.GET_META_DATA
      )
      val meta = appInfo.metaData
      val fromMeta = meta?.getInt(META_SMALL_ICON, 0) ?: 0
      if (fromMeta != 0) return fromMeta
      if (appInfo.icon != 0) return appInfo.icon
    } catch (_: Exception) {
    }
    return R.drawable.logo
  }
}

