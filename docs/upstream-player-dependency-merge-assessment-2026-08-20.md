# 播放器上游依赖任务索引（当前分支恢复副本）

## Recovery anchor

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
| P2 子阶段 | `P2-4` | MPV/native/App | Android BL 硬解 + EL 软解 + GPU FEL 重建，新增手动选择项 | 日志39/40否决pure-BL候选；第9.11节生产者交接修正的host/两ABI/两APK通过，新候选待目标电视验收，本单元未提交 | [P2-4-mpv-android-fel.md](P2-4-mpv-android-fel.md) |
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
