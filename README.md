# SwipeGuard — ColorOS 16 划卡保护白名单编辑工具

## 概述
ColorOS 16 的 Athena 系统组件维护一个**应用白名单**。划卡时，Athena 先于 LMK 检查：
- ❌ 不在白名单 → 直接杀进程（即使有 Thanox 等 LMK 保护也没用）
- ✅ 在白名单 → 跳过，进程存活

**SwipeGuard** 让你自由编辑这个白名单，把 Thanox 保护的重要 app 加进去。

## 工作原理
1. Hook `ActivityManagerService.killBackgroundProcesses` → 拦截划卡杀进程
2. Hook `FileInputStream` 读取 `sys_elsa_config_list.xml` → 注入白名单条目
3. Hook `OomAdjuster.applyOomAdjLocked` → 强制白名单 app adj=-17（辅助）

双路径保护：Hook 运行时拦截 + 配置文件注入。

## 环境要求
- ColorOS 16 (Android 14/15)
- 已 Root + LSPosed v1.9.2
- (可选) Thanox 或其他 LMK 保护工具

## 安装
1. 下载 APK 并在 LSPosed 中启用模块
2. 作用域：**系统框架（system）**
3. 重启设备
4. 打开 SwipeGuard 添加想保护的 app

## 从源码构建
```bash
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## 技术栈
- libxposed API v100
- Kotlin + Jetpack Compose + Material 3
- kotlinx.serialization

## 许可与免责
仅供学习研究使用，风险自负。
