# C-AVS3：AVS3 视频解码

## Recovery anchor

- 目标：按用户 2026-09-16 明确要求，让当前手机上的 AVS3 视频实际输出画面；按 Exo → MPV 接入成熟实现，保留现有 AV3A 音频、其他解码、ASS、DV/FEL 与性能行为。用户随后明确要求深读论文、文档、issues 和成熟项目代码。
- 当前阶段：2026-09-17 用户明确“可以了，打tag”接受本次结果，按关闭快速路径提交并创建本地恢复tag。主Surface就绪修复已应用，Mobile ARM64 debug APK构建通过；先前Exo两种位深MP4/MKV的实机出帧、双播放器native解码及来源校验继续有效。最终MPV切换场景以用户确认为验收依据，本轮没有运行handoff命令探针，不能冒称自动实机复测通过。High profile 0x30/0x32仍未支持。
- 工作区：`feature/mpv-dv7-fel`，基线/回滚锚点 `dbff1ffecd973c6d89eef1bf139f7243ac6b3ead`；guard `C-AVS3-video` / upstream；保护既有 `app/.cxx/` 104 文件。前一 P2-4 的电视性能验收继续等待设备证据。
- 设备：`10CF6H1D2L0009S`，vivo V2453A / PD2453，Android 15/API 35，arm64-v8a，QTI SM8735。`MediaCodecList.ALL_CODECS` 枚举得到 **55 条视频 decoder/MIME 条目，AVS 为 0**（含别名，不代表 55 个不同硬件单元）。本机无公开的 AVS3 MediaCodec 解码路径，需要软件后端。已通过 OEM 安装助手安装候选 APK；未重启手机或清空日志。
- 样片：手机 `/sdcard/Download/影音测试库/A09_AVS3A/AVS3_4K50_DASH.mp4`，31,308,084 字节，SHA-256 `e780726556974bc84e9b6307336c97bcc5082b9813fe9d9d85a2b225da6df677`；60 秒、3840×2160、50fps、约 4.3Mbps、BT.709，**仅视频轨**。裸 MP4 本身无音轨；配套 DASH 音频另行引用。
- 关键发现：序列头 `00 00 01 B0 32 55 ...`，profile 为 **0x32**；uavs3d 仅实现 `0x20/0x22`。`parser.c:dec_parse_sqh` 的 `com_check_val2` 在非 debug 构建把非法值写回 0x22，不是安全拒绝；必须在调用前检查原始序列头，不能靠打开 decoder 或放宽 profile 完成。
- 已完成：核对实际双 ARM Exo/MPV 打包库均含 AV3A decoder、不含 libuavs3d；Exo 当前 MP4 没有选出视频轨；固定 FFmpeg 已有 AVS3 MP4/TS/裸流识别，但 decoder 构建禁用；拉取 uavs3d 固定源码并阅读 FFmpeg wrapper、配置/官方文档和 Media3/nextlib 接线。
- 新实证：HPM 12.2 固定源码已在本机编译，真实样片的 37 组参考帧列表使其默认 32 组数组越界；启用上游 `RPL_GOP32=1` 的 64 组容量后序列头通过。首图片头 350 字节，参考程序在 bit 971 的填充位断言失败，输出仍为 0 帧。434 次位读取均经独立 Python 读取核对一致；这只确认取值一致，**不确认字段语义、版本兼容或图像正确**。
- 16:51 补充研究：阅读了 Bevara 的 GPAC/WebAssembly AVS3 filter 和晶晨公开驱动的完整图片头处理函数。前者实际调用 uavs3d 且拒绝非 8-bit 输出；后者通过硬件参数区取得已解析字段，关键软件读位代码不参与驱动构建，不能移植为本机软件 decoder。hysAnalyser 当前公开树没有解析器源码。这些结果限定于已审阅版本，不证明其他 High profile 开源实现不存在。
- 本次提交范围：两文档、依赖锁、AVS3 三层补丁、共享/Exo/MPV 构建接线、`ExoUtil` 的 AVS3 专用软件回退、`PlayerManager` 跨内核Surface重建和 `MpvPlayer` 加载就绪等待；Exo AAR 与 MPV 双 ABI codec 已替换。nextlib r4 除 4 个 AVS3 相关 class、两 ABI 的 libavcodec 和新增许可证外，所有 AAR 项与 r3 逐字节相同；MPV 其余 18 个库完全保留。最终fixtures的3项提取器测试、包装/ABI/来源及此前APK对应关系均通过；最终App增量构建通过，用户接受手机结果。
- 正确性证据：`build/avs3-native/exo-device-final-{8,10}.log`；8-bit 重建 SHA-256 `4af75530b1dc15dd41f1ec2e47f6ad64deb0fddf9f6b46bd772dba6f65589ecb`，10-bit `745e6cefa14496131199293c118bbf664ac0a1f07dd8333ffd6d36d9e2e39cc4`。固定 uavs3e `e1ff0f37a8d67814e1d650f6b49d495c49dde946` 已修正 Apple 位深宏，并用单线程编码避免其并行生成流与重建不一致的问题；解码仍用两线程，256×144 保留底边覆盖。最终流和 manifest 已纳入本任务测试路径。
- 原始证据：`/private/tmp/webhtv-avs3-20260916-1412/`。
- 外部资料：用户已提供 `/Users/macbookpro/Downloads/AVS3-P2-TAI109.2-2021.pdf`，437 页，SHA-256 `f357b0fd264cd4a34a31fd4ca261e7b80c94899239af61173775b6cbaf48895b`。正文已提取，并阅读序列/图片头及 ESAO/CCSAO 表；正式表 29 的 DBR 参数顺序与 HPM12.2 不同，首次已证实语义差异在原图片头 bit 100。另一目录 `AVS3P10_RM0_V3p1` 的 README 和 API 明确为实时语音 P10（16/32 kHz、WAV/PG），不提供本次 P2 视频后端。原文件只读，不拷入产品。
- 设备临时状态：11:10手机 `10CF6H1D2L0009S` 已重连，已调用OEM安装助手安装最终APK，随后用户确认“可以了，打tag”。关闭阶段不再查询安装状态或运行探针。此前临时命令探针包 `com.fongmi.android.tv.avs3probe` 尚在手机，未再次执行；此前仅恢复player首选项为原值2，未覆盖整份配置，无ADB forward。
- 下一动作：执行原 `C-AVS3-video` guard 的finish，原子提交任务文件并创建唯一annotated本地恢复tag，不推送；commit/tag以该提交的Task-Guard记录及guard输出为准。

## 授权、范围与验收

用户明确要求“连接我的手机，当前正在播放 avs3 视频，不支持。我希望支持解码”，并要求深度研究。此为新增 AVS3 视频能力的实施授权；已有 AVS3A/AV3A 音频不是本次新功能。完整功能不能用 baseline profile 的通用测试替代当前 0x32 样片。

2026-09-17 最新“需求实现了吗？打tag，打包apk”继续授权完成当前修复及交付。范围只增加原本干净的 `MpvPlayer.java`，原 `C-AVS3-video` guard 和 `app/.cxx/` 104 个受保护文件保持。08:30 Asia/Shanghai 估计剩余12分钟：修复2、单ARM64增量构建4、实机切换/跳转4、文档及提交/tag2，目标08:42。保留已经通过的容器及native验证，不重复构建native；未支持0x30/0x32的限制随此次baseline单元交付明确保留。

允许路径详见 guard：唯一文档与任务索引、AVS3 构建/校验脚本、Media3/nextlib/FFmpeg AVS3 补丁与依赖锁、相关 Maven 和双 ARM MPV 制品、Exo 解码器接线及定向测试。禁止引入无关上游升级、替换已验证 FEL/ASS 实现、改变其他编码的硬解/软解选择、降低画质或冒称硬解。Native 输出仍保持 Exo `libav*` 与 MPV `libmv*`/`libmw*` 命名隔离。

验收：

1. 正确识别实际 MP4 的视频轨、profile、尺寸、时间戳、初始化数据；畸形/截断配置安全失败。
2. 当前手机实际解码并输出原样片；首帧、连续播放、seek/flush/退出可用，清楚报告软件/硬件路径。纯视频 MP4 不要求不存在的音轨。
3. 明确支持的 profile/8-bit/10-bit 范围，不能以 decoder 注册成功代替实际解码。
4. 两 ARM ABI 的原生构建、ELF 依赖/导出、来源/许可证与 APK 包含的库一致；仅做所改链路的必要回归。
5. 现有解码/字幕/DV 等合同保持；记录 4K50 软件解码实际速度，未实测不承诺实时。
6. 通过必要验证后原子提交并创建唯一 annotated 本地恢复 tag，不推送。

14:14 Asia/Shanghai 原估计：源码 25–35 分钟、构建和手机验证 15–25 分钟、关闭约 5 分钟，目标 15:15。发现 profile 0x32 后先解决解码器可用性这一决定性问题，不对错误 decoder 做无意义构建。

15:06 续接估计：参考程序实证 15–25 分钟，路线成立后的接线/构建/定向验证 35–55 分钟，本机目标 16:00–16:25；手机验收依赖恢复连接。首要不确定性已从“是否有 0x32 源码”收敛到“参考版本能否解用户实际码流”。

16:21–16:25 状态：手机能力查询已完成，软件参考路径仍在首图片头失败。原完成时间失效，原因是缺少已证实兼容的 Phase 2 后端，以及待登记获取的规范，而非构建等待。停止重复的泛搜和对无效 decoder 的打包；保留下面的精确失败记录，资料到达后继续同一任务。

## 当前代码与调用链

- `third_party/media-lock.json`：nextlib `6ff6cf9d0820382b3c233d018c52e4163b09d345`，制品 `1.10.0-0.12.1-fongmi-softload-av3a-ffmpeg901-r3`，FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18`，独立 Exo 构建。
- nextlib sources jar：`FfmpegLibrary.getCodecName()` 没有 AVS3；`FfmpegVideoDecoder.getExtraData()` 只转交 H.264/H.265 初始化数据；`FfmpegVideoRenderer.supportsFormat()` 要求 MIME→可用 native decoder。Media3 当前 `MimeTypes` 和 MP4 reader 没有 AVS3 视频接线。
- `ExoUtil.FfmpegRenderersFactory.buildVideoRenderers()`：默认硬解模式不创建 FFmpeg video renderer；接入时必须保留硬解优先和其他编码的选择合同，不能全局打开无关软解回退。
- MPV `third_party/mpv-native-lock.json`：mpv `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`，相同 FFmpeg 源码独立按 NDK29 构建；两 ABI `CONFIG_LIBUAVS3D=0`、`CONFIG_LIBUAVS3D_DECODER=0`，parser/demuxer 已启用。
- FFmpeg `libavcodec/libuavs3d.c`：官方 wrapper 负责帧输出、线程、flush、初始化数据；`libavformat/isom_tags.c` 已映射 `avs3`，`mpegts.c` 已映射 AVS3 stream type。官方 wrapper 本身不能扩大 uavs3d 的 profile 能力。
- AV3A 已有 native decoder、MIME、TS/MP4/DASH reader 与多声道适配，不重复实施。

## 研究证据（2026-09-16）

| 证据/等级 | 固定来源 | 实際支持的结论、适用性与限制 |
| --- | --- | --- |
| A：用户设备与码流 | 本目录 recovery anchor 中样片 SHA-256；`phone-baseline.log` / `logcat-baseline.txt` / `sample-probe.json` | Exo 无视频轨；真实输入为 0x32、50fps 纯视频 fMP4。文件名 AVS3A 目录不能用于判断视频 profile。 |
| A：uavs3d 源码与 README | [uavs3/uavs3d](https://github.com/uavs3/uavs3d/tree/0e20d2c291853f196c68922a264bcd8471d75b68)，`source/decoder/parser.c:dec_parse_sqh`、`source/decore/com_sys.h:com_check_val2`、CMakeLists、README、COPYING | 仅实现 `0x20/0x22`，发布版宏会改写未知 profile，需调用前显式拒绝；10bit 需 `COMPILE_10BIT=1`；BSD-3-Clause。ARMv7 写死 hard-float，需适配 Android softfp。对 0x32 输入不可用。 |
| A：FFmpeg wrapper/官方文档 | [FFmpeg 本地固定源码](https://github.com/FongMi/FFmpeg/tree/177f090e0503b7e013922ca903bde14b1c375f18)，`libavcodec/libuavs3d.c`、`avs3.h`、`doc/decoders.texi`、`configure` | 成熟接线是 FFmpeg 外部 decoder；必须编译外部库并显式启用。当前常量同样仅 baseline profile，不能据此承诺 high profile。 |
| A：Android/Media3 官方文档 | [Supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats)，访问日 2026-09-16，全文保存在证据目录 | 容器支持与 sample decoder 支持分离；默认能力来自平台 MediaCodec，软件扩展需自行编译/接入。不能仅改 MIME 就声称可解码。 |
| A：手机系统能力实测 | `CodecProbe.java`、`codec-probe.jar`、`phone-mediacodecs.txt`，2026-09-16，原设备 | 通过 `MediaCodecList.ALL_CODECS` 枚举全部非 encoder 视频 MIME，打印名字和 hardwareAccelerated；55 条结果中无 AVS。结论仅覆盖公开 MediaCodec 路径；不能再将容器接线当作这台手机的解码解决方案。 |
| B：成熟播放器映射 | [VLC](https://github.com/videolan/vlc/blob/778ec071ff216602f2a6e4ddd522dfdb6b791472/modules/codec/avcodec/fourcc.c)，已读 `VLC_CODEC_CAVS3→AV_CODEC_ID_AVS3` | VLC 复用 libavcodec，无证据说明这个映射本身可解 0x32；保留格式接线和 decoder 实际能力两道检查。 |
| B/C：维护者 issue 与用户复现 | [uavs3d #35 high profile](https://github.com/uavs3/uavs3d/issues/35)、[#37 extradata](https://github.com/uavs3/uavs3d/issues/37)、[#14 10bit](https://github.com/uavs3/uavs3d/issues/14)，已读公开正文 | #35 的 High profile 请求仍开放，未提供可用实现；#37 显示启用 decoder 后仍可能缺少序列头/尺寸；#14 是旧版 Android 10-bit 链接问题。只能支持能力与构建风险判断，不能代替本样片验证。 |
| A：Phase 2 参考实现 | [pclmeng/avs3_v1](https://github.com/pclmeng/avs3_v1/tree/d3d11b73e2d84ed37c2fb404fcb8b34ff3830b5c)，HPM 12.2，`inc/com_typedef.h`、`src/dec_eco.c:dec_eco_sqh/dec_eco_pic_header` | 包含 0x30/0x32 的完整参考算法。真实样片需要 37 组 RPL，而默认容量为 32；上游 GOP32 配置提供 64 组。参考软件的存在、能通过序列头均不等于完整码流可解。许可证有用途限制，见下节。 |
| B：FFmpeg 高档次补丁全文 | [2022-02-26 mailing list patch](https://ffmpeg.org/pipermail/ffmpeg-devel/2022-February/293513.html) | 标题虽称 High profile 支持，全文仅修改 `avs3.h`、`avs3_parser.c`、`avs3dec.c` 的高层识别/位深，不含 Phase 2 图像 decoder；不能据标题判断能力。其指向 AVS 官方标准下载链接现已 404。 |
| C：GPU 解码论文全文 | [GPU Based Real-Time UHD Intra Decoding for AVS3](https://hpc.pku.edu.cn/docs/pdf/a20200915258.pdf)，HPM4.0/All-Intra/i7-8700K/RTX2080Ti | CPU 熵解码，GPU 执行逆变换、预测、DBF/SAO/ALF；异步队列、事件与有界流水线可借鉴。基于 Phase 1 和桌面 CUDA，不能据其 4K 速度推断手机 0x32 能力或性能。 |
| C：跨项目实时系统论文全文 | [A Real-Time AVS3 8K-UHD Encoding and Decoding System](https://www.jdl.link/doc/2011/20201229_12.pdf)，2020 IEEE | DAVS3 采用帧/LCU 多粒度并行、SIMD 友好数据布局、减少内存访问，并接入 FFmpeg。论文基于 Phase 1/HPM5.0 和桌面 CPU；未提供可公开接入的 Phase 2 代码。原站证书过期，此公开 PDF 读取关闭了证书校验，来源证据强度受此限制。 |
| C：屏幕内容编码综述全文 | [Overview of Screen Content Coding in Recently Developed Video Coding Standards](https://arxiv.org/pdf/2011.14068)，HPM9.0 | IBC/ISC 等解释 Phase 2 不能靠放宽 profile 判定完成；算法可参考，但不是 Android 实时 decoder 的现成实现。 |
| C：独立生态梳理 | [Codec Wiki AVS3](https://wiki.x266.mov/docs/video/AVS3)，已读全文 | 区分 Main/High；常规 FFmpeg 接入仍基于 uavs3d。只作为源码证据的补充。 |
| A：正式规范获取渠道 | [AVS 官网资料下载](https://www.avs.org.cn/index/list?catid=7) → [视频标准登记](https://standard.avs.org.cn/avs3_download/)，访问 2026-09-16 | 官网提供 `T/AI 109.2-2021`。用户后来已提供完整 PDF，哈希及所读章节见 recovery anchor；已包含高级档次。登记页的参考软件选项属于 P10 语音，未找到公开 P2 HPM 下载入口。 |

已审阅其他播放器/平台：`rbaucells/uavs3d` 的 Android 分支 parser 仍只收 0x20/0x22；`xeonliu/FFmpeg-AVSPlus` 的支持链和 `JunliangRen/LAV-FFmpeg` 的 configure 仍使用 libuavs3d；`unacez/LAVFilters-GB-CAVS-AVS2-AVS3-decoder@247cf77da613575d54a2d828571b0ec3624b8f6a` 的 Windows 构建脚本同样启用 libuavs3d。`Choise-ieee/AVS3-uavs3d-CUDA@835f4ecf47736a9d70792d816acaceeb708e71fd` 的完整 Git 树只有 README 和四个视频文件，没有可审阅的 CUDA decoder 源码。以上均不能视为已找到成熟 High profile 解码器。

补充排查结果（均为只读，不合并任何上游提交）：

- `pclmeng/CAC1.0@d37c309cdce441bc2c9b86ce694aac39da220f70`：读取 README、宏定义、完整 `dec_eco.c`；仍是 HPM 12.2，图片/序列头代码与原候选一致，不能解决当前差异。
- `zhuxihehe/HW_LCU_IME_FOR_AVS3@4a2b7d120032eb4350caaf9f914d660ec309094a`：完整树只有 README，说明基于 HPM 4.0.1，既无可用新版本代码也无 High profile 证据。
- `rujiewan/AVS3@744cee2dbb7ce0878a94428709f1f22b858d4b29`（Gitee）：Git 树与实际宏定义确认是 HPM 7.1，较旧，未对当前样片作无意义重建。
- `blindwang/ZJUHDR` 的公开 README（非实施输入）注明研究使用 HPM 15.7、仅 AVS 工作组成员可获取；官方 `gitlab.com/AVS3_Software/hpm` 匿名 Git 读取要求认证，Web 读取遇到挑战页，未取得源码。
- `hty19971211-tech/AVS3converter`、`DanielZhang17/ffmpeg-mmt-avs`、`zmcity/ffmpeg-codec-player` 的完整 README 均明确依赖 uavs3d；它们是构建/封装参考，未据此声称拥有新的 High profile 解码器。收费 VLC 衍生项目只有宣传信息，不将营销性能数字当证据。
- GitHub/Gitee 其余同名 HPM 候选经树/语言检查为网页或 Java 等无关项目，排除。未要求 GitHub token，未购买闭源播放器，未联系维护者，未绕过成员访问限制。

### 开源实现范围澄清及跨平台补查（2026-09-16 16:41–16:51）

本轮决定性问题：是否存在此前漏查的独立 High profile 软件解析实现，能解释 HPM 在首图片头的字段差异。继续现有 guard 和授权，仅追加文档及临时证据，没有修改参考程序、生产代码或再次运行已失败的相同解码命令。

- **uavs3d 是确实存在的成熟开源软件解码器。**固定版本 README 明确列出 Android/iOS/Windows/Linux/macOS、ARMv7/ARMv8/SSE4/AVX2、10-bit；`source/decoder/parser.c:71–72` 明确限制 `0x20/0x22`。这里的 `AVS3-P2` 表示 AVS3 标准第 2 部分（视频），不能仅凭名称把它理解为包含全部 High profile 工具。已有播放器接入与当前样片兼容性应分别表述。
- A，公开晶晨驱动：[`khadas/common_drivers@3a11a86a02e759fc57fc79410215f7c0c3a0d8e0`](https://github.com/khadas/common_drivers/tree/3a11a86a02e759fc57fc79410215f7c0c3a0d8e0/drivers/media_modules/frame_provider/decoder/avs3)。已读取 `dec_eco.c` 的序列头准入、完整 `dec_eco_pic_header()`，以及 `avs3_bufmgr.c` 调用位置、`avs3_global.h` 参数结构、宏定义。`com_typedef.h` 标注 HPM 10.0；序列头准入包含 `0x30/0x32`，但函数输入是 `union param_u`，字段取自 `param->p`，软件读位/ESAO/CCSAO 等代码位于未启用的 `ORI_CODE` 分支。ALF 系数也取自参数区。**这是硬件配套驱动，不是可在高通 CPU 上运行的完整软件 decoder；仅有 profile 分支也不能证明该版本能解当前样片。**文件保留 HPM 用途限制许可，不能因位于公开驱动仓库而改称普通 BSD/GPL 算法。处置：只读参考，不合并该提交。
- A，跨到 GPAC/WebAssembly：[`Bevara/libuavs3d@7283de9b9a0bfef4eda7371efd9a7c45e6a8feab`](https://github.com/Bevara/libuavs3d/tree/7283de9b9a0bfef4eda7371efd9a7c45e6a8feab)。已完整读取 `dec_avs3.c`、`CMakeLists.txt`、`filters.cmake` 和 README。`avs3dec_process()` 调用 `uavs3d_create/decode/flush/delete`，构建链接仓库的静态 `libuavs3d.a`；`avs3dec_on_frame()` 明确拒绝 `bit_depth != 8`。它是另一平台的接入实现，未提供新的 High profile 算法。其整文件处理、单活动实例和单线程约束也不适合直接搬进 WebHTV。处置：只读参考，不合并该提交。
- A/D，码流分析器候选：[`zymill/hysAnalyser@1c7eff0bef2111d7ce086eca97840e2eb74251fa`](https://github.com/zymill/hysAnalyser/tree/1c7eff0bef2111d7ce086eca97840e2eb74251fa)。完整 Git 树是 README、Windows 压缩包和图片；README 描述支持 AVS3 分析，但没有可审阅的解析源码，不能据此声称找到独立开源 decoder。未购买、执行或修改其二进制。处置：不作为实现输入。
- 公共代码索引补查使用 `ph_awp_refine_flag`、`HPM_VERSION|PHASE_2_PROFILE` 和 `dec_eco_pic_header` 三组查询；保存完整 Sourcegraph 响应。无适用结果只说明该索引未返回源码，不能推导互联网范围的不存在结论。晶晨源码是直接按项目目录取得，恰好说明代码索引覆盖并不完整。

新证据仍放在 `/private/tmp/webhtv-avs3-20260916-1412/`：`khadas-avs3-ref.json`、`khadas-avs3-contents.json`、固定版本源码子目录；`bevara-ref.json`、`bevara-tree.json`、`bevara-dec_avs3.c`、构建文件；`hysanalyser-ref.json`、树和 README；三份 `sourcegraph-*-search.txt`。五个晶晨源文件已按 Git blob SHA-1 与 API 内容清单逐一核对。

结果：补查纠正了“找不到 AVS3 开源实现”的过宽表述，扩大了已核验的跨平台实现范围，但没有补齐当前样片的首图片头语义。原 0 帧验证结果仍有效；不把参考程序失败解释为全体 High profile 实现缺失，也不把登记规范视为唯一可能路线。下一步仍是取得可核验的对应语法并逐字段对照现有 trace，而不是给只支持其他 profile 的库做无效 App 打包。

### HPM 参考实证与使用边界

- 临时源码/构建位于证据目录 `avs3_v1-d3d11b73e2d84ed37c2fb404fcb8b34ff3830b5c/`、`hpm-host/`。仅构建 `app_decoder`，x86_64 macOS、Clang、RelWithDebInfo `-O2 -g -DLINUX -DX86_64 -msse4.2`，保留 assert（源码包含在 assert 内消费码流的操作，不能加 NDEBUG）。
- 两项平台适配：Apple `posix_memalign` 代替不可用的 `<malloc.h>/memalign`；文件内部函数 `dec_is_pix_ref_valid` 改为 `static inline` 避免 C inline 链接差异。未改变像素解码算法。
- `sample-16frames.avs3` 由原 MP4 前 16 个压缩包加 `dump_extra` 抽取；235 字节序列初始化、首图片头 350 字节。独立逐位读取与参考程序均得到 3840×2160、10-bit 内部精度、37 组 RPL。
- `hpm-decode-16frames.log` / `hpm-decode-probe.log` 留存默认 32 组容量下的越界。启用现有 `RPL_GOP32=1` 并加入探针边界停止后，`hpm-decode-gop32.log` 序列头通过，首图片头尾部断言失败。此为未通过的兼容性验证，输出 YUV 仍为 0 字节。
- 最终位级证据：`hpm-decode-bit-trace.log`、`hpm-picture-bit-trace.json` 记录 434 次读取及源码行；原始图片头无 `00 00 02` 防竞争字节，独立 Python 按固定位数/无符号和有符号 Exp-Golomb 复核所有值一致。参考程序解释字段到 bit 969，随后 bit 971 读到 `1` 而期望填充 `0`。不修改码流、不跳过断言、不伪造输出。
- `hpm-host-probe.patch` 保存所有临时平台适配、GOP32 容量和诊断插桩；`hpm-experiment-summary.json` 保存原样片、16 包裸流、源码归档、实际探针二进制、字段记录和手机能力输出的 SHA-256。可直接重放，后续不必重新搜索已排除的仓库。
- HPM 许可证原文：`Redistribution and use ... are permitted only for the purpose of developing standards within ... AVS and for testing and promoting such standards.` 当前用于私有标准测试与算法参考；不是普通 BSD 全用途许可，不能无依据作为公开发布依赖。它没有直接写“禁止商业使用”，也不能将该结论附会给原文。正式分发的适用性另行核实；用户已允许依据算法逻辑独立实现。
- 已发现参考代码使用全局状态、输入数组无边界检查、assert 退出和负数移位，不能未经适配原样放入 App 进程。正确性 oracle 与生产后端是不同验收阶段。

## 方案比较与当前决策

| 路线 | 收益 | 问题 | 当前处置 |
| --- | --- | --- | --- |
| 不改动 | 零回归与包体变化 | 用户样片仍无画面 | 不满足目标 |
| 原样启用 FFmpeg + uavs3d | 成熟 API，支持 baseline profile | 已读源码不接受本样片 0x32；ARMv7 构建还需适配 | 不能作为本需求完成方案 |
| 窄接线 + 真正支持 0x32 的 decoder / 平台硬解 | 有望解决实际样片；沿用既有容器/生命周期 | 实现、授权来源、移动 ABI 和性能尚需核实 | 当前调查方向；证据足够再落地 |
| 复用 BSD 基础实现，依据规范独立补 Phase 2 | 能覆盖真实 High profile；可复用已证实等价的 NEON/内存管理 | 完整头语法、熵解码、预测、变换、滤波及一致性验证是实质工作；当前首图片头还不兼容 | 用户已允许；规范/正确性基准未满足前不发布后端 |
| 原样把 HPM 放进 App | 最短的参考 API 接线 | 实际样片失败；已实证数组越界，且有全局状态/assert 退出、使用范围等问题 | 拒绝作为生产实现 |
| 修改 profile 检查冒充 baseline | 无可靠收益 | 语法工具集可能不同，解码错误/内存安全风险 | 拒绝 |

回滚：保留上述基线 commit/tag，按 Exo/MPV 兼容制品集合原子恢复，不跨播放器替换库。不接受来源不明、无可核验许可证或不含目标 ABI 的二进制作为正式依赖。

## 实施路线与当前未满足的条件

用户已授权目标和独立实现方向。以下是接线边界及最小顺序；正式规范和码流兼容性仍是未满足的设计条件，不能称为已完成的最佳实践实现。

1. 先核对真实 `0x32` 字段语义，得到首个完整图像和可用的正确性对照；明确支持的工具集、位深、输入边界，所有解析失败返回错误，不退出 App 进程。
2. 在可复用的许可明确代码基础上补齐 Phase 2。解码状态按实例持有，参考帧计数和输出重排有界，支持 flush/seek/EOS；避免把 HPM 全局变量、裸数组和 assert 作为输入校验直接移入播放器。
3. FFmpeg 负责正常 packet/frame、时间戳、AVBufferRef 生命周期；Exo 与 MPV 从相同固定源码分别构建，维持库名/SONAME 隔离。AV3A/ASS/DV/FEL 补丁及现有 ABI 合同完整保留。
4. Exo 补 MP4/TS/DASH 等实际需要的格式接线，只有 native 确实有 decoder 才报告可解码；默认硬解模式下仅为 AVS3 增加必要软件回退，其余格式的选择策略不变。Exo 实测成立后再接 MPV。
5. 正确性成立后参考 uavs3d、DAVS3 的帧/LCU 调度、SIMD 数据布局及内存复用，先测 CPU 热点再决定 NEON 优化；CUDA 论文只提供异构调度思路，不直接搬到 Android，也不预先承诺 4K50 实时。

16:25 历史状态（已由下方基础档次实施记录更新）：当时尚未实现视频后端，High profile 的正确图像仍未得到。后续不重启已有仓库调查。

## 17:13 授权后的可落地实施单元

用户已明确批准实际实现全部可落地能力，且接受此前提出的基础解码与 High profile 分阶段推进。当前单元保持 guard `C-AVS3-video` 与原允许路径；已有两个任务文档是本任务编辑，104 个 `app/.cxx/` 文件仍为保护路径。

### 已决设计

1. 采用 `uavs3/uavs3d@0e20d2c291853f196c68922a264bcd8471d75b68` 和已固定 FFmpeg 官方 wrapper；该源码本单元处置为“实施”。启用 10-bit 编译；wrapper 的 `uavs3d_img_cpy_cvt()` 根据实际位深输出 8/10-bit，不能通过关闭检查将 0x32 当成 baseline。Android ARMv7 沿用 NDK softfp ABI，修正源码面向 musleabihf 的 hard-float 构建选项。
2. Exo 补齐 AVS3 MIME/codec string、MP4 初始化信息和实际可用的容器入口；nextlib 添加 MIME→`libuavs3d` 映射、初始化数据传递。硬解优先模式仅为 AVS3 创建软件后备 renderer；其他编码继续遵守现有策略，软件性能模式不强制改变 AVS3 画质。
3. Exo 与 MPV 分别使用既有 NDK/FFmpeg 配置构建，uavs3d 静态链接进入各自 libavcodec；保持 `libav*` 与 `libmv*`/`libmw*` 隔离，不增加冲突的同名动态依赖。保留 AV3A、ASS、DV/FEL 及全部现有补丁。
4. 正式标准表 29、38、39、37用于解释 HPM 参考失败；最先修正已证实的 DBR 顺序和 ESAO 亮度参数顺序。HPM 仅作为标准验证参考，尚无正确图像前不纳入产品；P10 实时语音不冒充 P2 视频能力。

### 取舍、验收与回滚

- 不改动不满足用户目标；原样打开 FFmpeg 开关缺少外部库、Exo 容器/renderer 接线和 ARMv7 ABI 适配；窄适配方案可独立交付基准档次，High profile 未完成时仍明确报告范围。
- 本单元验收：8-bit 和 10-bit 基准档次真实帧输出；Exo/MPV 各自首帧及 seek/flush；受影响双 ABI ELF/依赖/来源与 APK 内容一致；截断初始化数据安全失败；其他格式解码选择和既有原生依赖不被替换。4K50 软件实时性需实测，未验证不承诺。
- 本单元通过后可独立提交、打本地 annotated 恢复 tag；文档明确保留 High profile 未满足项。失败时保留已验证旧制品，不安装无视频能力的替代包。回滚到 `dbff1ffecd973c6d89eef1bf139f7243ac6b3ead` 对应接线/锁/制品集合，不触及其他任务。
- 17:15 Asia/Shanghai 预计：资料 10–15 分钟、接线 20–30 分钟、双 ARM 构建与必要验证 25–40 分钟、关闭约 5 分钟，目标 18:15–18:45。已有 MPV 双 ARM 缓存；Exo 源码需准备，使用已提交制品的源码和兼容合同保持本地修复。

## 基础档次实现与验证（2026-09-16）

- 共享 builder 固定 uavs3d 源码/归档哈希并按播放器、ABI 隔离构建；`COMPILE_10BIT=ON` 的同一后端支持实际 8/10-bit 输出。版本头不读取外层 WebHTV Git；ARMv7 使用 Android softfp，Android pthread 由 libc 提供。BSD-3-Clause 许可证进入 nextlib AAR/APK 的 `assets/licenses/uavs3d.txt`。
- `media3-exo-avs3.patch` 补 MIME/codec string、`avs3`/`av3c` MP4 box、MKV `V_AVS3`、TS `0xD4`；`Avs3Config` 按标准读取 profile、库标记、尺寸、位深、宽高比与帧率，有界检查截断初始化数据；`Avs3Reader` 沿用 Media3 ElementaryStreamReader/PES 生命周期，处理跨块 start code、I/P/B 边界、seek 和 EOS。
- nextlib 映射 `video/avs3` 到 `libuavs3d`，转交初始化数据；只对 AVS3 禁用低分辨率/跳帧/滤波降质参数。renderer 类和 `supportsFormat` 允许继承，`ExoUtil` 在硬解优先模式仅添加 AVS3 专用软件后备；既有其他格式策略保持。
- FFmpeg wrapper 补 av3c 长度/起始码检查，传递 uavs3d 错误、空序列保护、配置零初始化、失败清理、flush 后输出指针清除及 EOS 空指针算术修正；不放宽 profile 检查。
- 两套播放器均只替换各自 `libavcodec.so` / `libmvcodec.so`；nextlib 其余 `.so` 与 AAR 项、Media3 非目标 class、MPV 其余 18 个库均与基线逐字节一致。含原始 profile 拒绝的最终 MPV codec 增量为 ARM64 488,440 字节、ARMv7 443,572 字节（未压缩），无新增独立动态库。ASS/FEL/渲染/音频库保持原制品。
- 首次 APK 定向编译抓到 `FfmpegVideoRenderer` 类仍为 final，已在源码、补丁、sources.jar 与 classes.jar 同步修正。未重编无关 native 库。新 nextlib r4 不再声明未提供的旧 javadoc 制品。

### 已完成的决定性证据

| 检查 | 结果/记录 |
| --- | --- |
| Exo / MPV、ARM64、8-bit / 10-bit | 原生探针各解 16 帧，再 flush 重放；四条链路两次输出分别匹配独立编码器重建的 SHA-256，详见 recovery anchor 和 `build/avs3-native/{exo,mpv}-device-final-{8,10}.log`。不是只检查 decoder 注册。 |
| 双 ABI | Exo NDK28、MPV NDK29 构建通过；MPV `--stage-only` 成功；`verify_mpv_native_assets.sh --require-elf` 已通过现有全部能力标记、SONAME/DT_NEEDED 和 ABI 校验。ARMv7 手机运行尚未实测，当前手机为仅 ARM64。 |
| 真实测试流 | 固定 uavs3e `e1ff0f37a8d67814e1d650f6b49d495c49dde946`，256×144、25fps、16 帧、B 帧；Apple 位深宏修正后用串行编码，解码仍两线程。旧并行编码生成流与其重建不一致，已弃用，不再拿错误 oracle 定位 decoder。 |
| Java 提取器 | `test_avs3_contract.sh --baseline dbff1ffecd973c6d89eef1bf139f7243ac6b3ead` 对实际交付 AAR 运行，3 项通过：原始/av3c 8/10-bit 信息，截断/畸形安全失败，1/2/3/5/188/4096 字节分块、seek、重复 EOS 且完整保留 16 帧。 |
| 包装保留 | 同一脚本核对 Maven sidecar/module 哈希、源码哈希、固定 decoder、许可证、ABI/namespace、非目标 AAR 项/class 及 MPV 非 codec 库与基线相同。最终 APK 另核对其原生条目。 |
| 容器样片 | `avs3_mux_fixture.c` 用 FFmpeg 获取真实 display order，再设 PTS/DTS；不把 B 帧解码顺序误当显示顺序。8/10-bit 的 MP4/MKV/TS 均封装成功；MP4 重复完整 GOP 为 32 秒，用于可观察的应用 seek。 |

### High profile 仍未完成的准确边界

用户 PDF 的表 29（DBR 参数顺序）、38（ESAO 参数早于亮度类型）、39（各 CCSAO set 的参数紧接偏移）与 HPM12.2 不同；标准校正后首 I/B 图片头已对齐。仅首 I 的参考运行约 2.58 秒输出一个 10-bit 4K 图像，但可见块损坏；完整输入在 B slice `dec_eco_cbf` / `ctp_zero_flag == 1` 断言失败。**该图像不是正确性 oracle，不关闭断言，不将 HPM 打包进 App。**真实 `0x32`/4K50 样片仍不支持；基准档次验收不替代它，也不承诺 4K50 软件实时性能。P10 目录为语音标准，未作为视频库接入。

### 19:20 应用复现后的两项必要修正

- 用户已确认 Exo 基准档次硬解优先/软解均可见正确画面。MPV `mpv-user-failure.txt` 记录 `hwdec=no` 后立即 `Software decoding fallback is disabled`。已固定 mpv `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` 的 `video/decode/vd_lavc.c` 和 `DOCS/man/options.rst` 是本次 A 级证据（2026-09-16）：`software_fallback == INT_MAX` 同样阻止显式软解。保持现状无法播放；全局启用 fallback 会改变硬解合同；采用 App 根据 `decode` 传参，硬解仍 `no`，显式软解为 `yes`，不改 libmpv。范围补入 `MpvPlayerEngine.java` 与现有 `MpvHardwareDecodePolicyTest.java`，属于用户明确反馈的 MPV 失败修复。
- uavs3d 固定源码 `parser.c:dec_parse_sqh` → `com_sys.h:com_check_val2` 为 A 级证据：发布版不执行断言，且把未知 profile 写为 0x22。原样 wrapper 及仅传递返回值均不能防止误解；窄适配在原始 extradata/av3c 与每个 in-band 序列头调用前验证长度、起始码、原始 profile，未知档次返回 `AVERROR_PATCHWELCOME`，截断返回 `AVERROR_INVALIDDATA`。不修改输入标识，不更换 uavs3d 算法，不阻止平台硬件 renderer 自行支持高级档次。
- 两项均为既有设计的正确性收口；验收补充基准档次仍可解、High profile 两种初始化形式及 in-band 明确拒绝、MPV 显式软解实际出帧/跳转，回滚仍为本任务原子基线。
- 官网当日复查：公开 [登记页面](https://standard.avs.org.cn/avs3_download/) 提供视频标准文本与 **P10 语音**参考软件，未列出 P2 HPM 视频参考软件。已给用户 [官方联系方式](https://www.avs.org.cn/index/list?catid=13)，建议申请匹配正式标准和勘误的 HPM（0x30/0x32）、一致性码流/参考 YUV 与校验值、版本说明及使用许可；没有代用户提交个人资料或联系第三方。
- 用户补充 Gemini 建议后逐项核对：FFmpeg 2022 补丁仅为解析器/识别；实际阅读 [mpv #10695](https://github.com/mpv-player/mpv/issues/10695) 正文，是 2022 年 MKV CodecID 支持问题，未提供 0x32 图像解码证据；[ExoPlayer #2148](https://github.com/google/ExoPlayer/issues/2148) 正文是 AC3 音频直通/FFmpeg 扩展选择，与 AVS3 视频无关。GitHub API 限流后改读公开 HTML，保存于 `build/avs3-native/gemini-*.{html,txt}`；两条 issue 不作为高级档次能力依据。`profile_id` 与 TS `stream_type` 不混用，不全局强制软件 renderer。
- 用户询问 Exo 选择硬解却能播放：明确解释实际 decoder 为 FFmpeg/libuavs3d 软件后备，手机公开 MediaCodec 无 AVS3；不能把设置按钮或画面出现当成实际硬解证据。

### 最终 profile 修复制品与验证

| 交付件 | SHA-256 | 字节数 |
| --- | --- | ---: |
| Exo ARM64 libavcodec | `e42513f52849349c083e57c2641928eeec1c48e1e1d339172c3e9cc0bc6b4708` | 5,346,424 |
| Exo ARMv7 libavcodec | `a80871856bdb562210227e6f4ae68b06c32b35967b67fac83061e10d4f93ba42` | 4,848,096 |
| MPV ARM64 libmvcodec | `37114bda4673f642229fb71eb3bf4f63d82d3d4939f18cc10556ab51f43359f6` | 15,809,952 |
| MPV ARMv7 libmvcodec | `e4413018818c5853a2222e0791bb19c5b5dd34ee25a30a391199b5157bdc0404` | 14,920,264 |

- nextlib r4 AAR：`bfd9c635cd18613c728e6e4d9635da2f80716d20adb1927b360cf5818085fb16`；sources JAR 保持 `2a08cc5f58718a3da3dc437784891680a0710c376b322a385cd6e5adcde01492`。FFmpeg AVS3 补丁：`4a04bf7d11b592602590983fec0e0e12c2769f2623ff19850e1d9e6df9b8a929`。
- 最小重建：MPV 两套既有 FFmpeg build tree 仅编译 wrapper、链接并安装 codec，使用原 staging 完成 namespace/strip；Exo ARM64 增量，ARMv7 按原配置重建所需 codec 依赖。日志 `rebuild-profile-*.log`，未重建 libmpv/libplacebo/ASS/FEL；阶段内临时驱动脚本 `build/avs3-native/rebuild_profile_guard.py` 保留精确命令，正式可复现接线仍是已提交构建脚本与补丁。
- 最终两套 ARM64 手机探针均通过：raw/av3c/in-band 的 0x30、0x32 返回不支持，截断头返回无效数据；同一探针再验证 8/10-bit 各 16 帧及 flush 重放，像素哈希与 manifest 一致。记录 `build/avs3-native/{exo,mpv}-profile-device-{8,10}.log`。
- `MpvHardwareDecodePolicyTest` 2 项通过，硬解模式 `no`、显式软解 `yes`；同次 ARM64 APK 构建通过，1m18s。`profile-apk-contract.log` 已确认最终 AAR/双 ABI/hash/license/namespace、非目标二进制保留和 APK native 条目一致；`mpv-profile-assets.log` 的原生能力检查通过。无关已通过的提取器测试不重复运行。

### 用户追加的手机测试库

- 已按新授权写入 `/sdcard/Download/影音测试库/A09_AVS3A`：新增 0x22 的 8-bit 与 10-bit，各自 MP4/MKV/TS/裸流共 8 个视频，另有 2.0/5.1/7.1.4 AVS3A 联播的 3 个 DASH MPD。每段视频 800 帧/32秒，含 I/P/B 帧；低分辨率用于能力验证，不是 4K 性能测试。最初 8-bit 被误标为 0x20，2026-09-17 已核对原始头部并修正文档和 manifest，没有修改 profile 字节。
- 初次音视频混流实证发现固定 FFmpeg TS muxer 把 AV3A 写成私有 data，MOV muxer 没有 AV3A tag；这些失败制品移入临时 discarded 目录，没有写入手机。采用原有 DASH 分轨方式，复用未改动的 AV3A 音频。最初视频 MP4 的二次重封装丢失 av3c，旧初始化/索引范围不再使用；2026-09-17 改由 fixture muxer 直接以 dash/global_sidx/negative_cts_offsets 输出最终文件，两份 MP4 的初始化范围均为 0–831、sidx 832–1471、timescale 12800。
- 根 README 总媒体/播放列表数 91→102，A09 16→27（不计系统 `.DS_Store`、README 与 JSON）。增加 A09 README 和 `AVS3_MANIFEST.json`，明确原有 0x32/4K50 未支持、按钮不等于实际硬解，以及官方 High 一致性码流的未覆盖项。
- 已保存原根 README，更新前核对未被外部修改；8 个视频各 800 帧、32秒元数据及 3 个 MPD 引用检查通过。复制后的 14 个文件（11 个媒体/播放列表与 3 个说明/校验文件）SHA-256 全部匹配，见 `build/avs3-native/library-phone-hashes.txt`。主机镜像在 `build/avs3-native/library/`。

### 21:15 用户复测失败：CPU/GPU Surface 交接

- A 级设备证据：`user-recheck-current.log` 中 MPV 明确 `decode=0`、`hwdec=no`；`user-recheck-logcat.txt` 中同一 SurfaceView BufferQueue 连续报告 `cur=2 req=1`、`native_window_api_connect` 失败和 `EGL_BAD_ALLOC`。带音频 MPD 可以只播放音频，不能视为视频成功。早先一次完整 8-bit 正常包播放记录为 `normal-software-start.png/.log`；两种现象并存，不把失败归咎于用户选择硬解。
- A 级实现证据：固定 nextlib `6ff6cf9d0820382b3c233d018c52e4163b09d345` 的 `media3ext/src/main/cpp/ffvideo.cpp` 使用 `ANativeWindow_lock/unlockAndPost`；析构仅 `ANativeWindow_release`。当前 `PlayerManager.switchPlayer` 的直接、fresh-spec、fresh-result 三条入口释放 decoder 后均传 `onPlayerRebuild(player, false)`。`PlaybackActivity.resetVideoSurfaceForDecoderSwitch` 已通过替换 render view 创建新 Surface，但此前只由部分软硬解切换触发。
- A 级平台证据：[Android Surface and SurfaceHolder](https://source.android.com/docs/core/graphics/arch-sh)，访问 2026-09-16，正文已保存 `aosp-surface.html`。官方明确 CPU producer 连接后直到 Surface 被销毁才断开，不能直接交给 GLES/视频 decoder。经系统代理 `127.0.0.1:7897` 成功读取；此前直连超时不是资料缺失。
- 决策：保持现状会稳定复现黑屏；在 native 中调用非公开 disconnect 接口有兼容与所有权风险；采用现有 App Surface 重建回调，在**内核实际变化**时重建，保留同内核 fresh-source 行为。三个入口统一，不依赖“硬解”按钮推断是否使用了 CPU renderer，因为 AVS3 硬解优先也可能用软件后备。属于用户已授权 AVS3 落地与复测故障修复；范围仅补入原本干净的 `PlayerManager.java`，不更改 nextlib/MPV 原生图形实现。
- 成本与验收：只在内核切换时创建新 Surface；稳态解码、逐帧渲染、字幕与 DV/FEL 算法不变。必须以同一活动页面中的 Exo 软件出帧 → MPV 软件出帧、实际 Surface 更替、跳转后继续出帧验证；不能用重启 App 后的独立播放代替交接检查。同内核软件/硬件 producer 交接作为邻接风险按实际验证结果处理，不据本修复声称已全面解决。回滚仍为任务基线的原子提交单元。

### 2026-09-17：测试库 MP4 配置修正

- A 级实证：同版本交付 AAR 的 `FragmentedMp4Extractor` 在两份新增 MP4 的 `Avs3Config.parse` 抛出 `Invalid AVS3 sequence size`；二者 av3c 总长8字节、payload为空。相同提取器对两份 MKV 输出 `video/avs3`、`avs3.22.6A`、256×144、800 samples、时间戳0..31960000及一份初始化数据。证据在 `build/avs3-native/container-probe/result*.log`。MKV 解析通过不替代手机 decoder/output 验证。
- A 级源码依据：固定 FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18` 的 `libavformat/movenc.c:mov_write_av3c` 要求 extradata，`mov_write_av3c_tag` 未传播缺失错误；同版本 `mov.c` 没有 av3c reader。fixture muxer 原本已提供正确 raw sequence，之后的 MP4→fMP4 中间重封装丢掉配置。
- 决策：不变会保留损坏样本；放宽 Media3 初始化解析会掩盖损坏输入；窄修正是在 `avs3_mux_fixture.c` 接受可选 movflags，直接生成最终 fMP4，保留原有 PTS/DTS 与32秒内容。无生产 parser 放宽、无 FFmpeg 或 APK 重建。此为既有生成方案的局部修复，无新的架构或上游合并，已形成决定性本地源码/失败证据，不重复外部研究。
- 本机工具已重新编译，两个 MP4 写入成功，av3c payload均116字节，与各自原始序列头逐字节一致；FFmpeg复核800 packets/32秒/256×144与8/10-bit精度。三个MPD的codec字符串、视频码率、init/sidx范围已更新；清单按最终文件重算哈希。两级README澄清实际profile均0x22、没有0x20一致性样本，以及复用约60秒音轨导致部分播放器超出32秒Period的问题。
- 复现入口：`python3 build/avs3-native/repair_library_mp4.py`；坏样本备份 `library-discarded/*.missing-av3c`，命令/metadata为 `library-mp4-repair.log/.json`。主机版本待复制到手机；ADB未连接时不冒称手机已更新或修复已通过实播。
- 决定性验证已通过：`python3 build/avs3-native/container-probe/run.py`，原样交付 AAR 对修正后的 MP4 与已有 MKV 四个文件均 sniff=true、`video/avs3`、`avs3.22.6A`、256×144、800 samples、时间戳0..31960000、init=1，完整结果在 `container-probe/result-repaired.log`。这只验证容器提取，不替代手机连续出帧和跳转。

### Surface 重建后的第二个时序缺陷（07:19）

- A 级设备证据：安装助手在2026-09-16 21:45报告成功；21:48:01.601日志显示已执行Surface重建，之后新SurfaceView尺寸0×0、holder无效。21:48:01.876 MPV开始加载时明确 `surface=false attached=false`，21:48:02.021才绑定新Surface；原生输出随即报告 `vo/gpu-next/android: Missing surface pointer`。故此次不是旧CPU producer仍占用，而是VO初始化早于Surface绑定。完整记录 `exo-container-user.log`，故障trace `p-196fj0t-x`。
- A 级成熟实现：本地 `build/mpv-native/mpv-android/app/src/main/java/is/xyz/mpv/BaseMPVView.kt`，固定 `99a60ad2141d5ace94453590903c2c6b9a0a2443`，07:19已读全文；`playFile`只保存路径，`surfaceCreated`先 `MPVLib.attachSurface`，后 `loadfile`。`force-window`也在Surface就绪前关闭，避免提前VO初始化。沿用已读Android SurfaceHolder生命周期资料；该问题是有既定设计的局部生命周期修正，无需泛搜新论文。
- 本地调用链：`MpvPlayer.openCurrent`→`continueOpenCurrent`→`startPreparedMedia`→`loadCurrentUri`；已有 `pendingOsdLoadGeneration` 只等OSD，普通GPU不走该等待。`bindVideoOutput`由holder回调绑定视频Surface，但此前不恢复等待视频Surface的加载。
- 方案对比：保持现状会继续触发初始化失败；直接搬上游单视图字段会丢失本地媒体代次、OSD与后台音频合同；推荐把现有等待拓展到前台可见SurfaceView的有效且已绑定主Surface，并在绑定/OSD回复后恢复。沿用媒体代次校验、stop/replace取消；headless或隐藏窗口不增加主Surface等待。无固定延时、无失败重试掩盖、无native/ABI或依赖升级。
- 具体补丁：`build/avs3-native/mpv-surface-load-gate.patch`；只涉及上述App adapter一文件。恢复前检查released/stopping/initialized/error，清除等待代次后才发loadfile，避免重复回调加载。08:30已获继续授权并应用，最终APK构建通过；用户随后明确接受并要求打tag，本轮未另跑handoff探针。
- 验收：同一Activity里Exo CPU出帧→MPV GPU出帧，日志中第一次loadfile必须在视频Surface绑定之后；8/10-bit与seek、无窗口音频不阻塞、stop/替换后的旧回调不重启媒体；保持已有OSD等待语义。最小编译为单ARM64 APK增量，设备场景用既有命令探针。回滚仍为原任务基线；不以原Surface包安装成功代替此验收。
- 07:22 本机收口：修正文件的manifest/hash、三个MPD的codec/init/sidx检查通过；8文件更新包 `build/avs3-native/AVS3-test-library-update-20260917.zip` 已生成。`sync_library_corrections.py` 会先核对手机旧文件仍为本任务原哈希，再逐个复制并复核，避免覆盖用户另行修改的README。脚本尚未执行，ADB仍无设备。上游checkpoint校验通过（0 error，1项预期的本任务依赖制品脏状态warning），候选补丁 `git apply --check` 通过；没有重复native/APK构建或已通过的提取器测试。
- 当前手机已安装的Surface重建APK SHA-256：`09f6c0f64f03aa3ec8c80c13adfcc0333d4ecfadc05a66945e35562dc8dfcdb7`。它不包含尚待范围批准的主Surface加载等待补丁；旧正常日志APK哈希不能代替此记录。待批准后应用补丁、一次增量构建，再在恢复连接的手机上验证并关闭同一任务。

### 07:49–08:03 手机同步与 Exo 容器实播

- 同步后补查发现，07:12生成脚本误用了 `build/avs3-native/fixtures` 的旧缓存，虽容器可解析，但不是已独立重建核对的正式码流。已改用 `app/src/test/java/androidx/media3/extractor/fixtures`，强制核对fixture manifest SHA与库中裸流=原始16帧×50。这个差异不能由容器解析通过掩盖。
- 最终8-bit输入SHA `d0dbd57f983ac27cae2ff70376e22bcc8275a747fd6bc0452b404a124af71cb4`，10-bit输入SHA `6fb9cfddb8645942b9883304cc5f783da8368d139715dc1e09cd88068f994df7`。最终MP4分别为265,320字节 / `b64d5026911b978c35ac48e154adb10bf0384fd021c7edc8f7433660f1a018d5` 与359,420字节 / `df0b77b988b23198bdd2bbc7fef7454f89adadec76b30c69043b5dc57b5ad80a`。init/sidx范围保持0–831 / 832–1471。
- `library-phone-sync-verified-fixtures.log`：8个修正文件全部同步并核对哈希；重算后的8文件更新ZIP为32,239字节、SHA `0378dd55ff06f681443367214af88de9ea4f2c39b16f2b69317ab35ba200ce76`。旧ZIP已被替换，旧缓存版本不再交付。`container-probe/result-verified-fixtures.log`：实际AAR对正式输入的MP4/MKV四项通过。
- 命令探针前两次是启动/就绪问题，尚未执行容器实播：首次OEM拒绝后台启动HomeActivity（结果102），改为shell前台启动；第二次服务存在但spec仍为空，补等spec和isOwner。未将这些错误登记为播放器故障，也未靠模拟点击切换。
- 最终 `command-probe/containers-ready-service.log`：Exo明确选软解，8-bit TS基线及8/10-bit MP4/MKV五项均播放至1.5–2.3秒并取得真实视频Surface。MP4/MKV四项像素分别包含4074、2371、4598、4598种颜色，10-bit MKV图像已查看为预期连续渐变。图片为 `command-probe/exo*-20260917.png`。此项验收是实际出帧/推进，不声称已验证整段32秒或所有容器seek。
- 该次instrumentation结束时异步Web日志未持久化到最后场景，`containers-runtime-log-20260917.txt`的末条时间仍为07:58，因此不把它冒充08:03最终实播的decoder日志；最终场景以命令结果和PixelCopy证据为准。用户原player首选项恢复为2，目标进程已停止。

### 最终打包与用户确认（2026-09-17）

- `MpvPlayer.java` 已加入原guard范围并应用主Surface加载等待：可见窗口需有效且已绑定的Surface，绑定及OSD回复恢复加载；清除输出允许原后台路径继续，stop/替换清除等待代次。未改native图形后端或重新构建AVS3依赖。
- 实际Gradle构建通过：`app-surface-gate-build-final.log`，`assembleMobileArm64_v8aDebug --offline --max-workers=4`，103项任务中7项执行。首次命令因沙箱不能写Gradle缓存锁而未开始构建；获准使用缓存后成功。Gradle报告56分33秒，超出原4分钟估计；未重复成功构建。
- 最终APK：`app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`；SHA-256 `2e40323b300942eecc88482e3254b8a36f9c6e240fcd0f8eadb0a30651ee978f`。首次安装遇设备断开；11:10设备重连后再次调用OEM安装助手，用户随后明确“可以了，打tag”。此确认关闭该场景的额外验证，不把它记录为命令探针结果。
- 交付范围是uavs3d可落地的baseline 0x20/0x22、8/10-bit接线与相关容器/内核切换修复；实测fixtures均为0x22。原始4K50/0x32样片仍不支持，本次tag不代表High profile或4K实时软件性能已经实现。
