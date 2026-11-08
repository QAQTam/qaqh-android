# 字体决策(Android 端)

> **2026-11-08 变更**:Android 端移除内嵌字体,改用**系统默认字体**。
> 原四端统一方案(HarmonyOS Sans SC + Cascadia Code 随包)在此终止;桌面 webui / WinUI 仓不受影响,仍各自携带字体。

## 现状

- **正文/标题**:系统默认(Roboto;中文回退 OEM 字体——ColorOS 上即 OPPO Sans,HarmonyOS 上即 HarmonyOS Sans)。
- **等宽**:系统 `FontFamily.Monospace`(SessionScreen 代码块/DiffView、SettingsScreen)。
- **字重**:系统字体为可变字重,M3 的 W400–W700 各档名副其实(此前内嵌只有 Regular/Bold,W500/600 被压到两档的问题不复存在)。
- `Theme.kt` 不再设置任何 `typography`/`fontFamily`:全部字槽走 material3 默认 = 系统字体。

## 为什么移除

1. 资源成本:内嵌 SC Regular+Bold+Cascadia 共 **17.3MB**(裁剪后),换系统默认后 APK 减重;
2. 字重收益:系统可变字重补齐了 M3E 依赖的 Medium/Semibold 对比,不用再等官方 Medium 文件;
3. 中文观感:OEM 系统字体本就是各端"原生"观感,与系统 UI 一致性反而更好。

## 历史方案(存档)

曾按四端统一决策嵌入 `hmos_sans_sc_regular/bold.ttf`(桌面仓同源裁剪文件,8.3M+8.2M)+
`cascadia_code.ttf`(WinUI 仓可变字体),`AppSans`/`AppMono` 全槽覆盖 M3 Typography。
许可证(HarmonyOS 字体许可 / SIL OFL 1.1)当时随 `docs/fonts/` 存放,随移除一并删除——
不再分发字体即不再需要;桌面仓仍嵌入的话以桌面仓的许可证为准。
