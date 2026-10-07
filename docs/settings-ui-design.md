# 设置页 UI 设计（基于后端 config API）

> 2026-10-07 · 数据源：ringing v2 `service/{method}`，方法 `config.load` / `config.save`（JSON Merge Patch）/ `config.set_permission_level` / `profile.apply`。
> 读模型 `ConfigDto`、写模型 `ConfigPatch` 定义见 `qaqh-backend/crates/qaqh-config-api/src/lib.rs`；桌面端结构参照 `qaqh-desktop-app/src/settings/SettingsView.tsx`。

## 原则

1. **写模型是 `ConfigPatch`（K3 merge-patch）**：只发改过的字段，缺失 = 不动；wire 上永不出现 null。安卓侧用 kotlinx.serialization 可空字段 + `explicitNulls=false` 对齐。
2. **api_key 永不出 daemon**：读回来是 `"****"`（已配置）或空串（未配置）。输入框占位显示掩码；**只有用户显式输入新值才把该字段放进 patch**（`"****"`/空串 = 保持现值，服务端守卫冻结）。
3. **服务端 validate 是底线**（值域：wire=openai|responses|anthropic，reasoningEffort=low|medium|high|xhigh|max，permissionLevel=1..=3，maxTokens/contextLength>0，autoCompactThreshold∈[0,1]，subagent.maxDepth 1..=16）；客户端先做同款校验提前报错，不等服务端打回。
4. **MCP/LSP 移动端只读**（后端写模型本来就未冻结，桌面端也是）。

## 信息架构（手机单列，分组卡片 + 组内行，无桌面左侧分区导航）

现有「连接 / 配对 / 实时活动 / 关于」保留，config 部分插在「连接」之后。分区与桌面 8 区对齐但按移动场景重排：

| # | 分区 | 字段（控件） | 写入口 |
|---|---|---|---|
| 1 | **权限档位** | 1 只读 / 2 工作区写 / 3 跳过审批——三段分段按钮 | `config.save(patch.permissionLevel)`；**档 3 需确认对话框**（红色警示文案「普通工具全部自动放行」） |
| 2 | **模型与端点**（BYOK） | model（文本+历史建议）、wire（三选一胶囊）、baseUrl（文本）、apiKey（掩码密码框）、maxTokens（数字） | 同一 patch 一次提交 |
| 3 | **推理与上下文** | reasoningEffort（5 段分段或下拉）、contextLength（数字）、autoCompactThreshold（滑杆 0–1，0=关，标注「关闭自动压缩」） | 同一 patch |
| 4 | **外观** | theme（跟随系统/浅色/深色——**安卓默认且推荐跟随系统**，Material You 动态取色跟随壁纸）、lang、fontFamily（安卓已嵌入四端同源字体，此项只读展示） | `config.save`；theme 仅影响桌面 webui，安卓端展示为「仅作用于电脑端」注释 |
| 5 | **通知** | notificationsEnabled（开关，联动现有「实时活动」组的常驻通知说明） | `config.save` |
| 6 | **子代理**（默认折叠） | model/baseUrl/apiKey(掩码)/maxTokens/timeoutSecs/maxDepth/defaultTools | `patch.subagent`（SubagentPatch 嵌套补丁） |
| 7 | **MCP / LSP**（只读） | 服务器名 + transport 徽标列表；点击进详情 bottom sheet（command/args/env 占位符/超时） | 无写入口，页脚注「在电脑端修改」 |
| 8 | 档案预设（P2 再做） | profiles 列表 + 应用 | `profile.apply`；移动端先不做 save_current/delete |

## 交互模式

- **分区级脏跟踪与保存**：每个可写分区本地持有 draft，行内改动出现「保存」按钮（分区头右侧）；保存 = 单独 patch 该分区字段 → 成功 Snackbar、失败把 `validate()`/服务端错误映射到具体行。不做全局保存条（避免长表单底部迷失）。
- **掩码密钥行**：值显示 `••••` + 「已配置」徽标；点击进入编辑态，留空提交 = 不动（输入框上方注明）。
- **数字字段**：软键盘 number 类型；`maxTokens/contextLength` 空值不提交。
- **危险操作**：档 3 与「解除配对」同样走确认对话框模式。

## 数据层新增（安卓侧）

- `RingingApi`：service 调用已有通用方法（`workspace.list` 同路），加 `configLoad(): ConfigDto` / `configSave(patch): ConfigDto`，模型放 `data/ringing/model/Config.kt`（`ConfigDto` 全字段 + `ConfigPatch` 可空字段，`@OptIn(ExperimentalSerializationApi::class) explicitNulls=false`）。
- `ConnectionService`：`configLoad` 带内存缓存 + 设置页进入时刷新；保存后广播刷新（对齐桌面 reload 广播语义）。

## 分期

- **P1**：分区 1/2/3/5（权限、BYOK、推理上下文、通知）+ 数据层。覆盖日常联调会动的所有字段。
- **P2**：分区 4（外观，含安卓本地 theme override 接入 AppTheme）、8（档案预设）。
- **P3**：MCP/LSP 详情 sheet、defaultTools 多选编辑。
