# QuotaView

QuotaView 是多 Provider 的 Android 套餐额度与 API 余额仪表盘。核心展示是“额度已用 vs 时间已过”，并提供用户主动开启的三态悬浮窗：贴边条 → 圆环 → 详情卡。

## 当前基线

- 版本：0.14.0（versionCode 49）
- Android：minSdk 26，targetSdk / compileSdk 36
- UI：Kotlin 纯代码 View，无 Compose/XML layout；主页支持折叠列表与响应式圆环卡片
- 数据：App 直连各 Provider 的额度或余额端点，不经过中间服务器
- 后台：`specialUse` 前台服务承载用户开启的额度悬浮窗，每 5 分钟刷新；横屏自动隐藏

原生灵动岛、Android Live Updates 与 OPPO 流体云方案已废弃并移除；当前唯一展示形态为用户主动开启的系统悬浮窗。Android 要求常驻悬浮窗的前台服务保留最低服务通知，应用不再申请普通通知权限。

## Provider 支持

- Codex：ChatGPT 套餐额度窗口
- GLM：Coding Plan 额度窗口与 MCP 工具额度
- Kimi Code：Coding Plan 额度窗口
- Claude：Claude.ai OAuth 套餐额度窗口
- MiniMax：Coding Plan 额度窗口（中国区 / 国际区）
- 火山方舟：Coding Plan（5h / 周 / 月）、Agent Plan AFP（5h / 日 / 周 / 月）及火山账户可用余额；使用账号级 AK/SK Signature V4，三类数据独立查询与容错
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

- `MainActivity.kt`：主额度面板、折叠/圆环卡片视图与费用模拟展示
- `SettingsActivity.kt`：Provider、主页圆环主指标、凭证与悬浮窗设置
- `OverlayService.kt`：三态悬浮窗、横竖屏可见性、手势与定时刷新
- `data/Providers.kt`：Provider API、统一额度/余额模型与费用估算
- `data/VolcengineArkApi.kt`：火山方舟 Coding / Agent Plan、账户余额与 Signature V4 请求签名
- `ui/`：双进度条、双圆环与折叠边缘条
- `tools/collect_tokens.py`：Mac 侧 token 明细汇总工具

## 安全说明

Provider 凭证当前只保存在应用私有 SharedPreferences；应用已禁用备份、设备迁移导出和明文网络。后续正式发布前仍应迁移到 Android Keystore 支持的加密存储，并完成 release 签名。
