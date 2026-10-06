# HANDOFF — QAQ-Harness 安卓端

给后续会话/接手者的交接说明。规划见 [PLAN.md](PLAN.md)。

## 本轮收尾状态（2026-10-06 下午，接手者从这里继续）

- **P5 剩余两项已收口（2026-10-06 晚，真机冒烟通过）**：① P5b `usesCleartextTraffic` → `networkSecurityConfig`：main 配置全禁明文（正式链路 https+pinning），新增 `app/src/debug/res/xml/network_security_config.xml` 放开明文（debug 构建 LAN Direct 手输配对仍可走 http），已用 aapt2 验证 debug APK 合并结果 `cleartextTrafficPermitted=true`；② P5c attach/bootstrap 时序收紧：`RingingConnection.connectOnce` 的 attach 不再 `runCatching` 静默吞失败——ack=rejected 抛 `ApiException(0, code)` 判本轮周期失败走退避重试（避免带未 attach 租约去撞 401 lease_required/403），`streamEvents` 里 bootstrap 后的重复 attach 已删（时序定为 open → attach → bootstrap → SSE，每租约恰好一次）。真机装机后 logcat `attach ok ... ack=accepted` 恰一条、会话发现正常。**P5 只剩 a) 配对端到端验收（仍等桌面 `--show-qr`，backend 仓库 grep 确认未实现）。**
- **未提交改动已含全部修复，先 `git add -A && git commit`**（若上一会话没来得及提交）。
- **ask（提问）面板三连修（真机验证）**：①"关闭（中止回合）"不再弹生物认证（那是授权动作的关卡，ask 中止不是授权，`ApprovalCard.respondWithGate` 对 kind=ask 直接放行）；②ack 一被接受就**乐观移除**面板和通知（`respondApproval` 成功分支 filterNot），不再等可能迟到/丢失的 `interaction_resolved` SSE——这是"点关闭面板不消失"的主因；③摘要清洗：**ask 的 request 是 ref 型**（`{kind:"ref",data:{content_ref}}`），fetch `/content/{id}` 拉回的原文就是 ask 载荷 JSON（`{kind:"ask",questions:[{question,options}]}`）——`extractSummary` 现在先 `askSummaryOf` 解析出问题文本（如"你更喜欢猫还是狗？"），不再透传原始 JSON。inline text 里嵌 JSON 的形状也兜了。
- **⚠️ 凭据丢失第 5 次的真相（重要，收回旧结论）**：不是 OPPO ESP 又坏了——是本日新增的"吊销自动掉回配对屏"**误杀**：后端 `lease_required`（401+JSON code）也被判成吊销 → `CredentialStore.clear()` 把主存和备份一起清了（备份文件 `<map />` 即证据）。已修两层：①`RingingConnection` 只有无 code 的真鉴权失败（后端 auth.rs 裸文本 "unauthorized"）才置 `unauthorized=true`，`lease_required` 排除；②ConnectionService 需**连续 2 次**真鉴权失败才 wipe（单次不 reversibly 清凭据）。**给后端**：`lease_required` 与鉴权失败共用 401 但形态不同（JSON code vs 纯文本），建议长期分开状态码。
- **连接风暴修复**：`runWithBackoff` 的退避重置原来在 `connectOnce()` 成功后立即执行——bootstrap 持续失败（本日实测 `409 snapshot_missing: canonical snapshot cursor is unavailable`，正是后端 v1/v2 迁移正在处理的问题）时 connectOnce 每次都成功 → 退避永远 1s → attach 风暴（实测 ~1.1s/次）。已改为**只有走完 connectOnce+streamEvents 完整周期才重置**，失败按 1→2→4…30s 指数退避。
- **NetworkOnMainThreadException 两例（模式教训）**：`ConnectionService` companion 里的网络函数若从 UI scope 调用必须切 IO——`respondApproval`（UI 协程直接调，已 `withContext(Dispatchers.IO)`）和 `refreshNow`（`scope.launch(Dispatchers.IO)`）。新代码凡在 companion 暴露 suspend 网络函数的，默认套 IO。
- **真机验证过**：ask 摘要干净、点关闭无生物认证、面板即点即消失（最后截图面板已消失，会话恢复可发消息——注意验证时该会话正被后端 409 snapshot_missing 折腾，属后端侧）。
- **遗留（按优先级）**：① ask 回答表单（questions 渲染 + answers 提交）——与 M0 challenge 流一体，等后端 v2（`/approvals/respond` ask→submit{answers}）；② M0 challenge 流切换（同前，等 device 身份配对可用）；③ 流式期尾部渲染已做（streamTail 1200 字符），若还有 jank 再深化；④ 重复会话洪水（39 个 raw，联调测试制造）需要在 daemon 侧清理或加防护；⑤ OPPO ESP 依旧不可信（双写兜底保留），长期可考虑迁移到 Keystore 直管 + 文件加密。
- **调试技巧补充**：`adb shell input text` 遇空格必须用 `%s`（否则截断在第一个空格）；键盘弹起后发送按钮位置会变，点前重新 uiautomator dump；`logcat -c` 时机要小心——排查"日志没出现"前先确认不是自己清掉的；OPPO 进程日志配额会丢 D 级日志，关键探针用 I/W 级。

## 项目现状（2026-10-06）

- **TLS + SPKI pinning 已完成并真机验证（2026-10-06）**：daemon 已启用自签 rustls（https，`daemon.json` endpoint 已是 https://60.0.0.65:64413）。新增 `data/pairing/TlsPinning.kt`：`SpkiPinnedTrustManager`（指纹即信任根，不做有效期/主机名校验）+ `pinnedHttpClient(fp)`，注入 PairingClient（配对时）与 ConnectionService→RingingConnection（运行期）；`StoredCredentials.tlsFingerprint` 持久化指纹；手输配对对话框增加"TLS 指纹"必填项（https 时）。⚠️ **后端 bug 待反馈**：`/pairing/tokens` 返回的 `tls_fp` 实际是**整张 DER 证书**的 sha256（`tls.rs:46 sha256_hex(leaf.as_ref())`），与 `types.rs:101` 自己的文档（"SPKI 的 sha256"，与 OkHttp pin 语义对齐）不符。安卓端已兼容两种语义（都算、任一匹配）；若后端改齐为 SPKI 也无需改安卓端。
- **聊天回复不实时上屏已修复并真机验证（2026-10-06）**：手机发 ping → "pong"+思考过程块**实时上屏**，无需重进。双管齐下：① `RingingConnection` 新增 `leases: SharedFlow<String>`（replay=1，每次 open 成功发新值），`SessionTimeline` 订阅循环改为**跟随租约重订阅**（`leases.first()` 取当前租约 + `drop(1).first()` 等轮换即取消重订）——旧实现只在 Connected 状态变化时订阅，而续租失败路径的 connectOnce 不经过 Connecting，租约已换但 SSE 还挂着旧租约，服务端逐帧静默丢弃；② `RingingApi.timelineEvents` 的帧解码失败原先被 `runCatching` **静默吞掉**（表现为"零帧"且无线索），已补日志。真机 75s 观察：零重连、零 401、零解码失败。
- **消息实时性对齐 webui 消费模型（2026-10-06，实测首帧 <1s）**：用户反馈"移动端总是落后桌面端几秒"。对比 `E:\qaqh-backend\webui`（Tauri + qaqh-client Rust 宿主）后确认安卓消费方式错了：webui 的 timeline SSE 永不全量回放——重连带 `Last-Event-ID: {epoch}:timeline:{seq}` 只补尾巴，seq 缺口才拉快照重定基，另有 45s idle 看门狗（服务端 timeline 流每 15s 发 keep-alive 注释保活，`sse.rs` KeepAlive::interval(15s)）。安卓旧实现从不带游标 → 每次订阅服务端全量回放整个会话（900+ 帧），断流后要先吞完全部重放才追上尾巴。已改：`SessionTimeline` 维护 `lastSeq`（REST 页用 snapshot.watermark 推进、SSE 逐帧推进），订阅时带 `{epoch}:timeline:{lastSeq}` 游标（epoch 变了服务端自动退回全量，reducer 幂等）；60s 无帧看门狗（服务端 keepalive 字节 OkHttp 监听器看不到，只能按帧级空闲判定）触发带游标重订阅。真机量化：发送→turn_opened 首帧 <1s，长回复期间 delta 帧随生成节奏持续到达，工具块状态实时翻转。webui 渲染层还有 rAF 合帧/水位去重等优化，安卓目前 StateFlow 逐帧赋值 + LazyColumn key=turnId 够用，若以后流式掉帧再参照 `webui/src/lib/session/store.ts` 优化。
- **timeline reducer 已对齐后端 TimelineEvent 实际形状（2026-10-06，对照 qaqh-domain/timeline.rs）**：`block_opened` 的块数据在 `event.block` 下（旧代码读顶层 block_id 全部丢弃）；新增 `block_checkpoint`（arg 增量 / text 覆写）与 `tool_progress`（chunk 追加到 tool.output）分支；`block_sealed` 不再有 text 字段（只翻状态）。`turn_opened` 事件无 turn_index（那是 TimelineTurn 字段），新 turn 用 Long.MAX_VALUE 兜底排序。
- **吊销自动掉回配对屏（2026-10-06）**：`RingingConnection.State.Failed` 增加 `unauthorized` 标记；ConnectionService 收到 401 失败 → 清凭据 + stopSelf + `authRevoked` StateFlow；MainActivity 收集后清 credentials 回配对屏。
- **UI 渲染第一轮 + 性能夹具（2026-10-06）**：①思考链卡片折叠态默认显示**最新 3 行**（`remember(block.text)` 只为流式中的块重算尾部）；②工具卡：运行中转圈、失败红色标签、mono 参数/输出 + 复制按钮；③自动追底改为**贴底检测**（`derivedStateOf` 读 layoutInfo）——上翻历史不被打断，流式滚动 80ms 节流（scrollToItem + scrollBy 补滚溢出高度）；④性能夹具三层：JVM reducer 基准（`SessionTimelineReducerPerfTest`：10k delta **13.6µs/帧**、30k 帧 GC 驻留 **+1.24MB**——无树堆积的直接证据，阈值宽松防回归）、debug 重组探针（`DebugRecomposeProbe`，注意 OPPO 进程日志配额会丢日志）、**JankStats**（debug 门控，>32ms 帧打 logcat `JankStats`）。真机实测：~35-40 帧/秒 delta 流下约每秒 1 帧 33-53ms jank，主因是增长的 Text 段落每帧全量重排版（Text 固有成本）；下轮优化方向 = webui 同款"流式期只渲染尾部、sealed 后整体渲染"。
- **工具卡数据清洗对齐 TUI（2026-10-06 第二轮）**：学 `E:\qaqh-tui-app`（ui/v2/adapter.rs + transcript.rs）的数据策略，新增 `ui/session/ToolFormatter.kt`：标题 = 中文动词（exec/shell→运行、read→读取、grep→搜索…、path.op→编辑/写入/补丁/删除）+ typed `display.header` 正文（shell.command/path/query，路径 48 字符中段省略）；header 缺席回退 summary 但 **JSON 形摘要一律拒收**（isJsonLike）；**args_json 永不进标题**（默认隐藏，展开后按"原始参数"查看）；正文 = typed body → output → progress，清洗管线 = 剥 ANSI → CRLF/\r 覆盖折叠 → 剔控制字符；折叠预览取输出**尾部** 3 行 + "+N 行（点击展开）"，失败头部带 (exit N)、理由只在正文一次；时长来自 display.metrics.elapsed_ms。`TimelineTool` 模型补齐后端实际字段（progress/display/failure/diff）；reducer 的 tool_progress 增量改存 `progress`（8K 截尾保尾，对齐 TUI MAX_PROGRESS_LEN），不再污染 output。
- **自动追底重写（2026-10-06 第二轮）**：旧实现用 layoutInfo 推断"是否贴底"，contentPadding/长 item 残余偏移导致判定抖动失效（用户报告"不会自动追底"）。改为交互驱动 `followBottom` 开关：仅用户拖动（DragInteraction.Start）置 false、拖回底部（!canScrollForward）或自己发消息（新回合带 user_text）恢复 true；pinToBottom = scrollToItem(last) + scrollBy(10_000f)（LazyList 在真正底边 clamp，天然处理高于视口的 item）。
- **Qoder 式过程折叠 + Markdown（2026-10-06 第三轮）**：解剖 Qoder（Electron，`app.asar` 字符串挖掘）得到其三层折叠：工具卡默认收起、完成过程按类别计数聚合一 行（"执行工具 {{count}} 次 / 搜索 N 次、修改 N 个文件"）、**回复完成后把正文前的全部过程（思考+工具）收进活动详情**，细节放独立 executionTrace 视图。安卓端移动化落地（TurnItem）：①回合进行中=聚合芯片"已运行工具 · N 步 <分类计数>"（失败红）+ 当前思考卡（3 行尾）+ 运行中工具单行芯片（转圈+动词，无正文）；②回合完成后=过程整体收成"查看执行过程 · N 步"一行，聊天流只剩用户气泡+正文；③点芯片 → ModalBottomSheet 完整过程列表（复用 ToolCard/ReasoningItem）。**Markdown 已接入**（mikepenz multiplatform-markdown-renderer 0.45.0，m3 模块）：text 块 `sealed` 后 Markdown 渲染（行内代码/标题/列表等），流式中仍纯文本（每帧重解析是唯一卡顿做法）。新增依赖：material-icons-core（BOM 管理版本）。文件级 `@OptIn(ExperimentalMaterial3Api)`。
- **todo UI + 实况通知 + diff + 流式尾部（2026-10-06 第四轮，真机验证）**：①`TodoState`（data/ringing/model/TodoState.kt）对齐 TUI TodoBlock 契约：`todo_write` 整表替换（items 数组、title 空且带既有 id 沿用旧标题、畸形整体拒绝）、`todo_update` 单条 delta（id 未知忽略）、id 归一 "1"/"T1"→T{n}、排序进行中→待办→已完成；SessionTimeline 在 block_opened/tool_updated 帧摄取 + 分页后 rebuildTodos() 重放。②会话内 TodoPanel（全部完成自动隐藏，折叠预览 3 行，✓/▸/○ 标记）。③**实况通知**：API 36+ `Notification.ProgressStyle`（待办 N/M 进度条 + "进行中：<条目>"，channel=todo LOW），低版本回退 setProgress；由聊天屏 LaunchedEffect 驱动（SSE 只在屏内流动），全部完成即取消。④diff 渲染：ToolCard 展开区 DiffView（+绿 −红 @@灰，cap 200 行），取 tool.diff 或 display.body(kind=diff).unified。⑤流式文本只渲染尾部 1200 字符（streamTail，sealed 后全文 Markdown）——把"增长段落每帧全量重排版"的 jank 封顶。单测 TodoStateTest 5 项通过。真机：模型真实 todo_write/update → 面板"待办 1/3 ✓alpha"正确、通知 channel=todo 挂载。
- **新发现缺口（下轮）**：ask 交互卡片摘要显示原始 JSON——`ConnectionService.extractSummary` 只处理 permission 的 text/content_ref 结构，ask 的 request（`{"kind":"ask","questions":[{id,question,options}]}`）需要解析成"问题 + 选项列表"，且 ask 的回答表单（challenge 流的 answers 提交）仍是遗留缺口，两者一起做。
- **待办（下轮 UI）**：① Markdown 库接入（sealed 后渲染，流式仍纯文本）；② 流式期只渲染尾部降 jank；③ **todo UI**：数据来自 `todo_write`/`todo_update`/`todo_list` 的 `args_json`（`{"items":[{id,title,status,description?}]}`，最后一次成功 todo_write 为全量、其后 todo_update 按时序折叠，id 归一 T{n}；详见 TUI transcript.rs TodoBlock / adapter::current_todo），会话内清单面板 + **Android 16 Live Update 通知**复用（ProgressStyle 槽位显示 N/M 完成，参照已有审批倒计时通知）；④ diff 渲染（TimelineTool.diff / display.body Diff 变体）。
- **M0 审批 challenge 流：暂缓**——`/approvals/respond` 只对 device 身份开放，手机当前只能 admin token Direct 配对（桌面 QR 渲染未实现），切了反而不可用。等 /pair 真机可测时一并切换（见"待办"5）。
- P1-P4 状态见下文与 git log；`SessionAttach` 已发（RingingConnection connectOnce/streamEvents 两处）。
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

## 真机调试经验（2026-10-05 晚，重要）

- **not_driver（"another client holds the driver seat"）根因已查清并修复（2026-10-05 深夜）**：
  1. **`RingingApi.events()` 会话主 SSE 流误用默认 OkHttp client（readTimeout=10s）**——空闲 10s 必被掐断，手机每 ~11s 重连换一个 client_session_id（logcat `attach ok` 每 11s 一条可确认）。HANDOFF 早先说"已修复"其实只修了 `timelineEvents`。已改用 `sseHttp`（readTimeout=0），装机验证 40s 仅 1 次 attach。
  2. **not_driver 恢复路径脆弱**：旧代码无视 claim 的 `accepted:false`（driver_busy），且 claim 异步生效、紧跟的重试命令仍被拒。已重写为 `sendWithDriverRecovery`：claim→检查 accepted→短退避重试 3 轮；`claimDriverSeat` 用 `mapCatching`（**`Result.map` 不捕获 transform 异常**，第一次修复时 require 的异常曾漏到 UI 显示"发送失败：driver_busy"）。
  3. 真机验证：占席者（curl 模拟）租约活跃时发消息 → 3 轮重试后显示"命令未受理：另一客户端正在驾驶该会话"；占席者租约过期（TTL 30s）后重发 → claim 驱逐 stale holder，消息成功、agent 响应。协议本身无"抢占活席位"语义（见后端 plan-mobile-remote-access.md M3，接管 UX 是后端后续项）。
- **凭据丢失第 3/4 次已缓解**：EncryptedSharedPreferences 在该 OPPO（Android 16）上反复读回为空。`CredentialStore` 已改双写（ESP 主存 + 普通私有 prefs 备份，ESP 读空时回退备份并自愈回写）。重装后凭据不再丢（待长期观察）。
- **复现/调试手段**：daemon 的 token 在 `~/.qaqh/daemon.json`（endpoint+token），可直接 curl 模拟第二客户端：`POST /ringing/v2/clients/open {schema:"qaqh.Ringing",version:2,client_instance_id}` → `POST /ringing/v2/commands/{channel}`（path 是 channel 不是 command_id！header `X-QAQH-Client-Session-Id`）→ `POST /ringing/v2/sessions/{seed}/driver/claim`。租约 TTL 30s，renew 需带 header。手机 UI 可用 adb tap+input 驱动（配对对话框：手动输入 → 地址/token → 配对）。
- ⚠️ 联调期间出现一波 ~30 个重复会话（同一 ULID 时间戳批量创建，疑似 daemon 测试模型/会话创建路径的副作用），来源未查清，下次联调注意。

## 已结案：聊天回复不实时上屏（2026-10-06 修复，见顶部）

2026-10-05 的排查结论复盘：根因是"租约轮换后 SSE 挂旧租约，服务端逐帧静默丢弃"（当时嫌疑 3）+ "帧解码失败被 runCatching 静默吞掉"（当时没列入嫌疑，加重了排查难度）。当时嫌疑 1（Clash 代理掐 SSE）与嫌疑 4（reducer 嵌套） respectively 不成立/独立已修。当日联调是 https+重配对后的全新环境，无法复现旧故障做单因子归因，但两处修复都有独立价值且真机验证通过。调试技巧仍有效：日志过滤 `SessionTimeline` TAG 判断 SSE 是否收到帧；"重进聊天屏就好"是因为新建实例重新 loadPage+订阅。

- **daemon 重启 = token 轮换**：手动 `qaqh server` 起的实例每次重启 token 都变，手机必须重新配对（device_token 落地前无解）。18:13 的"没反应"就是这个。
- **两个 daemon 实例的坑**：TUI 宿主的 daemon 和手动起的 daemon 是两个实例、不同数据根——会话列表空不一定是 app 的 bug，先确认连的是哪个实例（`~/.qaqh/.qaqh-data-root.json`）。
- **SSE 被读超时掐断**：OkHttp 默认 readTimeout 10s，timeline/events 空闲即断——已用专用 client（readTimeout=0）修复；SSE 重放会重复投递，reducer 必须 merge/dedupe（block 按 block_id、turn 按 turn_id 合并）。
- **timeline 帧结构**：`event: timeline.entry` 的 data 里 TimelineEntry 嵌在 `entry` 键下，别按顶层解析。
- **会话 id 不一定是完整 UUID**（如 `01a10b72-1222-75`）；`session.list` 里有 `.tmp*` 内部会话（bootstrap 404），已过滤。
- **凭据丢失 2 次**：EncryptedSharedPreferences 读回为空（"no stored credentials"），怀疑 allowBackup 自动恢复弄坏加密 prefs——已关 allowBackup，观察是否复发。
- **adb 无线调试端口会变**：锁屏后掉线，重连用 `adb mdns services` 取新端口；手机截屏是 1240x2772，adb 点击坐标按真实分辨率换算。

## 真机验证记录（2026-10-05）

- ✅ 扫码/手输配对 → 前台服务连接 → `session.list` 会话发现 → Home 卡片（模型/轮次/running）→ bootstrap 活动投影（空闲徽标）→ 挂起交互快照投影（重启不丢）。
- ✅ 修复：Android 9+ 默认禁明文 HTTP 导致首次配对失败 → Manifest 加 `android:usesCleartextTraffic="true"`（**P5 上 TLS 后应收掉并换 networkSecurityConfig**）。
- ✅ 交互应答扩展：permission/plan/ask 三类命令分派（`respondInteraction`），ask 暂只有"关闭交互"；应答失败/被拒显示在 Home。
- ⚠️ **远程审批卡 Running（已修复）**：最初怀疑投递路径是错的。实际根因 = 归因→回执折叠断链（hub-fact-bus 2.3 迁 fact 链后写侧未接 causation；`engine_turn.rs` 的 `is_ulid` 把安卓 UUID 命令 id 丢成 None）。后端已修：FactCausation 统一归因 + UUID 归一进 ULID 通道 + 补 DriverChanged 折叠臂 + 过期 receipt 摘除。daemon 已重启跑新构建（2026-10-05 16:16）。详见 `qaqh-backend/docs/bug-ringing-v2-commands-stuck-in-running.md`。
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

## 后端 M0 鉴权面确认（2026-10-06，接手首日核查）

后端已落地建议稿 M0（spec-daemon-auth-devices）。对安卓端的影响：

- **身份模型**：`axum_impl/authz.rs` authenticate 中间件单次解析 Bearer → `Identity::Admin`（旧 daemon.json 全局 token，行为不变全豁免）或 `Identity::Device{device_id, scope}`（device_token 摘要反查注册表，吊销即 401）。公开路径仅 `/health` 与 `/ringing/v2/pair`。device 的 lease instance_key 强制 = device_id，客户端自报 `client_instance_id` 降级为诊断字段（v2.rs:96-106）。
- **scope 矩阵**（view<interact<admin）：命令 SessionAttach=View、其余=Interact（command.rs:58-65）；SessionCreate/Resume/Attach 豁免 owns_session（建立归属的入口）。`/approvals/respond`、`/content` POST=Interact；`/control/v1/stop*`、`/pairing/tokens`、`/devices*`=Admin。bootstrap/team/approvals GET/events SSE（含逐帧,sse.rs:119）/timeline(+events)/content GET：device 必须先 SessionAttach，否则 401 lease_required / 403 forbidden_not_owner（v2.rs:1341-1362）。⚠️ 疑似后端缺口：`/service/{method}` 无 scope 检查（service_api.rs 只查 lease+owns_session+driver 门），view 设备可能调写方法——待向后端确认。
- **新端点**：`POST /ringing/v2/pairing/tokens`（Admin）→ `{pairing_token(一次性 120s), expires_in_ms, tls_fp}`；`POST /ringing/v2/pair`（免鉴权）→ `{device_id, device_token, scope, daemon_version, protocol_version}`。**请求体无 scope_request 字段**——档位由桌面签发时定死，安卓 PairingClient 现发的 scope_request 被 serde 忽略（兼容但应删）。`GET /devices`、`POST /devices/{id}/revoke`（Admin，吊销同时掐在途 lease/SSE）。capabilities 增 `pairing:true`。
- **审批 wire 变化（device 身份）**：GET /approvals 对 device 回 `{challenges:[{challenge_id(64hex), kind, expires_in, details}]}`（details 白名单：tool_name/action_summary/reason/paths/category/level/risk/consequence），canonical id 不再出 daemon；新端点 `POST /approvals/respond {challenge_id, decision, payload}`：tool_permission→approve|reject|trust（device 的 trust 降级为单次放行）、ask→submit{answers}|dismiss、plan→approve|reject{message,autonomous}。challenge 一次性、TTL 5min、上限 16、绑 seed（错 seed 消费即烧）。admin 不用该端点。
- **TLS**：非回环 bind 自动启用 rustls，自签证书持久化 `<data_dir>/tls/{cert,key}.pem`；`tls_fp` **文档说 SPKI 的 sha256，实现是整张 DER 证书的 sha256**（`tls.rs:46`，安卓端已兼容两种，待后端改齐或改文档）；SAN 变化→重签→设备重扫码。**明文 HTTP 连 LAN 时代结束**。

安卓端对照（2026-10-06 更新）：PairingClient /pair ✅、SessionAttach ✅（**2026-10-06 晚收紧：attach 在 connectOnce 内必达且失败即退避重试，streamEvents 不再重复发**）、CredentialStore ✅、**HTTPS+指纹 pinning ✅（TlsPinning.kt，真机验证通过）**、**吊销→掉回配对屏 ✅**、scope_request 已删 ✅、**⑤ usesCleartextTraffic → networkSecurityConfig ✅（debug 放开/release 全禁）**。**剩余缺口**：② 审批应答仍在用 canonical id 发命令（ConnectionService respondApproval），要切 challenge 流且 /approvals 应成为唯一事实源（plan §5.3）——**前置：手机以 device 身份配对**（当前只能 admin Direct，切了 /approvals/respond 会被拒）。联调前置：桌面 QR 渲染未实现（无 --show-qr）；`/pairing/tokens` 请求体需要 `{scope_grant:"interact", device_name, platform}`。

## 待办（按 PLAN.md 阶段）

0. **重要：后端建议稿已对齐（2026-10-05）** — 后端团队提出 `E:\qaqh-backend\docs\plan-mobile-remote-access.md`（扫码配对 + TLS + device_token + scope + 双端原生 app 共享 Rust core）。已分析：**P1 协议层零返工**（wire 格式不变，Bearer 换 device_token 即可）；P2/P3 规格已按建议稿更新进 PLAN.md（JSON 二维码 payload + `/ringing/v2/pair` 换 device_token、审批改用 opaque challenge id、trust_folder 远程默认拒绝）。**网络层决策：安卓首版保留纯 Kotlin，不引入 UniFFI；若后续困难再换 Rust core**（预案写在 PLAN.md 决策记录：Connection 对外三面 API 与 core 同构，UI 层可平移；MockWebServer 测试可当对照基准）。鸿蒙侧由后端走 Rust core + NAPI。后端安全前置 = 其建议稿 M0，安卓端对应 P5。
1. ~~P1 协议层~~ ✅ 已完成（见上）
2. **P2 配对：安卓侧已完成（2026-10-05，未提交）**——`data/pairing/`（`PairingPayload`：建议稿 JSON 格式 + `qaqh://pair` 开发降级解析；`CredentialStore`：EncryptedSharedPreferences/Keystore；`PairingClient`：`/ringing/v2/pair` 换 device_token（M0 未就绪时报 404 提示）+ Direct 校验走 open）+ `ui/pairing/PairingScreen`（CameraX 1.6.2 + ML Kit barcode-scanning 17.3.0 扫码、相机权限、手输降级对话框）+ MainActivity 接线（无凭据→配对屏，有凭据→Home，可解绑）。PairingPayloadTest 5/5 通过，assembleDebug 通过。**剩余：真机联调；等后端 M0 出 /pair + TLS 后做端到端配对验收与 CertificatePinner。**
3. **P3 状态与审批 UI：已接入（2026-10-05，未提交）**——`ConnectionService` 重写为持有 `RingingConnection` 的前台服务：每 15s 轮询 `session.list`（RingingApi 新增 service RPC）发现活跃会话、seed 变化时 `setSessionSeed` 切流（RingingConnection 支持动态换 seed），SSE 事件投影为全局 StateFlow（连接状态/会话活动/待审批列表，UI 直接收集）；审批请求发高优先级通知（approvals channel + 深链 MainActivity）；Home 屏（`ui/home/HomeScreen.kt`）显示连接状态、会话卡片（summary/model/轮次/活动徽标）、待审批卡片（同意/拒绝 → `respondPermission`）。MainActivity：配对成功自动拉起服务、解绑停服务、POST_NOTIFICATIONS 运行时权限。**注：审批应答当前用 canonical call_id，后端 M0 的 opaque challenge id 落地后需切换。**
4. **P4 已完成（2026-10-05，未提交）**——生物认证：`ui/approvals/BiometricGate.kt`（androidx.biometric 1.1.0，BIOMETRIC_WEAK + 设备凭据降级；MainActivity 改继承 FragmentActivity），同意/拒绝前强制把关。Live Update：API 36+ 审批通知用 `Notification.ProgressStyle` 倒计时进度条（API 面已对照本机 android-36 android.jar 核实），每秒刷新，resolved/expired 时取消；低版本回退 BigText。Home 布局规范化：布局常量集中定义（ScreenPadding 16/BlockSpacing 16/CardInner 16/ItemSpacing 8/StatusDot 10），状态点颜色映射、全文省略策略（head/单行 MiddleEllipsis）、按钮等宽 weight(1f)。
5. **P5 剩余**：~~TLS pinning~~ ✅；~~吊销回配对屏~~ ✅；~~usesCleartextTraffic → networkSecurityConfig~~ ✅（2026-10-06 晚：main 全禁 + debug 变体放开，Direct 手输联调不受影响）；~~streamEvents 的 attach/bootstrap 时序收紧~~ ✅（2026-10-06 晚：attach 必达 + 去重复发）。剩：a) 配对端到端验收（等桌面 `--show-qr`：`/pairing/tokens` 造 QR JSON → 扫码 → /pair 换 device_token → 审批 challenge 流切换 + PinningScreen 手输路径保留）。
6. **真机调试新增经验（2026-10-06）**：键盘弹起后发送按钮会上移，adb 点击前要重新 uiautomator dump 取坐标；`adb shell` 里 `/sdcard` 会被 Git Bash 转路径，用 `//sdcard//`；Direct 手输配对三件套 = 地址 + token（`~/.qaqh/daemon.json`）+ TLS 指纹（curl `/pairing/tokens` 取 tls_fp）。

## 用户偏好

- 版本尽量最新稳定版；原生设计（Compose），克制复用系统组件
- 决策已定：扫码配对；后端允许分阶段小改
