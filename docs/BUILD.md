# 构建说明（Build Notes）

## 标准构建（Windows / macOS / x86_64 Linux）

```bash
cd NeuralMLTerminal
./gradlew :app:assembleRelease
```

需要：JDK 17、Android SDK Platform 35、Build-Tools 35.0.0。
Android Studio 首次打开会自动写入 `local.properties` 的 `sdk.dir`。

## ARM64 Linux 主机（Termux / proot Ubuntu / 树莓派）

Google 发布的 `aapt2` 只有 x86_64 Linux 二进制，在 ARM64 主机会报：

```
AAPT2 aapt2-...-linux.jar 无法执行 / Exec format error
Illegal instruction
AAPT2 ... Daemon startup failed
```

解决方式：安装 `qemu-user-static` 与 amd64 运行库，写一个包装脚本，用 Gradle 属性覆盖 aapt2：

```bash
sudo apt-get update
sudo apt-get install -y qemu-user-static libc6:amd64 libstdc++6:amd64 zlib1g:amd64

mkdir -p /opt/toolchain
cat > /opt/toolchain/aapt2 <<'EOF'
#!/bin/sh
exec /usr/bin/qemu-x86_64-static "$ANDROID_HOME/build-tools/35.0.0/aapt2" "$@"
EOF
chmod +x /opt/toolchain/aapt2

export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=/opt/toolchain/aapt2
```

该属性只影响本机构建，不会修改仓库文件；在正常 x86_64 机器或 Android Studio 上无需任何处理。

## 无网络（离线）构建

若依赖已缓存，可加 `--offline`：

```bash
./gradlew --offline :app:assembleDebug
```

首次构建仍建议联网，让 Gradle 下载 AGP 8.7.3 / Kotlin 2.0.21（Maven 标准依赖）。

## 产物

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 签名

- 签名材料（`release.keystore` / `keystore.properties`）**不入库**，公开仓库不含私钥。
- 首次构建 release 前先生成：`bash tools/gen-keystore.sh 你的密码`
- 未签名 release 包可用 `apksigner sign` 自行签名，或用 Android Studio 的
  *Generate Signed APK* 流程。

## 常见构建错误

| 现象 | 处理 |
|---|---|
| `Unsupported class file major version` | Gradle 使用的 JDK 太新/太旧，切换为 JDK 17 |
| `SDK location not found` | 配置 `local.properties` 的 `sdk.dir` |
| `compileSdk 35 not installed` | `sdkmanager "platforms;android-35" "build-tools;35.0.0"` |
| `AAPT2 ... Exec format error` | ARM64 主机，按上文 qemu 包装 |
| release 未签名 | 检查 `keystore.properties` 是否存在且路径正确 |
