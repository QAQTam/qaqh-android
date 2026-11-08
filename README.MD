本项目是安卓与QAQ-Harness的电脑-手机连接，确保在局域网下设备可以实时确认电脑端工作情况以及处理授权事宜。后续会逐渐引入生物认证授权等功能。这里工作的LLM需要寻找最新API的原生框架skills以完成这些工作。

建议安卓使用原生前后端设计app，且力克复用系统组件ui等，同时sdk需要控制在新版本确保新功能支持
安卓需要实现实况通知等Android16/SDK36全新特性以及至少Android15以上的权限管理。

## 架构(2026-11-05 起)

传输层 = **Rust core via UniFFI**:后端仓 `crates/qaqh-ffi` 包 `qaqh-client`
(lease 续租/重协商、退避重连、SSE 单流、TLS 指纹 pin 全在 Rust),Kotlin 侧
只保留 UI + 契约数据模型(`app/src/main/java/com/qaqh/harness/data/core/`)。
后端默认位于 `../qaqh-backend`，可用 `QAQH_BACKEND_DIR` 指定其他检出。

## 构建 Android APK

需要 Bash 和 JDK（已用 JDK 25 验证）。安装 Android SDK 37、NDK `30.0.16248370`，
并将 `ANDROID_HOME` 指向 SDK 根目录。
已在 `local.properties` 设置 `sdk.dir` 的项目也可直接使用该配置。

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
./core/build-core.sh debug
./gradlew :app:assembleDebug
```

发布包使用 `./core/build-core.sh release` 和 `./gradlew :app:assembleRelease`，生成未签名的 Release APK。
每种构建使用对应的原生库。切换后端提交或执行 `./gradlew clean` 后，重新运行 core 构建。
Rust 构建默认使用两个并发任务，可用 `CARGO_BUILD_JOBS` 调整。

真机联调纪要见 `docs/worklog/2026-11-05-rust-ffi-transport.md`。
