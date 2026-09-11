# 播放器上游依赖任务索引（当前分支恢复副本）

## Recovery anchor

- 当前分支：`feature/mpv-dv7-fel`。
- 当前实施基线：`fc62397591701b2232ae7de4f50a032bd7742064`（2026-09-12）。
- 历史完整评估：已核实仓库历史提交 `9fcab83f9084446566240a8e8f5233d87d0274cc` 中的同名文件可读取；主线提交 `784b90420d646eb6c7ddcc63ad622a92c65b02b4` 删除了根目录本地任务文档，因此本分支只恢复当前实施需要的稳定索引。
- 当前任务：`P2-4`，手动选择的「FEL 双层重建」App/native 接线及两 ABI 产物已完成；57 项定向测试、native/ELF/符号/资产校验和两个 debug APK 通过，真机验收因 ADB 无设备待进行。尚未提交/tag，不宣称既有性能已完成真机回归。
- 下一步：手机连接并授权后，安装现成候选包，验证真实 FEL 与默认模式回归；详见 [P2-4-mpv-android-fel.md](P2-4-mpv-android-fel.md)。

## 稳定任务 ID 与唯一文档索引

历史任务 `P0` 至 `P8` 的完整状态仍以提交 `9fcab83f9084446566240a8e8f5233d87d0274cc` 保存的评估为准；本恢复副本不重新编号或复制其近五千行台账。

| 顺序 | 任务 ID | 类别 | 功能/能力 | 状态 | 唯一文档 |
| ---: | --- | --- | --- | --- | --- |
| 22 | `E9-3` | Exo/App | 普通 HEVC 硬解 + Vulkan/libplacebo 的 DV5 色彩映射默认准入 | 2026-09-12默认准入已实现，三个定向测试类及 Mobile/Leanback arm64 Java 编译通过；保留原生杜比和设备能力门控，待新版包原场景复测 | [E9-3-exo-dv5-vulkan-renderer.md](E9-3-exo-dv5-vulkan-renderer.md) |
| P2 子阶段 | `P2-4` | MPV/native/App | Android BL 硬解 + EL 软解 + GPU FEL 重建，新增手动选择项 | 2026-09-12源码/两 ABI 构建/57 项测试/两个 APK 资产校验完成；默认不自动启用，待真机 FEL、生命周期与性能验收，未提交/tag | [P2-4-mpv-android-fel.md](P2-4-mpv-android-fel.md) |
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
