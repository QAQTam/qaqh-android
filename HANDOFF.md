# HANDOFF — QAQ-Harness 安卓端

给后续会话/接手者的交接说明。规划见 [PLAN.md](PLAN.md)。

## 项目现状（2026-10-04）

- 仓库：`E:\qaqh-android`，已有两个提交（`README` + 初始骨架）。
- 已完成：Gradle 项目骨架、`MainActivity`（Compose 骨架）、`ConnectionService`（前台服务占位，`dataSync` 类型）、Manifest 权限声明。**协议层尚未实现。**
- 后端在 `E:\qaqh-backend`（Rust workspace，v2.0.0-beta.2），架构报告见其 `architecture-report.md`。

## 本机构建环境（重要）

- JDK：`C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot`（JDK 25，跑 sdkmanager 需要 `export JAVA_HOME`）
- Gradle 9.8.0：`E:\qaqh-android\.tools\gradle-9.8.0\bin\gradle`
- Android SDK：`E:\qaqh-android\.tools\android-sdk`（已装 platform 36、37.2、build-tools 36、platform-tools），`local.properties` 已指向它（gitignored）
- 构建：仓库内直接 `./gradlew assembleDebug`，当前 BUILD SUCCESSFUL
- ⚠️ `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指向本地 `tmp/gradle-9.8.0-bin.zip`（Java 不走系统代理，远程下载失败过）。`tmp/` 已 gitignore，勿删该 zip，或改回官方 URL 并配好代理。
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

1. **P1 协议层**：`data/ringing/` 模型 + `RingingApi` + `RingingConnection`（退避重连）+ MockWebServer 回环测试 ← 下一步从这里开始
2. **P2 配对**：二维码 payload `qaqh://pair?v=1&endpoint=...&token=...&proto=1`；安卓端 ML Kit 扫码 + EncryptedSharedPreferences 存凭据；后端加 `--show-qr`（改动在 `qaqh-backend`，需切到那个仓库做）
3. **P3 UI**：Home 会话状态流 + 审批通知/操作
4. **P4**：Android 16 Live Update（`Notification.ProgressStyle`）+ BiometricPrompt 审批把关
5. **P5**：TLS pinning（等后端补 TLS）

## 用户偏好

- 版本尽量最新稳定版；原生设计（Compose），克制复用系统组件
- 决策已定：扫码配对；后端允许分阶段小改
