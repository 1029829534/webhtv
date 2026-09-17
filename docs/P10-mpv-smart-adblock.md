# P10：MPV 全局智能去广

## Recovery anchor

- 目标：开启现有全局“智能去广”时，MPV复用Exo广告判定并自动跳过广告，保留HLS播放、时间轴、音画/字幕同步、seek与切源；关闭开关保持原播放。用户2026-09-17提出实现需求并要求深读成熟实现。
- 工作区：`feature/mpv-dv7-fel` / `54e7947c272a7b3ebad8b80bfab4c889e1ea86d5`；guard `P10-mpv-smart-adblock` / upstream；保护104个原有 `app/.cxx/` 文件。
- 范围：MPV的 `MpvHlsProxy.java`、`MpvPlayer.java`、必要的 `HlsAdTimeline.java` 及对应两个定向测试，本文/任务索引，`build/p10-mpv-smart-adblock/` 和既有隔离C++输出。未授权扩展为新广告识别算法或native/依赖升级。
- 完成证据：第2、3节研究完成；三个MPV源文件与两个定向测试已实现。15项定向单测通过，Mobile ARM64 APK构建通过（2分59秒）。APK CRC/v2签名通过，27个native库和10个MPV assets与基线一致；具体哈希见第4节。
- 当前状态：实现、15项单测、APK构建/身份检查和手机 `10CF6H1D2L0009S` 的真实MPV场景均已通过。源码、单测及本文作为同一P10单元提交，恢复tag由guard收尾生成；不推送远端。
- 验收：全局开/关；正文—广告—正文跨边界；手动seek落在广告/正文；中途暂停、切源/释放无旧会话跳转；多码率/独立音轨不能误用另一清单的区间；无法可靠识别时保留正文。一次定向构建/真实MPV场景，native身份保持。
- 时间：13:18 Asia/Shanghai开始；单次Gradle于13:49完成，14:10真机场景全部通过。收尾超出原估计的原因是OEM安装确认、测试包安装参数与探针换源/起点修正；没有重复成功的正式APK构建、单测或扩大native工作。
- 唯一下一动作：用guard提交并生成恢复tag，交付已安装的APK；本单元无待修复项。

## 1. 已有代码与回归来源

- `Setting.isAdblock()` 读取全局 `adblock`（默认true）；手机和电视设置共用它。
- `ExoUtil` 将该开关传给Media3 builder；本地 `media3-exoplayer-hls` sources jar的 `HlsPlaylistParser` 在解析前调用 `HlsAdsParser.process`。该解析器只处理带 `EXT-X-ENDLIST` 的清单，先按路径/前缀主群组识别，后按不连续块识别。不是视频画面分类器。
- Exo `HlsMediaChunk.createInstance` 以 `discontinuitySequence` 获取 `TimestampAdjuster`，`sharedInitializeOrWait` 将每个不连续段对齐到清单累积时长，因此仅对比过滤后文本不足以证明MPV可以直接复用整条链路。
- `MpvPlayer.continueOpenCurrent` 经本机 `MpvHlsProxy`；根和嵌套清单已调用同一个检测器。`MpvHlsProxy.applyAdblock` 对MPV的变化结果记录 `mpv-ts-timestamp-integrity` 并返回原文，IJK仍走原有过滤。这解释全局开关打开但MPV广告照播。
- 保护分支来源：`b5f4129ea34ed3b02c0148512a2aaa76ee736162`（2026-08-07，keep mpv buffering and hls seeks accurate），应保留其时间轴/seek意图，不能当死代码删除。
- 固定FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18` 已有 `hls_timestamp.c`。`normalize_playlist_timestamp` 只在存在discontinuity时启用，并用 `max(2 * target_duration, 1秒)` 判断跳变；短广告造成的差值仍可能保留，不能把它当作任意删片后的完整重映射。
- 固定MPV `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` 已有EDL原生时间轴；当前HLS load options却强制 `demuxer=lavf,demuxer-lavf-format=hls`。EDL还涉及源start_time、主清单、多音轨、打开多个区间的时延，不能仅把清单改成EDL文本。

## 2. 研究与方案记录（已完成）

访问日期均为2026-09-17。五类证据以实际源码和规范为主，不把搜索摘要当结论；Google返回挑战页、GitHub部分REST接口403，已改用固定Git SHA和raw源码读取。无关搜索结果不计入证据。

| 证据 | 修订/来源 | 等级与用途 |
| --- | --- | --- |
| 当前Exo检测/时间戳链 | 本地 `third_party/maven/androidx/media3/media3-exoplayer-hls/1.11.0-alpha01-fongmi/*-sources.jar`，锁定Media3 `e3e922d5c01bc0b564849940fe589daf37360d15` | A；共用广告判定，不能脱离extractor时间轴照搬删片 |
| HLS规范 | <https://www.rfc-editor.org/rfc/rfc8216.txt> | A；discontinuity、media sequence、AES IV、byterange、独立rendition约束 |
| 当前FFmpeg实现 | `build/mpv-native/mpv-android/buildscripts/deps/ffmpeg/libavformat/hls.c`、`hls_timestamp.c`，`177f090e0503b7e013922ca903bde14b1c375f18` | A；短跳变和显式删片不等价 |
| MPV EDL | <https://github.com/FongMi/mpv/blob/cca559b41ceb0bb7731cf6ef2e1f33276cd30c42/DOCS/edl-mpv.rst>，`demux/demux_edl.c` | A；原生虚拟时间轴可用，但多源启动、源起点和HLS强制demuxer需额外适配 |
| hls.js | <https://github.com/video-dev/hls.js/blob/3ca40a0d1f422dbbee6e2443d513e19535ca7066/src/utils/discontinuities.ts> 及同修订测试 | A/B；切码率/音轨需按CC/PDT/序号对齐，不能混用另一路媒体时间轴 |
| MPV现场问题 | <https://github.com/mpv-player/mpv/issues/10277>、<https://github.com/mpv-player/mpv/issues/11024>、<https://github.com/mpv-player/mpv/issues/16205> | B/C；实际PTS跳变会导致音画/外挂字幕错位，须看当前代码与样例，不能由旧issue认定本机当前仍有同一缺陷 |

方案比较：保留现状不满足需求；直接采用Exo过滤文本遗漏MPV时间戳/序号合同；原生EDL能提供剪辑时间轴但需处理上述额外约束；借鉴成熟播放器在原时间轴跳过已识别广告区间，可保持清单/密钥/Range和字幕时间戳，但会保留原时长且每个广告区间发生一次正常seek。最终选择见第3节。

回滚：本任务只改变App层，原子回退本任务提交即可恢复MPV原有保护分支；Exo/IJK/媒体库、FEL、P8 HDR10及视频手动解码合同保持。

## 3. 最终设计与实施边界

用户本轮已请求“全局开关打开后，mpv也能支持智能去广”；本单元在该能力范围内实现，沿用检测器，不增加开关、云端请求或native依赖。选择**保持原始清单和播放时间轴，以已识别广告区间驱动既有精确seek**。这是Kodi的商业广告跳过、mpv SponsorBlock的成熟执行方式；本项目无需复制其脚本、联网数据库、投票或音量/播放速度修改。

- `HlsAdsParser.process` 仅提供识别结果；将过滤前后媒体分片建立唯一的有序对应，恢复广告在原始清单中的时间区间。相邻广告合并为一次跳转；重复URI导致无法唯一定位、无效时长/溢出、非VOD、删除全部内容、非保序结果均不跳转。读取URI/时长/byterange标识，不输出完整地址。
- 代理仍返回完整原清单，并沿用现有URL代理改写。AES隐式IV/密钥轮换、MAP、字节范围、MPEG-TS/fMP4原始时间戳均交给现有播放器路径；无需改写或重编码媒体。
- 每个播放会话保存独立计划。主清单多码率分别保存，依据native已选码率取匹配计划；选择未知时只有所有已声明视频清单的计划一致才采用，不能让最后一次HTTP请求或音频/字幕/I-frame清单覆盖视频计划。
- 起播/恢复位置或用户拖动落在广告区间时，直接解析到该区间末尾；正常播放复用已有50ms合并的时间位置事件，命中广告只发一次 `absolute+exact` seek。使用现有seek位置稳定器、缓存失效和暂停意图；旧事件不重复跳转，手动seek/新文件/停止清理已执行区间。
- 保留原总时长和时间轴，进度条直接跨过广告；后台缓存仍按原时间轴记录。切换播放器原有位置合同不改造，不宣称MPV展示压缩后的Exo时长。开关关闭立即停止后续自动跳转；与Exo一样，在开关打开后新播放才保证已有识别计划。
- 不保证逐帧无缝拼接：中途广告跳过走一次正常seek，仍可能有设备/网络相关的短缓冲或边界瞬间画面。这比删除分片并错置时间轴更可控。计划解析在清单处理线程，播放线程仅查短区间列表，不新增定时轮询、视频/音频分析或额外媒体下载；实际耗时和画面由定向样例验证，不能预先声称性能提升。

| 方案 | 决策与理由 |
| --- | --- |
| 维持MPV一律忽略检测结果 | 拒绝；不满足已请求的全局去广 |
| 原样套用Exo删除后的清单 | 拒绝；Exo的TimestampAdjuster未跟随迁移，还存在隐式IV和byterange语义问题 |
| 原生EDL虚拟剪辑 | 本单元不采用；绝对source start_time、主清单/独立音轨、逐区间打开和强制HLS demuxer需要更大改造，不能当作低风险文本包装 |
| 原时间轴区间跳过 | 采用；复用实际MPV seek、会话与缓存生命周期，原清单/媒体字节保持；代价是保留原总时长且切点发生seek |

补充证据（访问2026-09-17）：

- A/B：[Kodi `CheckAutoSceneSkip`](https://github.com/xbmc/xbmc/blob/1a9c02dceef8f03d84002429b88e591b17330d6c/xbmc/cores/VideoPlayer/VideoPlayer.cpp) 实际读取已同步播放时钟，合并相邻区间，按末端去重，再排队seek；同修订 `Edl.cpp` 区分CUT和COMM_BREAK并维护时间映射。本单元采用COMM_BREAK的原时间轴执行思路，不照抄CUT显示时间转换。
- A/B：[mpv SponsorBlock](https://github.com/po5/mpv_sponsorblock/blob/7785c1477103f2fafabfd65fdcf28ef26e6d7f0d/sponsorblock.lua) 的 `skip_ads` 观察 `time-pos`，命中起止区间后跳到末端并记录已执行；`file_loaded` 清空旧媒体区间。它的数据来自YouTube众包，而WebHTV使用已存在的本地Exo检测器。
- C：[SponsorBlock作者博文](https://blog.ajay.app/voting-and-pseudo-randomness-or-sponsorblock-or-youtube-sponsorship-segment-blocker)（2019-07-18）解释重叠边界分组、错误提交和重复跳转；采用“区间合并/避免重复”的工程原则，不引入其随机投票筛选或远端数据。
- 学术边界：[Ramires等，2018，音频广告检测](https://arxiv.org/abs/1811.02411)，已读取2页PDF全文（本地证据 `research/audio-ad-detection.txt`）。其方法需音频解码、±6秒局部上下文、150秒滑窗和训练数据，验证集为26小时葡萄牙电视，作者明确讨论误删正文及跨地区泛化限制。本需求已有清单检测器，不以该论文改成音频/视频分类，不把其0.874相关系数套成WebHTV准确率；借鉴保守处理不确定区间的原则。

以上覆盖准确源码/测试、规范、issues与维护者分类、两个成熟相关项目、博文与论文。所列外部修订全部仅为阅读参考，没有合入任何上游提交。原生/ABI/许可证及二进制所有权不变。

## 4. 实施与验证记录

- `HlsAdTimeline`：不可变原时间轴区间、唯一保序分片映射、相邻区间合并、无效输入保守退出、二分定位；`SkipState` 防旧位置事件重复触发，手动seek可重置。
- `MpvHlsProxy`：每会话/视频变体记录去广计划；排除独立音频/字幕/I-frame清单；按实际码率选择，不确定时要求各已声明视频变体一致；保留原MPV清单与IJK行为。
- `MpvPlayer`：复用既有位置事件与精确seek，手动seek/起播位置落入广告时定位至末端，切源/停止重置；无新增轮询定时器或解码策略变化。
- 定向验证：`HlsAdTimelineTest` 10项、`MpvHlsAdblockTest` 5项全部通过；同一次Gradle调用完成 `assembleMobileArm64_v8aDebug`。完整输出保存在 `build/p10-mpv-smart-adblock/gradle.log`，不重复成功构建。
- APK：`app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`，165351568字节，SHA-256 `3f1a3f163493ab95c4e7607279cc3d18c826006a49ee436c4ee2d79755d81b77`。CRC和v2签名通过，27个native库及10个MPV assets与此前P8兼容基线逐项相同。
- 回滚APK：`build/p10-mpv-smart-adblock/before-mobile64.apk`，SHA-256 `18e641a666268a80369bf426d7761f1e9d3d740cacdd63c5d341b677589befe2`；原提交 `54e7947c272a7b3ebad8b80bfab4c889e1ea86d5`，tag `recovery/P2-4-p81-hdr10-compat/20260917131448-54e7947c272a`。
- 真机：vivo V2453A / Android 15 / ARM64，ADB `10CF6H1D2L0009S`，现有APK已安装。命令探针调用实际应用和MPV，以native `time-pos` 与PixelCopy取样验收；各次画面取样均为4608/4608像素匹配预期，`hwdec-current=mediacodec`。

| 真机场景 | 结果与证据 |
| --- | --- |
| 原时间轴 `[0,6)` 蓝色正文、`[6,8)` 红色广告、`[8,14)` 绿色正文 | 正常起播蓝色，自动跨过广告后绿色；总时长保留14.016秒 |
| 手动seek到6.5秒广告内 | 定位至8.321秒，实际画面绿色 |
| 播放中关闭全局智能去广后seek到6.5秒 | 6.754秒，实际画面红色，允许播放广告 |
| AES-128 CBC、从37开始的media sequence、无显式IV | 0.955秒蓝色起播，广告seek后8.354秒绿色；未删除或重编号清单片段 |
| 两个视频变体的主清单 | 0.854秒正常起播，广告seek后8.321秒绿色 |
| 暂停与恢复 | 暂停后位置保持8.354秒，恢复后继续切源 |
| 从广告计划源切换至无识别线索源 | 6.721秒实际画面红色，旧会话广告区间没有误作用到新源 |
| 停止、退出和设置恢复 | 探针执行stop/finish，原全局开关与播放器选项恢复；临时探针在验证后卸载 |

完整证据：`build/p10-mpv-smart-adblock/device-probe.log`（普通/手动seek/关闭开关）、`device-probe-remaining.log`（AES）、`device-probe-master.log`（主清单/暂停/切源/退出）。前两份包含当时后续场景的失败记录：测试驱动曾将 `start(PlaySpec,long)` 的超时参数传为0，且同一owner key下新源保留旧位置，和“必须从第1秒开始”的探针断言冲突。改为正常 `browse` 入口，并等待native path切换后明确seek到0；最终记录中新源最初位置8.554秒、归零后0.888秒，证实测试起点需显式设置。只补跑尚未完成场景，正式APK和生产代码保持原构建。

验证覆盖检测、跳过执行、加密、变体隔离和生命周期；不把短样例等同于所有站点的广告识别率。识别能力与Exo相同，对有歧义的分片对应或不同视频变体区间保持正文；跳转仍有正常seek的缓冲/边界显示限制，原总时长保持。

提交定位：本单元使用 `Task-Guard: P10-mpv-smart-adblock` trailer，唯一恢复tag前缀 `recovery/P10-mpv-smart-adblock/`。最终40位提交ID和具体tag由guard输出及本地task-state记录，避免为记录自引用提交ID再制造文档提交。回滚为撤销该原子提交，或安装上面保留的基线APK。
