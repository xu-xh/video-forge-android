# Video Forge Android

Remote-first MVP for API-driven video generation.

## 功能（v0.2.0）

- Generic async REST 适配器：`POST {base}/videos` 提交，`GET {base}/videos/{id}` 轮询（遵循 docs/provider-contract.md）
- ComfyUI 适配器：`POST {base}/prompt` 提交（自动把 Prompt 注入工作流 text 输入），`GET {base}/history/{prompt_id}` 轮询，输出转 `{base}/view` 链接
- Profile 配置界面：适配器选择、Base URL、API Key、Auth Header/Prefix、默认模型、ComfyUI workflow JSON
- API Key 使用 Android Keystore AES-GCM 加密存储，明文不落盘（security/ApiKeyCipher.kt）
- 真实任务流转：已提交 → 排队中 → 生成中 → 完成/失败，带输出 URL 与错误信息
- 「打开结果」按钮：显式用户操作，用系统浏览器打开/下载生成结果
- 未配置服务时进入**模拟模式**（页面明确标注 ⚠ 模拟模式），用于演示与离线测试
- 作业持久化到 JSON 文件，重启后自动恢复未完成任务并继续轮询

## Supported adapters

- Generic async REST: POST /videos, then GET /videos/{id}
- ComfyUI: POST /prompt, then GET /history/{prompt_id}; output uses /view

## Security boundary

- API keys are encrypted with Android Keystore AES-GCM.
- Release builds disallow cleartext HTTP; debug permits HTTP for private LAN testing.
- This MVP does not persist generated media locally and does not upload local files.

## 使用

1. 打开 App → 在顶部配置面板选择适配器（Generic REST / ComfyUI）
2. 填写 Base URL（如 ComfyUI：`http://192.168.1.10:8188`）、API Key（可留空）、鉴权头与默认模型
3. ComfyUI 还需粘贴 API 格式 workflow JSON（ComfyUI 菜单 Workflow → Export(API)）
4. 点「保存配置」，状态栏显示「真实接入: …」
5. 输入 Prompt 与模型 →「提交任务」，任务自动流转到终态；完成点「打开结果」

## Known next steps

- ComfyUI WebSocket 进度回调
- 可插拔的按提供商请求 schema
- Room 存储与 WorkManager 定时重试
- 本地下载/分享（当前用浏览器打开）

## Build and use

以 Gradle 8.9 与 Android SDK 构建 debug 包：

/root/gradle-8.9/bin/gradle :app:assembleDebug --no-daemon --console=plain

APK 输出到 app/build/outputs/apk/debug/app-debug.apk。单元测试（状态机/解析器/集成）：

/root/gradle-8.9/bin/gradle :app:testDebugUnitTest --no-daemon --console=plain

## Download the debug APK

最新 debug 包发布在 GitHub Releases：

https://github.com/xu-xh/video-forge-android/releases

手机上直接打开 Release 中 app-debug.apk 的下载链接安装（允许未知来源）。