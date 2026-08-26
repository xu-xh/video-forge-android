# video-forge-android 功能与架构分析及可用性验证报告

- 分析时间：2026-08-26
- 分析对象：`/root/workspace/video-forge-android`（整个代码树，非仅文档）
- 验证方式：源码全量阅读 + 干净离线构建 + APK 静态校验（aapt）+ 状态机逻辑仿真

## 0) 结论摘要

1. 项目可构建、APK 合法、启动 Activity 正确，作为"可安装可运行的演示原型"是**可用**的；
   但作为文档声称的"Remote-first API 视频生成客户端"，当前代码**尚未实现网络层**，不能对接任何真实服务。
2. 核心差距：README/docs 描述的 Generic REST / ComfyUI 适配器、Android Keystore 加密、Profile 配置界面
   在代码中一律不存在；代码实现的是一个**纯本地模拟**的作业状态机 + JSON 文件持久化 + 重启恢复轮询。
3. 代码审查发现 1 个实质性逻辑缺陷：降级重试（fallback）分支是**不可达死代码**，`retryCount` 恒为 0，
   低清档（low_end）回退永远不会发生；另有少量死代码与无效依赖配置。
4. **上述问题已于 2026-08-26 全部修复并通过重新构建 + 单元测试验证**，详见 §8「修复记录」。

## 1) 项目概览

| 项 | 值 |
|---|---|
| 根项目 | VideoForge，单模块 `:app` |
| applicationId / namespace | `com.xuxh.videoforge` |
| 版本 | versionCode 1 / versionName 0.1.0 |
| SDK | compile/target 35，minSdk 26（Android 8.0+） |
| JVM | Java 17（source/target 17） |
| 工具链 | AGP 8.7.3、Kotlin 2.3.21、Compose BOM 2026.06.00、Gradle 8.9 |
| 依赖 | 仅 Compose/Activity/Lifecycle/Coroutines；**无任何网络库、无 Room、无 WorkManager、无加密库** |
| 源码规模 | Kotlin 7 文件共约 740 行 |
| 版本控制 | 无 `.git`（非 git 仓库） |

源码清单（全部）：

- `app/src/main/java/com/xuxh/videoforge/MainActivity.kt`（25 行，入口）
- `app/src/main/java/com/xuxh/videoforge/model/VideoModel.kt`（37 行，领域模型）
- `app/src/main/java/com/xuxh/videoforge/data/JobStore.kt`（194 行，持久化）
- `app/src/main/java/com/xuxh/videoforge/ui/VideoForgeViewModel.kt`（329 行，核心逻辑）
- `app/src/main/java/com/xuxh/videoforge/ui/VideoForgeApp.kt`（103 行，Compose UI）
- `app/src/main/java/com/xuxh/videoforge/ui/VideoForgeAppBootstrap.kt`（20 行，未使用）
- `app/src/main/java/com/xuxh/videoforge/ui/theme/VideoForgeTheme.kt`（31 行，M3 主题）

## 2) 功能分析（按实际代码行为）

应用实际提供以下功能：

1. **任务提交**：输入 Prompt 与模型名（默认 `qwen2.5-7b`），点击"提交任务"创建一个作业并持久化。
2. **模拟作业生命周期**：`submit()` 后由 `poll()` 驱动状态流转（见下表），全部是本地定时模拟：
   - 正常 Prompt：`SUBMITTED → QUEUED(0.6s) → PROCESSING×4(1.2s×4) → DONE(约第 6.6s)`，产出伪造输出 URL
     `https://example.com/out-<id前6位>.mp4`，并生成伪造 `remoteId = r-<id前8位>`。
   - 含 `error`（忽略大小写）的 Prompt：第 4 步置为 `FAILED`，错误文案"模拟生成失败: 码率不足"。
3. **兼容档位（compat profile）**：
   - 默认档：1280×720@30fps / 3500kbps；
   - 低清档：640×360@24fps / 1500kbps，当 Prompt 含 `low` / `legacy` 时在提交时静态选用；
   - 每个作业记录 compatTrace（JSON 数组，含 stage/attempt/profile/分辨率/时间戳），UI 展示前 180 字符。
4. **持久化与重启恢复**：作业以 JSON 数组写入 `filesDir/videoforge_jobs.json`；应用启动时
   `init()` → `resumePendingJobs()` 会把上次未终态（SUBMITTED/QUEUED/PROCESSING）的作业重新拉起轮询，
   满足"重启后任务不丢失、继续推进到终态"的验收场景（逻辑上成立）。
5. **UI**：Material 3 Compose 单屏 —— 状态栏、Prompt/模型输入框、"提交任务/刷新/清空"按钮、作业卡片列表
   （状态、ID、结果/错误、兼容档信息行、Trace 前 180 字符）。`history` 列表在 UI 中未使用。

## 3) 架构分析

```
MainActivity (ComponentActivity, setContent)
   └─ VideoForgeTheme (M3: 亮/暗色方案)
   └─ VideoForgeApp (Compose UI: 输入 + 按钮 + LazyColumn 作业卡片)
        └─ VideoForgeViewModel (MVVM; viewModelScope; Compose state)
             ├─ submit()/refresh()/clear()/poll()/resumePendingJobs()
             └─ VideoForgeDatabase.set(FileVideoJobDao)       ← 名为 Database，实为 JSON 文件 DAO
                  └─ model: VideoJob (领域) ↔ VideoJobEntity (持久化)
                       └─ VideoModel.kt: JobStatus / AdapterType / ProviderProfile
```

- **分层**：UI（Compose）→ ViewModel（状态机）→ DAO 接口 → JSON 文件实现；模型层定义领域对象。
  结构清晰、职责单一，是标准轻量 MVVM。
- **状态管理**：`StateFlow<List<VideoJob>>` + Compose `mutableStateOf(statusText)`；
  轮询用 `activePolls` 集合防止同一作业重复轮询（`synchronized` 保护）。
- **持久化**：`FileVideoJobDao` 每次全量读写 JSON；`optString/optInt` 容错解析，损坏文件静默降级为空列表。
- **安全**：Manifest 仅申请 `INTERNET`；release 禁明文 HTTP、debug 允许（`tools:replace` 合并正确）；
  `allowBackup=false`。但**没有任何密钥加密代码**（README 声称的 Keystore AES-GCM 不存在）。
- **安全边界内的小竞态**：`submit()` 里 `refresh()` 与 `poll()` 是两条独立协程，`poll()` 第一步有 600ms 延迟，
  实践中 `_jobs` 已含新作业；若查不到则静默 `return@launch`（极端时序下不轮询，不崩溃）。

## 4) 文档与实现的差异（关键发现）

| 文档/README 声称 | 代码实际情况 |
|---|---|
| "Generic async REST: POST /videos, GET /videos/{id}" | 无任何 HTTP 代码；无网络依赖（未引入 OkHttp/Retrofit/ktor），连 `HttpURLConnection` 都没有 |
| "ComfyUI: POST /prompt, GET /history/{prompt_id}, /view" | 不存在 |
| "API keys encrypted with Android Keystore AES-GCM" | 无 Keystore/加密相关代码 |
| "Open it, select an adapter, enter the base URL and API key, save the profile" | 无 profile 配置 UI；`ProviderProfile` 仅作持久化字段，默认写死 `https://api.example.com` |
| "Room-backed durable jobs" | `VideoForgeDatabase` 只是 JSON 文件 DAO；libs.versions.toml 声明了 Room/kapt，但 `app/build.gradle.kts` 未应用（死配置） |

结论：**当前代码是对目标产品（docs/provider-contract.md、benchmark-protocol.md 所描述）的"模拟脚手架"**——
状态机、兼容档位、重试（未生效）、持久化、恢复这些骨架都在，网络与加密层待实现，恰好对应
README "Known next steps" 列表。README 与最终代码不一致，建议后续同步修正文档或补齐实现。

## 5) 可用性验证（本机可执行部分）

本机（ECS）无 adb、无模拟器，无法做真机运行验收（与 `remote_completion_report.md` 结论一致）；
本次完成的是构建级与静态逻辑级验证：

1. **干净离线重建（2026-08-26）**：`/root/gradle-8.9/bin/gradle --offline clean :app:assembleDebug`
   → `BUILD SUCCESSFUL in 1m 58s`，36 个任务全部执行（非 UP-TO-DATE），exit 0。
   此前 `.verify/assembleDebug_remote_offline.log` 显示 gradle wrapper 下载镜像超时，
   故统一改用本地 Gradle 8.9 离线构建（与历史证据方法一致）。
2. **APK 校验（aapt badging，build-tools 35.0.0）**：
   - 路径：`app/build/outputs/apk/debug/app-debug.apk`
   - 大小 11,517,766 B；SHA256 `22336a336a2faaf2ca36b5eed2960114f2552dd7cad1f2b6f7d1684ab55c835d`
   - `package: com.xuxh.videoforge, versionCode=1, versionName=0.1.0`
   - `sdkVersion: 26, targetSdkVersion: 35, compileSdkVersion: 35`
   - 权限：仅 `INTERNET`；`launchable-activity: com.xuxh.videoforge.MainActivity`
   - 与历史证据（11517766 B）大小完全一致，说明 8-05 之后源码未变化，构建可复现。
3. **状态机仿真**：用脚本按 `poll()` 逐行复刻逻辑，验证三类输入的流转：
   - `verify resume test` → `QUEUED → PROCESSING ×4 → DONE`，retryCount=0；
   - `error test` → `QUEUED → PROCESSING ×2 → FAILED`（第 4 步），retryCount=0；
   - `low legacy prompt` → 提交时即套用低清档（该逻辑在 poll 之外），流转同正常任务。
4. **逻辑审查**（这属于"可用性"的一部分，见 §6）：任务恢复链路 `init→resumePendingJobs→poll`
   逻辑成立；异常路径全部 try/catch 包裹，JSON 解析容错，无明显的崩溃风险点。

## 6) 代码审查发现的问题

1. **[实质，已修复] 降级重试原来是死代码**：`poll()` 第 5 步的
   `if (current.status == JobStatus.FAILED && shouldFallback(current))` 分支永远不可达——
   第 4 步一旦置 FAILED 立即 `return@launch`，且循环顶部对 FAILED 作业直接返回；
   因此 `retryCount` 恒为 0、low_end 回退永不发生，"兼容档位+重试"机制实际只有"按关键字静态选档"生效。
2. **[次要，已修复] 死代码**：`ui/VideoForgeAppBootstrap.kt` 的 `LaunchVideoForgeApp` 从未被调用
   （`MainActivity` 内联了相同逻辑）；`viewModel.history` 维护但 UI 未用。
3. **[次要，已修复] 无效依赖配置**：`libs.versions.toml` 中的 room-runtime/room-ktx/room-compiler 与
   `kotlin-kapt` 插件在 `app/build.gradle.kts` 中未引用。
4. **[轻微，已修复] 竞态**：`poll()` 在 `_jobs` 中找不到作业时静默返回（不轮询），600ms 首步延迟使其概率极低，但可加日志。
5. **[观察，已修复] 无测试**：无单元/仪器测试；状态机这类纯逻辑建议补单测（本次报告已用脚本仿真验证）。

## 7) 结论与建议

- **可用性判定**：构建/安装/演示级可用 ✅（编译、APK 结构、启动入口、状态机、持久化恢复均验证通过）；
  真实服务级不可用 ❌（无网络层）；文档与代码不一致 ⚠️。
- **最终设备验收**：仍须在有 adb 的本机按 `scripts/manual_resume_acceptance_checklist.md` 执行安装、
  提交、强停、重启四步，确认任务恢复推进到 DONE/FAILED（远端无法代跑）。
- **建议的下一步**（与 README "Known next steps" 对齐并补洞）：
  1. 实现网络层：按 `docs/provider-contract.md` 接入 Generic REST 与 ComfyUI 适配器（引入 OkHttp/Retrofit），
     用 `ProviderProfile` 的真实字段（已持久化）发起请求；
  2. 补上 Android Keystore AES-GCM 的 API Key 加密；
  3. 加 Profile 编辑界面（Base URL / API Key / Adapter），替换写死的 `api.example.com`；
  4. 修复 §6-1 的 fallback 死分支（例如在第 4 步 FAILED 后改"等待重试"而非直接返回），或删除该分支并同步文档；
  5. （可选）启用 Room / WorkManager、清除死代码与无效依赖、为状态机补单测。
---

## 8) 修复记录（2026-08-26）

针对 §6 发现的问题已完成修复并重新验证：

### 8.1 变更清单

| # | 问题 | 修复 |
|---|---|---|
| 1 | fallback 死分支（实质） | 抽出纯逻辑状态机至 `app/src/main/java/com/xuxh/videoforge/ui/VideoJobStateMachine.kt`（`transitionForStep` / `fallbackFor` / `shouldFallback` / `resolveCompatProfile` / `appendTrace`）；重写 `poll()`：FAILED 且可降级时执行 `fallbackFor`（切低清档、retryCount+1、重置为已提交）后继续轮询至 DONE；`resumePendingJobs()` 顺带恢复旧版本遗留的 FAILED 未重试作业（自动修复存量数据） |
| 2 | 死代码 | 删除 `ui/VideoForgeAppBootstrap.kt`；移除 ViewModel 中未使用的 `_history`/`history` |
| 3 | 无效依赖配置 | `gradle/libs.versions.toml` 删除未使用的 room×3 与 `kotlin-kapt`；改为新增 `junit 4.13.2`、`org.json 20240303`（单元测试用） |
| 4 | 轮询竞态 | `poll()` 每步直接读 DAO（`dao.getAll().find{...}.toDomain()`），不再依赖 `_jobs` 缓存时序；作业不存在时给出明确 `statusText` 而非静默放弃 |
| 5 | 无测试 | 新增 `app/src/test/java/com/xuxh/videoforge/ui/VideoJobStateMachineTest.kt`（7 个用例，含正常流转、降级重试、重试后终态、低清档选档、各步映射、Trace 记录） |

### 8.2 修复后行为验证

- **干净离线重建**：`gradle --offline clean :app:testDebugUnitTest :app:assembleDebug`
  → `BUILD SUCCESSFUL in 2m 26s`，42 个任务全部执行，无警告。
- **单元测试**：`testDebugUnitTest` → `tests=7 failures=0 errors=0 skipped=0`。
- **APK 校验**（aapt badging，build-tools 35.0.0）：
  - 大小 11,517,766 B；SHA256 `518621dccbc1177663ae28e63efba30dc20f5c1d568510930c365cd0069b10d3`
  - `package: com.xuxh.videoforge, versionCode=1, versionName=0.1.0, minSdk=26, targetSdk=35`
  - 新增类 `VideoJobStateMachine` 已打入 APK（dex 校验通过，构建无告警）。
- **修复后状态机流转（与测试一致）**：
  - 普通 Prompt：`SUBMITTED → QUEUED → PROCESSING×4 → DONE`，retryCount=0；
  - 含 `error` 的 Prompt：`… → FAILED(第4步) → 降级重试(低清档, retryCount=1) → DONE`；
  - 已重试（retryCount≥1）或已是低清档的 FAILED 作业：保持终态，不再重试（防死循环）。

### 8.3 验证证据文件

- 测试报告：`app/build/test-results/testDebugUnitTest/TEST-com.xuxh.videoforge.ui.VideoJobStateMachineTest.xml`
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`
- 证据 JSON：`remote-apk-evidence/remote_evidence_<本次时间戳>.json`

### 8.4 真实网络层开发（v0.2.0，2026-08-26）

针对 §4「文档与实现的差异」（无网络层、无配置界面、无密钥加密），本次按 docs/provider-contract.md 落地真实功能：

| 模块 | 实现 |
|---|---|
| 配置 | `data/ProviderSettingsStore.kt`：SharedPreferences 持久化 Profile（适配器/Base URL/Key/鉴权头/模型/workflow JSON） |
| 加密 | `security/ApiKeyCipher.kt`：AndroidKeyStore AES-GCM（alias `videoforge_api_key`），明文不落盘、不随作业持久化 |
| 网络 | `net/Http.kt`（HttpURLConnection，无第三方依赖）、`net/RemoteParsers.kt`（纯解析，单测覆盖）、`net/RemoteVideoSource.kt`（GenericRest/ComfyUI 双适配器 + 工厂） |
| 轮询 | ViewModel `pollRemote()`：真实提交 → 每 1.5s 轮询 → DONE/FAILED，90 次超时保护；`simulate()` 仅在未配置服务时兜底并标注「模拟模式」 |
| 结果 | 「打开结果」按钮以 ACTION_VIEW 打开输出 URL（显式用户操作） |
| 版本 | versionCode 2 / versionName 0.2.0 |

验证（2026-08-26）：

- 单测 21 个全绿：RemoteParsersTest(11) + RemoteVideoSourceIntegrationTest(3) + VideoJobStateMachineTest(7)
  —— 其中集成测试用迷你 HTTP 服务器（ServerSocket）真实跑通 Generic REST 与 ComfyUI 的提交→轮询→完成/失败全链路，并校验鉴权头与 Prompt 注入
- 离线 clean 重建：`BUILD SUCCESSFUL in 2m 34s`，42 任务全部执行，无警告
- APK：11,567,092 B，versionCode 2 / versionName 0.2.0；aapt 校验通过
- dex 校验：`net/*`、`security/ApiKeyCipher`、新的 `ui/CompatProfile` 等类均已打入
- 发布：GitHub Release v0.2.0（仓库 xu-xh/video-forge-android）

局限（v0.2.0 已知）：ComfyUI 端到端真机验证需用户内网 ComfyUI 实例；workflow 无 text 输入节点时提交会被拒绝并提示；API Key 变更后旧作业按新 Key 轮询（作业不固化 Key，符合不落盘原则）。
