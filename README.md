# NeuralML Terminal

纯原生 Android（Kotlin）「ML 脑控终端」仪表盘，单 Activity、纯黑荧光赛博风格。
**完全本地运行**：没有网络权限、不采集任何数据、没有任何真实模型或外部服务，
所有数值 / 波形 / 日志 / 指令反馈都由本地代码实时生成，用于个人娱乐与视觉展示。

- 应用名：`NeuralML Terminal`
- 包名：`com.neuralml.terminal`
- minSdk 26（Android 8.0+）· targetSdk 34 · compileSdk 35
- 语言：Kotlin 2.0.21 / JVM 17 · **零第三方依赖、零 androidx**
- 权限：仅 `WAKE_LOCK`（屏幕常亮）
- 默认横屏全屏、沉浸式状态栏/导航栏、CRT 扫描线、代码雨背景、启动自检动画

---

## 一、界面一览

| 区域 | 内容 |
|---|---|
| 顶部状态栏（8% 高） | 标题 `NEURAL-ML DECODER TERMINAL V5.1.0`（随机 glitch）、实时时钟、E2E 加密标识、信号强度、链路状态；右侧 CPU / NPU / 内存 / 推理 FPS |
| 左侧上（28% 宽） | `TARGET BIOMETRICS`：目标编号、α/β/θ/δ 脑波（Hz）、心率、皮电、注意力、神经活跃度 + 进度条 |
| 左侧下 | `ML ENGINE STATUS`：模型名、推理模式、特征提取率 / 情绪置信度 / 解码准确率、迭代步数、Loss、推理延迟 |
| 中央（44% 宽） | 上：四通道实时波形 + 特征高亮光点；下：匀速旋转的 3D 线框大脑 + 脑区热力高亮；底部叠加「神经同步率 / 解码置信度」双进度条 |
| 右侧（28% 宽） | `COMMAND CONSOLE`：可交互终端，固定提示符 `> neural-ml@decoder:~$ `，打字机输出 |
| 底部（15% 高） | `SYSTEM & ML LOG`：三级着色滚动日志（INFO 浅灰 / WARN 黄 / ERROR 红） |

所有数值平滑插值，不会生硬跳变；低概率触发「信号抖动 → 自动重校准 → 恢复」事件。

## 二、可执行指令

| 指令 | 作用 |
|---|---|
| `scan` | 目标扫描：扫描进度 → 锁定新目标编号 |
| `sync` | 神经链路同步：握手、相位锁定 |
| `status` | 打印全部状态（目标 / 脑波 / 生命体征 / 引擎 / 指标 / 同步 / 主机） |
| `decode` | 深度解码全流程：特征提取 → 张量转换 → 模型推理 → 结果输出 |
| `train` | 在线微调：迭代步数、Loss 下降曲线、校准完成 |
| `predict` | 未来 30 秒情绪概率分布 |
| `model switch light` / `standard` / `precision` | 切换轻量 / 标准 / 高精度解码档位（改变推理帧率与置信度参数） |
| `clear` | 清屏 |
| `help` | 指令列表 |
| 其它 | 标准错误提示 |

点击控制台任意位置会自动唤起软键盘；软键盘弹出时布局自动上移，不遮挡输入行。

---

## 三、目录结构

```
NeuralMLTerminal/
├── build.gradle.kts                # 根构建脚本（AGP 8.7.3 + Kotlin 2.0.21）
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
├── release.keystore                # release 签名密钥（本地生成，不入库）
├── keystore.properties             # 签名配置（不入库，见 tools/gen-keystore.sh）
├── app/
│   ├── build.gradle.kts            # 模块配置：minSdk 26 / targetSdk 34 / compileSdk 35
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/neuralml/terminal/
│       │   ├── MainActivity.kt            # 沉浸式全屏、视图绑定、帧驱动、启动动画
│       │   ├── core/
│       │   │   ├── SimState.kt            # 全部可显示数值的数据模型
│       │   │   ├── SimulationEngine.kt    # 30Hz 模拟引擎：漂移/插值/波形/事件/日志
│       │   │   ├── WaveData.kt            # 4 通道环形缓冲 + 特征点
│       │   │   ├── LogBus.kt              # 滚动日志总线
│       │   │   └── CommandProcessor.kt    # 全部终端指令
│       │   ├── ui/
│       │   │   ├── TopStatusBarView.kt
│       │   │   ├── BiometricsPanelView.kt
│       │   │   ├── EngineStatusPanelView.kt
│       │   │   ├── WaveformView.kt
│       │   │   ├── BrainWireframeView.kt
│       │   │   ├── SyncMeterView.kt
│       │   │   ├── ConsoleView.kt
│       │   │   ├── LogPanelView.kt
│       │   │   ├── CodeRainView.kt
│       │   │   ├── CrtOverlayView.kt
│       │   │   └── BootOverlayView.kt
│       │   └── util/HudKit.kt             # 配色 + 缓存画笔 + 面板/进度条绘制
│       └── res/
│           ├── layout/activity_main.xml       # 横屏主布局（默认）
│           ├── layout-port/activity_main.xml  # 竖屏兼容布局
│           ├── values/{strings,colors,themes}.xml
│           ├── drawable/ic_launcher_foreground.xml
│           └── mipmap-anydpi-v26/ic_launcher.xml
├── tools/
│   ├── ktcheck.sh                  # 快速 Kotlin 语法检查（开发用）
│   └── gen-keystore.sh             # 重新生成 release 签名
├── docs/BUILD.md                   # 构建说明（含 ARM64 主机注意事项）
└── .github/workflows/build-apk.yml # GitHub Actions 自动构建 APK
```

---

## 四、构建 APK

### 方式 A：Android Studio（推荐）

1. 解压项目，Android Studio（建议 Koala 2024.1.1 或更新，JDK 17）打开根目录。
2. 等待 Gradle 同步完成（首次会下载 AGP / Kotlin 插件，均为标准 Maven 依赖）。
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**，产物在
   `app/build/outputs/apk/debug/app-debug.apk`。
4. 需要正式包时：**Build → Generate Signed Bundle / APK → APK**，选择仓库内
   自己生成的 `release.keystore`（见下方 `tools/gen-keystore.sh`），
   或直接执行下方命令行方式（检测到签名配置即自动签名）。

### 方式 B：命令行

```bash
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 已签名 release 包
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

> **签名**：签名私钥不随仓库公开。clone 后先执行 `bash tools/gen-keystore.sh 你的密码`
> 生成 `release.keystore` + `keystore.properties`，`app/build.gradle.kts` 会自动使用它签名；
> 若密钥缺失，release 包为未签名（需自行签名后才能安装）。
> 也可以在仓库的 **Releases** 页直接下载已签名 APK。

### 安装到平板

- 平板开启「开发者选项 → USB 调试」，USB 连接后：`adb install -r app-release.apk`
- 或把 APK 拷到平板（内存存储/下载目录），用文件管理器点击安装（允许「安装未知应用」）。

### 方式 C：GitHub Actions

仓库已包含 `.github/workflows/build-apk.yml`：推送到 `main`/`master` 或手动
**Actions → Build APK → Run workflow**，构建完成后在 Artifacts 下载
`neuralml-terminal-apk`（含 debug 与 release 两个 APK）。

---

## 五、适配说明

- 默认 `sensorLandscape`（横屏全屏），面向 12 英寸平板（荣耀平板 Z Pro 类）；
  `res/layout-port/` 提供竖屏堆叠布局，系统强制竖屏窗口（如分屏）时仍可读。
- 所有自定义视图按容器实际像素自绘，字号/行高按视图尺寸自适应，不同分辨率不会溢出。
- 屏幕常亮：`FLAG_KEEP_SCREEN_ON`；`onPause` 停止 30Hz 模拟循环，后台不耗电、无崩溃风险。
- 无网络、存储、定位、相机、麦克风权限；无第三方 SDK，无数据上报。

## 六、常见问题

**Q：Gradle 同步失败 / 找不到 SDK？**
`local.properties` 里的 `sdk.dir` 需要指向本机 Android SDK；Android Studio 会自动改写。

**Q：release 包提示未签名？**
公开仓库不含签名私钥。执行 `bash tools/gen-keystore.sh 你的密码` 后再重新构建，
或直接下载 Releases 里的已签名 APK。

**Q：在 ARM64 Linux（Termux / proot Ubuntu）上直接构建？**
Google 的 `aapt2` 只有 x86_64 版本，需要 qemu 包装，见 `docs/BUILD.md`。

**Q：横屏锁定后能旋转吗？**
Manifest 中 `sensorLandscape` 允许左右横屏旋转；如需固定单方向可改为 `landscape`。
