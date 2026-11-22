package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
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
     * Ultra-compact JSON schema (~80% token reduction):
     * Keeps ONLY the top 25 visible interactive/readable elements with ONLY id, text, x, y.
     */
    fun toCompactJson(maxElements: Int = MAX_COMPACT_NODES): String {
        val root = JSONObject()
        root.put("pkg", packageName)
        root.put("w", screenWidth)
        root.put("h", screenHeight)

        val prioritized = nodes
            .filter { node ->
                val label = node.text.ifBlank { node.contentDescription }.trim()
                label.isNotEmpty() || node.viewId.isNotBlank() || node.clickable || node.editable
            }
            .sortedByDescending { node ->
                var score = 0
                if (node.editable) score += 50
                if (node.clickable) score += 35
                if (node.text.isNotBlank()) score += 25
                if (node.contentDescription.isNotBlank()) score += 15
                if (node.viewId.isNotBlank()) score += 10
                if (node.scrollable) score += 5
                score
            }
            .take(maxElements)

        val arr = JSONArray()
        for (node in prioritized) {
            val label = node.text.ifBlank { node.contentDescription }.trim().take(64)
            val obj = JSONObject()
            if (node.viewId.isNotBlank()) obj.put("id", node.viewId.take(36))
            if (label.isNotEmpty()) obj.put("text", label)
            obj.put("x", node.centerX)
            obj.put("y", node.centerY)
            arr.put(obj)
        }
        root.put("el", arr)
        return root.toString()
    }

    companion object {
        const val MAX_COMPACT_NODES = 25
    }

    fun toPlainReadableText(): String {
        val lines = nodes
            .mapNotNull { it.text.ifBlank { it.contentDescription }.takeIf(String::isNotBlank) }
            .distinct()
            .take(55)
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
        if (!pkg.isNullOrBlank() && pkg != packageName) {
            _activePackageName.value = pkg
        }
    }

    override fun onInterrupt() {
        // Required override; service stays active until disabled by user.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        _isServiceRunning.value = false
    }

    /**
     * Collects rootInActiveWindow plus all interactive application/system windows
     * so dialogs, sheets, popups, and keyboards are always captured accurately.
     */
    private fun collectInteractiveRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val seenWindowIds = HashSet<Int>()

        rootInActiveWindow?.let { active ->
            roots.add(active)
            seenWindowIds.add(active.windowId)
        }

        val allWindows = runCatching { windows }.getOrNull().orEmpty()
        for (win in allWindows) {
            val type = win.type
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
            ) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            // Skip our own floating Stop HUD pill when inspecting other apps
            val winPkg = root.packageName?.toString().orEmpty()
            if (winPkg == packageName && roots.any { it.packageName?.toString() != packageName }) {
                continue
            }
            if (seenWindowIds.add(root.windowId)) {
                roots.add(root)
            }
        }
        return roots
    }

    /**
     * Traverses rootInActiveWindow AND all interactive windows, extracting visible nodes
     * with rect.width() > 0 && rect.height() > 0 into compact JSON.
     */
    fun readScreenHierarchy(maxNodes: Int = 85): ScreenHierarchySnapshot? {
        val roots = collectInteractiveRoots()
        if (roots.isEmpty()) return null

        val bounds = gestureEngine.getScreenBounds()
        val nodes = mutableListOf<ScreenNodeModel>()
        val seenKeys = HashSet<String>(maxNodes)
        val pkgName = roots.firstOrNull()?.packageName?.toString()
            ?: _activePackageName.value.ifBlank { "unknown" }

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 26 || nodes.size >= maxNodes) return
            if (!node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val hasValidBounds = rect.width() > 0 &&
                rect.height() > 0 &&
                rect.right > 0 &&
                rect.bottom > 0 &&
                rect.left < bounds.widthPx &&
                rect.top < bounds.heightPx

            val text = node.text?.toString()?.trim().orEmpty().take(120)
            val desc = node.contentDescription?.toString()?.trim().orEmpty().take(120)
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty().take(80)
            } else ""
            val effectiveText = text.ifBlank { hint }
            val shortId = node.viewIdResourceName?.substringAfterLast("/")?.trim().orEmpty()
            val shortClass = node.className?.toString()?.substringAfterLast(".")?.trim().orEmpty()

            val isPureContainer = shortClass.endsWith("Layout") ||
                shortClass == "ViewGroup" ||
                shortClass == "View" ||
                shortClass == "FrameLayout" ||
                shortClass == "LinearLayout" ||
                shortClass == "RecyclerView"

            val hasContent = effectiveText.isNotEmpty() || desc.isNotEmpty() || shortId.isNotEmpty()
            val isInteractive = node.isEditable || node.isClickable || node.isCheckable || node.isScrollable

            if (hasValidBounds && (effectiveText.isNotEmpty() || desc.isNotEmpty() || (isInteractive && (!isPureContainer || hasContent)))) {
                val cx = rect.centerX().coerceIn(2, bounds.widthPx - 2)
                val cy = rect.centerY().coerceIn(2, bounds.heightPx - 2)
                val dedupKey = "${effectiveText.ifBlank { desc }.ifBlank { shortId }}@${cx / 14}_${cy / 14}_${node.isEditable}"
                if (seenKeys.add(dedupKey)) {
                    nodes.add(
                        ScreenNodeModel(
                            index = nodes.size,
                            text = effectiveText,
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

        for (root in roots) {
            if (nodes.size >= maxNodes) break
            traverse(root, 0)
        }

        val snapshot = ScreenHierarchySnapshot(
            packageName = pkgName,
            screenWidth = bounds.widthPx,
            screenHeight = bounds.heightPx,
            nodes = nodes
        )
        _lastScreenSummary.value = snapshot.toHumanSummary()
        return snapshot
    }

    /**
     * Physical finger tap gesture using Path.moveTo(x, y) (80ms duration) via dispatchGesture
     * awaited cleanly with CompletableDeferred so multi-step automation waits until touch finishes.
     */
    suspend fun tapXY(x: Float, y: Float, durationMs: Long = 80L): Boolean {
        val bounds = gestureEngine.getScreenBounds()
        val cx = x.coerceIn(2f, (bounds.widthPx - 2).toFloat())
        val cy = y.coerceIn(2f, (bounds.heightPx - 2).toFloat())
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 300L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        if (!dispatched) return false
        return withTimeoutOrNull(4_000L) { deferred.await() } ?: false
    }

    /**
     * Dual-Action Reliable Touch Execution:
     * 1. Finds matching node across all interactive windows by text/contentDescription/hintText.
     * 2. Tries AccessibilityNodeInfo.ACTION_CLICK (climbing up to parent if needed).
     * 3. If ACTION_CLICK fails OR node is inside WebView/Compose surface without standard click handler,
     *    immediately executes physical tapXY(rect.centerX(), rect.centerY()) on screen bounds!
     */
    suspend fun tapByText(targetText: String): Boolean {
        val query = targetText.trim()
        if (query.isEmpty()) return false
        val roots = collectInteractiveRoots()
        if (roots.isEmpty()) return false

        var bestMatch: AccessibilityNodeInfo? = null
        for (root in roots) {
            bestMatch = findBestMatchingNode(root, query)
            if (bestMatch != null) break
        }
        val matched = bestMatch ?: return false

        val rect = Rect()
        matched.getBoundsInScreen(rect)
        val hasBounds = rect.width() > 0 && rect.height() > 0
        val clsName = matched.className?.toString().orEmpty()
        val isWebOrCompose = clsName.contains("WebView", ignoreCase = true) ||
            clsName.equals("android.view.View", ignoreCase = true)

        var clickableNode: AccessibilityNodeInfo? = matched
        var hops = 0
        while (clickableNode != null && !clickableNode.isClickable && hops < 6) {
            clickableNode = clickableNode.parent
            hops++
        }

        if (!isWebOrCompose && clickableNode != null && clickableNode.isClickable) {
            if (clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        if (hasBounds) {
            val tapped = tapXY(rect.centerX().toFloat(), rect.centerY().toFloat())
            if (tapped) return true
        }

        if (clickableNode != null && clickableNode.isClickable) {
            return clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 320L): Boolean {
        return gestureEngine.dragAnyDirection(x1, y1, x2, y2, durationMs)
    }

    suspend fun scrollDown(): Boolean = performDirectionalScroll("SCROLL_DOWN")

    suspend fun scrollUp(): Boolean = performDirectionalScroll("SCROLL_UP")

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 750L): Boolean {
        return gestureEngine.longPress(x, y, durationMs)
    }

    suspend fun performDiagnosticTestTap(): Boolean {
        val bounds = gestureEngine.getScreenBounds()
        // Dispatch a harmless physical tap at the neutral top-center status bar margin
        return tapXY(bounds.widthPx * 0.5f, (bounds.heightPx * 0.03f).coerceAtLeast(18f), durationMs = 80L)
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
        val roots = collectInteractiveRoots()
        if (roots.isEmpty()) return false

        var targetNode: AccessibilityNodeInfo? = null
        for (root in roots) {
            targetNode = when {
                !targetFieldHint.isNullOrBlank() -> {
                    findBestMatchingNode(root, targetFieldHint, requireEditable = true)
                        ?: findBestMatchingNode(root, targetFieldHint, requireEditable = false)
                }
                else -> {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?: findFirstEditableNode(root)
                }
            }
            if (targetNode != null) break
        }
        val editableNode = targetNode ?: return false

        val rect = Rect()
        editableNode.getBoundsInScreen(rect)
        if (!editableNode.isFocused) {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (rect.width() > 0 && rect.height() > 0) {
                tapXY(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var entered = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (!entered) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("jarvis_input", text))
            entered = editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        if (entered && submitAfterTyping) {
            var submitted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                submitted = runCatching {
                    editableNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }.getOrDefault(false)
            }
            if (!submitted) {
                // Fallback: tap a visible Search / Send / Go button on screen or keyboard
                val submitLabels = listOf("Search", "Send", "Go", "Done", "Submit")
                for (label in submitLabels) {
                    if (tapByText(label)) break
                }
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
            if (node == null || depth > 26 || exactMatch != null) return
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
            if (node == null || depth > 26 || found != null) return
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
            if (node == null || depth > 22 || found != null) return
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
