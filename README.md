# QuotaView

QuotaView 是 Codex 与 GLM Coding Plan 的 Android 套餐额度仪表盘。核心展示是“额度已用 vs 时间已过”，并提供用户主动开启的三态悬浮窗：贴边条 → 圆环 → 详情卡。

## 当前基线

- 版本：0.12.11（versionCode 39）
- Android：minSdk 26，targetSdk / compileSdk 36
- UI：Kotlin 纯代码 View，无 Compose/XML layout
- 数据：App 直连 Codex `wham/usage` 与 GLM `quota/limit`，不经过中间服务器
- 后台：`specialUse` 前台服务承载用户开启的常驻额度悬浮窗，每 5 分钟刷新

Android 16 Live Updates 与 OPPO 流体云在 ColorOS 16 上已完成真机终审：第三方应用分别受 OEM 入队剥离和签名权限限制。正式产品路线不再依赖这两条原生岛通道；相关诊断代码仅为未来平台政策变化保留。

## 构建与验证

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 代码结构

- `MainActivity.kt`：主额度面板与费用模拟展示
- `SettingsActivity.kt`：Provider、凭证、悬浮窗设置与实验诊断
- `IslandService.kt`：三态悬浮窗、手势、前台服务与定时刷新
- `data/Providers.kt`：Codex/GLM API、统一额度模型与费用估算
- `ui/`：双进度条、双圆环与折叠边缘条
- `tools/collect_tokens.py`：Mac 侧 token 明细汇总工具

## 安全说明

Provider 凭证当前只保存在应用私有 SharedPreferences；应用已禁用备份、设备迁移导出和明文网络。后续正式发布前仍应迁移到 Android Keystore 支持的加密存储，并完成 release 签名。

