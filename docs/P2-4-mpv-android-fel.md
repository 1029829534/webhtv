# P2-4：Android MPV DV7 FEL 双层重建

## Recovery anchor

- 目标：在 MPV「播放性能 → DV7处理」增加 **FEL 双层重建**，仅手动选择时允许 BL 硬解 + EL 软解 + libplacebo GPU 重建；默认值与现有模式不变。
- 授权：2026-09-12 用户批准新分支实施、去掉名称中的“（实验）”，要求保护既有功能和性能、后续无需逐步确认；08:56 用户进一步批准第 8 节最佳实践方案实施。未授权推送。
- 分支：`feature/mpv-dv7-fel`；基线/回滚锚点：`fc62397591701b2232ae7de4f50a032bd7742064`。
- Lane/guard：首轮 `upstream` / `P2-4` 已按用户要求保存已知问题快照；本轮 `quick-fix` / `P2-4-log-stall`，原 `app/.cxx/` 35 个未跟踪文件仍受保护。
- 状态（2026-09-12，Asia/Shanghai）：基线快照为 `792c1f880bc151eb1cb6675034ec144aadc14766`，tag 为 `recovery/P2-4/20260912074110-792c1f880bc1`。18 项测试通过的电视候选已被用户复测：新日志证实音频查询风暴消失、BL 硬解及首个 FEL/NLQ GPU 输入成功，但仍有 49 次硬解错误和至少 34.661 秒主线程堵塞。已有 Java 修复尚未提交，native 未变；不宣称播放验收通过。最新请求仅作外部调研，结果和推荐方案见第 8 节。
- 当前文件/符号：`PlaybackPerformanceSetting.getMpvDv7HandlingMode()`、`PlaybackPerformanceDialog.optionAction()`、`MpvPlayerEngine.selectDv7Handling()/buildConfig()`、`PlayerManager.evaluateMpvAutoOutput()`、native `update_vo_chain_el_state()/mp_enhancement_pair_create()`。
- 已完成证据：57 项 JUnit（8 类）通过；Mobile arm64/Leanback armv7 Java 与 APK 构建通过；最终 native 源码契约和两 ABI ELF/锁定依赖检查通过；导出符号未变，仅两份 `libmpv.so` 改变，其他 18 份 MPV 资产未变；APK 内各 10 份资产均匹配。日志和最终哈希见第 6 节。
- 未验证改动：本轮只更新本文件和评估索引，无新生产代码、锁、补丁或 APK。既有 Java 修复消除了一个已确认的阻塞来源；持续重建、画质、吞吐与退出仍未验收。第 8 节为待实施设计，不是已修复清单。
- 未解决风险：MediaCodec 输出缓冲饥饿仍须计数实证；主线程同步轨道查询的阻塞已有直接证据。电视不能 ADB，后续所有必要诊断须进入 `http://192.168.1.5:9978/debug/logs` / `/debug/stream?v=0`。独立进程恢复界面仅评估，尚未授权结束进程的新行为。
- 唯一下一步：将已通过 18 项测试及电视日志验证的日志门控单元独立提交/tag，然后为已批准的异步轨道快照/JNI 等改动登记新的 guard 作用域；不将整个 FEL 卡死标成已修复。

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

## 7. 电视启动卡死：日志回调主线程饥饿（2026-09-12）

用户随后明确要求保存当前已知问题快照，故第 6 节产物及源码已于 07:41 提交/tag，**并非通过了设备验收**。本轮回滚锚点为 `792c1f880bc151eb1cb6675034ec144aadc14766`，不修改其 tag。

### 证据与最小设计

- 用户离线证据：`/Users/macbookpro/Downloads/webhtv-debug-log (30).txt`，trace `p-w2su2i-1`，本地 `P7_FEL_GIJoe_The_Rise_of_Cobra.mkv`。在线原始快照保存于 `/private/tmp/webhtv-p24-freeze.Y6FQWF/tv-live-initial.json` 和 `tv-live-raw.txt`。
- 07:43:31 原生 `video/dolby-vision` Profile 7 decoder 不存在；07:43:32 App 已正确转入手动 FEL/gpu-next。后续日志显示 `c2.mtk.hevc.decoder` 启动成功，EL 走独立软件 HEVC，不能把首次原生直出失败当作 FEL BL 硬解也失败。
- 明确卡死链：`MpvPlayer.logMessage()` 将每条日志入主线程队列，再无条件调用 `maybeRetryDtsHdAsCore()`；后者在检查是否配置 DTS-HD 或是否为 AudioTrack 初始化错误之前，就同步读取 `audio-params/format` 与 `current-tracks/audio/codec-profile`。Java 参数提前求值还使 `firstNonEmpty(cached, query())` 在缓存非空时照样查询。
- 这次实际 `audio-spdif` 为空（PCM），根本不需要 DTS-HD 回退；查询却反复花费约 1545–1550ms，主线程看门狗记录连续堵塞超过 178 秒。Vulkan 自报创建耗时只有 10.207ms，而主线程打印相关日志跨越数分钟，证明当前日志时间混入严重排队延迟，不能直接当作 native 初始化耗时。
- 最小修复：在任何属性访问前按既有 DTS-HD 配置/一次性门控/AudioTrack 初始化错误筛选；缓存只在确实缺失且需要回退判断时同步补读，保持真实 DTS-HD 回退语义。普通、FEL、非 DTS-HD、非初始化错误日志均不查询这两项 native 属性。
- 日志补齐：关键 FEL、BL/EL 解码、DV 与失败诊断在 native 回调到达时写入现有 `PlaybackTrace → SpiderDebug → DebugLogStore`，不等待主线程；主线程仍负责状态变化，禁止从回调线程操纵 MPV。补采样限频的主线程日志排队耗时，并显式保留 `WebHTV FEL pair`、`WebHTV FEL GPU input`/NLQ 标记。不开逐帧日志、不增加非调试模式的文件 I/O。
- 这是有现成策略和日志证据的局部 bug 修复，沿用 P2-4 既有设计；不扩大上游研究、不更改 BL/EL 解码或 GPU 路由、不升级依赖。

### 验收与保护

作用域：`MpvPlayer.java`、`MpvDtsHdFallbackPolicy.java`、`MpvDiagnosticsPolicy.java`、其两份策略测试、本文件和索引。通过复现条件的门控测试、既有真实 DTS-HD 回退测试、日志分类/脱敏测试及定向编译，生成 Leanback armv7 debug 包后用在线日志验证。APK 必须继续包含第 6 节的同一份 armv7 libmpv，不重建 native。代码/测试约 15 分钟，打包约 5 分钟，目标 08:20；电视安装和交互等待另计。

新增“至少可退出”问题的只读评估：Manifest 已有独立 `:error_activity` 的 `CrashActivity`；当前 MPV watchdog 仅在调试开启时运行且只记录堆栈，不会救援。主线程挂死时同进程弹窗不能保证操作；可以补独立恢复页，由用户选择结束播放并返回首页。它还需处理原进程身份、重复触发、服务重启、二进程 App 初始化与后台启动限制，不能用主线程超时或强杀线程冒充。此次未将该新进程行为混入定向日志修复，也不自动杀进程或重播。

### 本机结果与候选交付

- `MpvDtsHdFallbackPolicyTest`：10 项通过，含日志突发/PCM/重复回退前置门控及原有 DTS-HD 回退场景。
- `MpvDiagnosticsPolicyTest`：8 项通过，含 FEL/NLQ/解码关键消息即时日志分类、普通逐帧日志排除和原有脱敏策略。
- 一次 Gradle 调用完成 Mobile arm64 定向单测/编译与 `:app:assembleLeanbackArmeabi_v7aDebug`，结果 `BUILD SUCCESSFUL in 41s`。日志 `/private/tmp/webhtv-p24-freeze.Y6FQWF/java-tests-tv-apk.log`，继续使用上一轮隔离 CMake staging，保护 `app/.cxx/`。
- 电视候选：`app/build/outputs/apk/leanbackArmeabi_v7a/debug/app-leanback-armeabi_v7a-debug.apk`，144857928 bytes，SHA-256 `26f5b1dbfee477e948ada579323f228f1337bb8ed5eaa0688f3e9a811dcaf390`。
- APK 内 `assets/mpv-libs/armeabi-v7a/libmpv.so` 实际 SHA-256 仍为 `6b7bc5f5bb3dfacb279f233d378cc6426f9696e8a51737da6d5abfe371a586da`，与已知问题快照一致。此次不混入任何 native 变量。
- 新日志标签为 `mpv-native`（回调到达即写入 App 调试日志）和 `mpv-anr: native-log-queue delay=...`（主线程排队耗时，5 秒限频）。现有 `/debug/logs` 与 `/debug/stream` 会直接展示，无需 ADB。
- 随后的 `(29).txt` 和在线快照已证明用户使用新候选复测，结果见第 8 节：音频查询门控有效，但卡死场景未解决。不提交/tag 为已验收的完整修复。独立恢复页仍未实施，不能把本候选当作已经有强制退出兜底。

## 8. 真机二次证据与定向外部研究（2026-09-12）

### 8.1 范围、授权和研究问题

用户要求深度查阅论文、帖子、博文、官方文档、issues 和项目源码，为当前电视上的 **MediaCodec BL 硬解 + FFmpeg EL 软解 + libplacebo GPU FEL 重建** 卡死寻找参考。最新授权为调研，不进行新的播放代码修改、构建、安装、提交/tag 或推送。

仅更新本文件及 `docs/upstream-player-dependency-merge-assessment-2026-08-20.md`，沿用当前 guard 已包含的文档路径；保护现有五份 Java/测试修改和最初的 `app/.cxx/` 35 个文件。原有源码、锁、两 ABI 二进制及其归属不变。研究从 08:25 Asia/Shanghai 开始，预计 25–35 分钟；核验采用已保存响应、源码调用链、完整提交 ID 和文档校验，不重复构建。

三个决策问题：

1. 软件 EL 较慢时，配对器是否可能占满 BL 的有限硬解输出缓冲？现有上游是否有针对性的修正？反假设是驱动/渲染 fence 或解码数据本身先失败；需要持有数、PTS、端口等待和释放计数区分。
2. 已经支持 BL 硬解的电视，应怎样保留原始 10-bit 信号并进行真正 FEL 重建？哪些项目可直接提供算法或调度参考，哪些不能证明 Android 实时可用？
3. native 变慢时，怎样避免 UI 的同步等待；若发生永久 native 死锁，怎样让用户仍有退出途径？异步化与进程隔离分别保证什么？

### 8.2 新日志已经证明什么

证据为 `/Users/macbookpro/Downloads/webhtv-debug-log (29).txt` 及在线原始快照 `/private/tmp/webhtv-p24-freeze.Y6FQWF/tv-after-log29.json` / `tv-after-log29.txt`。按时间而非文件编号排序：这是 08:12:58 开始的较新播放，trace `p-w3uti8-1`，本地 GIJoe P7 FEL MKV，从约 14.121 秒恢复。不要与 `(30).txt` 的 07:43 播放混用。

| 时刻/指标 | 直接观测 | 可以得出的结论 |
| --- | --- | --- |
| 08:13:05.261 | `MediaCodec started successfully: codec = c2.mtk.hevc.decoder` | BL 硬解启动成功，不是“电视不支持基础层硬解” |
| 08:13:05.925 / 08:13:06.900 | EL `1920x1080 yuv420p10`；BL `Using hardware decoding (mediacodec)` | 双路解码路径已经建立 |
| 08:13:07.035 | `BL=13.889000 EL=13.889000 NLQ=1 software-EL=1` | 已配对到真实 FEL 帧；恢复播放的预解码时间可早于目标时间 |
| 08:13:17.965 / 08:13:18.044 | 4K AHardwareBuffer `10-bit, raw Dolby Vision`；`matched EL uploaded with active NLQ` | 双层输入已经到达 GPU；不等于持续输出、位精度或画质验收通过 |
| 08:13:07.786–08:13:45.350 | 49 次 `Error while decoding frame (hardware decoding)` | native 错误仍存在；相邻间隔最小 0.759s、平均 0.783s、最大 0.865s |
| 08:13:41.561 | `main stalled=34661ms`，栈位于 `readTrackInfo → refreshTracks → handleEvent` | 主线程同步属性读取仍造成可见 ANR；各次调用多为约 1.55 秒 |
| 新日志标签/查询 | 已出现 `mpv-native`；未再出现那两项音频属性的 slow-native 查询 | 上轮日志查询门控确实生效，但只解决了一个入口 |

两层问题应分开：

- **已确认的 UI 缺陷**：`FILE_LOADED → refreshTracks()/syncOsdSurfaceRequirementFromMpv()/readTrackInfo() → MPVLib.getProperty*` 仍在主线程串行查询 `sub-visibility`、`sid`、`secondary-sid`、`track-list/*`。
- **有强依据但未实证的 native 假设**：16 帧 BL 配对队列与有限 MediaCodec 缓冲冲突。49 次错误的节奏吻合本地 FFmpeg 750ms 端口饥饿超时，但最新导出仍没有那条明确的 starvation 日志，也没有实际缓冲持有数量；不能写成“根因已完全证实”。
- **已确认的重试策略缺口**：`hwdec-software-fallback=no` 在 mpv 映射到 `INT_MAX`；`handle_err()` 只有累计到该阈值才设 `hwdec_failed`。已有 `force_eof` 重初始化保护不能自动终止这类运行时错误循环。禁止 BL 软回退与允许无限错误重试不是同一要求。
- **诊断盲点**：当前 `shouldLogNativeImmediately()` 按 `error/failed/invalid` 等文本筛选；FFmpeg 的关键句包含 `failing hardware`，不命中这些词。若主线程堵塞，这条错误可能一直留在旧的主线程日志路径。后续应按 native 日志级别保留 warn/error，并限频，而非只扩大关键词列表。

### 8.3 最直接的上游参考：mpv PR #18375

[PR #18375](https://github.com/mpv-player/mpv/pull/18375) 名为 `f_enhancement_pair: keep retained frames within the hw surface budget`，API 确认为已合并，合并时间 2026-08-22T19:00:12Z。读取了 PR 文件 diff、提交列表、实际合入提交及当前配对器文件历史；截至此次查询，该文件最新修改仍是以下预算修复。没有由此声称整个 mpv 最新树都已完成回归审查。

当前锁定代码只包含最初配对器 `5330cae57eba5d34719d2d6a13d98770e8841afc` 的行为及本地 FEL 适配：仍为 `QUEUE_MAX=16`，没有 `el_seen` 和 extra-frame hint 接线，以下三个改动未被本地实现覆盖。

| 实际合入的完整提交 | 实际改变 | P2-4 处置 |
| --- | --- | --- |
| [`3b4caf0f8ba3101c3aa0b2f59fbd13863e965cfc`](https://github.com/mpv-player/mpv/commit/3b4caf0f8ba3101c3aa0b2f59fbd13863e965cfc) | 首次 EL 尚未就绪时，不因为 BL 队列满就提前输出 BL-only；seek/reset 清除 `el_seen` | **建议窄适配**，同时保证 EL 调度能推进、有界失败；不能只添加一个无限等待条件 |
| [`228f3109fd1a620094758fea90dd387c64ec22c9`](https://github.com/mpv-player/mpv/commit/228f3109fd1a620094758fea90dd387c64ec22c9) | 增加 decoder wrapper 的 extra hardware frame hint，交给 `VDCTRL_SET_EXTRA_HW_FRAMES` | **有条件参考**；固定 AVHWFramesContext 帧池可用，不能当作 MediaCodec Surface 扩容开关 |
| [`b955aa28f3dc93dc6b21485a0d5b7feb8e6dc10f`](https://github.com/mpv-player/mpv/commit/b955aa28f3dc93dc6b21485a0d5b7feb8e6dc10f) | 配对队列 16→8，并为 BL/EL 申请对应额外硬解帧预算 | **建议适配其资源预算原则**，不机械照搬数字 8 或假定电视容量会随之增加 |

三者实际父子关系已由 API 验证。PR 中较早的三个修订 ID 分别是 `d3f337bf10603b5f371a0f225fd8abb8f1c9a989`、`4f4e8f6135c6c57109675a9cb48296b40332d524`、`8cadd3b93f03ccced57a2111db7b5b673616ef7b`；处置为被上述实际合入版本覆盖的参考修订，不重复作为待合并提交。

为何不能原样照搬到 Android：

- FFmpeg `libavcodec/mediacodecdec.c:mediacodec_hw_configs` 只声明 `AD_HOC | HW_DEVICE_CTX`，没有 `HW_FRAMES_CTX`。
- mpv `vd_lavc.c` 据此选择 `use_hw_device`，`init_generic_hwaccel()` 在 `!use_hw_frames` 时直接返回；`hwdec_get_extra_frames()` 增加 `initial_pool_size` 的路径不服务当前 MediaCodec Surface decoder。
- 当前 BL `AVMediaCodecBuffer` 在配对器中被引用，直到 `hwdec_aimagereader.c:mapper_map()` 才经 `av_mediacodec_release_buffer_status(..., 1)` 提交到 Surface；单纯持有 `mp_image` 并不是廉价、无限的普通内存缓存。
- 本地 `AImageReader_newWithUsage(..., maxImages=5)` 是另一层“已获取 AImage 数”限制，**不是 MediaCodec 的总输出缓冲数**。不能用 `16 > 5` 直接证明根因，也不能认为把 5 改大就解决了解码端饥饿。

### 8.4 各类资料及适用边界

访问日期均为 2026-09-12，网络使用用户指定的本机 7897 代理。等级 A=官方契约/实际代码/设备原始证据，B=维护者解释或成熟项目经验，C=未在本机复现的报告/性能结果。论文的可得范围单独标明。

| 来源与版本 | 已读证据、等级 | 对我们场景的实际价值与限制 |
| --- | --- | --- |
| [Android MediaCodec 官方文档](https://developer.android.com/reference/android/media/MediaCodec)，Buffer Processing | A；官方明确警告持有 input/output buffers 可令 codec 停滞，而且设备相关，部分 codec 要等所有 outstanding buffers 归还才继续 | 支持减少 BL 硬件帧滞留；**不**证明该电视的具体容量或当前错误唯一原因 |
| [NDK AImageReader 官方文档](https://developer.android.com/ndk/reference/group/media#aimagereader_acquirelatestimage)，API 24 起的 acquire/release 契约 | A；`maxImages` 耗尽会报 `MAX_IMAGES_ACQUIRED`，acquireLatest 丢旧帧需要至少 2 个余量 | 三层资源分别计数：codec output、AImage、GPU/fence；FEL 必须保持时间戳关联，不能靠随意丢 BL/EL 解套。当前网页涉及新 API 的格式变化不外推到这台 Android 14 电视 |
| [Kodi Android MediaCodec](https://github.com/xbmc/xbmc/blob/45a32be559f5a70a56f089353f71843b9dbbc0ec/xbmc/cores/VideoPlayer/DVDCodecs/Video/DVDVideoCodecAndroidMediaCodec.cpp)，`45a32be559f5a70a56f089353f71843b9dbbc0ec` | A/B；`GetAllowedReferences()` 返回 4；归还、丢弃、EOS 均有明确 release；正常解码短等待，丢弃预解码帧时用非阻塞 poll。该提交附有 MediaTek armv7 AVC seek 数据 | 可借鉴有限持有、及时归还、错误状态和预滚动调度；4 不是跨设备容量规范，AVC seek 数据不证明 HEVC FEL 性能，更不等于 Kodi 已提供通用 Android FEL 软件重建 |
| [libmpv client.h](https://github.com/mpv-player/mpv/blob/cca559b41ceb0bb7731cf6ef2e1f33276cd30c42/include/mpv/client.h)，`cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` | A；同步调用等待核心可用，等待时间可无界；提供 `mpv_get_property_async`、`mpv_observe_property`、async command/reply，异步调用顺序也需管理 | UI 使用缓存/订阅快照；禁止主线程等 Future、join 或重新同步补读。多个工作线程不会绕过核心串行锁，异步并不等于永久 native 死锁已隔离 |
| [media-kit 原生播放器](https://github.com/media-kit/media-kit/blob/c533e446755f51cf53c7e57aea873f2aa5355f81/media_kit/lib/src/player/native/player/real.dart)，`c533e446755f51cf53c7e57aea873f2aa5355f81` | A/B；以 `MPV_FORMAT_NODE` 观察整份 `track-list` 并更新状态；支持带 request id 的异步设置/命令 | 可借鉴事件驱动整份轨道快照。当前 WebHTV JNI `event.cpp` 只处理标量/string，尚无 NODE 分支及 GET_PROPERTY_REPLY 数据接线，不能只改 Java 调用名即视为实现；media-kit 也保留同步配置，不声称其所有路径都不会 ANR |
| [Android ANR 指南](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs)、[进程/线程](https://developer.android.com/guide/components/processes-and-threads)、[后台 Activity 限制](https://developer.android.com/guide/components/activities/background-starts) | A；默认输入超时 5 秒；主线程不做阻塞工作；组件可独立进程；后台拉起有版本限制；GPU 全局挂死通常无法由 App 修复 | 区分异步化、watchdog 检测和进程恢复；不能承诺任何硬件死锁下都能退出，也不能把“线程超时”当取消 native 的保证 |
| mpv [PR #17932 讨论](https://github.com/mpv-player/mpv/pull/17932)，本文件第 2 节固定源码；FongMi [Android hybrid 改动](https://github.com/FongMi/mpv/commit/06ec6e1746e5cbdcd271e613fdb1f7f7ecd36042) | A/B；已有双层拆分/配对；Android EL 强制软件解码避免共享同一 MediaCodec Surface；讨论记录了硬解表面不足及后续 #18375，也有另一个动态亮度元数据引发卡顿的案例 | 当前路线不是无参考自研；原有 force_swdec 是正确保留项。但不把桌面、不同 GPU、不同卡顿根因混为同一故障，不整包引入 fork 的 Surface/OSD 重写 |
| [libplacebo MR !851](https://code.videolan.org/videolan/libplacebo/-/merge_requests/851)；实际锁定代码 `b694a21bf2dc176c1e98b8a13c6421a0de5f3da5` 的 `sample_el()` / `sh_dovi_compose_nlq()` | A；已读实际 shader 及渲染接线：EL 采样、NLQ 残差叠加；MR 讨论原先 API 401，不当作已读 | 保留现有 GPU 重建核心，优先修调度/生命周期，不重写算法或把 4K 帧常态读回 CPU；原始编码值、位深和上采样仍须同帧参考验收 |
| [FelBaker](https://github.com/bbeny123/felbaker/tree/cae66302433578ec62afa8c5c5809e93d0405b2f)，`cae66302433578ec62afa8c5c5809e93d0405b2f`；[DoViBaker](https://github.com/erazortt/DoViBaker/tree/ffba39830b694ddca0bf5f73dcf1b462713bb7f4)，`ffba39830b694ddca0bf5f73dcf1b462713bb7f4` | B/C；BL+EL+RPU 烘焙至 PQ12；FelBaker 文档提供 RGB 输出 hash 校验和桌面 benchmark 方法 | 可作离线重建参考与画质验证思路，不移植 VapourSynth/AviSynth 整套运行时；M4/AVX2、best-of-10 的数据不外推到 Mali-G57/armv7。测试样片本身注明仅用于逻辑而非画质评价 |
| [Bigflake MediaCodec FAQ/样例说明](https://bigflake.com/mediacodec/) | B/C；codec 不是输入一帧必立即吐一帧；厂商 buffer 格式不同；Surface/GPU 路径和样例同步风险均有说明 | 交叉支持 backpressure、格式与 GPU 数据通路的注意事项；这是较旧 API 的技术文章，不把其 RGB/8-bit 样例直接用于 10-bit FEL |
| [mpv-android #1088](https://github.com/mpv-android/mpv-android/issues/1088) 及其维护者讨论、[FFmpeg #11676](https://trac.ffmpeg.org/ticket/11676) | B/C；Android TV 的 MediaCodec-copy 与 embed 结果不同，原生硬解能播不等于 copy/合成路径都兼容；FFmpeg ticket 仍 new、未复现/分析 | 反对用强制 `mediacodec-copy` 作为默认解决办法。报告是 Amlogic/Mali-G31，不能拿来断定这台 MediaTek/Mali-G57 的根因 |
| 论文 [Adaptive residual mapping for an efficient extension layer coding in two-layer HDR video coding](https://doi.org/10.1109/ICIP.2016.7532587)，ICIP 2016 | 本轮检索了 Crossref 与 OpenAlex 元数据/摘要；后者标为 closed，无公开全文地址，IEEE 页面未取得正文 | 摘要研究 LDR→HDR 双层编码残差映射/编码效率，不是 Android P7 FEL 解码调度实现。**未读全文、不作为算法正确性或安卓实时性能证据**；本轮不更换重建算法，因此该缺口不影响由官方契约和源码得出的调度/异步判断 |

常规 Google 结果为需脚本页面，Bing RSS 返回了明显无关内容，没有将这些内容或搜索摘要列为依据；转用精确项目 API、官方文档及正式论文索引。原始响应保存在 `/private/tmp/webhtv-p24-research.6Wp7xa/`，前期算法资料仍在 `/private/tmp/webhtv-dv7-fel.7Daduc/`；长期结论、链接、revision 和限制已记在本文件，不依赖临时文件存活。

### 8.5 适合 WebHTV 的方案比较

| 方案 | 正确性/兼容性 | 性能/维护与决定 |
| --- | --- | --- |
| 不改现状 | 保留当前代码，但已复现硬解错误和 UI ANR，不能满足需求 | **不接受为完成状态**；可作为冻结比较基线 |
| 原样合并 PR #18375 | EL 预热及固定表面预算有价值，但 MediaCodec 不走它的扩容帧池；仅改 16→8 仍可能超过设备可用输出数 | **不直接采用整包方案**；必须 Android 适配和真机验证 |
| Android FEL 窄适配 + 异步状态快照 | 仅新 FEL 软件 EL 路径限制 BL 持有/预取，优先可配对输出，保证 EL 能独立推进；严格 PTS/seek/reset；native 无进展有界退出；UI 不同步查询核心 | **推荐**。不改变未选 FEL 的默认/旧模式，不降低 EL 质量。减少重复 JNI 查询，但实际播放/功耗收益必须实测；包含 native/JNI 的部分须重新声明作用域 |
| BL 硬解后 CPU copy/readback 再上传 | 可作为格式验证后的诊断对照，但厂商布局/10-bit 读回支持不统一，不能默默转成 8-bit 或 BL 全软解 | **不作为默认修复**。仅线性 4K P010 数据约 24.9MB/帧，24fps 单方向约 597MB/s；这只是理论数据量，尚未计拷贝、上传和 stride，专有压缩布局另算 |
| 早期 GPU 复制至自有原始信号纹理 | 若确需更深配对，可能解除 codec buffer 持有；必须正确处理 AImage、fence、时间戳、10-bit 原始 YUV 信号与生命周期 | **后备研究方向**，不是现成修复。增加显存/带宽和渲染复杂度；先验证低持有方案，再决定是否值得引入 |
| 单独 watchdog / 独立进程 | watchdog 只能发现无响应；独立恢复进程或独立播放器进程才可能在故障核心无响应时继续接受操作 | 现有 `:error_activity` 可作为较小恢复页基础；完整 `:player` Service/IPC 是更大架构，**单列评估，不与 FEL 算法修复捆绑** |

推荐适配的关键约束：

1. **不是单纯缩小一个数组。** 先明确 BL/EL decoder、pair、VO/GPU 各持有多少帧；配对器有下游需求时先消费已匹配对，再决定是否继续拉 BL。软件 EL 预热与等待不得被 BL 端的同步等待饿死。队列值依实际链路验证，不能把 1、4、8 说成通用硬件容量。
2. **不把 EL 缺失伪装成成功。** 保留 RPU、PTS 精度、NLQ 和源格式；首个 EL 尚未就绪与“源确无 EL”分开。不能为了不卡而无提示长期只显示 BL，再把它计入 FEL 成功。
3. **不在主线程补读。** 订阅整份 `track-list`、相关 subtitle/selection 属性，用版本化快照更新 Java 状态；JNI 必须复制事件数据后再跨线程，处理 reply 生命周期、请求失败、过期 generation、解除订阅与 release。批量异步后也不能在 UI `Future.get()`。
4. **失败退出和软解策略分开。** 建立连续无进展/致命错误的有界终止语义，报告明确播放失败，不因用户禁用 BL 软回退而无限重试；不冒充正常 EOF，避免误触发下一集。不建议只把 750ms 改小。
5. **释放必须保持所有权。** 未经 GPU 消费/fence 确认，不能强制释放仍被引用的硬解图像；不能在线程超时后销毁仍可能被 native 使用的 handle/mutex/Surface。

### 8.6 最小分期、观测和验收门槛

这是下一轮的建议，不是本轮已实施清单。建议先解除确定的 UI 问题，同时取得 native 故障的必要计数，再独立改调度；无新增依赖、无全栈升级、无静默降画质。

1. **异步轨道快照与完整错误透传。** 复用已有 command/set-property 请求体系，补 NODE 快照与必要异步查询；保留 `source-dolby-vision-*`、HLS bitrate、音轨/字幕选择等本地字段。当前 quick-fix guard 没有 JNI 路径，不在它内直接实施。模拟核心延迟 10 秒时，主线程不得进入同步 MPV getter；遥控器移动/返回仍能被处理，输入响应目标 250ms 内。该目标是待测试门槛，非现有测量结果。
2. **FEL 资源预算与有界失败。** 在真实新增日志基础上适配 #18375 的预热/预算原则，只影响显式 FEL 路径。每 2–5 秒聚合一次 BL/EL pending/峰值、首帧/最近 PTS、配对/BL-only 数、MediaCodec 未归还 output 数及等待时间、AImage acquire/release/timeout、GPU/fence 在途数、错误及恢复次数；不逐帧刷文件。所有必要错误与汇总从独立 native 事件路径进入 App 调试日志，不能仅在 logcat。
3. **设备验收。** 同一电视、相同文件、相同设置/温度条件，分别从 0 和约 14.121 秒恢复，至少各 3 次可比较运行；记录首对/首帧耗时、连续 60 秒配对率/输出帧率/错误/晚帧、A/V 同步与 CPU/内存。可正常播放后再做 seek、暂停、退出和重复打开；FEL 缺层必须可解释，不能用长期 BL-only 换速度。因当前基线已卡死，先通过功能门槛，再与稳定旧模式比较性能。
4. **画质与原功能保护。** 用同一输入、相同 resize/色彩/DM 参数的离线 FelBaker/DoViBaker 参考和已知 FEL 差异样片确认残差生效，不以杜比标志/第一条 NLQ 日志替代画质验证。回归 DV5/P8.1、HDR10、FEL 关闭及一次蓝光菜单/退出的关键邻近场景；只覆盖改动触及的路径，不跑无关全矩阵。
5. **永久挂死退出兜底另列。** 若要进一步保证用户有操作入口，可在独立恢复进程提供“继续等待/结束故障播放并回首页”，只在用户确认后处置本 App 的目标进程，不自动重播。需要核对 PID/UID/进程代际、单实例、最小 App 初始化、服务重启及 Android 14+ 后台拉起限制。完整播放器独立进程需要 Surface/IPC/生命周期重新设计。系统或 GPU 驱动整体挂死不能承诺由 App 绝对挽救。

实现验证随实际改动选择：Java 策略单测和轨道快照/过期回包测试先行；改 JNI 则构建两个受影响 ABI 的 `libplayer.so`，改 mpv 则重建对应两 ABI `libmpv.so` 并核对 ELF/锁/资产/APK 哈希；不重建未改的 FFmpeg/libplacebo。EL 预热、有限缓冲 backpressure、seek 清队列和致命错误应有确定性回归用例；上游这三个提交未带此类测试，不能把“已合并”当作 Android 验证通过。

许可证/体积/回滚：目前只研究，包体为零变化。推荐方案不新增库；Kodi 源文件为 GPL-2.0-or-later、media-kit 为 MIT，本轮借鉴设计而非复制代码，后续若移植须保留相应许可与来源。不要引入桌面脚本运行时；二进制大小和性能变化以候选产物实测，不能预先保证数值。未来每个实施单元须将代码、补丁、锁/构建声明、产物和测试原子记录，并通过其 recovery tag 回滚；不得以恢复 `792c1f880bc151eb1cb6675034ec144aadc14766` 的方式抹掉尚未提交的日志修复。当前快照只是已知问题锚点，不是正常播放版本。

### 8.7 本轮结论与未决事实

- **路线有依据**：mpv/libplacebo 已提供核心重建，FongMi 提供 Android 软件 EL 接线，Kodi 和 media-kit 分别提供缓冲生命周期与异步状态管理的可借鉴实现。
- **发现了本地未覆盖的相关后续修复**：#18375 的真实三提交必须纳入 P2-4 适配评估，但 MediaCodec 不能照搬固定硬件帧池的扩容假设。
- **不能下“设备不支持 BL 硬解”的结论**；也不能从第一对 GPU 输入就宣称 FEL 完成。UI 同步等待已坐实，native 缓冲饥饿仍需计数验证，驱动/fence、数据及调度竞争仍是区分项。
- **未找到已证实可直接替换、并在我们这类 Android TV 路径完整验收的通用即插即用实现**。这不是“网上不存在”的断言；所查成熟部件可组合借鉴，但不是可跳过本机调试的整套成品。
- **本轮只读研究及文档更新**：生产代码、依赖锁、native 和 APK 均未更改，未复跑旧的成功测试/构建，未提交/tag/推送。下一步仅按顶部 Recovery anchor 继续，不把此调研当作实施授权或设备验收。

## 9. 最佳实践适配实施（2026-09-12 08:56 起）

用户现已明确批准第 8 节推荐方案。实施顺序：先关闭已独立验证的日志门控单元，再实施异步状态快照/完整错误透传、FEL 资源预算与有界失败、最小故障退出保护。各逻辑单元单独声明作用域和验证，不升级 FFmpeg/libplacebo，不修改 Exo、菜单或音频策略。

执行估计：当前代理代码/定向测试 60–80 分钟，温缓存双 ABI 构建与电视包 15–25 分钟，收尾约 5 分钟；目标 10:20–10:50 Asia/Shanghai，电视安装确认和真机播放另计。未通过的设备场景不能以编译结果关闭。

日志门控单元的验证依据保持第 7 节：10+8 项测试、电视 armv7 APK 构建成功；新 trace `p-w3uti8-1` 已显示即时 native 日志，未再出现 PCM 路径上的那两项音频属性 slow-native 查询。该单元可独立保存，但不代表 native 错误、其他 UI 同步调用或 FEL 画质/性能已验收。
