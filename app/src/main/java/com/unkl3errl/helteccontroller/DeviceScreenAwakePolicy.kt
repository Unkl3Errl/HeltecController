package com.unkl3errl.helteccontroller

/** Keeps the visible controller awake only while it has live work to protect. */
internal object DeviceScreenAwakePolicy {
    fun shouldKeepScreenOn(flashing: Boolean, deviceConnected: Boolean): Boolean =
        flashing || deviceConnected
}
