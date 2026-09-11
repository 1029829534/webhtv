# P2-4：Android MPV DV7 FEL 双层重建

## Recovery anchor

- 目标：在 MPV「播放性能 → DV7处理」增加 **FEL 双层重建**，仅手动选择时允许 BL 硬解 + EL 软解 + libplacebo GPU 重建；默认值与现有模式不变。
- 授权：2026-09-12 用户批准新分支实施、去掉名称中的“（实验）”，要求保护既有功能和性能、后续无需逐步确认。未授权推送。
- 分支：`feature/mpv-dv7-fel`；基线/回滚锚点：`fc62397591701b2232ae7de4f50a032bd7742064`。
- Lane/guard：`upstream` / `P2-4`；最初的 `app/.cxx/` 35 个未跟踪文件受保护，不编辑、不提交。
- 状态（2026-09-12 07:31 Asia/Shanghai）：App/native 接线、本机定向测试、两 ABI native/ELF/导出符号校验及两个 debug APK 均已完成。唯一剩余验收阶段是真机 FEL/默认性能/生命周期；USB 和无线 ADB 均无设备。尚未提交、创建 tag 或推送，不宣称已完成需求。
- 当前文件/符号：`PlaybackPerformanceSetting.getMpvDv7HandlingMode()`、`PlaybackPerformanceDialog.optionAction()`、`MpvPlayerEngine.selectDv7Handling()/buildConfig()`、`PlayerManager.evaluateMpvAutoOutput()`、native `update_vo_chain_el_state()/mp_enhancement_pair_create()`。
- 已完成证据：57 项 JUnit（8 类）通过；Mobile arm64/Leanback armv7 Java 与 APK 构建通过；最终 native 源码契约和两 ABI ELF/锁定依赖检查通过；导出符号未变，仅两份 `libmpv.so` 改变，其他 18 份 MPV 资产未变；APK 内各 10 份资产均匹配。日志和最终哈希见第 6 节。
- 未验证改动：全部 App/native 工作树改动尚未做真机 FEL、生命周期和性能验收。已打包不等于播放通过，不重跑已经通过且输入未变的构建/测试。
- 未解决风险：实际硬件帧 YUV 保真、EL 软解吞吐与帧配对、Surface 饥饿、切换恢复；当前 ADB 无设备，不得用编译替代设备验收。
- 唯一下一步：手机连接并授权 USB 调试后，使用 Android 安装辅助脚本安装现成的 Mobile arm64 APK，开始真实 DV7 FEL 与基线的对照验收。

## 1. 完成范围与约束

允许改动：MPV 设置/弹窗、MPV 引擎/输出策略及必要 PlayerManager 接线、相应测试；独立 `third_party/patches/mpv-android-fel.patch`、native 构建/校验脚本与构建说明、两 ARM ABI 的受影响 MPV 资产；本文件与总索引。工作缓存仅用于可复现构建。

不改：Exo、IJK、音频策略、原盘菜单逻辑、JNI API、FFmpeg/libplacebo 版本、既有 Surface/fence/MediaCodec starvation 补丁。`libplayer.so` 不因本任务无关地重建。保留旧设置值 `0=P8.1`、`1=HDR10`，新增值，不迁移用户偏好、不自动启用 FEL。

当前代理沿用 `http://127.0.0.1:7897` / `socks5://127.0.0.1:7897`。无需重复已完成的上游搜索。

2026-09-12 02:40 Asia/Shanghai 的执行估计：代码和测试 40–50 分钟；温缓存双 ABI 构建/校验 15–25 分钟；打包/设备回归 20–30 分钟；收尾约 5 分钟，目标 04:25 前（设备连接等待另计）。估计不是退出或降低验收的依据。

## 2. 固定基线与上游台账

来源身份以 `third_party/mpv-native-lock.json`、已有源码缓存和前轮保存的上游 API 响应为证。旧 P2-4 分期可从 `9fcab83f9084446566240a8e8f5233d87d0274cc:docs/upstream-player-dependency-merge-assessment-2026-08-20.md` 的 42.5 节恢复。

| 仓库/组件 | 完整 revision | 本阶段处置 |
| --- | --- | --- |
| FongMi/mpv（锁定） | `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` | 保持，基于现有 WebHTV 补丁追加窄适配 |
| FongMi/FFmpeg | `177f090e0503b7e013922ca903bde14b1c375f18` | 已有 dovi_split / RPU；不升级、不重编无关 codec |
| FongMi/libplacebo | `b694a21bf2dc176c1e98b8a13c6421a0de5f3da5` | 已有 FEL/NLQ；复用 GPU 合成 |
| FongMi/mpv-android | `99a60ad2141d5ace94453590903c2c6b9a0a2443` | 构建框架保持 |
| mpv-player/mpv PR #17932 merge | `99b4c12cccb4d8d3f72b41944cb6c640e2156650` | 已覆盖，已核实为锁定 mpv 祖先；不重复合并 |
| mpv-player/mpv PR #17932 head | `fb39a3c147bd5da26085a2bed57a5e40670d8c14` | 已被上述 merge 覆盖，仅作为配对/调度参考 |
| libplacebo MR !851 head | `05ac2cca6571c04d06369a26825d207781b73f32` | 当前源码含相关实现；本地浅克隆无该对象，不声称已证明 ancestry |
| FongMi/mpv Android hybrid | `06ec6e1746e5cbdcd271e613fdb1f7f7ecd36042` | **部分适配**：只取 EL force_swdec 与必要接线；其余 Surface/HDR/OSD 重写不采用，保留本地后续修复 |

NDK `29.0.14206865` / r29，native API 24；`arm64-v8a`、`armeabi-v7a`；构建使用已有 `build/mpv-native/mpv-android/buildscripts/deps/` 与 ABI build/prefix 缓存。缓存含现有补丁，不 reset/覆盖不明修改。

## 3. Best-practice 决策记录

访问日期均为 2026-09-12。等级：A=实际源码/测试/官方资料；B=维护者讨论或成熟相关项目；C=性能推断，必须实测。

| 证据类别与来源 | 等级/支持的结论 | WebHTV 适用性、限制与决定影响 |
| --- | --- | --- |
| [mpv PR #17932](https://github.com/mpv-player/mpv/pull/17932)，源码 `demux/dovi_split.c`、`filters/f_enhancement_pair.c`、`vo_gpu_next.c` | A；现有拆分、PTS 配对、EL 软件帧上传可复用 | 使用原配对/seek/reset，不另写不精确的帧序号容错算法；上游桌面成功不证明 Android 成功 |
| [FongMi/mpv 06ec6e](https://github.com/FongMi/mpv/commit/06ec6e1746e5cbdcd271e613fdb1f7f7ecd36042)，实际各文件 diff | A；EL 独立强制软解可避免两解码器争用同一 AImageReader Surface | 拒绝 hardware/hybrid decoder wrapper，不能仅关闭普通 hwaccel；只适配必要接线，不能整包 cherry-pick |
| [libplacebo MR !851](https://code.videolan.org/videolan/libplacebo/-/merge_requests/851)，锁定源码 `sh_dovi_compose_nlq()` / `sample_el()` | A；已有 BL reshape、NLQ 与采样融合 | 采用 GPU 重建，不把 4K 图像 readback 到 CPU；MR notes API 401，未获取的讨论不作已读证据 |
| [MediaCodec 官方文档](https://developer.android.com/reference/android/media/MediaCodec)、[AHardwareBuffer 官方文档](https://developer.android.com/ndk/reference/group/a-hardware-buffer) | A；Surface / GPU buffer 生命周期及格式能力是硬约束 | 原始 YUV 编码值、10-bit 精度与持有/释放规则必须保持，不能将已映射或 8-bit BL 再叠 EL |
| FelBaker `cae66302433578ec62afa8c5c5809e93d0405b2f`、DoViBaker `ffba39830b694ddca0bf5f73dcf1b462713bb7f4` 的源码/技术说明 | B；真实 FEL 残差与仅用 RPU/BL 不是同一画面 | 可作算法交叉证据和样片预期；离线 VapourSynth 工作负载不是手机实时性能证明 |
| 上述 mpv PR 维护者讨论及原始 API comments；相关项目 benchmark/field 说明 | B/C；双解码队列/残差并非所有设备都能实时承担 | 不承诺所有手机/电视无额外成本；只 opt-in，保留原模式和诊断，性能需比较冻结基线 |
| 论文类别 | 不适用作为实施规范 | 此阶段不是推导新的 DV 算法；没有论文能替代实际 RPU/NLQ 源码、Android 互操作和真机验收，故不新增泛视频论文搜索 |

原始研究响应保存在 `/private/tmp/webhtv-dv7-fel.7Daduc/`；长期事实已记入此文档，不依赖临时目录作为唯一决策记录。

### 当前代码与数据流

- `PlaybackPerformanceSetting` 已将 MPV 与 Exo 的 DV7 key 分开；MPV 默认 P8.1，旧值/旧 key 兼容必须保留。
- `PlaybackPerformanceDialog.optionAction()` 目前在两值间轮换；Mobile 与 Leanback 共用，新增第三值，不扩展 Exo。
- `MpvPlayerEngine.selectDv7Handling()` 对旧模式始终优先原生 DV7；新 FEL 显式请求必须能覆盖原生直出，其他判断顺序不变。
- `PlayerManager.evaluateMpvAutoOutput()/prepareMpvOutputForNewItem()` 负责获取源 profile 并按需重建/保留进度；必须防止自动直出覆盖 FEL、也防止普通文件继承上个 FEL 文件的输出状态。
- `mpv-android-dovi-el-surface.patch` 默认不允许 Android EL；不能简单全局删除保护或让两个 MediaCodec 共享输出 Surface。
- FFmpeg 两 ABI `CONFIG_DOVI_SPLIT_BSF=1`；libplacebo 两 ABI `PL_HAVE_DOVI=1`、API 375。现有 Vulkan AHB direct 已保留 RGB_IDENTITY/FULL 与真实 sample depth；保留全部 direct/stable/generic 流程。

### 方案比较与决定

| 方案 | 正确性/功能 | 性能/兼容/维护 | 决定 |
| --- | --- | --- | --- |
| 不改 | 只能用 BL 或保留 RPU 的单层映射，不能真正重建 FEL | 无新风险 | 不满足已批准需求 |
| 原样整包采用 06ec6e | 包含混合解码，但还重写 Surface/HDR/OSD | 会覆盖本地原盘、输出时序与 starvation 修复，回滚面过大 | 拒绝 |
| WebHTV 窄适配 | 独立 opt-in；BL 使用现有 HEVC 硬解，EL 强制软件，gpu-next/libplacebo 合成；非 FEL 路径保持原状 | 不新增大型依赖；EL/GPU 有真实开销，仅选择后承担；容易连同资产整体回滚 | **实施** |
| CPU 4K readback/自行重写合成 | 可能可出图，但易损失精度且带宽/同步开销大 | 不符合当前性能与维护目标 | 不采用 |

安全/许可：不引入 JVM、远程执行或新下载机制；沿用 mpv/FFmpeg/libplacebo 许可和源码来源。依旧视码流/RPU 为不可信输入，复用既有有界配对和解码错误流程，不新增无界缓存。JNI/API/ABI 不改变。

## 4. 验收、观测与回滚

1. **设置隔离**：旧值 0/1 与默认值不变；只有 MPV 有第三项；用户手动选择并重新起播/按现有重建流程生效；切回恢复原模式；未知/损坏值回到原默认值。
2. **路径隔离**：未选 FEL 时不创建 EL decoder，不改变原生 DV、P8.1、HDR10、普通视频的既有路由；选 FEL 后仅符合源 profile 的文件进入重建；不与 `mediacodec_embed` 混用。
3. **真正 FEL**：日志证明 BL 硬解、EL 软件 HEVC、PTS 配对与 EL/NLQ 合成；使用能分辨 BL-only 和 FEL 的样片，不能把画面正常误称 FEL 成功。MEL/无 EL 不误报重建。
4. **生命周期**：seek、暂停恢复、连续重开、切回旧模式；不得有重复重建循环、解码 Surface 争用、无限等待、崩溃或已知颜色损失。
5. **性能**：冻结基线与候选同设备/样片/设置比较；默认/旧模式额外 EL 工作为零。启动、持续丢帧、A/V 同步、CPU/热负载都要报告；FEL 模式新增成本不是默认性能收益，不能用降低精度或丢 EL 冒充优化。
6. **二进制与产品**：两 ABI mpv 增量构建和 ELF/SONAME/DT_NEEDED/导出与 native 资产检查；Mobile/Leanback 定向 Java 测试/编译，最小 APK 资产匹配；不更新无关 FFmpeg/JNI 库。

最便宜的决定性检查是设置/输出策略单元测试与 native 源码路径断言，然后才做 native 编译；真实播放/性能不能由这些静态检查替代。

回滚：未提交时只撤销本任务自己创建的改动；提交后对本任务原子提交执行 `git revert`，同次恢复 App、补丁、构建脚本和两 ABI `libmpv.so`。不 reset 整个工作区、不改保护路径、不动已发布 tag。验证完成后由 guard 原子提交并立即创建唯一 annotated 本地恢复 tag；不推送。

## 5. 实施与验证记录

- 02:33–02:40：恢复 Git 和研究；从 `feature-menu` 新建 `feature/mpv-dv7-fel`；guard 保护 35 个初始 dirty 文件；ADB 暂无设备。
- 02:50：基线 native cache diff 与两 ABI 资产 SHA-256 已冻结到 `/private/tmp/webhtv-p24.z9ixxT/`；不执行全栈 checkout/reset。新补丁以 `VO_CAP_GPU_DOVI_EL_SW` 单独声明软件 EL 能力，Android 仍须显式 option + 源 Profile 7 才启用；默认 `VO_CAP_GPU_DOVI_EL` 保护原样保留。force_swdec 传入配对器、解码线程并拒绝硬件/hybrid wrapper；沿用原 PTS/队列/reset。在新路径内对 MEL 仅省略无用 EL 上传，不改变旧路径。设置、策略接线开始，定向测试未运行。
- 03:10：首轮两 ABI native 编译通过。补上与 WebHTV `hwdec-software-fallback=no` 的必要兼容：主动 force_swdec 的 EL 不属于 BL 硬解失败回退，不能被该设置拒绝；未 force_swdec 时判断保持不变，修正后需要增量重编。第一轮裸 ninja 的 PATH/跨平台 pkg-config 环境不完整，改用已有 buildall 的单 mpv 目标，不重建无关依赖。Java 定向验证正在运行；Gradle 使用临时 CMake staging 目录，避免改动受保护的 `app/.cxx/`。
- FEL 的必要 VO/GPU/BL 保真与 demux 选项是耦合契约，只有真实 DV7 激活 FEL 时覆盖冲突的 mpv.conf 同名配置；音频、缓存、Vulkan backend、用户 shader 等不变。显式软解 BL 仍尊重用户选择，但该新模式关闭破坏残差精度的解码跳帧/省滤波，仅允许输出丢帧；EL 始终软解。
- 06:58（会话续接后重新核对本地时间）：57 项定向 JUnit（8 类）全部通过，Mobile arm64 与 Leanback armv7 Java 编译通过（日志 `java-verification.log`，69 秒）；两 ABI 更新后的 native 编译/链接通过。原时间估计已过，剩余本机校验目标约 07:25；ADB 仍无设备。
- 07:05 源码复核：OpenGL 原始 DOVI 映射已有 `GL_EXT_YUV_target` 能力拒绝门控，不会拿 RGB8 冒充原始 YUV；Vulkan 继续复用既有原始 YUV direct/stable/generic。另补齐 `receive_frame()` 的 `force_eof` 重试终止条件，与现有 reinit 的有界失败策略一致，避免新 FEL GPU 路径遇到硬解不兼容时无限重试。此 native 修正须重编两 ABI；Java 未改，不重复已通过的 JUnit。
- 验证结果、最终资产 SHA-256、构建日志和提交/tag 由本任务后续阶段补充；此时不宣称实现完成。

## 6. 最终本机验证与待真机状态（2026-09-12）

证据根目录：`/private/tmp/webhtv-p24.z9ixxT/`。当前 HEAD 仍为基线，工作树持有本任务完整未提交改动；不自动推送。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 8 类定向 JUnit，57 项 | 通过 | `java-verification.log`；不重复运行 |
| Mobile arm64 / Leanback armv7 Java 编译 | 通过 | 同上 |
| 最终双 ABI native 编译（含 forced-EOF 修正） | 通过 | `native-bounded-fallback-build.log` |
| `verify_mpv_fel_contract.py --mpv-source build/mpv-native/mpv-android/buildscripts/deps/mpv` | 通过 | 已应用补丁反向检查、opt-in、EL 强制软解、BL fallback 隔离及有界配对 |
| `build_mpv_native.sh --abi all --stage-only --install` | 通过 | `native-final-stage.log` |
| `verify_mpv_native_assets.sh --require-elf` | 两 ABI 通过 | `native-final-assets-verification.log`；NDK r29 llvm-readelf |
| 基线/最终 native 资产 SHA-256 | 仅两份 libmpv 改变，其余 18 份一致 | `assets-before.sha256`、`assets-final.sha256` |
| 基线/最终 libmpv 动态导出符号 | 两 ABI 完全一致 | `native-symbols-verification.log`、`exports-*-before.txt`、`exports-*-final.txt` |
| 手机 64 位、电视 32 位 debug 打包 | 通过，Gradle 报告 2m 8s | `apk-build.log`；两个 assemble 目标，一次调用 |
| APK 内 MPV 资产 | 各 10 份逐一匹配最终资产 | `apk-assets-verification.log` |
| 真机、USB / 无线发现 | 无设备，待连接 | `adb devices -l` / `adb mdns services` 均为空；不以模拟器替代 ARM MediaCodec 验收 |

Gradle 使用 `JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`，并带 `--init-script /private/tmp/webhtv-p24.z9ixxT/isolated-cxx.gradle` 将 CMake staging 隔离于原 `app/.cxx/`。构建目标是 `:app:assembleMobileArm64_v8aDebug :app:assembleLeanbackArmeabi_v7aDebug`，没有全产品矩阵或无关 native 全栈重建。

最终 SHA-256：

| 产物 | SHA-256 |
| --- | --- |
| arm64-v8a libmpv.so | `2a936ec8491c6c7826a6bf16503e6eddf8b78c740c29aa606e985f9fceaa9213` |
| armeabi-v7a libmpv.so | `6b7bc5f5bb3dfacb279f233d378cc6426f9696e8a51737da6d5abfe371a586da` |
| Mobile arm64 debug APK（180178693 bytes） | `117960c1c0ee4e23007b3465a7f4dd2bbbc1f5d4106ad616ae8215858d47d599` |
| Leanback armv7 debug APK（144857928 bytes） | `52275d4f023f7874597f362b05609a5049ff5a059953bf3abb80afea28218ae3` |
| mpv-native-lock.json（未改） | `a009a6dd9066eacd8547383f93cc7dce2956fff7d6d338bd886be75b9e4ae159` |
| mpv-android-fel.patch | `06636e67117a491e99efac4a2cde0cdf090ea73b745a1ad8679d502b66f7f611` |
| build_mpv_native.sh | `8722d86d12b4346b472f3ba9045f029894df59dec19f61f458f108c85b0178d5` |
| verify_mpv_native_assets.sh | `daccc5d2f7f7ca9e5fbf312d5ed7d656ff0375b49e6a645a2c2113f24b76e8b5` |
| verify_mpv_fel_contract.py | `ef04328bdaeb4012e0cb3cb70d70d2f14e23b214aa45fc54253cdeeb3b8b9e52` |

候选包位于：

- `app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`
- `app/build/outputs/apk/leanbackArmeabi_v7a/debug/app-leanback-armeabi_v7a-debug.apk`

基线 Mobile APK 及两份 libmpv 已冻结在证据目录的 `baseline-mobile-arm64-debug.apk`、`baseline-arm64-libmpv.so`、`baseline-arm32-libmpv.so`，供同设备对照。

样片筛选：下载并核实 `quietvoid/dovi_tool@614c816b6446dcd1dbaf433403d499a6026fbb5a` 的 `assets/hevc_tests/regular.mkv`。它实际是 256×144、Profile 8、`el_present_flag=0`；该项目的 mux 测试也只拼接测试层，不能证明真实 FEL 残差重建。保存 `dovi-regular.probe.json` 和 `dovi-hevc-demux-test.rs`，不将这些输入算作 FEL 验收。

尚须完成第 4 节的真实 BL 硬解/EL 软解/有效 NLQ、视觉差异、seek/暂停/切换恢复与默认模式对照。没有设备证据前，不允许用编译成功或 marker 存在声称画质、吞吐、Surface 生命周期或默认性能已验收；不调用 guard finish 创建“已验证”提交/tag。
