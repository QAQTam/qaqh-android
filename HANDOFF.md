# HANDOFF — QAQ-Harness 安卓端

给后续会话/接手者的交接说明。规划见 [PLAN.md](PLAN.md)。

## 项目现状（2026-10-05）

- 仓库：`E:\qaqh-android`，已有两个提交（`README` + 初始骨架）。
- 已完成：Gradle 项目骨架、`MainActivity`（Compose 骨架）、`ConnectionService`（前台服务占位，`dataSync` 类型）、Manifest 权限声明。
- **P1 协议层已完成（2026-10-05，未提交）**：`data/ringing/` 下 `model/RingingModels.kt`（envelope/ack/bootstrap/SSE 事件信封/approvals，JSON 形状对齐 qaqh-ringing v2 wire 格式）、`RingingApi`（open/renew/bootstrap/events SSE→Flow/commands/approvals，Bearer + `X-QAQH-Client-Session-Id`）、`RingingConnection`（open→renew 半间隔续租、2 次失败重开、bootstrap 取 cursor 后订阅 SSE、1s→30s 指数退避、reset_required 回 bootstrap、payload 投影为 SessionEvent）。测试 `RingingRoundTripTest`（MockWebServer 回环 open→bootstrap→SSE→ToolPermissionRespond→ack + 401/reset 用例）4/4 通过；`assembleDebug` 通过。
- 依赖：okhttp 5.5.0（含 sse）、mockwebserver3 5.5.0（**测试用 `mockwebserver3` 包名，非 okhttp3.mockwebserver**；关闭用 `close()` 非 `shutdown()`）、kotlinx-serialization 1.9.0、coroutines 1.10.2。kotlinx.serialization 扩展导入：`kotlinx.serialization.encodeToString`、`kotlinx.serialization.json.decodeFromJsonElement`。
- 后端在 `E:\qaqh-backend`（Rust workspace，v2.0.0-beta.2），架构报告见其 `architecture-report.md`。

## 本机构建环境（重要）

- JDK：`C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot`（JDK 25，跑 sdkmanager 需要 `export JAVA_HOME`）
- Gradle 9.8.0：`E:\qaqh-android\.tools\gradle-9.8.0\bin\gradle`
- Android SDK：`E:\qaqh-android\.tools\android-sdk`（已装 platform 36、37.2、build-tools 36、platform-tools），`local.properties` 已指向它（gitignored）
- 构建：仓库内直接 `./gradlew assembleDebug`，当前 BUILD SUCCESSFUL
- ⚠️ `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指向本地 `tmp/gradle-9.8.0-bin.zip`（Java 不走系统代理，远程下载失败过）。`tmp/` 已 gitignore，勿删该 zip，或改回官方 URL 并配好代理。
- 真机调试：adb 在 `.tools/android-sdk/platform-tools/`；测试机已无线配对（Android 16 / SDK 36，OPPO）。`adb install -r app/build/outputs/apk/debug/app-debug.apk` 装包，logcat 持续抓取写到 `tmp/logcat-session.log`。

## 真机验证记录（2026-10-05）

- ✅ 扫码/手输配对 → 前台服务连接 → `session.list` 会话发现 → Home 卡片（模型/轮次/running）→ bootstrap 活动投影（空闲徽标）→ 挂起交互快照投影（重启不丢）。
- ✅ 修复：Android 9+ 默认禁明文 HTTP 导致首次配对失败 → Manifest 加 `android:usesCleartextTraffic="true"`（**P5 上 TLS 后应收掉并换 networkSecurityConfig**）。
- ✅ 交互应答扩展：permission/plan/ask 三类命令分派（`respondInteraction`），ask 暂只有"关闭交互"；应答失败/被拒显示在 Home。
- ⚠️ **远程审批命令卡在 daemon：手机的 `tool_permission_respond` 被 daemon 受理但 worker 从不消费（TUI 不收敛）。已定位为 daemon 侧 bug——所有 ringing 命令（含 webui 的 ULID 命令、多租约、2487 条 stuck 告警）全部卡 Running。Bug 报告已挂 `qaqh-backend/docs/bug-ringing-v2-commands-stuck-in-running.md`，等后端修复后手机批准链路应即刻可用（wire 格式已被 daemon 验收）。**
- 待真机验证：审批通知的 Live Update 倒计时样式、BiometricPrompt（弹窗正常但端到端被上述 bug 挡住）、同意/拒绝真正生效。
- client 侧遗留（后端修复后做）：`SessionAttach` 未实现（owns_session 收紧后会成硬要求）；ask 交互的回答表单（`interaction_ask_respond` 需解析提问结构 + `question_id`）；`content_ref` 型请求的内容拉取（`/content/{id}`）以显示摘要原文。
- 版本坑：**AGP 9 内置 Kotlin**，不能再用 `org.jetbrains.kotlin.android` 插件；Compose Compiler 插件（`org.jetbrains.kotlin.plugin.compose`）仍需保留。最新 Compose 库要求 compileSdk ≥ 37。

## Ringing v2 协议要点（安卓端实现依据）

- 传输：HTTP + SSE + JSON，鉴权 `Authorization: Bearer <token>`（严格校验，见 `qaqh-daemon/src/axum_server/axum_impl/auth.rs:7-21`）
- 路由：`/ringing/v2/clients/open`、`/ringing/v2/leases/renew`、`/ringing/v2/sessions/{seed}/bootstrap`、`/ringing/v2/sessions/{seed}/events`(SSE)、`/ringing/v2/commands/{id}`、`/ringing/v2/sessions/{seed}/approvals`
- 命令信封 `RingingCommandEnvelope`：`qaqh-ringing/src/envelope.rs:19-38`（schema/version/channel/command_id/expected_revision/command…）
- 状态事件：`DomainEvent::SessionActivityChanged`（`qaqh-domain/src/event.rs:572-576`）
- 审批流：`ToolEvent::ToolPermissionRequested`（`event.rs:479-492`）→ 回 `DomainCommand::ToolPermissionRespond`（`command.rs:310`）
- Rust 客户端参考：`qaqh-client/src/`（client.rs / v2.rs / v2_stream.rs / session.rs）——**实现安卓版前先通读这几个文件**
- daemon 启动：`qaqh server --bind 0.0.0.0 --port <port> --token <token>`；token 未给则随机生成并打印 stderr；`daemon.json`（discovery 文件）含 endpoint+token（`qaqh-types/src/discovery.rs`）

## 待办（按 PLAN.md 阶段）

0. **重要：后端建议稿已对齐（2026-10-05）** — 后端团队提出 `E:\qaqh-backend\docs\plan-mobile-remote-access.md`（扫码配对 + TLS + device_token + scope + 双端原生 app 共享 Rust core）。已分析：**P1 协议层零返工**（wire 格式不变，Bearer 换 device_token 即可）；P2/P3 规格已按建议稿更新进 PLAN.md（JSON 二维码 payload + `/ringing/v2/pair` 换 device_token、审批改用 opaque challenge id、trust_folder 远程默认拒绝）。**网络层决策：安卓首版保留纯 Kotlin，不引入 UniFFI；若后续困难再换 Rust core**（预案写在 PLAN.md 决策记录：Connection 对外三面 API 与 core 同构，UI 层可平移；MockWebServer 测试可当对照基准）。鸿蒙侧由后端走 Rust core + NAPI。后端安全前置 = 其建议稿 M0，安卓端对应 P5。
1. ~~P1 协议层~~ ✅ 已完成（见上）
2. **P2 配对：安卓侧已完成（2026-10-05，未提交）**——`data/pairing/`（`PairingPayload`：建议稿 JSON 格式 + `qaqh://pair` 开发降级解析；`CredentialStore`：EncryptedSharedPreferences/Keystore；`PairingClient`：`/ringing/v2/pair` 换 device_token（M0 未就绪时报 404 提示）+ Direct 校验走 open）+ `ui/pairing/PairingScreen`（CameraX 1.6.2 + ML Kit barcode-scanning 17.3.0 扫码、相机权限、手输降级对话框）+ MainActivity 接线（无凭据→配对屏，有凭据→Home，可解绑）。PairingPayloadTest 5/5 通过，assembleDebug 通过。**剩余：真机联调；等后端 M0 出 /pair + TLS 后做端到端配对验收与 CertificatePinner。**
3. **P3 状态与审批 UI：已接入（2026-10-05，未提交）**——`ConnectionService` 重写为持有 `RingingConnection` 的前台服务：每 15s 轮询 `session.list`（RingingApi 新增 service RPC）发现活跃会话、seed 变化时 `setSessionSeed` 切流（RingingConnection 支持动态换 seed），SSE 事件投影为全局 StateFlow（连接状态/会话活动/待审批列表，UI 直接收集）；审批请求发高优先级通知（approvals channel + 深链 MainActivity）；Home 屏（`ui/home/HomeScreen.kt`）显示连接状态、会话卡片（summary/model/轮次/活动徽标）、待审批卡片（同意/拒绝 → `respondPermission`）。MainActivity：配对成功自动拉起服务、解绑停服务、POST_NOTIFICATIONS 运行时权限。**注：审批应答当前用 canonical call_id，后端 M0 的 opaque challenge id 落地后需切换。**
4. **P4 已完成（2026-10-05，未提交）**——生物认证：`ui/approvals/BiometricGate.kt`（androidx.biometric 1.1.0，BIOMETRIC_WEAK + 设备凭据降级；MainActivity 改继承 FragmentActivity），同意/拒绝前强制把关。Live Update：API 36+ 审批通知用 `Notification.ProgressStyle` 倒计时进度条（API 面已对照本机 android-36 android.jar 核实），每秒刷新，resolved/expired 时取消；低版本回退 BigText。Home 布局规范化：布局常量集中定义（ScreenPadding 16/BlockSpacing 16/CardInner 16/ItemSpacing 8/StatusDot 10），状态点颜色映射、全文省略策略（head/单行 MiddleEllipsis）、按钮等宽 weight(1f)。
5. **P5**：TLS pinning（等后端补 TLS，OkHttp 加 CertificatePinner）+ 配对端到端联调（等 M0 `/pair` + `--show-qr`）← 剩余工作均依赖后端 M0 或真机

## 用户偏好

- 版本尽量最新稳定版；原生设计（Compose），克制复用系统组件
- 决策已定：扫码配对；后端允许分阶段小改
