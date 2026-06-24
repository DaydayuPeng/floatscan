package com.yourcompany.floatscan

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class InjectService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    private val injectRunnable = Runnable { runInjectAttempt() }

    private fun runInjectAttempt() {
        val text = ScanResultBus.pendingText ?: return
        if (injectText(text)) {
            ScanResultBus.pendingText = null
            ScanResultBus.clearTarget()
            retryCount = 0
            ScanResultBus.notifyInjectResult(true)
        } else if (retryCount++ < MAX_RETRIES) {
            handler.postDelayed(injectRunnable, RETRY_DELAY_MS)
        } else {
            copyToClipboard(text)
            ScanResultBus.pendingText = null
            ScanResultBus.clearTarget()
            retryCount = 0
            ScanResultBus.notifyInjectResult(false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        scheduleInject()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || ScanResultBus.pendingText == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val target = ScanResultBus.targetPackage
        if (target != null && pkg != target) return
        handler.removeCallbacks(injectRunnable)
        handler.postDelayed(injectRunnable, 200)
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        handler.removeCallbacks(injectRunnable)
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /** 点击悬浮球时调用，记录当前前台应用 */
    fun captureTargetApp() {
        ScanResultBus.targetPackage = findForegroundThirdPartyPackage()
    }

    fun scheduleInject() {
        if (ScanResultBus.pendingText == null) return
        handler.removeCallbacks(injectRunnable)
        retryCount = 0
        handler.postDelayed(injectRunnable, INITIAL_DELAY_MS)
    }

    fun injectText(text: String): Boolean {
        val roots = collectTargetRoots()
        if (roots.isEmpty()) return false

        try {
            for (root in roots) {
                if (tryInjectIntoRoot(root, text)) return true
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return false
    }

    private fun findForegroundThirdPartyPackage(): String? {
        val active = rootInActiveWindow
        if (active != null) {
            val pkg = active.packageName?.toString()
            active.recycle()
            if (!pkg.isNullOrEmpty() && isTargetPackage(pkg)) return pkg
        }

        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            val pkg = root.packageName?.toString()
            root.recycle()
            if (!pkg.isNullOrEmpty() && isTargetPackage(pkg)) return pkg
        }
        return null
    }

    private fun isTargetPackage(pkg: String): Boolean {
        return pkg != packageName &&
            pkg != "com.android.systemui" &&
            pkg != "com.android.launcher" &&
            !pkg.contains("launcher", ignoreCase = true)
    }

    private fun collectTargetRoots(): List<AccessibilityNodeInfo> {
        val targetPkg = ScanResultBus.targetPackage
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val seen = mutableSetOf<String>()

        windows?.forEach { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
            val root = window.root ?: return@forEach
            val pkg = root.packageName?.toString() ?: ""
            if (!isTargetPackage(pkg)) {
                root.recycle()
                return@forEach
            }
            if (targetPkg != null && pkg != targetPkg) {
                root.recycle()
                return@forEach
            }
            if (seen.add(pkg)) {
                roots.add(AccessibilityNodeInfo.obtain(root))
            }
            root.recycle()
        }

        return roots
    }

    private fun tryInjectIntoRoot(root: AccessibilityNodeInfo, text: String): Boolean {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val ok = injectIntoNode(focused, text)
            focused.recycle()
            if (ok) return true
        }

        for (viewId in KNOWN_INPUT_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: continue
            try {
                for (node in nodes) {
                    if (injectIntoNode(node, text)) return true
                }
            } finally {
                nodes.forEach { it.recycle() }
            }
        }

        val editable = findBestEditableNode(root)
        if (editable != null) {
            val ok = injectIntoNode(editable, text)
            editable.recycle()
            if (ok) return true
        }

        return false
    }

    private fun injectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        if (setTextOnNode(node, text)) return true
        return pasteIntoNode(node, text)
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pasteIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        copyToClipboard(text)
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        return false
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("floatscan", text))
    }

    private fun findBestEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byAndroidId = root.findAccessibilityNodeInfosByViewId("android:id/edit")
        if (!byAndroidId.isNullOrEmpty()) {
            val node = byAndroidId.first()
            byAndroidId.drop(1).forEach { it.recycle() }
            return node
        }
        return findEditableRecursive(root)
    }

    private fun findEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isInputCandidate(node)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableRecursive(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun isInputCandidate(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString() ?: return false
        if (className.contains("EditText", ignoreCase = true)) return true
        if (className.contains("WebView", ignoreCase = true) && node.isFocusable) return true
        if (className.contains("Input", ignoreCase = true) && node.isFocusable) return true
        val viewId = node.viewIdResourceName ?: return false
        return viewId.contains("input", ignoreCase = true) ||
            viewId.contains("edit", ignoreCase = true) ||
            viewId.contains("url", ignoreCase = true)
    }

    companion object {
        private const val MAX_RETRIES = 15
        private const val RETRY_DELAY_MS = 350L
        private const val INITIAL_DELAY_MS = 500L

        private val KNOWN_INPUT_IDS = listOf(
            // 微信
            "com.tencent.mm:id/bkk",
            "com.tencent.mm:id/al_",
            "com.tencent.mm:id/o4",
            "com.tencent.mm:id/cdk",
            "com.tencent.mm:id/b4e",
            "com.tencent.mm:id/pi",
            // 夸克浏览器
            "com.quark.browser:id/search_input",
            "com.quark.browser:id/url_bar",
            "com.quark.browser:id/et_input"
        )

        @Volatile
        var instance: InjectService? = null
    }
}
