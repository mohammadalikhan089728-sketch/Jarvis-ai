package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OfflineExecutionResult(
    val actionTag: String,
    val paramsJson: String,
    val reply: String
)

class OfflineCommandProcessor(private val context: Context) {

    private var flashlightState = false

    suspend fun tryExecute(rawCommand: String): OfflineExecutionResult? {
        val text = rawCommand.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)

        // 1. Time & Date
        if (lower.matches(Regex(".*\\b(what time|current time|tell.*time|time now|kya time|samay)\\b.*")) || lower == "time") {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return OfflineExecutionResult(
                actionTag = "OFFLINE:TIME",
                paramsJson = JSONObject().put("time", timeStr).toString(),
                reply = "It is currently $timeStr, sir."
            )
        }
        if (lower.matches(Regex(".*\\b(what date|today'?s date|what day is it|aaj ki tarikh)\\b.*")) || lower == "date") {
            val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
            return OfflineExecutionResult(
                actionTag = "OFFLINE:DATE",
                paramsJson = JSONObject().put("date", dateStr).toString(),
                reply = "Today is $dateStr."
            )
        }

        // 2. Battery telemetry
        if (lower.contains("battery") || lower.contains("charge level")) {
            val batteryInfo = readBatteryStatus()
            return OfflineExecutionResult(
                actionTag = "OFFLINE:BATTERY",
                paramsJson = JSONObject().put("status", batteryInfo).toString(),
                reply = batteryInfo
            )
        }

        // 3. Flashlight / Torch
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOn = when {
                lower.contains("off") || lower.contains("disable") || lower.contains("band") -> false
                lower.contains("on") || lower.contains("enable") || lower.contains("chalu") -> true
                else -> !flashlightState
            }
            val success = toggleFlashlight(turnOn)
            val reply = if (success) {
                if (turnOn) "Flashlight engaged." else "Flashlight powered down."
            } else {
                "Unable to toggle flashlight. Please check camera hardware or permissions."
            }
            return OfflineExecutionResult(
                actionTag = if (turnOn) "OFFLINE:FLASHLIGHT_ON" else "OFFLINE:FLASHLIGHT_OFF",
                paramsJson = JSONObject().put("enabled", turnOn).put("success", success).toString(),
                reply = reply
            )
        }

        // 4. Volume control
        if (lower.contains("volume up") || lower.contains("increase volume") || lower.contains("louder")) {
            adjustVolume(AudioManager.ADJUST_RAISE)
            return OfflineExecutionResult("OFFLINE:VOLUME_UP", "{}", "Volume increased.")
        }
        if (lower.contains("volume down") || lower.contains("decrease volume") || lower.contains("quieter")) {
            adjustVolume(AudioManager.ADJUST_LOWER)
            return OfflineExecutionResult("OFFLINE:VOLUME_DOWN", "{}", "Volume lowered.")
        }
        if (lower == "mute" || lower.contains("mute volume") || lower.contains("silent mode")) {
            adjustVolume(AudioManager.ADJUST_MUTE)
            return OfflineExecutionResult("OFFLINE:VOLUME_MUTE", "{}", "Media volume muted.")
        }

        // 5. Global system actions (AccessibilityService)
        val service = JarvisAccessibilityService.instance
        if (lower == "go home" || lower == "home screen" || lower == "home") {
            val ok = service?.performGlobalSystemAction("HOME") == true
            return OfflineExecutionResult(
                "OFFLINE:GLOBAL_HOME",
                "{}",
                if (ok) "Returning to home screen." else "Enable Jarvis Accessibility Service to navigate home."
            )
        }
        if (lower == "go back" || lower == "back") {
            val ok = service?.performGlobalSystemAction("BACK") == true
            return OfflineExecutionResult(
                "OFFLINE:GLOBAL_BACK",
                "{}",
                if (ok) "Navigating back." else "Enable Jarvis Accessibility Service to press back."
            )
        }
        if (lower.contains("recent apps") || lower == "recents" || lower == "overview") {
            val ok = service?.performGlobalSystemAction("RECENTS") == true
            return OfflineExecutionResult(
                "OFFLINE:GLOBAL_RECENTS",
                "{}",
                if (ok) "Opening recent applications." else "Enable Jarvis Accessibility Service first."
            )
        }
        if (lower.contains("open notifications") || lower.contains("show notifications") || lower == "notifications") {
            val ok = service?.performGlobalSystemAction("NOTIFICATIONS") == true
            return OfflineExecutionResult(
                "OFFLINE:GLOBAL_NOTIFICATIONS",
                "{}",
                if (ok) "Pulling down notification shade." else "Enable Jarvis Accessibility Service first."
            )
        }
        if (lower.contains("quick settings")) {
            val ok = service?.performGlobalSystemAction("QUICK_SETTINGS") == true
            return OfflineExecutionResult(
                "OFFLINE:GLOBAL_QUICK_SETTINGS",
                "{}",
                if (ok) "Opening quick settings." else "Enable Jarvis Accessibility Service first."
            )
        }
        if (lower.contains("take screenshot") || lower == "screenshot") {
            val ok = service?.performGlobalSystemAction("SCREENSHOT") == true
            return OfflineExecutionResult(
                "OFFLINE:GLOBAL_SCREENSHOT",
                "{}",
                if (ok) "Screenshot captured." else "Enable Jarvis Accessibility Service to capture screenshots."
            )
        }
        if (lower.contains("lock screen") || lower == "lock phone") {
            val ok = service?.performGlobalSystemAction("LOCK_SCREEN") == true
            return OfflineExecutionResult(
                "OFFLINE:GLOBAL_LOCK",
                "{}",
                if (ok) "Locking device." else "Enable Jarvis Accessibility Service to lock the screen."
            )
        }

        // 6. Directional Scroll
        if (lower.startsWith("scroll ") || lower == "scroll") {
            val dir = when {
                lower.contains("up") -> "SCROLL_UP"
                lower.contains("left") -> "SCROLL_LEFT"
                lower.contains("right") -> "SCROLL_RIGHT"
                else -> "SCROLL_DOWN"
            }
            val ok = service?.performDirectionalScroll(dir) == true
            return OfflineExecutionResult(
                "OFFLINE:$dir",
                JSONObject().put("direction", dir).toString(),
                if (ok) "Scrolling ${dir.removePrefix("SCROLL_").lowercase(Locale.ROOT)}."
                else "Enable Jarvis Accessibility Service to control scrolling."
            )
        }

        // 7. Multi-touch zoom & rotate
        if (lower.contains("zoom in") || lower.contains("pinch out")) {
            val ok = service?.gestureEngine?.pinchZoomIn() == true
            return OfflineExecutionResult(
                "OFFLINE:MULTI_TOUCH_ZOOM_IN",
                JSONObject().put("gesture", "PINCH_OUT").toString(),
                if (ok) "Zooming in." else "Enable Jarvis Accessibility Service for multi-touch gestures."
            )
        }
        if (lower.contains("zoom out") || lower.contains("pinch in")) {
            val ok = service?.gestureEngine?.pinchZoomOut() == true
            return OfflineExecutionResult(
                "OFFLINE:MULTI_TOUCH_ZOOM_OUT",
                JSONObject().put("gesture", "PINCH_IN").toString(),
                if (ok) "Zooming out." else "Enable Jarvis Accessibility Service for multi-touch gestures."
            )
        }
        if (lower.contains("rotate left") || lower.contains("rotate counter")) {
            val ok = service?.gestureEngine?.twoFingerRotate(degrees = -45f) == true
            return OfflineExecutionResult(
                "OFFLINE:MULTI_TOUCH_ROTATE",
                JSONObject().put("degrees", -45).toString(),
                if (ok) "Rotating counter-clockwise." else "Enable Jarvis Accessibility Service for rotation gestures."
            )
        }
        if (lower.contains("rotate right") || lower.contains("rotate clockwise") || lower == "rotate") {
            val ok = service?.gestureEngine?.twoFingerRotate(degrees = 45f) == true
            return OfflineExecutionResult(
                "OFFLINE:MULTI_TOUCH_ROTATE",
                JSONObject().put("degrees", 45).toString(),
                if (ok) "Rotating clockwise." else "Enable Jarvis Accessibility Service for rotation gestures."
            )
        }

        // 8. 360-degree Drag & Swipe
        if (lower.startsWith("drag ") || lower.startsWith("swipe ")) {
            val angleMatch = Regex("(\\d+)\\s*(deg|degree|degrees)").find(lower)
            if (angleMatch != null) {
                val angle = angleMatch.groupValues[1].toFloatOrNull() ?: 0f
                val ok = service?.gestureEngine?.dragByAngle(angleDegrees = angle, distancePx = 380f) == true
                return OfflineExecutionResult(
                    "OFFLINE:DRAG_ANGLE",
                    JSONObject().put("angleDegrees", angle).toString(),
                    if (ok) "Dragging at ${angle.toInt()} degrees."
                    else "Enable Jarvis Accessibility Service to perform drag gestures."
                )
            }
            val swipeDir = when {
                lower.contains("up left") || lower.contains("top left") -> SwipeDirection.UP_LEFT
                lower.contains("up right") || lower.contains("top right") -> SwipeDirection.UP_RIGHT
                lower.contains("down left") || lower.contains("bottom left") -> SwipeDirection.DOWN_LEFT
                lower.contains("down right") || lower.contains("bottom right") -> SwipeDirection.DOWN_RIGHT
                lower.contains("up") -> SwipeDirection.UP
                lower.contains("down") -> SwipeDirection.DOWN
                lower.contains("left") -> SwipeDirection.LEFT
                lower.contains("right") -> SwipeDirection.RIGHT
                else -> null
            }
            if (swipeDir != null) {
                val isTwoFinger = lower.contains("two finger") || lower.contains("2 finger")
                val ok = if (isTwoFinger) {
                    service?.gestureEngine?.twoFingerSwipe(swipeDir) == true
                } else {
                    service?.gestureEngine?.swipeDirection(swipeDir) == true
                }
                return OfflineExecutionResult(
                    if (isTwoFinger) "OFFLINE:TWO_FINGER_SWIPE" else "OFFLINE:DRAG_${swipeDir.name}",
                    JSONObject().put("direction", swipeDir.name).toString(),
                    if (ok) "Executed ${swipeDir.name.lowercase(Locale.ROOT).replace('_', ' ')} swipe."
                    else "Enable Jarvis Accessibility Service for touch gestures."
                )
            }
        }

        // 9. Simple offline "open <app>" (only when no follow-up multi-step "and ..." clause exists)
        val openMatch = Regex("^(open|launch|start)\\s+([a-z0-9 ._-]+)$").find(lower)
        if (openMatch != null && !lower.contains(" and ")) {
            val appQuery = openMatch.groupValues[2].trim()
            val launchedName = openInstalledApp(appQuery)
            if (launchedName != null) {
                return OfflineExecutionResult(
                    actionTag = "OFFLINE:OPEN_APP",
                    paramsJson = JSONObject().put("appName", launchedName).toString(),
                    reply = "Launching $launchedName."
                )
            }
        }

        // 10. Direct phone call ("call <number or contact>")
        val callMatch = Regex("^(call|dial)\\s+(.+)$").find(lower)
        if (callMatch != null && !lower.contains(" and ")) {
            val target = callMatch.groupValues[2].trim()
            val resultMsg = initiateCall(target)
            return OfflineExecutionResult(
                actionTag = "OFFLINE:CALL",
                paramsJson = JSONObject().put("target", target).toString(),
                reply = resultMsg
            )
        }

        return null
    }

    fun readBatteryStatus(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        return if (pct >= 0) {
            val chargeText = if (isCharging) "and currently charging" else "on battery power"
            "Power reserves are at $pct percent, $chargeText."
        } else {
            "Battery telemetry is temporarily unavailable."
        }
    }

    fun toggleFlashlight(enable: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(cameraId, enable)
            flashlightState = enable
            true
        } catch (_: Exception) {
            false
        }
    }

    fun adjustVolume(direction: Int) {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    fun openInstalledApp(appNameQuery: String): String? {
        val query = appNameQuery.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return null

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)

        val exact = apps.firstOrNull { info ->
            info.loadLabel(pm).toString().trim().lowercase(Locale.ROOT) == query
        }
        val partial = exact ?: apps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().trim().lowercase(Locale.ROOT)
            val pkg = info.activityInfo.packageName.lowercase(Locale.ROOT)
            label.contains(query) || pkg.contains(query)
        } ?: return null

        val packageName = partial.activityInfo.packageName
        val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null

        return try {
            context.startActivity(launchIntent)
            partial.loadLabel(pm).toString()
        } catch (_: Exception) {
            null
        }
    }

    fun initiateCall(target: String): String {
        val cleanDigits = target.filter { it.isDigit() || it == '+' }
        val numberToCall = if (cleanDigits.length >= 3) {
            cleanDigits
        } else {
            lookupContactPhoneNumber(target)
        }

        if (numberToCall.isNullOrBlank()) {
            return "I couldn't find a phone number for $target. Verify Contacts permission or speak the digits."
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(numberToCall)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            if (hasCallPermission) "Placing a call to $target." else "Opening dialer for $target."
        } catch (_: Exception) {
            "Unable to launch the phone dialer on this device."
        }
    }

    private fun lookupContactPhoneNumber(nameQuery: String): String? {
        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) return null

        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$nameQuery%")
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(1)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
