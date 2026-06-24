package com.yourcompany.floatscan

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class InjectService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var weChatPrepared = false

    private val injectRunnable = Runnable { runInjectAttempt() }

    private fun runInjectAttempt() {
        val text = ScanResultBus.pendingText ?: return
        if (injectText(text)) {
            resetInjectState()
            ScanResultBus.notifyInjectResult(true)
        } else if (retryCount++ < MAX_RETRIES) {
            handler.postDelayed(injectRunnable, retryDelayMs())
        } else {
            copyToClipboard(text)
            resetInjectState()
            ScanResultBus.notifyInjectResult(false)
        }
    }

    private fun resetInjectState() {
        ScanResultBus.pendingText = null
        ScanResultBus.clearTarget()
        retryCount = 0
        weChatPrepared = false
    }

    private fun retryDelayMs(): Long {
        return if (ScanResultBus.targetPackage == WECHAT_PACKAGE) 450L else RETRY_DELAY_MS
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        scheduleInject()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || ScanResultBus.pendingText == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            return
        }
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

    fun captureTargetApp() {
        ScanResultBus.targetPackage = findForegroundThirdPartyPackage()
    }

    fun scheduleInject() {
        if (ScanResultBus.pendingText == null) return
        handler.removeCallbacks(injectRunnable)
        retryCount = 0
        weChatPrepared = false
        val delay = if (ScanResultBus.targetPackage == WECHAT_PACKAGE) 700L else INITIAL_DELAY_MS
        handler.postDelayed(injectRunnable, delay)
    }

    fun injectText(text: String): Boolean {
        if (ScanResultBus.targetPackage == WECHAT_PACKAGE) {
            if (tryInjectWeChat(text)) return true
        }

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

    /**
     * 微信专用注入：先点击聚焦输入框，再通过全局粘贴填入（微信常拦截 SET_TEXT）。
     */
    private fun tryInjectWeChat(text: String): Boolean {
        copyToClipboard(text)
        val inputs = collectWeChatInputNodes()
        if (inputs.isEmpty()) return false

        try {
            if (!weChatPrepared) {
                for (node in inputs) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                weChatPrepared = true
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (performGlobalAction(GLOBAL_ACTION_PASTE)) return true
            }

            for (node in inputs) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
                if (setTextOnNode(node, text)) return true
            }
        } finally {
            inputs.forEach { it.recycle() }
        }
        return false
    }

    private fun collectWeChatInputNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seen = mutableSetOf<String>()

        val roots = collectTargetRoots()
        try {
            for (root in roots) {
                if (root.packageName?.toString() != WECHAT_PACKAGE) continue

                for (viewId in WECHAT_INPUT_IDS) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: continue
                    for (node in nodes) {
                        val key = node.viewIdResourceName + "@" + node.hashCode()
                        if (seen.add(key)) {
                            result.add(AccessibilityNodeInfo.obtain(node))
                        }
                    }
                    nodes.forEach { it.recycle() }
                }

                collectWeChatInputRecursive(root, result, seen)
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return result
    }

    private fun collectWeChatInputRecursive(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        seen: MutableSet<String>
    ) {
        if (isWeChatInputCandidate(node)) {
            val key = (node.viewIdResourceName ?: node.className?.toString() ?: "") + "@" + node.hashCode()
            if (seen.add(key)) {
                out.add(AccessibilityNodeInfo.obtain(node))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectWeChatInputRecursive(child, out, seen)
            child.recycle()
        }
    }

    private fun isWeChatInputCandidate(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName ?: ""
        if (viewId.startsWith(WECHAT_PACKAGE) &&
            (viewId.contains("input", ignoreCase = true) ||
                viewId.contains("edit", ignoreCase = true) ||
                viewId.contains("chat", ignoreCase = true))
        ) {
            return true
        }
        val className = node.className?.toString() ?: return false
        if (!className.contains("Edit", ignoreCase = true) &&
            !className.contains("MM", ignoreCase = true)
        ) {
            return false
        }
        if (node.isEditable) return true
        if (className.contains("EditText", ignoreCase = true) && node.isFocusable) return true
        if (className.contains("MMEdit", ignoreCase = true)) return true
        val desc = node.contentDescription?.toString() ?: ""
        return desc.contains("输入") || desc.contains("发消息") || desc.contains("Type")
    }

    private fun findForegroundThirdPartyPackage(): String? {
        val active = rootInActiveWindow
        if (active != null) {
            val pkg = active.packageName?.toString()
            active.recycle()
            if (!pkg.isNullOrEmpty() && isTargetPackage(pkg)) return pkg
        }

        windows?.forEach { window ->
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
        val seen = mutableSetOf<Int>()

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
            val key = System.identityHashCode(root)
            if (seen.add(key)) {
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

        for (viewId in BROWSER_INPUT_IDS) {
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
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val MAX_RETRIES = 18
        private const val RETRY_DELAY_MS = 350L
        private const val INITIAL_DELAY_MS = 500L

        private val WECHAT_INPUT_IDS = listOf(
            "com.tencent.mm:id/bkk",
            "com.tencent.mm:id/al_",
            "com.tencent.mm:id/o4",
            "com.tencent.mm:id/cdk",
            "com.tencent.mm:id/b4e",
            "com.tencent.mm:id/pi",
            "com.tencent.mm:id/chatting_content_et",
            "com.tencent.mm:id/auj",
            "com.tencent.mm:id/kao",
            "com.tencent.mm:id/kii",
            "com.tencent.mm:id/al7",
            "com.tencent.mm:id/bhn",
            "com.tencent.mm:id/aks",
            "com.tencent.mm:id/szu",
            "com.tencent.mm:id/m7b",
            "com.tencent.mm:id/bkk",
            "com.tencent.mm:id/p5g"
        )

        private val BROWSER_INPUT_IDS = listOf(
            "com.quark.browser:id/search_input",
            "com.quark.browser:id/url_bar",
            "com.quark.browser:id/et_input"
        )

        @Volatile
        var instance: InjectService? = null
    }
}
