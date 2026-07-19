# AGENTS.md

面向 AI 编码代理与贡献者。构建命令见 [README.md](./README.md)。

---

## 1. 项目介绍

**视频采集**（`com.zhenshi.capture`）是一款 Android 单路全屏采集/播放 App，界面文案全中文。

| 项 | 说明 |
|----|------|
| 定位 | USB 采集卡/UVC 低延迟预览 + 网络拉流观看 + RTMP 推流 |
| 平台 | minSdk 24，targetSdk / compileSdk 37（Android 17） |
| 技术栈 | Kotlin · Compose · Material 3 · Hilt · Media3 · AUSBC · RootEncoder（`RtmpClient`）· DataStore |
| 交互 | 底栏四 Tab（设备 / 网络 / 推流 / 设置；设备为首页）→ 全屏播放页；播放页内 overlay 推流；固定暗色扁平 UI |
| 模块 | 仅 `:app`，用包划分边界 |

**核心原则**

- USB 预览：AUSBC → `AspectRatioSurfaceView` **直出**（`RenderMode.NORMAL`），零转码环；**禁止**预览注册 `IPreviewDataCallBack` / `setRawPreviewData`（仅推流开 NV21）。UAC 听声走自建 `UsbDeviceMicPlayer` + `AudioSource.NONE`，**不经** AUSBC `startPlayMic`。
- 网络观看：Media3 硬解上屏，不做录像/滤镜/二次编码；可展示缓冲延迟。
- 推流：AUSBC 只采集（NORMAL + NV21/UAC PCM）→ **自建 MediaCodec H.264/AAC** → RootEncoder `RtmpClient`；`suspendForPush` 让出预览。有 UAC 则带伴音，无则纯视频。禁止 `captureStreamStart` / RootEncoder `VideoSource`。
- 参数：分辨率/帧率从 UVC 能力集点选；推流码率仅低/中/高三档。
- 延迟目标：USB 本机 < 100ms；RTMP < 1s 仅局域网或指定稳定源验收，不为公网加第二播放器或无限缓冲。

---

## 2. 项目文件结构

```
dev/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/zhenshi/capture/
│       │   │   ├── CaptureApp.kt                 # Hilt Application；preload libUACAudio
│       │   │   ├── MainActivity.kt               # 单 Activity、edge-to-edge
│       │   │   ├── data/                         # 最近源 MRU、推流目标 DataStore
│       │   │   ├── di/AppModule.kt
│       │   │   ├── media/
│       │   │   │   ├── Models.kt / PlaybackSession / DefaultPlaybackSession / SourceKeys
│       │   │   │   ├── network/NetworkStreamPlayer.kt
│       │   │   │   ├── push/                     # RtmpPushController、PushAvPipeline、
│       │   │   │   │                             # H264Nv21Encoder、AacEncoder、前台 Service…
│       │   │   │   └── usb/                      # UsbCameraController、UsbDeviceMicPlayer、
│       │   │   │                                 # UsbHostCoordinator、UvcDescriptorParser
│       │   │   ├── navigation/AppNavigationRequests.kt
│       │   │   ├── screens/
│       │   │   │   ├── AppNav.kt / NavigationExt.kt
│       │   │   │   ├── components/ / theme/      # 通用 UI、ZhenShiTheme（固定暗色）
│       │   │   │   ├── usb/                      # 首页：设备 + 最近源
│       │   │   │   ├── network/ / settings/
│       │   │   │   ├── player/                   # 全屏播放
│       │   │   │   └── push/                     # Tab CRUD + PlayerPushPanel overlay
│       │   │   └── util/                         # 权限、标签、延迟采样、SystemBars…
│       │   ├── cpp/                              # libUACAudio（libuac + JNI，16KB）
│       │   └── res/                              # values、drawable、xml、mipmap 启动图标
│       ├── test/
│       └── androidTest/
├── gradle/libs.versions.toml                     # AUSBC 3.6.0、Media3 1.10.1、RootEncoder 2.8.0…
├── gradle.properties                             # 本机代理勿提交密钥
├── settings.gradle.kts
├── AGENTS.md
└── README.md
```

**依赖方向**：`screens.*` → `media` / `data`；`media` 不依赖 Screen。

```
SignalSource → DefaultPlaybackSession → UsbCameraController / NetworkStreamPlayer
                    ↓ 推流
              suspendForPush → RtmpPushController → stop → resumePreviewAfterPush
```

---

## 3. 能力概览

| 模块 | 说明 |
|------|------|
| 骨架 | Hilt、Navigation Compose、暗色 `ZhenShiTheme`、四 Tab（设备首页）、最近历史 MRU |
| USB | 枚举 UVC、权限、描述符能力集、分辨率/帧率点选；预览 Surface 直出 + UAC 听声 |
| 网络 | RTMP/RTSP、`LiveConfiguration` + 收紧 `LoadControl`、缓冲延迟采样 |
| 播放页 | 全屏、横竖屏、常亮、工具栏自动隐藏、错误/网络延迟；页内推流 overlay |
| USB 推流 | 自建 H.264（NV21）+ AAC（UAC）→ `RtmpClient`；handoff；前台 Service；码率三档 |
| 热插拔 | `UsbHostCoordinator.devices` + `reconcileDevices`；插拔导航/断线 Error/`scheduleUsbReopen` |
| Android 17 | `ACCESS_LOCAL_NETWORK` 声明 + 启动申请；RTMP 明文 `network_security_config` |
| 生命周期 | 切后台停推流+停采集；软断开 Error + 可 reopen；推流断连统一 `endPushTransport` 并恢复预览 |
| 测试 | JVM：源键、历史、推流校验、handoff、错误映射、档位、UVC 描述符、延迟、权限等；仪器化：SourceKeys、MainActivity、Tab 布局 |

Preview / Push 均为 `RenderMode.NORMAL`，开关推流无需 OPENGL Surface 回收。

---

## 4. 改代码时必留意

结论向约束；改相关路径前先核对现码。

### 推流与会话

- **路径**：AUSBC 采集 → 自建 MediaCodec → `RtmpClient`。禁止 RootEncoder `VideoSource`、AUSBC `captureStreamStart`、主线程 `runBlocking`。
- **Overlay**：只用播放页内 `PlayerPushPanel`，禁止 `ModalBottomSheet`（会触发配置变更 → UVC 软断开）。
- **Handoff**：`suspendForPush` → 推流 → `stopPush` → `resumePreviewAfterPush`；保留 `pendingSurfaceView`；`PushViewModel` 用 `Mutex.withLock` + `UsbPushHandoffTracker`，禁止 `tryLock` 丢 stop；Surface 就绪门闩在 `openWithFallback` → `resolveRenderSurface`。
- **断连收尾**：失败/认证错/断开/编码失败统一走 `endPushTransport()`（停 Service + `stopInternal`）；`RtmpClient.shouldFailOnRead = true`；曾 Ready 后 Idle/Error → `finishUsbPushHandoff` + 恢复预览。
- **切后台**：`ON_STOP` 先停推流（不恢复预览）再 `pausePreviewForAppBackground`；`ON_START` → `resumePreviewFromAppBackground`（允许 Error 重开）。
- **离开页**：`leavePlayback` → Activity 级 `stopThenLeave`（await）→ `leavePreview`；同步 `halt` + 清 `pendingSurfaceView`/detach，再 `onNavigate`，异步只 `stopPreview`（`closeCamera` + `warmControlBlock`）——勿在异步收尾再清 Surface（会盖住快速重进的 attach）。勿在离开时永久释放设备；切源/物理拔出才 `stopPreviewAndReleaseDevice`。

### USB / 软断开 / 权限

- **控制块**：从 `controlBlocks` 摘下必须 `UsbControlBlock.close`（`takeAndClose` / `peel` 匹配实例）；覆盖旧块同规。迟到 `onDisconnect`：CB ≠ `activeControlBlock`（或 map 已换新）则 **stale 忽略**，勿拆新会话。主动 `closeCamera` 前登记 `expectedSoftDisconnectDeviceIds`。
- **意外软断开**：标记会话 Error + `usbDeviceLost`；预览中可 `scheduleUsbReopen`；主线程只 halt/摘引用，UAC await / `closeCamera` 放 `cameraScope`；**禁止**热插拔路径主线程 `sleep` / `runBlocking`。
- **权限**：只经 AUSBC `requestPermission`；授权完成入口 `UsbHostCoordinator.onUsbAccessGranted`（含回前台 `hasPermission` 收尾）。勿再包一层 PendingIntent（库在 Android 14+ 用 `FLAG_IMMUTABLE`，会丢 `EXTRA_DEVICE`）。已授权勿重复 `requestPermission`（会触发主线程 GC/sleep）。
- **设备列表**：真相源 `UsbHostCoordinator.devices`；同步只走 `reconcileDevices()`。有 CB 时用其 `rawDescriptors` 探测能力，禁止再 `UsbManager.openDevice` 抢占。
- **预览视图**：必须 `AspectRatioSurfaceView` + `RenderMode.NORMAL`；普通 `SurfaceView` + 默认 OPENGL 会黑屏（`surface measure size null`）。

### UAC / NDK

- 听声与推流 PCM 均走 `UsbDeviceMicPlayer`；关流：**halt → 等 `isReleased` → `closeCamera`**（抢 FD 会 SIGABRT）。物理拔出立刻 halt，UAC 释放放后台，勿主线程等。
- 自研 `app/src/main/cpp` `libUACAudio`（16KB Align）；勿恢复 CapView 的 `jniLibs/libUACAudio.so`；`pickFirsts` 处理重复 `libusb100.so`。
- `PlayerViewModel` 相位用 `Idle/Watching/SurfaceLost/AppBackground/Left`，勿叠多布尔。

### 平台与杂项

- **`ACCESS_LOCAL_NETWORK`**：targetSdk 37 访问局域网必留（声明 + 运行时），不可删。
- AUSBC 走 JitPack；解析失败用 `gradle.properties` 代理，勿改源码 module 接入。
- 可忽略噪声：MACROSILICON `bEndpointAddress is null`；RootEncoder `MediaCodec flush` Configured；MIUI `xlog` / 部分 Codec2 query。

---

## 5. 开发维护规范

### 5.1 原则

- **一条主路径**：禁止无实测依据的双播放器、双推流栈、魔法 sleep 重试。
- **先读再改**：以仓库当前文件为准；Media 栈统一 Media3。
- **少即是多**：新功能同步删死代码与失效补丁。
- **Bug**：复现 → 根因（栈/线程/生命周期）→ 最小修复 → 删旧 workaround。

### 5.2 功能闭环

入口 → 校验 → 状态（Idle/Connecting/Ready/Error）→ 释放 → 可观测日志。

- 切源：`release → open → attachPreview`；单路，禁止双源并行解码。
- 推流画质：分辨率/帧率与码率下限跟 `openResult.profile`（`BitratePreset.forResolution`）。
- USB 能力探测：读描述符勿抢 `sessionMutex`；授权后勿再 `refresh()` 叠探测。
- `libUACAudio`：`CaptureApp` 后台 preload；USB monitor 在首帧后注册。

### 5.3 媒体硬约束

- USB 预览禁止编码→解码环；禁止预览帧回调（仅 Push 开 NV21）。
- 网络观看禁止二次处理；直播小缓冲 + 丢旧帧。
- 推流必须 Foreground Service + 中文通知。
- 参数 UI 禁止自由输入宽高/帧率/码率。

### 5.4 依赖选型

| 领域 | 唯一选型 |
|------|----------|
| USB | AUSBC ernestp 3.6.0（JitPack `libausbc` + `libuvc`，禁源码进仓） |
| USB 音频 | `UsbDeviceMicPlayer` + NDK `libUACAudio`（16KB）；预览 `AudioSource.NONE` |
| 播放 | Media3；RTMP 用 anenasa `LibRtmp-Client`（16KB），排除官方 `rtmp-client` |
| 推流 | 自建 MediaCodec H.264/AAC + RootEncoder `RtmpClient`（AUSBC 仅采集） |

版本见 `gradle/libs.versions.toml`；替换须更新本表。

### 5.5 UI 与文案

- 文案：`strings.xml` 简体中文。
- 主题：`ZhenShiTheme` 固定暗色 + 品牌绿，不跟系统浅色/壁纸取色。
- Edge-to-edge：`enableDarkEdgeToEdge()`（`SystemBarStyle.dark`）；离开播放页 `restoreDarkSystemBars()`；insets 与 padding 不叠两层。禁止默认 `auto`（浅色会让图标发黑）。
- 改 edge-to-edge / Compose 导航前可参阅 [android/skills](https://github.com/android/skills)。

### 5.6 提交与文档

- 一次提交一件事，说明为什么。
- USB/推流改动注明测试设备；延迟改动附测量方式。
- 合入前：`:app:testDebugUnitTest` + `:app:assembleDebug`；发版再 `:app:assembleRelease`。
- CI（`android-ci.yml`）会去掉本机代理；发版签名/版本读环境变量 `SIGNING_*` / `KEY_*` / `VERSION_*`。
- 实现变更后同步本文件与 README。

### 5.7 改代码前自检

1. 开/关/错/释放是否闭环？
2. USB 仍直出？网络观看仍无二次处理？
3. 推流是否仍避开主线程死锁与旧编码路径？
4. 局域网权限是否仍保留？
5. 是否引入无用兜底或重复实现？

### 5.8 发版检查清单

1. `.\gradlew.bat :app:testDebugUnitTest`
2. `.\gradlew.bat :app:assembleRelease`（R8 / shrinkResources 无阻断）
3. APK 中 `libUACAudio.so` / `librtmp-jni.so`：`llvm-readelf -l` → LOAD Align ≥ `0x4000`
4. 真机冒烟：USB 有画有声 → 推流开停预览恢复 → 拔插重开
5. 勿提交 `local.properties`、代理密钥、临时解压目录、`*.apk`

---

**文档冲突时优先级**：用户指令 > 本文件 > android/skills / developer.android.com > 第三方库当前版 README。
