package com.venjsx.notifications

object VenjsXPushTokenStore {
  @Volatile
  private var token: String? = null

  fun setToken(value: String?) {
    token = value
  }

  fun getToken(): String? = token
}

