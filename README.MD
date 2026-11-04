本项目是安卓与QAQ-Harness的电脑-手机连接，确保在局域网下设备可以实时确认电脑端工作情况以及处理授权事宜。后续会逐渐引入生物认证授权等功能。这里工作的LLM需要寻找最新API的原生框架skills以完成这些工作。

建议安卓使用原生前后端设计app，且力克复用系统组件ui等，同时sdk需要控制在新版本确保新功能支持
安卓需要实现实况通知等Android16/SDK36全新特性以及至少Android15以上的权限管理。

## 架构(2026-11-05 起)

传输层 = **Rust core via UniFFI**:后端仓 `crates/qaqh-ffi` 包 `qaqh-client`
(lease 续租/重协商、退避重连、SSE 单流、TLS 指纹 pin 全在 Rust),Kotlin 侧
只保留 UI + 契约数据模型(`app/src/main/java/com/qaqh/harness/data/core/`)。
协议演进随 backend rebuild `.so` 即可(`core/build-core.sh`,前置环境见其头注释)。

- 后端:`../qaqh-backend`(LAN 分支;Windows 机器上的旧路径 "E:\qaqh-backend" 已过时)
- 构建链/SDK 根:`../hiandroid`(env.sh;NDK 30 + rust targets + cargo-ndk)
- 当前里程碑与真机联调纪要:`docs/worklog/2026-11-05-rust-ffi-transport.md`
