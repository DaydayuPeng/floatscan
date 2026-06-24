明白！既然要适配**全安卓生态**（华为、小米、OPPO、vivo、三星、荣耀及原生AOSP），那份仅针对OPPO的文档就必须升级为**“通用兼容层+厂商专项适配”**架构。

我重写了这份开发文档。核心改动点在于：**不再硬编码OPPO的特定Intent，而是采用标准Android API + 厂商包名动态探测机制**。请把下面这份**V2.0通用版**复制给Cursor。

---

# 项目技术规格说明书 V2.0：悬浮扫码自动输入器（全安卓通用版）

## 1. 项目概述
- **应用名称**：FloatScan Injector
- **包名**：`com.yourcompany.floatscan`
- **最低 SDK**：Android 7.0 (API 24)
- **目标 SDK**：Android 14 (API 34)
- **核心目标**：在屏幕顶层悬浮扫码按钮，扫描快递一维码，利用无障碍服务将字符串直接注入当前输入框。
- **兼容性范围**：必须兼容 **华为(HarmonyOS/EMUI)、小米(MIUI/HyperOS)、OPPO(ColorOS)、vivo(Funtouch/OriginOS)、三星(OneUI) 及原生安卓 (AOSP)**。

---

## 2. 技术栈与依赖 (Gradle)
```gradle
dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    
    // ZXing 扫码库
    implementation 'com.journeyapps:zxing-android-embedded:4.3.0'
    implementation 'com.google.zxing:core:3.5.2'

    // 协程
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
}
```

---

## 3. 核心模块与类职责

| 模块名称 | 类名 | 职责描述 |
| :--- | :--- | :--- |
| **权限统筹引导** | `MainActivity` | 启动页，动态申请相机权限；引导开启悬浮窗(`SYSTEM_ALERT_WINDOW`)和无障碍(`ACCESSIBILITY`)；检测厂商并给出专属设置指引。 |
| **悬浮窗服务** | `FloatButtonService` | 继承 `Service`，全局显示悬浮球，支持拖拽，点击后启动扫码。 |
| **扫码界面** | `ScanActivity` | 透明主题，调用ZXing，**限定仅识别一维码**（CODE_128, CODE_39, EAN-13, ITF）。 |
| **文本注入引擎** | `InjectService` | 继承 `AccessibilityService`，核心执行 `ACTION_SET_TEXT` 注入。 |
| **厂商辅助工具** | `VendorHelper` | **（新增核心类）** 静态工具类，用于检测当前手机品牌，并生成对应的“跳转设置页”Intent或用户指引文案。 |

---

## 4. 核心实现逻辑（Cursor 必须遵循）

### 4.1 悬浮窗服务 (`FloatButtonService`)
- **类型**：使用 `WindowManager` 添加全局悬浮窗。
- **关键Flag（防抢焦点）**：`LayoutParams.FLAG_NOT_FOCUSABLE` 和 `FLAG_WATCH_OUTSIDE_TOUCH`，确保点击悬浮球不导致输入框失焦。
- **点击响应**：启动 `ScanActivity`，必须添加 `FLAG_ACTIVITY_NEW_TASK` 和 `FLAG_ACTIVITY_NO_ANIMATION` 以减少卡顿感。

### 4.2 扫码界面 (`ScanActivity`) —— 一维码强制设定
- **主题**：`android:theme="@style/Theme.AppCompat.Translucent"`（半透明，看到背后输入框更佳）。
- **ZXing配置（关键代码钩子）**：
  ```kotlin
  val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
      this[DecodeHintType.POSSIBLE_FORMATS] = listOf(
          BarcodeFormat.CODE_128,
          BarcodeFormat.CODE_39,
          BarcodeFormat.EAN_13,
          BarcodeFormat.ITF,
          BarcodeFormat.CODABAR  // 极兔、德邦常用
      )
      this[DecodeHintType.CHARACTER_SET] = "UTF-8"
      this[DecodeHintType.TRY_HARDER] = true // 提升低分辨率扫码率
  }
  ```
- **解码回调**：获取字符串后立即 `finish()`，通过全局单例或EventBus将文本发送给 `InjectService`。

### 4.3 无障碍注入引擎 (`InjectService`) —— **通用注入逻辑**
- **核心方法（必须包含兜底方案）**：
  ```kotlin
  fun injectText(text: String) {
      val root = rootInActiveWindow ?: return
      val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      
      if (focusedNode != null) {
          // 优先方案：ACTION_SET_TEXT（兼容 Android 8.0~14 所有主流机型）
          val args = Bundle()
          args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
          focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
      } else {
          // 兜底方案：如果没有焦点，尝试在当前窗口查找第一个可编辑文本框（针对部分WebView/H5场景）
          val editableNode = root.findAccessibilityNodeInfosByViewId("android:id/edit")?.firstOrNull()
          editableNode?.let {
              val args = Bundle()
              args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
              it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
          }
      }
  }
  ```

### 4.4 厂商专项适配模块 (`VendorHelper`) —— **解决碎片化核心**
Cursor 必须实现此类，用于处理不同厂商对“悬浮窗”和“后台省电”的差异化设置。

- **功能1：探测厂商**：读取 `Build.MANUFACTURER`（转为小写，如 `xiaomi`, `huawei`, `oppo`, `vivo`, `samsung`）。
- **功能2：生成引导Intent（带异常捕获）**：
  ```kotlin
  fun getBatteryOptimizationIntent(context: Context): Intent? {
      val manufacturer = Build.MANUFACTURER.lowercase()
      return when {
          // 小米/红米
          manufacturer.contains("xiaomi") -> {
              try { Intent("miui.intent.action.APP_PERM_EDITOR").apply { 
                  putExtra("extra_pkgname", context.packageName) 
              }} catch (e: Exception) { null }
          }
          // OPPO / 一加
          manufacturer.contains("oppo") || manufacturer.contains("oneplus") -> {
              try { Intent("com.coloros.safecenter.SAFECENTER") } catch (e: Exception) { null }
          }
          // vivo
          manufacturer.contains("vivo") -> {
              try { Intent("com.vivo.permissionmanager.action.BG_LAUNCH_MANAGER") } catch (e: Exception) { null }
          }
          // 华为 / 荣耀
          manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
              try { Intent("com.huawei.systemmanager.optimize.process.ProcessManagerActivity") } catch (e: Exception) { null }
          }
          else -> null
      }
  }
  ```
  **重要提示**：若上述特定Intent抛出 `ActivityNotFoundException`，则统一降级跳转到系统应用详情页（`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`），并弹出Toast提示用户手动关闭“省电策略”和“应用速冻”。

---

## 5. AndroidManifest.xml 关键声明
```xml
<!-- 基础权限 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<!-- 小米/华为部分机型需要 -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<application>
    <!-- 悬浮窗服务（必须带 foregroundServiceType 适配 Android 14） -->
    <service
        android:name=".FloatButtonService"
        android:enabled="true"
        android:exported="false"
        android:foregroundServiceType="mediaProjection" />
        <!-- 注意：如果不想申请 mediaProjection 权限导致复杂，可改用 specialUse 并配通知渠道，但 mediaProjection 存活率最高 -->
    
    <!-- 无障碍服务 -->
    <service
        android:name=".InjectService"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
        android:exported="true">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data
            android:name="android.accessibilityservice"
            android:resource="@xml/accessibility_service_config" />
    </service>

    <!-- 透明扫码 Activity -->
    <activity
        android:name=".ScanActivity"
        android:theme="@style/Theme.Transparent"
        android:screenOrientation="portrait"
        android:configChanges="orientation|screenSize" />
</application>
```

---

## 6. UI/UX 交互约束
- **悬浮球**：默认吸附在屏幕右侧中间，尺寸 48dp，半透明蓝色背景。长按可拖拽移动位置（松手后自动吸附边缘）。
- **扫码界面**：取景框为正方形，四角带有绿色扫描角。中间显示动态扫描线。
- **反馈机制**：
  - 扫码成功：手机震动 50ms，悬浮球短暂闪烁绿色并自动隐藏（防止遮挡后续输入）。
  - 注入失败：悬浮球变红 1 秒，并弹出短暂 Toast 提示“未找到输入框，请重试”。

---

## 7. 兼容性测试注意事项（请 Cursor 在生成代码时注意逻辑）
1. **Android 10+ 的悬浮窗**：必须使用 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 引导用户授权，且在 `onActivityResult` 中捕获结果。
2. **Android 14 (API 34)**：前台服务必须声明 `FOREGROUND_SERVICE_TYPE`，本方案中使用 `mediaProjection`（无需实际录屏，仅用于保活）。若不想要此权限，可替换为 `dataSync` 并配一个常驻通知。
3. **华为鸿蒙系统**：部分设备限制 `findFocus(AccessibilityNodeInfo.FOCUS_INPUT)` 返回 null，务必实现上面提到的“兜底查找第一个EditText”逻辑。

---

## 8. Cursor 执行指令
请 Cursor 基于上述规格，生成一个完整的 Kotlin Android 项目：

1. 创建标准项目结构，包含 `MainActivity`, `FloatButtonService`, `ScanActivity`, `InjectService` 和 `VendorHelper`。
2. 实现 `VendorHelper` 中的厂商检测逻辑，并为每个主流品牌（小米、华为、OPPO、vivo、三星）生成专属的“跳转设置”或“文案提示”。
3. 在 `MainActivity` 的 `onCreate` 中，按顺序引导：**相机权限** → **悬浮窗权限** → **无障碍权限** → **关闭省电限制（利用 VendorHelper）**。任一权限未开启，界面显示对应按钮，点击跳转。
4. 扫码界面必须限制 `DecodeHintType.POSSIBLE_FORMATS` 仅包含上述一维码类型，杜绝扫描二维码。
5. 注入引擎必须优先使用 `ACTION_SET_TEXT`，并编写后备逻辑。

---

**文档结束**。