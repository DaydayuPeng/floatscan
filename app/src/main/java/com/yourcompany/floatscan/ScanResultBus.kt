package com.yourcompany.floatscan

/**
 * 扫码结果与注入状态的全局事件总线。
 */
object ScanResultBus {

    @Volatile
    var pendingText: String? = null

    /** 点击悬浮球时记录的前台应用包名（微信、夸克等） */
    @Volatile
    var targetPackage: String? = null

    var onInjectResult: ((success: Boolean) -> Unit)? = null

    fun deliverScanResult(text: String) {
        pendingText = text
        InjectService.instance?.scheduleInject()
    }

    fun notifyInjectResult(success: Boolean) {
        onInjectResult?.invoke(success)
    }

    fun clearTarget() {
        targetPackage = null
    }
}
