# QAQ-Harness 安卓端接入规划（PLAN）

状态：已获用户批准（2026-10-04）。配套交接文档见 [HANDOFF.md](HANDOFF.md)。

## 背景（后端调研结论）

- 后端 daemon（Rust/axum，默认 `127.0.0.1:64413`，LAN 模式 `--bind 0.0.0.0`）暴露 **Ringing v2 协议：纯 HTTP + SSE + JSON + Bearer token**。
  - 路由表：`qaqh-backend/crates/qaqh-daemon/src/axum_server/axum_impl/mod.rs:147-186`
  - 客户端流程：`open` → `leases/renew` → `bootstrap` → **SSE `/sessions/{seed}/events`** → `POST /commands/{id}`
  - 参考实现：`qaqh-backend/crates/qaqh-client/`（Rust），安卓端照此实现
- 现成概念：会话状态 `SessionActivityChanged`（含 `WaitingUser`）、审批流 `ToolPermissionRequested` → `ToolPermissionRespond` 命令。
  - 枚举/事件定义：`qaqh-domain/src/event.rs`（`ActivityState`:100-104、`ToolPermissionRequested`:479-492）
  - 命令信封：`qaqh-ringing/src/envelope.rs:19-38`
- 缺口：明文 HTTP、无配对机制（token 靠本地 `daemon.json` 分发）。

## 技术选型

- 网络层：**OkHttp（含 EventSource/SSE）+ kotlinx.serialization + Coroutines/Flow**，纯原生。
- 依赖注入：手动构造（单 `AppContainer`），暂不引 Hilt。
- UI：现有 Compose + Material3（已搭建，AGP 9.4.1 / Kotlin 2.4.20 / compileSdk 37 / targetSdk 36）。

## 安卓端架构

```
app/src/main/java/com/qaqh/harness/
├── data/
│   ├── ringing/            # Ringing v2 协议层（核心接入点）
│   │   ├── RingingApi.kt       # OkHttp 封装：open/lease/bootstrap/commands/SSE
│   │   ├── model/              # 协议模型（对应 qaqh-ringing envelope、DomainEvent 投影）
│   │   └── RingingConnection.kt# 连接生命周期：重连退避、lease 续租、SSE → Flow<DomainEvent>
│   ├── pairing/            # 配对：解析二维码 payload、Keystore/EncryptedSharedPreferences 存 token
│   └── HarnessRepository.kt    # 唯一数据门面：连接状态流、会话状态流、审批操作
├── service/
│   └── ConnectionService.kt    # 前台服务，持有 RingingConnection；实况通知在此更新
├── ui/
│   ├── pairing/            # 配对屏（扫码 ML Kit Barcode Scanning + 手输降级）
│   ├── home/               # 会话列表 + 活动状态 + 待审批列表
│   └── approvals/          # 审批详情/操作（后续加 BiometricPrompt）
└── MainActivity.kt
```

## 分阶段实施

**P1 协议接入（安卓端，不动后端）**
1. `model/`：按 `qaqh-ringing` 的 envelope.rs 与 DomainEvent 投影手写 kotlinx.serialization 模型（只建本期用到的子集）。
2. `RingingApi`：`POST /ringing/v2/clients/open`（带 Bearer）、lease 续租循环、`GET .../events` SSE 转 `Flow`、`POST /commands/{id}`（`ToolPermissionRespond` 等）。
3. `RingingConnection`：指数退避重连、断线检测、`StateFlow<ConnectionState>`。
4. 单元测试：OkHttp MockWebServer 起假 SSE，跑通 open→events→command 回环。

**P2 配对（后端小改）**
1. 约定二维码 payload：`qaqh://pair?v=1&endpoint=http://192.168.x.x:64413&token=...&proto=1`，结构定义放在 `qaqh-types/src/discovery.rs` 旁（新增 `pair.rs`），电脑端 CLI 加 `qaqh server --show-qr` 打印终端二维码。
2. 安卓端：ML Kit 扫码 → 解析校验 `proto` 版本兼容 → 凭据存 EncryptedSharedPreferences（key 在 Keystore）；配对屏留手输降级。
3. 首连校验：用 token 调一次 `open`，成功即完成配对。

**P3 状态与审批 UI**
1. Home 屏：会话列表 + `ActivityState` 徽标（关注 `WaitingUser`），数据来自 SSE 投影。
2. 审批：收到 `ToolPermissionRequested` 时发高优先级通知（点进深链到审批屏），展示 summary/category/risk，同意/拒绝 → `ToolPermissionRespond` 命令。
3. 前台服务通知常驻显示当前活跃会话状态。

**P4 Android 16 实况通知 + 生物认证**
1. 通知升级为 **Live Update**（`Notification.ProgressStyle`，SDK 36+）：审批等待时显示倒计时/进度样式。
2. 审批操作前 `BiometricPrompt` 把关（README 规划的生物认证授权），可在设置中开关。

**P5 传输安全（后端阶段二，单独立项）**
- 后端补 TLS（自签证书 + 二维码里带证书指纹 pinning）后，安卓端只需在 OkHttp 加 CertificatePinner，协议层不变。

## 用户决策记录

- 配对方式：**扫码配对**（手输 IP+token 作为降级）。
- 后端修改：**允许但分阶段**——本期安卓端纯 HTTP + Bearer，传输层抽象好；TLS 后端补上后安卓端只换配置。

## 明确不做（本期）

- WebSocket/gRPC（协议已是 HTTP+SSE）
- mDNS 自动发现（扫码已够）
- 后端协议面改动（只加 `--show-qr` 与配对 payload 结构）

## 验收

- `gradlew assembleDebug` 通过 + MockWebServer 协议回环测试通过
- 真机与电脑端同一局域网：扫码配对 → 看到会话实时状态 → 收到审批通知 → 手机上完成一次同意/拒绝
