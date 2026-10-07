# 四端统一字体（HarmonyOS Sans + Cascadia Code）

四端（Android / HarmonyOS / 桌面 webui / WinUI）统一使用 **HarmonyOS Sans SC**（正文/标题）+ **Cascadia Code**（等宽：代码、diff、日志）。本目录存放随仓库携带的许可证原件。

## Android 端文件来源（与各端同源，保证渲染一致）

| Android 资源 | 来源 | 说明 |
|---|---|---|
| `res/font/hmos_sans_sc_regular.ttf` | qaqh-desktop-app `public/fonts/HarmonyOS_Sans_SC_Regular.ttf` | 桌面仓同款裁剪文件（8.3MB；SDK 全量 20.6MB 不入包） |
| `res/font/hmos_sans_sc_bold.ttf` | qaqh-desktop-app `public/fonts/HarmonyOS_Sans_SC_Bold.ttf` | 同上（8.2MB） |
| `res/font/cascadia_code.ttf` | qaqh-winui-app `apps/winui/assets/fonts/CascadiaCode.ttf` | 可变字体（wght 轴），Compose 按 `FontVariation.weight` 取实例 |

## 字重映射

HarmonyOS Sans 官方五档（Thin/Light/Regular/Medium/Bold/Black），随桌面仓只分发了 Regular/Bold 两档：

- M3 `W400`/`W500` → Regular
- M3 `W600`/`W700` → Bold

若后续拿到官方 Medium（500）文件，补进 `Theme.kt` 的 `AppSans` 即可（`hmos_sans_sc_medium.ttf`）。

## 许可证

- HarmonyOS Sans：华为字体许可（`HarmonyOS-Sans-LICENSE.txt`，允许随应用分发嵌入）
- Cascadia Code：SIL OFL 1.1（`Cascadia-Code-OFL-1.1.txt`）

## 各端状态

- **Android**：✅ 本轮嵌入（Theme.kt `AppSans`/`AppMono`，M3 Typography 全槽覆盖）
- **桌面 webui**：✅ 已有（woff2 + web fallback）
- **WinUI**：✅ 已有（CascadiaCode.ttf/CascadiaMono.ttf；HarmonyOS Sans 系统自带）
- **HarmonyOS**：✅ 系统自带 HarmonyOS Sans，无需嵌入
- **TUI**：终端字体由用户终端决定，不嵌入
