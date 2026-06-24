package com.yourcompany.floatscan

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

class InjectService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        injectPendingText()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅用于保持服务活跃，注入由 ScanResultBus 主动触发
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun injectPendingText() {
        val text = ScanResultBus.pendingText ?: return
        ScanResultBus.pendingText = null
        val success = injectText(text)
        ScanResultBus.notifyInjectResult(success)
    }

    fun injectText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focusedNode != null && setTextOnNode(focusedNode, text)) {
            focusedNode.recycle()
            root.recycle()
            return true
        }
        focusedNode?.recycle()

        val editableNode = findFirstEditableNode(root)
        val success = if (editableNode != null) {
            setTextOnNode(editableNode, text).also { editableNode.recycle() }
        } else {
            false
        }
        root.recycle()
        return success
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable && node.className?.contains("EditText") != true) {
            // 部分 WebView 输入框 isEditable 为 false 但仍支持 SET_TEXT
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byViewId = root.findAccessibilityNodeInfosByViewId("android:id/edit")
        if (!byViewId.isNullOrEmpty()) {
            val node = byViewId.first()
            byViewId.drop(1).forEach { it.recycle() }
            return node
        }

        return findEditableRecursive(root)
    }

    private fun findEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
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

    companion object {
        @Volatile
        var instance: InjectService? = null
    }
}
