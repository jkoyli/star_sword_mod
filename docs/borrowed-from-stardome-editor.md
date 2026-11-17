# 从 `stardome_seal-source/stardome-editor` 借鉴了什么

本轮重构的参照物是隔壁项目的独立 Swing 配置编辑器。原则：**只借结构与协议，不借美术，也不借
为它自己那套 JVMTI/分发需求而生的重机制。**

## 一、借鉴（已落地）

| # | 借鉴点 | 落地位置 | 解决什么问题 |
|---|---|---|---|
| 1 | **编辑器做成独立 Gradle 子工程**：`application` 插件、JDK 17、零第三方依赖、不 import `net.minecraft*`，只读写文本文件 | `mechanic-editor/build.gradle` | 能在游戏未启动、甚至没装 Forge 的机器上单独跑，也是唯一能做到「早于类加载」的形态 |
| 2 | **入口极简**：解析 `--root`、EDT 上建窗口、ToolTip 时序（400/20000/100） | `MechanicEditorMain` | 与隔壁 `EditorMain` 同构，几十行、无框架 |
| 3 | **一行一字段的 `FieldRow`**：两行文字（名字 + 人话说明）+ 悬停详细气泡 + 整行热区 | `SettingRow` | 让「这条开关到底影响什么」不用挤在标题里 |
| 4 | **分组卡片（`GroupPanel`）**：`TitledBorder` 标题写「组名 + 用途」，逐行 add | `EditorFrame#buildGroup` | 玩家项/全服项一眼分开，权限差异写在组标题上 |
| 5 | **行高由 `FontMetrics` 算，不写死像素** | `SettingRow` 的 preferred/maximum size | 隔壁踩过「写死 16px 把汉字下半截裁掉」 |
| 6 | **配置文件三级路径解析**：`-D` 覆盖 → `config/` → 根目录 | `SettingsFile#resolve`、mod 侧 `ClientMechanicPresets#filePath` | 开发期（工程根）与发布期（游戏目录）同一份代码；编辑器与游戏目录不同也能对上 |
| 7 | **IO 一律 Never-throws + 缺省回落**：文件缺失/损坏/编码异常都按默认值继续 | `SettingsFile#load`、`ClientMechanicPresets#ensureLoaded` | 配置文件写坏不该让编辑器打不开、更不该让游戏起不来 |
| 8 | **原子写**：先写 `.tmp` 再替换 | `SettingsFile#save` | 防止读端读到半个文件 |
| 9 | **`start-editor.bat` 放仓库根**、内部 `cd /d %~dp0` 后 `call gradlew.bat :mechanic-editor:run` | `start-editor.bat` | 隔壁把这个 bat 放在子工程目录里、导致双击找不到 `gradlew.bat`（它的文档里也承认这点） |
| 10 | **「保存 → 再启动」的顺序**：点「启动游戏」时先落盘、再 spawn | `EditorFrame#launchGame` | 保证游戏读到的就是界面上这份配置 |
| 11 | **不注入重复的启动拦截**：编辑器启动游戏时传 `-Dstar_sword.mech.startupPanel=false` | `GameLauncher#launch` | 玩家已经配过，进游戏不该再被面板挡一次 |
| 12 | **反向心跳文件**（借用「mod 写状态、编辑器轮询」这一半，去掉 live/revision 热改那一半） | mod：`MechanicStateWriter`；编辑器：`GameStateReader` + 1s 定时器 | 编辑器能回答「游戏到底在不在、模组加载没有、预设生效没有」——这正是隔壁半成品缺的那一环 |
| 13 | **状态枚举而不是布尔**：未启动 / 启动中 / 运行中未进世界 / 运行中 / 心跳停止（+ 进程退出） | `EditorFrame#refreshGameState` | 隔壁的六态诊断也是同一个教训：「一个 false 表示四种处境，界面就没法给出下一步」 |

## 二、明确不借（以及原因）

| 不借 | 原因 |
|---|---|
| `NativeAgentResolver` / `NativeAgentPathValidator`（1300+ 行） | 为「启动前把 native DLL 送到 `-agentpath`」而生，还把「路径含空格」变成硬失败。本项目不做 JVMTI，零收益 |
| `HmclSettings` 的「JSON 外科手术」 | 自写 JSON 解析器 + 在原文里正则替换 `jvmOptions` 单个字段，HMCL 换 schema 就炸。我们改用「`JAVA_TOOL_OPTIONS` 注入 `-D`」，不碰启动器配置 |
| `DistributionPanel`（81KB）实例/分发子系统 | 实例扫描、装 mod、拉启动器属于「分发工具」，与开关无关；我们只保留「可执行启动命令 + 兜底 `gradlew runClient`」 |
| `ConfigCatalog` 那 96KB 手写文案/可用性表 | 把编辑器与 mod 内部 key 硬耦合，源码一改就腐化。我们只在 `SettingsCatalog` 里写 6 条，且 id 必须与 `MechanicRegistry` 一致 |
| `controls.json` 导出链路 | 好处是「mod 导出、编辑器照抄」防漂移；但那需要一个能在无 MC 环境下跑的导出器。本项目 6 个开关，手工同步成本更低，已在两侧注释里互指 |
| live/result 双文件 + `revision` 轮询（热改） | 那是「游戏外热改、0.5s 生效」的通道，需要 mod 侧常驻轮询线程。本项目用「启动前配置 + 进服一次性下发」已够用 |
| 全部美术：Nimbus LAF、配色、像素常量 | 你明确说不抄美术。我们改回系统 LAF + 显式白底，像素只按内容实测调 |
| 多层备份（`.bak-*` + 时间戳 + 清理按钮） | 过度工程；本项目只有一份 JSON，覆盖写 + `.tmp` 足够 |

## 三、由此产生的两条自检

1. **id 一致性**：`mechanic-editor` 的 `SettingsCatalog` 与 mod 的 `MechanicRegistry` 必须同名同 id；
   写错的一方不会崩——mod 会把未知 id 当「不存在」忽略，编辑器会把它当未知键跳过。两侧注释都已标注。
2. **文件一致性**：编辑器写 `{"values":{...}}`，mod 用 Gson 读同一个结构；改格式必须两边同时改。

## 四、仍需你决定的一件事

## 四、已定的取舍

「游戏内删档」与「启动前预设」会互相覆盖（启动面板设了 `true`，进服后被删档删回默认，下次启动
预设又推回 `true`）。**已按 A 方案实现**：删档时同步把本地预设写成默认值
（`MechanicPanelScreen` 的删档分支调 `ClientMechanicPresets.set(id, def.defaultValue)`），
所以删档之后不会再被旧预设推回来。启动面板也因此保留「记住上次配置」的行为。
