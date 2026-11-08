# 2026-11-05 Rust FFI 传输层重写(autonomous run)

> 本文件是压缩存活痕迹:每完成一个里程碑立即更新。用户已全权授权(睡觉中),
> 目标 = qaqh-android 传输层整体切 Rust FFI(UniFFI 绑 qaqh-client)并在
> 真机(192.168.31.111:5555, root, 无密码)端到端可用。

## 背景与决策(已批准的计划)

- **为什么**:纯 Kotlin 协议层与 backend v3 断链(5 处已验证 break,第一条命令
  `session_attach` 即 404;timeline 端点整个被删)。缝补 = 每轮重移植 qaqh-client。
- **方案**:新 `crates/qaqh-ffi`(backend workspace 内,不新建仓库)+ UniFFI 0.32
  proc-macro + tokio;控制面窄类型 / 数据面 JSON passthrough;Kotlin 保留
  ~300 LOC 纯数据模型,UI 层不动;删 Kotlin 旧协议层 ~2.4k LOC。
- 计划原文:qaqh-android 会话 PLAN 已批准(M0-M5 六阶段)。

## 环境事实(本机)

- Android SDK 根 = `/home/qaqtamsy/Projects/hiandroid`(`env.sh`;~/.bashrc 已导出
  ANDROID_HOME 但非交互 shell **不生效**——每条命令都要 `source .../env.sh`)。
- NDK 30.0.16248370 已装(`$ANDROID_HOME/ndk/`);rust targets aarch64/x86_64
  linux-android 已装;cargo-ndk 4.1.2 已装;Rust 1.99.1。
- qaqh-android 构建环境已修:wrapper → `file:///home/qaqtamsy/Projects/hiandroid/gradle-9.8.2-bin.zip`;
  新建 local.properties → sdk.dir=/home/qaqtamsy/Projects/hiandroid。
  **assembleDebug 已通过**(7m44s,115MB debug apk)——AGP 9.4.1 × Gradle 9.8.2 × JDK25 兼容。
- **资源门控(常设)**:8 核 / 7.7G RAM / zram swap 3.8G / 无 sudo(不能加 swap)。
  gradle daemon 吃 2.5G——每次构建完 `./gradlew --stop`。构建前查 `free -h`
  (要求 avail≥4G)与 `df -h /`(79G 余,backend target 已 5.5G)。
- 手机:adb connect 192.168.31.111:5555,root,无密码,自行解锁调试。

## 代码状态(截至本文件最后更新)

### M1 已写完(待测试确认):
- 新 `crates/qaqh-client/src/tls_pin.rs`:parse_fingerprint/der_fingerprint/
  PinnedServerCertVerifier(DER sha256 匹配即信任,握手签名仍交 provider 校验;
  模块单测含 rcgen 真证书 accept/reject)。
- `crates/qaqh-client/src/client.rs`:RemoteEndpoint+tls_fp 字段;
  build_http_client(tls_fp) 助手(rustls ClientConfig::builder_with_provider →
  dangerous().with_custom_certificate_verifier → reqwest .tls_backend_preconfigured);
  `pair_remote()` 自由函数(POST /ringing/v2/pair,免鉴权);connect_async 接线。
- `crates/qaqh-client/src/v2.rs`:api_error 改 pub(crate);新增
  `device_challenges()`(GET approvals,typed)+ `respond_device_approval()`
  (POST approvals/respond → RingingV2CommandAck)。
- `lib.rs`:导出 pair_remote + RingingV2PairResponse/RingingV2ApprovalRespondRequest/
  RingingV2DeviceApprovalKind/RingingV2DeviceChallenge/RingingV2DeviceChallenges;mod tls_pin(私有)。
- Cargo.toml dev-dep + rcgen 0.14.10;examples/remote_fs.rs 字面量补 tls_fp: None。
- 已修一处编译错(ServerCertVerified 无 PartialEq → matches!)。
- **下一步**:`cargo test -p qaqh-client --lib` + clippy。

### 待办(顺序):
- M0 尾:cargo-ndk 最小 cdylib 冒烟(aarch64-linux-android)。
- M2:crates/qaqh-ffi——uniffi 0.32.2 features=["tokio"],crate-type cdylib+rlib,
  `src/bin/uniffi-bindgen.rs`(`uniffi::uniffi_bindgen_main()`);surface:
  pair_remote→PairResult / QaqhClient{connect,attach,activate/deactivate_stream,
  bootstrap→JSON,session_list,workspace_list,send_command_json(+常用命令 typed 糖:
  tool.permission.respond/interaction.ask.respond/driver.claim/release/conversation.send_message),
  device_challenges/respond_device_approval→JSON,service_json,content download,close};
  callback_interface CoreHandlers{on_event(session_id,seq,event_json),on_reset,on_status(StreamStatus 枚举),on_liveness};
  StreamStatus{Connecting|Open{server_epoch,cursor}|Reconnecting{retry_ms,reason}|Closed{reason}}
  ← V2StreamStatus 直映射(client.rs:45-70 ClientHandlers);错误 enum CoreError。
  编排参照 TUI(qaqh-tui-app/src/runtime.rs:675-737):connect→attach→activate_stream,
  收 Open 再 bootstrap,on_reset 重 bootstrap。桌面单测(JSON roundtrip/connect 校验)。
- M3:qaqh-android/core/build-core.sh(cargo ndk -t arm64-v8a -t x86_64 -o
  app/src/main/jniLibs build -p qaqh-ffi;uniffi-bindgen generate --language kotlin);
  迭代期用 debug .so 省内存(release 有 workspace LTO,最后一并做,必要时限 -j)。
- M4:Kotlin data/core(见计划);模型来源 = qaqh-ringing-contract/src/events.rs(20 变体,
  serde tag="type" snake_case)+ state.rs;fixture oracle =
  /home/qaqtamsy/Projects/qaqh-sdk/src/protocol/generated/fixtures/*.json。
- M5:adb 全流程(见下)。

### 真机 e2e 预案(M5):
- `adb connect 192.168.31.111:5555`;解锁:`input keyevent KEYCODE_WAKEUP` +
  `wm dismiss-keyguard`(root);装包 `adb install -r app-debug.apk`;
  `dumpsys deviceidle whitelist +com.qaqh.harness`。
- daemon:本机 build -p qaqh-daemon(dev profile 防 LTO 内存炸);LAN 模式
  `qaqh-daemon server --bind 0.0.0.0 --port 64413 --token <t>`(自动 TLS+自签证书,
  tls_fp 来自 daemon.json);备选 `adb reverse tcp:64413 tcp:64413` 走 http 明文短路。
- 配对自动化:app 有 dev 直连模式(deep link `qaqh://pair?...`,admin token)——
  `adb shell am start -a android.intent.action.VIEW -d "<url>"`;QR 扫码需真人,睡觉跳过,
  device scope 路径留人工验证,handoff 里注明。

## 提交策略
里程碑级本地 commit(不 push),遵循各仓现有 commit 风格(backend 中文 conventional)。

## 变更日志
- 2026-11-05 初始:M0 完成(工具链+构建环境+assembleDebug 通过);M1 代码写完待测;
  内存治理策略确立(gradle daemon 2.5G 是大户,停后 avail 4.0G)。

## 变更日志(续)
- M1 提交 2c6d53ea(backend):tls_fp pin + pair_remote + device 审批面,测试/clippy 净。
  存量失败 refresh_discovery_adopts_a_changed_record 与本改动无关(stash 验证)。
- M0 冒烟:qaqh-client 全依赖树 aarch64-linux-android 编译通过(58s)。
- M2 提交 dca885d6(backend):crates/qaqh-ffi(uniffi 0.32.2 proc-macro+tokio)。
  坑1:错误变体字段不能叫 message(撞 Throwable.message)→ 改 detail;
  坑2:导出方法不能叫 close(撞 Disposable.close)→ 改 shutdown;
  坑3:--library 模式 per-crate uniffi.toml 不自动发现 → --config 传
  crates/qaqh-ffi/uniffi-global.toml([crates.qaqh-ffi.bindings.kotlin])。
  Kotlin 绑定包名 com.qaqh.harness.core.ffi,61 suspend fun 生成验证。
- M3 提交 64d710e(android):core/build-core.sh(内存门控+llvm-strip+绑定生成),
  JNA 5.17.0@aar 接入,debug .so strip 后 13M/15M,:app:compileDebugKotlin 通过。
- 本机 git 身份补配(仓库级,同 backend:QAQTam <tsy3minso@gmail.com>)。
- 下一步 M4:读 RingingConnection/ConnectionService/SessionTimeline/TodoState +
  UI 消费点,写 data/core(CoreClient/CoreConnection/CoreModels),删旧协议层。
- M4 提交 2b4c6b2(android):传输层切 FFI 完成。新层 data/core 三件套 +
  SessionTimeline v3 reducer + ConnectionService/PairingClient 重写;
  删旧协议层 ~2.4k LOC;36/36 单测绿(含 Roborazzi 基线)。FFI 侧补
  lease_state()(LeaseState record,Connected 状态字段来源)。
  坑4:CoreConnection 嵌套 sealed interface SessionEvent 会遮蔽顶层契约同名类型
  → 投影泵里用 import alias(ContractEvent);嵌套类字段引用同名嵌套类要全限定。
  坑5:uniffi u16/u64 → Kotlin UShort/ULong(Int? 断言处 .toInt());ULong seq → .toLong()。
  已知限制(有意):v3 无 REST 分页 → loadOlder 直达 EndReached(快照即全量);
  exec/subagent 事件手机端暂不渲染(模型收 JsonElement,解析不失败)。
- 下一步 M5:adb(192.168.31.111:5555) 装真机 + 本机 daemon e2e。

## 真机 e2e 纪要(M5,2026-11-05 02:46-03:00)

**结果:全链路真实可用。** 环境:PHK110(OnePlus,Android 16,KernelSU root,
adb tcp 192.168.31.111:5555) × 本机 daemon(server --bind 0.0.0.0:64413,
QAQH_DATA_DIR=/tmp/e2e/qaqh,LAN+TLS)。

流程与证据:
1. 装包:adb install;解锁=WAKEUP+dismiss-keyguard;白名单 deviceidle;权限 pm grant。
2. 配对:桌面 curl POST /ringing/v2/pairing/tokens(loopback 明文面,注意
   64413 是 TLS 面明文打过去会 HTTP/0.9 错)→ 构造 QR JSON → 注入调试通道
   `am start --es qaqh_pair_payload '<json>'` → Rust pairDevice(TLS pin 生效)
   → daemon /ringing/v2/devices 出现设备(interact)。
3. 连接:Home 显示「已连接电脑端」(lease 协商+续租在 Rust)。
4. 建会话:点新建会话 → daemon sessions 表 +1。
5. 发现:15s 周期 session.list → 分组卡片「未分组 1 · 未命名会话」。
6. 聊天:进屏 attach+bootstrap+单流;input text + 发送 → 命令受理,turns/blocks
   落库;**气泡来自 TimelineAppended 事件回推(零乐观刷新,纯 SSE 路径)**。
7. 未验证项:真模型回合(本机 daemon 未配 provider,发消息后 turn 不跑——传输
   层已闭环,模型链路属于 daemon 配置问题);审批 challenge 流(单测覆盖,真机
   需要一个正在跑的工具请求才能触发)。

**三个真机坑(都已修):**
- `libc++_shared.so` 缺失 → dlopen 崩:build-core.sh 从 NDK sysroot 拷入 jniLibs。
- `there is no reactor running`:uniffi 导出必须标 `async_runtime = "tokio"`
  (async_compat::Compat 才会包 future 给出 tokio 上下文),后端仓 e39fa49a。
- 主屏新建会话静默失败:createSession 误走 driver 恢复路径要求 seed →
  Global 命令直发(38d7325)。

**留给用户:** daemon 仍在跑(/tmp/e2e/qaqh,token 在 /tmp/e2e/token.env);
手机已配对可直接继续体验;配 provider 后即可真模型对话。重启 daemon 后
手机端走既有 device_token 自动重连(daemon.json 换 epoch 会触发重协商)。

## 收尾(03:10)

- release .so:arm64-v8a 4.6MB / x86_64 5.5MB(LTO 全量,无 OOM);最终 APK
  (3.0.0-alpha.1 + release core)重装真机 → 自动重连,设备心跳 1.2s,
  历史会话/轮次完整显示。
- daemon 重启自愈验证:换代 epoch 后 10.9s 手机重新上线(Rust 侧重协商+重
  attach+流恢复全自动)。
- 提交索引(本仓):64d710e → 2b4c6b2 → 38d7325 → a2ef8c7 → 613dfca;
  (后端仓):2c6d53ea → dca885d6 → e39fa49a。
- 供用户:daemon 仍在跑(QAQH_DATA_DIR=/tmp/e2e/qaqh,token /tmp/e2e/token.env,
  loopback 端口每次重启会变——读 daemon.json);手机已配对即开即用;
  配置 provider 后可真模型对话。恢复生产环境时把 QAQH_DATA_DIR 指回默认即可。
