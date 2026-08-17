# QuotaView

QuotaView 是多 Provider 的 Android 套餐额度与 API 余额仪表盘。核心展示是“额度已用 vs 时间已过”，并提供用户主动开启的三态悬浮窗：贴边条 → 圆环 → 详情卡。

## 当前基线

- 版本：0.13.3（versionCode 43）
- Android：minSdk 26，targetSdk / compileSdk 36
- UI：Kotlin 纯代码 View，无 Compose/XML layout
- 数据：App 直连各 Provider 的额度或余额端点，不经过中间服务器
- 后台：`specialUse` 前台服务承载用户开启的常驻额度悬浮窗，每 5 分钟刷新

Android 16 Live Updates 与 OPPO 流体云在 ColorOS 16 上已完成真机终审：第三方应用分别受 OEM 入队剥离和签名权限限制。正式产品路线不再依赖这两条原生岛通道；相关诊断代码仅为未来平台政策变化保留。

## Provider 支持

- Codex：ChatGPT 套餐额度窗口
- GLM：Coding Plan 额度窗口与 MCP 工具额度
- Kimi Code：Coding Plan 额度窗口
- Claude：Claude.ai OAuth 套餐额度窗口
- MiniMax：Coding Plan 额度窗口（中国区 / 国际区）
- DeepSeek：官方 API 账户余额 + 用户自定义今日 / 近 7 日 / 近 30 日预算；消费取自 DeepSeek Platform 官方日账单并按自然日汇总，允许超过 100%
- Qwen / 阿里云百炼：官方尚未开放剩余额度查询 API，设置页明确标记为待开放

Provider 品牌图标统一采用 MIT 许可的 [Lobe Icons](https://github.com/lobehub/lobe-icons)，具体归属见 `THIRD_PARTY_NOTICES.md`。

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
- `data/Providers.kt`：Provider API、统一额度/余额模型与费用估算
- `ui/`：双进度条、双圆环与折叠边缘条
- `tools/collect_tokens.py`：Mac 侧 token 明细汇总工具

## 安全说明

Provider 凭证当前只保存在应用私有 SharedPreferences；应用已禁用备份、设备迁移导出和明文网络。后续正式发布前仍应迁移到 Android Keystore 支持的加密存储，并完成 release 签名。
