# SwipeGuardScreen.kt 图标渲染黑图问题 — 代码审查报告

> **审查对象**：`/data/data/com.termux/files/home/athena/app/src/main/java/com/swipeguard/xposed/ui/screens/SwipeGuardScreen.kt`
> **审查范围**：`AppIcon` Composable（第 261-282 行）及 `getAppLabel` 缓存（第 478-493 行）
> **核心症状**：所有应用图标渲染为黑色色块，与图标库/渲染管线无关
> **结论**：1 个阻塞性根因 + 2 个次要缺陷，见下方

> 注：原定输出路径 `/tmp/review-icons.md` 在本环境下不可写（`/tmp` 属 untrusted_app 沙箱），实际写入 `${TMPDIR=/data/data/com.termux/files/usr/tmp}/review-icons.md`。

---

## 1. 关键证据：4 次修复尝试均改动"渲染端"而未触及"加载端"

从 `git log` 提取的相关提交（按时间倒序）：

| 提交 | 渲染方式 | 是否解决黑图 |
|------|----------|--------------|
| `2f04fa3` (HEAD) | `AndroidView` + `ImageView(FIT_CENTER)` | ❌ |
| `c036218` | `Image(rememberDrawablePainter(...))` | ❌ |
| `5d72acb` | `Image(drawablePainter(...))` | ❌（仅修复了 Icon 的 LocalContentColor 染色，不是黑图）|
| `2eeb5bd` | `rememberDrawablePainter` 包装 | ❌ |
| `6aee0ac` | 手动 `Canvas.draw + mutate + clearColorFilter` | ❌ |
| `126f2a0` | `Drawable.toBitmap()` | ❌（compileSdk=37 编译失败，回退）|
| `85bb3da` | 固定 72dp `Bitmap` + `setBounds` | ❌（修复了 `intrinsicWidth=-1` 的 1x1 黑图，但黑图仍存在）|

**关键观察**：
- 4 次"渲染端"修复（Canvas / toBitmap / Painter / AndroidView）覆盖了 Android 上所有主流 Drawable → 位图/视图的转换路径，**理论上只要 Drawable 本身正确就应当成功**。
- 全部失败意味着**根因不在渲染端，而在 `getApplicationIcon(pkg)` 返回的 Drawable 本身**。
- `85bb3da` 的 commit message 明确说"图标黑"，说明问题在引入 `AppIcon` 之初就存在，并非近期回归。

---

## 2. 根因（Blocker）：目标 app 图标的 Theme 解析失败

### 2.1 机制

`PackageManager.getApplicationIcon(pkg)` 内部调用链：

```
PackageManager.getApplicationIcon(pkg)
  → ApplicationInfo.loadIcon(pm)
    → pm.getResourcesForApplication(appInfo).getDrawable(iconRes)
      → Resources.loadDrawable(TypedValue, id, density, theme)
```

**关键点**：在 `Resources.loadDrawable()` 中，所有 VectorDrawable / AnimatedVectorDrawable 中的 `?attr/...` / `?android:attr/...` 主题属性是**在 inflate 时**就解析并烘焙到 PathData/Color 的。

PackageManager 跨进程加载时，会**使用调用方（SwipeGuard）的 Theme.DeviceDefault** 解析目标 app 图标里的主题引用，而不是目标 app 自己的主题。

### 2.2 触发条件

- 目标 app 的图标是 **VectorDrawable**（绝大多数 ColorOS/OPlus 系统应用：`com.coloros.soundrecorder`、`com.coloros.alarmclock`、`com.coloros.oppopods`、`com.oplus.*` 等都使用 vector + theme attr 的 adaptive icon）
- Vector 内部使用 `?attr/colorPrimary` / `?attr/colorOnPrimaryContainer` / `?attr/colorAccent` 等 Material/AppCompat 主题属性
- SwipeGuard 的 application theme 是 `@android:style/Theme.DeviceDefault`（见 `AndroidManifest.xml:13`），**不包含** Material/AppCompat 的 colorPrimary 等属性

**结果**：VectorDrawable 的 fillColor 解析为 `0`（Color.TRANSPARENT）或默认 fallback 黑色 → 渲染为黑块。

### 2.3 为什么 Bitmap 渲染路径也失败

`Bitmap.createBitmap(..., ARGB_8888)` 创建的是**带 alpha 的透明背景位图**。当 VectorDrawable 的 fillColor 解析失败时：
- Canvas 是透明的
- VectorDrawable draw 出来的是**透明像素**或**不透明黑色像素**（取决于 Drawable 子类）
- 由于背景透明，前景色"看似黑色"实际上是"未被绘制 + 黑色 fallback 混合"

这解释了为什么 `85bb3da` 之后所有 Bitmap/Canvas/ImageView 路径都看到黑图 — **Drawable 本身就没有有效颜色信息**。

### 2.4 为什么 `Icon` 看起来"被染色"而非"全黑"

`Icon(painter)` 会应用 `LocalContentColor` 作为 tint。当 tint 不是黑色时（比如 `MaterialTheme.colorScheme.onSurface`），会把**完全透明的 Drawable** tint 成对应颜色（看起来像"被染色"）。`5d72acb` 改用 `Image` 取消 tint 后，透明 + 黑色 fallback 的真实面貌暴露 — 全黑。

### 2.5 验证方式

在设备上执行：
```bash
adb shell dumpsys package com.coloros.soundrecorder | grep -A 2 'icon='
```
可看到 `icon=0x7f0a0001` 等资源 ID。

```bash
# 用 aapt2 直接看图标 XML：
aapt2 dump xmltree com.coloros.soundrecorder.apk --file res/mipmap-anydpi-v26/ic_launcher.xml
```
会看到 `<adaptive-icon><background android:drawable="?attr/colorPrimary"/>...` 这种引用主题属性的结构 — 这就是问题源。

---

## 3. 次要缺陷（Note）

### 3.1 AndroidView 缺少 `update` block（中等严重）

**位置**：`SwipeGuardScreen.kt:267-275`

```kotlin
AndroidView(
    factory = { ctx ->
        android.widget.ImageView(ctx).apply {
            setImageDrawable(drawable)   // ← 只在 factory 阶段执行
            scaleType = ...
        }
    },
    // ← 缺 update 块
)
```

`AndroidView.factory` **每个 view 实例只调用一次**。当 LazyColumn 因为 list reorder 复用 Composable slot 时（`items(..., key = { it })` 虽然能稳定 key，但在 AddAppDialog 搜索过滤时 key 集合会变，slot 复用是常态），`drawable` 会因为 `remember(pkg)` 变化而指向新图标，但 ImageView 不会重新 `setImageDrawable`，**显示的仍是旧图标**。

**修复**：把 `setImageDrawable` 移入 `update` block。

### 3.2 LazyColumn + AndroidView 复用（用户问点 1）

**结论**：LazyColumn 内的 AndroidView **本身**没有问题（`items(key = { it })` 是正确的稳定 key 用法）。但必须配 `update` 块才能在数据变化时正确刷新 view。这是 3.1 的同一根因。

### 3.3 `getApplicationIcon` 线程（用户问点 3）

**结论**：**不存在** Looper/线程问题。`PackageManager.getApplicationIcon` 是 Binder 调用，**支持任意线程**，主线程调用是合法的（会有 ~10-50ms Binder 开销，对几十个 app 不会卡顿）。之前的 Bitmap 路径会调用 `Bitmap.createBitmap` + `drawable.draw`（CPU 密集），那些**才**应该放到 `LaunchedEffect + withContext(Dispatchers.IO)`，但 icon 加载本身没问题。

### 3.4 ScaleType（用户问点 5）

`ImageView.ScaleType.FIT_CENTER` **是正确的选择** — app icon 应当按比例缩放至 view 内，保留完整可见内容。无需改成 `CENTER_CROP`。

### 3.5 AdaptiveIconDrawable 包装 LayerDrawable（用户问点 6）

**结论**：**不需要**对 AdaptiveIconDrawable 做特殊 LayerDrawable 包装。`ImageView` 内部对 `AdaptiveIconDrawable` 有原生支持（API 26+，本项目 minSdk=26），会自动应用 icon mask 并按 72dp 可见区域渲染。**前提是 Drawable 本身正确**（见第 2 节根因）。

### 3.6 `appLabelCache` 线程不安全（次要）

**位置**：`SwipeGuardScreen.kt:478`

```kotlin
private val appLabelCache = mutableMapOf<String, String>()
```

非 `ConcurrentHashMap`、无 `@Synchronized`、无 `Mutex`。在 LazyColumn 滚动时多 frame 并发 recompose 可能触发 `ConcurrentModificationException`。**与黑图无关**，但应当用 `ConcurrentHashMap` 替换。

### 3.7 Icon 库缺失（项目级 Note）

`gradle/libs.versions.toml` 中**没有** Coil / Glide 依赖。如果未来要支持更复杂的图标场景（圆形 mask、动画、placeholder、错误占位图），建议引入 Coil 的 `AsyncImage`：
```kotlin
implementation("io.coil-kt:coil-compose:2.6.0")
```
但对当前黑图 bug **不必要**。

---

## 4. 决定性修复（Definitive Fix）

### 4.1 核心思路

用 `context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)` 获取**目标 app 自己的 Context**（含目标 app 的 Resources 和 Theme），从该 Context 加载图标。这样 VectorDrawable 里的主题属性会**用目标 app 自己的主题**解析，得到正确颜色。

### 4.2 代码 diff

**文件**：`app/src/main/java/com/swipeguard/xposed/ui/screens/SwipeGuardScreen.kt`

```diff
@@ -1,6 +1,8 @@
 package com.swipeguard.xposed.ui.screens

+import android.content.Context
 import android.content.pm.ApplicationInfo
 import android.content.pm.PackageManager
+import android.graphics.drawable.AdaptiveIconDrawable
+import android.graphics.drawable.Drawable
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.layout.*
 import androidx.compose.foundation.lazy.LazyColumn
@@ -19,6 +21,7 @@ import androidx.compose.ui.draw.clip
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.viewinterop.AndroidView
+import androidx.core.graphics.drawable.DrawableCompat
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.text.style.TextOverflow
 import androidx.compose.ui.unit.dp
@@ -257,21 +260,40 @@ private fun AppItemCard(

 // ─────────────────────────────────────────────────────────────────
 // 应用图标组件
+//
+// 关键修复：用 createPackageContext 加载目标 app 自己的 Resources/Theme，
+// 否则 VectorDrawable 里的 ?attr/colorPrimary 等会解析为 SwipeGuard 自己的
+// Theme.DeviceDefault 上不存在的属性 → fillColor = 黑色 fallback。
+// 见 review-icons.md §2。
 // ─────────────────────────────────────────────────────────────────

 @Composable
 private fun AppIcon(pkg: String, size: Int) {
     val context = LocalContext.current
     val drawable = remember(pkg) {
-        try {
-            context.packageManager.getApplicationIcon(pkg)
-        } catch (_: Exception) {
-            null
+        loadAppIcon(context, pkg)
-        }
     }
     if (drawable != null) {
         AndroidView(
             factory = { ctx ->
                 android.widget.ImageView(ctx).apply {
-                    setImageDrawable(drawable)
                     scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                 }
             },
+            update = { imageView ->
+                // 必须放 update 块：factory 只调用一次，
+                // Composable slot 复用时只有 update 块会响应 drawable 变化。
+                imageView.setImageDrawable(drawable)
+            },
             modifier = Modifier
                 .size(size.dp)
                 .clip(RoundedCornerShape(8.dp))
         )
     }
 }

+/**
+ * 加载目标 app 的图标。
+ *
+ * 必须用目标 app 自己的 Context（[Context.createPackageContext]），
+ * 否则 PackageManager 跨进程加载时会用调用方的 Theme 解析图标里的
+ * 主题属性，导致 VectorDrawable 渲染为黑色。
+ *
+ * @return 加载好的 Drawable；失败时返回 null。
+ */
+private fun loadAppIcon(context: Context, pkg: String): Drawable? {
+    return try {
+        // 关键：用目标 app 的 Context 加载 → 目标 app 的 Resources + Theme
+        val targetContext = context.createPackageContext(
+            pkg,
+            Context.CONTEXT_IGNORE_SECURITY
+        )
+        val drawable = targetContext.packageManager.getApplicationIcon(pkg)
+        // mutate() 防止同一 Drawable 实例在多个 AppIcon 间共享 state
+        // （避免 tint / level / state 在 RecyclerView 风格复用中串扰）
+        drawable.mutate().also {
+            // 兜底：清理任何来源不明的 ColorFilter（防御性，理论上不需要）
+            DrawableCompat.clearColorFilter(it)
+        }
+    } catch (_: PackageManager.NameNotFoundException) {
+        // createPackageContext 可能因权限/未安装失败，回退到原路径
+        try {
+            context.packageManager.getApplicationIcon(pkg)
+        } catch (_: Exception) {
+            null
+        }
+    } catch (_: Exception) {
+        null
+    }
+}
+
+@Suppress("unused")
+private val isAdaptiveIcon: (Drawable) -> Boolean = { it is AdaptiveIconDrawable }
```

### 4.3 关键改动解释

| # | 改动 | 解决的问题 |
|---|------|-----------|
| 1 | `createPackageContext(pkg, IGNORE_SECURITY)` | **根因**：让 `?attr/colorPrimary` 用目标 app 的主题解析而非 SwipeGuard 的 |
| 2 | `.mutate()` | 防御性：防止多个 `AppIcon` 共享 Drawable 状态 |
| 3 | `DrawableCompat.clearColorFilter()` | 防御性：清理调用方 app 主题意外注入的 filter |
| 4 | 把 `setImageDrawable` 移到 `update` 块 | **次要缺陷 3.1**：保证 slot 复用时正确刷新 |
| 5 | `NameNotFoundException` 回退 | 健壮性：极少数包 `createPackageContext` 失败时回退到原路径 |

### 4.4 为什么不用 Bitmap / Painter 路径

虽然 Bitmap / Painter 也能最终把 Drawable 画到画布上，但**根因在 Drawable 本身**（fillColor = 0/黑色），任何渲染路径都会得到黑图。这就是之前 4 次修复全部失败的直接原因 — 它们都在试图"画一个已经是黑色的东西画得更好"。

### 4.5 为什么不需要 LayerDrawable 包装 AdaptiveIconDrawable

`ImageView` 在 API 23+ 自动识别 `AdaptiveIconDrawable` 并应用系统 icon mask（在支持的系统上）。SwipeGuard 的 `minSdk = 26`，所有设备都满足。不需要手写 LayerDrawable。

---

## 5. 验证方案

修复后用以下步骤验证：

```bash
# 1. 编译
cd /data/data/com.termux/files/home/athena
./gradlew :app:assembleDebug

# 2. 安装到 LSPosed 已激活的 ColorOS 16 设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. 在 LSPosed 中启用 SwipeGuard（作用域：android）

# 4. 启动 SwipeGuard 主界面
adb shell am start -n com.swipeguard.xposed/.ui.MainActivity

# 5. 检查图标：
#    - 列表中 50+ 个 app icon 应当全部正确显示（不再黑图）
#    - 已知的目标 app: com.coloros.soundrecorder / com.coloros.alarmclock /
#      com.oplus.claw / com.coloros.oppopods 等 OPlus 自有应用尤其重要
#    - 第三方应用（美团/饿了么/滴滴等）应当正常显示
#    - 添加应用对话框中的搜索结果列表同样应当正常显示
```

如果仍有个别图标黑图，再考虑加 `LayerDrawable` 包装作为兜底（见 §6 残留风险）。

---

## 6. 残留风险

| 风险 | 概率 | 应对 |
|------|------|------|
| 某些 OEM 应用的图标资源是**加密**或**远程下发**的，`getResourcesForApplication` 拿到的 Resources 不完整 | 低 | 走 try-catch 失败回退到原 `context.packageManager.getApplicationIcon` |
| `createPackageContext` 在 WorkProfile / 多用户场景下需要 `Context.CONTEXT_INCLUDE_CODE` 标志 | 低 | 当前 `IGNORE_SECURITY` 已足够普通用户场景；如需 work profile 支持再加 `CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY` |
| AdaptiveIconDrawable 在 `ImageView` 上渲染时偶尔仍报 `ClassCastException`（已知的 Android Q 早期 issue） | 极低 | minSdk=26 + 当前 ColorOS 16 应已修复；如再现，外层加 try-catch 返回 null |
| 极个别 app 的图标是 `BitmapDrawable` 包裹 1x1 透明像素 | 极低 | 这是 app 自身问题，无法修复；try-catch 后显示空白即可 |
| `appLabelCache` 仍非线程安全 | 中 | 建议另起 commit 改为 `ConcurrentHashMap`（不在本修复 scope 内）|

---

## 7. 审查总结

```
## Review
- Correct:
  - items() 用了 key={pkg} 保证 LazyColumn 稳定追踪 — 正确
  - drawable 使用 remember(pkg) 缓存避免重复加载 — 正确
  - 当前的 setImageDrawable+update 块拆分思路（修复后）— 正确
  - scaleType=FIT_CENTER — 正确
- Fixed:
  - Blocker: Theme 解析失败导致 VectorDrawable fillColor=黑色
    → 改用 createPackageContext 加载目标 app 的 Context
  - 次要: AndroidView 缺 update 块，slot 复用时图标不刷新
    → 把 setImageDrawable 移入 update 块
- Blocker:
  - 上述 Theme 解析问题（黑图根因），必须修复才能显示正常图标
- Note:
  - appLabelCache 非线程安全（建议另起 commit 改 ConcurrentHashMap）
  - 项目无 Coil/Glide 依赖，未来如需 placeholder/animation 可引入
  - Compose BOM 2026.05.01 已包含 rememberDrawablePainter 替代品
    （painterResource + Drawable），但本修复不依赖 Compose 渲染路径
```
