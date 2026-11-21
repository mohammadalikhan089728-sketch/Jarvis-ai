package com.example

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class ScreenNodeModel(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean,
    val centerX: Int,
    val centerY: Int,
    val bounds: Rect
)

data class ScreenHierarchySnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<ScreenNodeModel>,
    val capturedAt: Long = System.currentTimeMillis()
) {
    /**
     * Ultra-compact JSON payload (short keys, stripped layout containers)
     * engineered for millisecond gemini-3.5-flash inference.
     */
    fun toCompactJson(): String {
        val root = JSONObject()
        root.put("p", packageName)
        root.put("w", screenWidth)
        root.put("h", screenHeight)
        val arr = JSONArray()
        for (node in nodes) {
            val obj = JSONObject()
            if (node.text.isNotBlank()) obj.put("t", node.text)
            if (node.contentDescription.isNotBlank() && node.contentDescription != node.text) {
                obj.put("d", node.contentDescription)
            }
            obj.put("x", node.centerX)
            obj.put("y", node.centerY)
            if (node.clickable) obj.put("c", 1)
            if (node.editable) obj.put("e", 1)
            if (node.scrollable) obj.put("s", 1)
            if (node.checked) obj.put("k", 1)
            arr.put(obj)
        }
        root.put("n", arr)
        return root.toString()
    }

    fun toPlainReadableText(): String {
        val lines = nodes
            .mapNotNull { it.text.ifBlank { it.contentDescription }.takeIf(String::isNotBlank) }
            .distinct()
            .take(45)
        return if (lines.isEmpty()) {
            "Active app: $packageName. No readable text detected on screen."
        } else {
            "Active app: $packageName\n" + lines.joinToString("\n")
        }
    }

    fun toHumanSummary(): String {
        val readableLabels = nodes
            .mapNotNull { it.text.ifBlank { it.contentDescription }.takeIf(String::isNotBlank) }
            .distinct()
            .take(18)
        return if (readableLabels.isEmpty()) {
            "Active app: $packageName (${nodes.size} UI nodes detected, no visible text)."
        } else {
            "Active app: $packageName. Visible elements: ${readableLabels.joinToString(" | ")}"
        }
    }
}

class JarvisAccessibilityService : AccessibilityService() {

    lateinit var gestureEngine: GestureEngine
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        gestureEngine = GestureEngine(this)
        instance = this
        _isServiceRunning.value = true
        _activePackageName.value = rootInActiveWindow?.packageName?.toString().orEmpty()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _activePackageName.value = pkg
        }
    }

    override fun onInterrupt() {
        // Required override; service remains bound unless user disables it.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        _isServiceRunning.value = false
    }

    /**
     * Captures a compressed UI tree by stripping empty/invisible layout containers
     * and keeping only meaningful text, input, scrollable, or clickable nodes with (x, y) coords.
     */
    fun readScreenHierarchy(maxNodes: Int = 72): ScreenHierarchySnapshot? {
        val root = rootInActiveWindow ?: return null
        val bounds = gestureEngine.getScreenBounds()
        val nodes = mutableListOf<ScreenNodeModel>()
        val seenKeys = HashSet<String>(maxNodes)
        val pkgName = root.packageName?.toString() ?: _activePackageName.value.ifBlank { "unknown" }

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 22 || nodes.size >= maxNodes) return
            if (!node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val inBounds = rect.width() > 6 &&
                rect.height() > 6 &&
                rect.right > 0 &&
                rect.bottom > 0 &&
                rect.left < bounds.widthPx &&
                rect.top < bounds.heightPx

            val text = node.text?.toString()?.trim().orEmpty().take(120)
            val desc = node.contentDescription?.toString()?.trim().orEmpty().take(120)
            val shortId = node.viewIdResourceName?.substringAfterLast("/")?.trim().orEmpty()
            val shortClass = node.className?.toString()?.substringAfterLast(".")?.trim().orEmpty()

            val isPureContainer = shortClass.endsWith("Layout") ||
                shortClass == "ViewGroup" ||
                shortClass == "View" ||
                shortClass == "FrameLayout" ||
                shortClass == "LinearLayout" ||
                shortClass == "RecyclerView"

            val hasContent = text.isNotEmpty() || desc.isNotEmpty()
            val isInputOrAction = node.isEditable || (node.isClickable && (!isPureContainer || hasContent))
            val isActionableScroll = node.isScrollable && hasContent

            if (inBounds && (hasContent || isInputOrAction || isActionableScroll)) {
                val cx = rect.centerX().coerceIn(0, bounds.widthPx)
                val cy = rect.centerY().coerceIn(0, bounds.heightPx)
                val dedupKey = "${text.ifBlank { desc }}@${cx / 16}_${cy / 16}_${node.isEditable}"
                if (seenKeys.add(dedupKey)) {
                    nodes.add(
                        ScreenNodeModel(
                            index = nodes.size,
                            text = text,
                            contentDescription = desc,
                            viewId = shortId,
                            className = shortClass,
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            scrollable = node.isScrollable,
                            checked = node.isChecked,
                            centerX = cx,
                            centerY = cy,
                            bounds = rect
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                if (nodes.size >= maxNodes) break
                traverse(node.getChild(i), depth + 1)
            }
        }

        traverse(root, 0)
        val snapshot = ScreenHierarchySnapshot(
            packageName = pkgName,
            screenWidth = bounds.widthPx,
            screenHeight = bounds.heightPx,
            nodes = nodes
        )
        _lastScreenSummary.value = snapshot.toHumanSummary()
        return snapshot
    }

    suspend fun tapByText(targetText: String): Boolean {
        val query = targetText.trim()
        if (query.isEmpty()) return false
        val root = rootInActiveWindow ?: return false

        val bestMatch = findBestMatchingNode(root, query) ?: return false

        var clickableNode: AccessibilityNodeInfo? = bestMatch
        var hops = 0
        while (clickableNode != null && !clickableNode.isClickable && hops < 6) {
            clickableNode = clickableNode.parent
            hops++
        }

        if (clickableNode != null && clickableNode.isClickable) {
            if (clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        val rect = Rect()
        bestMatch.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return gestureEngine.tap(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    suspend fun performDirectionalScroll(direction: String): Boolean {
        val normalized = direction.trim().uppercase()
        val root = rootInActiveWindow

        if (root != null && (normalized == "SCROLL_DOWN" || normalized == "DOWN" || normalized == "SCROLL_UP" || normalized == "UP")) {
            val scrollableNode = findFirstScrollableNode(root)
            if (scrollableNode != null) {
                val action = if (normalized.contains("DOWN")) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (scrollableNode.performAction(action)) {
                    return true
                }
            }
        }

        val swipeDir = when {
            normalized.contains("DOWN") -> SwipeDirection.UP
            normalized.contains("UP") -> SwipeDirection.DOWN
            normalized.contains("LEFT") -> SwipeDirection.RIGHT
            normalized.contains("RIGHT") -> SwipeDirection.LEFT
            else -> SwipeDirection.UP
        }
        return gestureEngine.swipeDirection(swipeDir)
    }

    suspend fun typeText(
        text: String,
        targetFieldHint: String? = null,
        submitAfterTyping: Boolean = false
    ): Boolean {
        val root = rootInActiveWindow ?: return false

        val targetNode = when {
            !targetFieldHint.isNullOrBlank() -> {
                findBestMatchingNode(root, targetFieldHint, requireEditable = true)
                    ?: findBestMatchingNode(root, targetFieldHint, requireEditable = false)
            }
            else -> {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findFirstEditableNode(root)
            }
        } ?: return false

        val rect = Rect()
        targetNode.getBoundsInScreen(rect)
        if (!targetNode.isFocused) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (rect.width() > 0 && rect.height() > 0) {
                gestureEngine.tap(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var entered = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (!entered) {
            // Clipboard fallback for custom input views that ignore ACTION_SET_TEXT
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("jarvis_input", text))
            entered = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        if (entered && submitAfterTyping && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return entered
    }

    fun performGlobalSystemAction(actionName: String): Boolean {
        val actionId = when (actionName.trim().uppercase()) {
            "HOME" -> GLOBAL_ACTION_HOME
            "BACK" -> GLOBAL_ACTION_BACK
            "RECENTS", "RECENT", "OVERVIEW" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS", "NOTIFICATION_SHADE" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "POWER", "POWER_DIALOG" -> GLOBAL_ACTION_POWER_DIALOG
            "SCREENSHOT", "TAKE_SCREENSHOT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    GLOBAL_ACTION_TAKE_SCREENSHOT
                } else {
                    return false
                }
            }
            "LOCK", "LOCK_SCREEN" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    GLOBAL_ACTION_LOCK_SCREEN
                } else {
                    return false
                }
            }
            else -> return false
        }
        return performGlobalAction(actionId)
    }

    private fun findBestMatchingNode(
        root: AccessibilityNodeInfo,
        query: String,
        requireEditable: Boolean = false
    ): AccessibilityNodeInfo? {
        val lower = query.lowercase()
        var exactMatch: AccessibilityNodeInfo? = null
        var partialMatch: AccessibilityNodeInfo? = null

        fun search(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24 || exactMatch != null) return
            if (!node.isVisibleToUser) return

            val text = node.text?.toString()?.trim().orEmpty().lowercase()
            val desc = node.contentDescription?.toString()?.trim().orEmpty().lowercase()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty().lowercase()
            } else {
                ""
            }

            val passesEditable = !requireEditable || node.isEditable
            if (passesEditable) {
                if (text == lower || desc == lower || hint == lower) {
                    exactMatch = node
                    return
                }
                if (partialMatch == null && (text.contains(lower) || desc.contains(lower) || hint.contains(lower))) {
                    partialMatch = node
                }
            }

            for (i in 0 until node.childCount) {
                search(node.getChild(i), depth + 1)
                if (exactMatch != null) return
            }
        }

        search(root, 0)
        return exactMatch ?: partialMatch
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun search(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24 || found != null) return
            if (!node.isVisibleToUser) return
            if (node.isEditable) {
                found = node
                return
            }
            for (i in 0 until node.childCount) {
                search(node.getChild(i), depth + 1)
                if (found != null) return
            }
        }
        search(root, 0)
        return found
    }

    private fun findFirstScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun search(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || found != null) return
            if (!node.isVisibleToUser) return
            if (node.isScrollable) {
                found = node
                return
            }
            for (i in 0 until node.childCount) {
                search(node.getChild(i), depth + 1)
                if (found != null) return
            }
        }
        search(root, 0)
        return found
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _activePackageName = MutableStateFlow("")
        val activePackageName: StateFlow<String> = _activePackageName.asStateFlow()

        private val _lastScreenSummary = MutableStateFlow("Awaiting screen scan…")
        val lastScreenSummary: StateFlow<String> = _lastScreenSummary.asStateFlow()

        // Multi-step autonomous phone execution state & emergency stop latch
        private val stopRequestedFlag = AtomicBoolean(false)

        private val _isMultiStepRunning = MutableStateFlow(false)
        val isMultiStepRunning: StateFlow<Boolean> = _isMultiStepRunning.asStateFlow()

        private val _currentStepStatus = MutableStateFlow("")
        val currentStepStatus: StateFlow<String> = _currentStepStatus.asStateFlow()

        fun isStopRequested(): Boolean = stopRequestedFlag.get()

        fun resetMultiStepSignal() {
            stopRequestedFlag.set(false)
        }

        fun requestStopMultiStep() {
            stopRequestedFlag.set(true)
            _currentStepStatus.value = "Stopping autonomous execution…"
        }

        fun updateMultiStepStatus(running: Boolean, status: String = "") {
            _isMultiStepRunning.value = running
            _currentStepStatus.value = status
            if (!running) {
                stopRequestedFlag.set(false)
            }
        }
    }
}
