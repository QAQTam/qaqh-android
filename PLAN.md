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

**P2 配对（对齐后端建议稿 `qaqh-backend/docs/plan-mobile-remote-access.md` §3，2026-10-05）**
1. 二维码 payload 采用建议稿 JSON 格式（不再用原 `qaqh://pair?...` 方案）：
   `{ "v":1, "kind":"qaqh-pair", "base_url":"https://<lan-ip>:64413", "pairing_token":"<one-time>", "tls_fp":"sha256:<hex>", "host_name":"..." }`。
   payload 结构定义在 daemon 侧（`qaqh-types` pairing 模块），桌面壳渲染二维码（后端负责）。
2. 配对流程：扫码校验 `kind`/`v` → TLS 连接 + `tls_fp` pinning 校验 → `POST /ringing/v2/pair { pairing_token, device_name, platform, scope_request }` → 换取 **device_token**（长期、per-device、可吊销，scope 默认申请 `interact`）→ 存 EncryptedSharedPreferences（key 在 Keystore）。移动端永不接触 admin token。
3. 首连校验：用 device_token 调一次 `open`，成功即完成配对。此后日常重连只靠 device_token，无需桌面端在线配合。
4. 前置依赖：该流程依赖后端 M0（rustls + pair 端点 + 设备注册表）；M0 未就绪前，P2 可先用临时全局 token 联调（局域网明文，仅开发用）。

**P3 状态与审批 UI（对齐建议稿 §4.6-4.7 约束）**
1. Home 屏：会话列表 + 活动状态徽标，数据来自 SSE 投影；多会话=单活跃流 + 后台轮询 `session.list`（不并行多流）。
2. 审批：收到待审批事件时发高优先级通知（点进深链到审批屏），展示 summary/category/risk。**应答用 `/approvals` 下发的 opaque challenge id（一次性、TTL 5min），canonical `call_*` id 不出 daemon**（后端 M0 第 6 条落地后生效）。首答即终：输方按 typed verdict 收敛 UI，不重试。
3. **trust_folder 远程默认拒绝**（M7）：远程 scope 来源的 `trust_folder=true` 应答一律不发；UI 不提供该开关。
4. 前台服务通知常驻显示当前活跃会话状态；后台掉 lease 靠现有 re-open + 重 bootstrap 路径回前台重协商。

**P4 Android 16 实况通知 + 生物认证**
1. 通知升级为 **Live Update**（`Notification.ProgressStyle`，SDK 36+）：审批等待时显示倒计时/进度样式。
2. 审批操作前 `BiometricPrompt` 把关（README 规划的生物认证授权），可在设置中开关。

**P5 传输安全（= 后端建议稿 M0，daemon 侧为主）**
- 后端 rustls 自签证书落地后，安卓端在 OkHttp 加 CertificatePinner（指纹来自二维码 `tls_fp`），协议层与连接层不变。
- 凭证从临时全局 token 换为 device_token（仍走 `Authorization: Bearer`，`RingingApi` 零改动）。
- 后端 owns_session / scope 检查（M0 第 3-4 条）对安卓端透明，无需配合改动。

## 用户决策记录

- 配对方式：**扫码配对**（对齐后端建议稿 D1：一次性 pairing_token 换 device_token；原"手输 IP+token"降级仅作开发联调用）。
- 后端修改：**允许但分阶段**——本期安卓端纯 HTTP + Bearer，传输层抽象好；TLS 后端补上后安卓端只换配置。
- **网络层路线（2026-10-05，回应后端建议稿 D2）**：安卓首版保留**纯 Kotlin 协议层**（P1 已交付），不引入 UniFFI/Rust core。鸿蒙侧由后端走 Rust core + NAPI（同一 core 服务两端中鸿蒙一端）。若后续出现实现困难（如 v2 契约演进导致双份维护成本过高）或协调不便，再评估切换到 UniFFI 绑定 qaqh-client。
- 换 Rust core 的预案（降低未来返工成本）：
  - 协议知识已有 MockWebServer wire 格式测试兜底，可作为 Rust core 行为的对照基准；
  - `RingingConnection` 对外只暴露 `state` / `events` / `respondPermission` 三个面，与建议稿 §5.1 core API 面（异步方法 + 事件回调）同构，UI 层可整体平移；
  - 凭证存储、配对 UI、通知/前台服务全部与网络层解耦，不受该决策影响。

## 明确不做（本期）

- WebSocket/gRPC（协议已是 HTTP+SSE）
- mDNS 自动发现（扫码已够）
- UniFFI/Rust core 集成（首版纯 Kotlin，见决策记录）
- 审批推送（FCM/厂商推送，建议稿 §6 列为独立决策点，不阻塞）
- 后端协议面改动（pairing 端点、TLS、scope 均由后端 M0 承担，见 `qaqh-backend/docs/plan-mobile-remote-access.md`）

## 验收

- `gradlew assembleDebug` 通过 + MockWebServer 协议回环测试通过
- 真机与电脑端同一局域网：扫码配对 → 看到会话实时状态 → 收到审批通知 → 手机上完成一次同意/拒绝
