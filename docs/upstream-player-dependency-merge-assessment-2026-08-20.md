# 播放器上游依赖任务索引（当前分支恢复副本）

## Recovery anchor

- 2026-09-13最新候选（18:39包）：P2-4第9.16节已完成逐帧重新绑定/录制及有界帧关联/慢API日志，保留AHB缓存/同步/画质/默认行为。真实函数ASan/UBSan、源/补丁、双ABI/ELF/导出、13项Java及两APK内容/签名/ZIP通过；1200帧1200次新录制、1128次对象槽命中、不重放，18项其他库不变。guard原子收尾并归还临时隔离的Release缓存，不推送。唯一下一步：目标电视安装TV32 SHA256=`4971498a956a723cf348592dc4b228f595efdda56a77a884afb51fec05645722`，同样片三轮完整播放和seek/退出，取新增App调试日志裁决。14:52候选的实机回跳、持续掉帧及独立751ms失败仍未取得新设备验收。以下为历史状态。

- 15:01收尾说明：P2-4-fel-vk-reuse本机验证与两APK均通过，但guard finish因新出现且无法归属本轮的35个`app/.cxx/RelWithDebInfo/621cr346/`及Release tools缓存返回4，尚未提交/tag；原35个保护文件未变。下一步请求临时隔离新增缓存并原样恢复的批准，不绕过guard；详见P2-4第9.15节。

- 2026-09-13日志30续修：基线`dc1401638532840a8362b869b3220cb952ca7b35`约12.9fps/A-V最大7秒。P2-4第9.15节已实现有界AHB/命令复用及API统计，真实函数ASan/UBSan、13项Java、两ABI/ELF/导出、18库不变及两包内容/签名通过，guard `P2-4-fel-vk-reuse`收尾，不推送。唯一下一步：电视安装TV32 SHA256=`67b007a129ba488491cc666683ed8b2cc4a35d3e96c7ef6bf26b8cd56df8ad68`，同GIJoe三轮完整57秒及seek/退出，取reuse/api perf日志验证性能；整体实时播放尚未验收。以下为历史记录。

- 当前2026-09-13日志29续修：基线`1620bac1566727f4067eda631647a11652082e74`三次起播成功但约10–12fps/A-V滞后，不是性能验收。P2-4第9.14节已移除纯FEL统计的主线程/Logcat重复处理，增加无GPU等待的CPU/驱动/GPU分段计时和真实线程报告，保持像素、同步、默认行为和依赖；host、23项Java、双ABI/ELF/导出通过，18库不变。两个最终debug包各10库/签名/ZIP结构通过，guard `P2-4-fel-steady-perf`收尾，不推送。唯一下一步：目标电视安装TV32 SHA256=`871ccbea4ac975055ad54258018c3071c0e51a6624b499746c2e2e1835593f5d`，同GIJoe三轮57秒及seek/退出，取新分段日志再决定CPU并行或GPU链路优化；整体需求未验收。以下均历史状态。

- 当前续修（2026-09-13）：日志42三次首帧751ms失败已按P2-4第9.13节窄修复：实际GPU冷初始化独立有界期限、pending帧回到可取消filter/dispatch、失败仅一次EOF。旧代码冷初始化负例失败，新实际VO/wrapper/GPU host回归、两ABI/ELF/公开导出通过；只变更2份libmpv、18依赖不变。两debug包构建4m44s通过，各10库与v2签名匹配。guard仍为`P2-4-fel-vo-handoff`，HEAD=`0a82dc13e255524d7c0e4e04c2f51ec9119aec88`、恢复tag=`recovery/P2-4-fel-buffer-progress/20260913025508-0a82dc13e255`，续修未提交/tag/推送。唯一下一步：目标电视安装TV32 SHA256=`038d77eb0d694a7c96aa0c17bf3aa23eac7996805702187cdc2de835686489f0`，同GIJoe样片3次起播、完整57秒及seek/退出，取App调试日志裁决；不能以host或打包成功称电视需求完成。其余为历史状态。

- 最新状态（2026-09-13）：上轮失败候选已完整提交为`cbb02fa4c40a2d0b1d04a43d6c5be4265129f98e`，tag为`recovery/P2-4-fel-reliability/20260912214750-cbb02fa4c40a`，未推送；日志39/40否决其可靠性/性能验收。active guard `P2-4-fel-buffer-progress`已按P2-4第9.11节实现BL生产者发布前暂存/源归还及BL/EL耗时日志；保留FFmpeg/既有超时保护、EL/RPU/10bit与默认路径。定向host测试、两ABI构建/ELF/公开导出通过，仅两libmpv变化/18依赖不变，两个debug APK各10库及v2签名通过；本轮未提交/tag，未安装到目标电视。唯一下一步：安装SHA256=`26a03ee980a26f900dbd79d6fc0b6c0cc03759f2b91829e52d24df014cae9ef8`的32位候选，同一GIJoe样片重复启动3次、完整57秒和seek/退出，按新交接/耗时日志判断根因。不能称已解决卡死/掉帧。

### 此前恢复记录（历史；当前状态以上方与P2-4第9.11节为准）

- 当前分支：`feature/mpv-dv7-fel`。
- 当前实施基线：`792c1f880bc151eb1cb6675034ec144aadc14766`（2026-09-12，用户要求保存的已知问题快照）。
- 历史完整评估：已核实仓库历史提交 `9fcab83f9084446566240a8e8f5233d87d0274cc` 中的同名文件可读取；主线提交 `784b90420d646eb6c7ddcc63ad622a92c65b02b4` 删除了根目录本地任务文档，因此本分支只恢复当前实施需要的稳定索引。
- 当前任务：`P2-4`，日志38两次否决v3候选：core暂存6/6、GPU完成6/6、source-held=0，BL仍12包/6帧后停滞4003；不能再用日志37的第6帧未进入VO解释，见任务第9.10节。
- 下一步：目标电视安装第9.10节新32位APK（SHA256=`7666ca86315bd107dea68ece38bbaeaed211724142f453ddac7703f2c2fe3955`）进行同样片FEL完整播放/seek/退出，核对pure-bl及RPU来源/缺失。pure-BL候选的120包BL、360帧EL/RPU/NLQ、两ABI/ELF/公开导出及最终紧凑两APK各10库/签名已通过；跨项目研究涵盖FFmpeg/Kodi/VLC/GStreamer/Nova。active guard及HEAD保持，未提交/tag、目标电视未验收。证据和完整台账见 [P2-4-mpv-android-fel.md](P2-4-mpv-android-fel.md)。

## 稳定任务 ID 与唯一文档索引

历史任务 `P0` 至 `P8` 的完整状态仍以提交 `9fcab83f9084446566240a8e8f5233d87d0274cc` 保存的评估为准；本恢复副本不重新编号或复制其近五千行台账。

| 顺序 | 任务 ID | 类别 | 功能/能力 | 状态 | 唯一文档 |
| ---: | --- | --- | --- | --- | --- |
| 22 | `E9-3` | Exo/App | 普通 HEVC 硬解 + Vulkan/libplacebo 的 DV5 色彩映射默认准入 | 2026-09-12默认准入已实现，三个定向测试类及 Mobile/Leanback arm64 Java 编译通过；保留原生杜比和设备能力门控，待新版包原场景复测 | [E9-3-exo-dv5-vulkan-renderer.md](E9-3-exo-dv5-vulkan-renderer.md) |
| P2 子阶段 | `P2-4` | MPV/native/App | Android BL 硬解 + EL 软解 + GPU FEL 重建，新增手动选择项 | 日志29三轮起播成功但严重掉帧；9.14按CPU/驱动/GPU分段继续优化，整体实时播放未验收 | [P2-4-mpv-android-fel.md](P2-4-mpv-android-fel.md) |
| 38 | `P9-MPV-BLURAY-MENU` | MPV/native/App | HDMV Blu-ray 菜单画面、按钮高亮、方向/确认/返回/Popup、菜单跳转与 still frame；BD-J 无提示回退现状 | 2026-09-11父菜单未命中修复已实现，定向验证及构建通过，用户测试确认并要求tag | [P9-MPV-BLURAY-MENU.md](P9-MPV-BLURAY-MENU.md) |

## Checkpoint 55：2026-09-06 P9 HDMV 菜单实施启动

- WebHTV MPV 基线：`cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`。
- FongMi MPV 当前审阅头：`13eafa069366edb54606637b323b0d10efd05fa3`。
- 参考菜单链最终已审阅状态包含官方/FongMi 合并链提交 `c318236b8882af860f16f936225430ad053a2179`；不能整体升级到当前审阅头，因为它还携带与菜单无关的渲染、音频、格式和网络变更。
- 产品决定：仅支持 HDMV；`c625405ddcdf9d40cdda2ffe3708865c105ed965` 的 BD-J 菜单接入不实施，不启用 JVM/JAR，不新增提示。
- 实施策略：保留固定 MPV/FFmpeg/libplacebo/mpv-android 基线和 WebHTV 现有补丁，只增加独立、可撤销的 HDMV 菜单补丁及最小 App 输入接线。
- 回滚锚点：`966b3b6747ece447c2f34f7787df7c6af572baa0`。
- 下一步：源码补丁已在隔离固定基线与现有 WebHTV 补丁序列中干净应用；待 native 环境可用时执行双 ABI 构建/资产校验。

## Checkpoint 56：2026-09-07 P9 适配补丁完成

- 生产补丁 `third_party/patches/mpv-discnav.patch` 已替换为相对 `p9-local` 的最终 HDMV 适配版本；未引入 BD-J JVM/ARGB overlay。
- App 与脚本接线已完成，Java 编译、脚本语法、Task Guard check 和完整 MPV patch apply-chain 已通过。
- native 构建阻塞于当前环境缺少 `libplacebo`、Android NDK/clang；双 ABI ELF/资产和设备验收保持未执行。
- 详见 [P9-MPV-BLURAY-MENU.md](P9-MPV-BLURAY-MENU.md) 的 Checkpoint 2；唯一下一步为具备依赖后做一次 native 构建/资产校验。
