package com.yourcompany.floatscan

/**
 * 扫码结果与注入状态的全局事件总线。
 */
object ScanResultBus {

    @Volatile
    var pendingText: String? = null

    var onInjectResult: ((success: Boolean) -> Unit)? = null

    fun deliverScanResult(text: String) {
        pendingText = text
        InjectService.instance?.scheduleInject()
    }

    fun notifyInjectResult(success: Boolean) {
        onInjectResult?.invoke(success)
    }
}
