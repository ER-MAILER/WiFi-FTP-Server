package com.example.server

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteControlAccessibilityService? = null
            private set
        
        val isServiceRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("RemoteControlService", "Accessibility Service Connected!")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.d("RemoteControlService", "Accessibility Service Disconnected!")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    fun tap(x: Float, y: Float): Boolean {
        Log.d("RemoteControlService", "Tapping at ($x, $y)")
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        Log.d("RemoteControlService", "Swiping from ($x1, $y1) to ($x2, $y2) duration $duration")
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBackButton(): Boolean {
        Log.d("RemoteControlService", "Pressing back button")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHomeButton(): Boolean {
        Log.d("RemoteControlService", "Pressing home button")
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun pressRecentsButton(): Boolean {
        Log.d("RemoteControlService", "Pressing recents button")
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
}
