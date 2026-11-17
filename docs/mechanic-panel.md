# 机制面板（Mechanic Panel）

游戏内实时查看并切换本模组机制开关的界面。开关状态**全部持久化**：玩家四项写玩家 NBT（跟存档、
跨死亡克隆、跨重启），全服两项写世界存档。

> 版本：`2.3.0` 引入面板，`2.3.1` 增加持久化并移除 `Shift+Z` / `Shift+C` 快捷键，
> `2.3.2` 增加「删除存档项」与启动拦截（启动即弹面板，点「启动游戏」才进主菜单），
> `2.3.3` 新增**独立配置编辑器** `mechanic-editor`（游戏进程之外的启动前配置）。

## 三种打开方式（按「离游戏进程多远」排）

### A. 独立编辑器 `mechanic-editor`（2.3.3，真正的「启动前」）

```powershell
.\start-editor.bat          # 推荐：先挑一个 Java 17+，再启动编辑器
```

> **不要直接双击 `mechanic-editor\build\libs\*.jar`。**
> 这台机器上 `.jar` 的文件关联指向 `C:\Program Files\Java\jre1.8.0_481\bin\javaw.exe`，
> 用 Java 8 去跑编译自 Java 17 的类会在进入 `main` 之前抛 `UnsupportedClassVersionError`，
> 弹出系统通用的「A Java Exception has occurred.」——那不是编辑器坏了，是 JDK 选错了。
>
> `start-editor.bat` 会调用 `run-editor.ps1`：先扫描 `JAVA_HOME`、`C:\Program Files\{Java,
> Eclipse Adoptium,Microsoft,Zulu,BellSoft}` 与 PATH，挑出 **17..24 里最低的那个**（Gradle 8.14
> 支持上限是 24；本机 `JAVA_HOME` 是 25，会跳过它选 JDK 17），再：
> - 有已构建的 `star_sword_mechanic_editor-java17.jar` → 直接用它的 `javaw` 启动（绕过 .jar 关联）；
> - 没有 → 用这个 JDK 跑 `gradlew :mechanic-editor:run`。
>
> 自检（不弹窗）：`$env:STAR_SWORD_EDITOR_DRYRUN=1; .\start-editor.bat`。

它是一个**独立 JVM 进程**（JDK 17 + Swing，零第三方依赖、不 import Minecraft/Forge），
在游戏加载任何类之前就能运行。**它是给真实游戏（HMCL 实例）用的，不是开发环境**：

#### 选实例 → 装 mod → 保存并启动

| 步骤 | 编辑器做什么 | 落在哪 |
|---|---|---|
| ① 自动探测 | 找「有 `HMCL*.exe` 且有 `.hmcl\`」的目录作 HMCL 根；再读 `.hmcl\config\game-directories.json` 得到它管理的游戏目录（相对路径按 HMCL 根展开） | 只读 |
| ② 列实例 | 扫每个游戏目录的 `versions\*`，按「有 `mods\` / `.hmcl\` / `<名字>.json`」识别实例，并显示 MC 版本、mod 数、已装的星辉剑版本 | 只读 |
| ③ 选实例 | 默认选**已装星辉剑**的那个（本机是 `1.20.1-Forge`，27 个 mod） | — |
| ④ 保存配置 | 写 `<实例目录>\config\star_sword_mod_1785589476_mechanics.json` | 与 mod 默认读取路径**完全一致**，所以不需要任何 `-D` |
| ⑤ 装到实例 | 把 `build/libs/star_sword_mod-*.jar` 复制进 `<实例>\mods\`，旧 jar 移到 `mods\.star-sword-backup\` | 实例 mods |
| ⑥ 保存并启动 | 打开 HMCL（不传参数） | 由你在 HMCL 里点一次启动 |

**为什么不在 ⑥ 里直接进游戏**：HMCL 3.16 只提供 `-Dhmcl.*` 这类调试属性，没有「命令行启动指定版本」
的开关（见 [HMCL 文档：JVM 选项与环境变量](https://docs.hmcl.net/launcher/jvm-options-and-env.html)）。
硬要绕过它就得自己拼 Forge 的 `--launchTarget`/模块路径/鉴权参数，那是重写启动器，脆弱且不值。
所以这里选择「打开 HMCL + 心跳确认」：你点启动后，编辑器会自己看到游戏起来、进世界、预设是否生效。

自检（不弹窗，直接看它认的是哪个实例）：

```powershell
java -jar mechanic-editor\build\libs\star_sword_mechanic_editor-java17.jar --selftest   # 认到哪个实例/哪份配置
java -jar mechanic-editor\build\libs\star_sword_mechanic_editor-java17.jar --dump       # 打印组件树（排查「窗口空白」）
java -jar mechanic-editor\build\libs\star_sword_mechanic_editor-java17.jar --uitest     # 自动截图到 build/editor-uitest.png
```

本机实测输出（节选）：`HMCL = ...\桌面\文件夹\游戏\HMCL-3.16.3.exe`、`默认实例 = 1.20.1-Forge`、
`配置文件 = ...\versions\1.20.1-Forge\config\star_sword_mod_1785589476_mechanics.json`、
`已装 mod = star_sword_mod-2.3.4.jar`。

> **界面曾经是空白的**，原因有两个，都记在这里免得再犯：
> ① `JScrollPane` 里的内容面板用普通 `JPanel + BoxLayout` 时，BoxLayout 会按子组件的
> `maximumSize`（我们为了横向铺满写成了 `Integer.MAX_VALUE`）算出 2147483647 的首选宽度，
> 所有组件被布局到屏幕外。修法：内容面板实现 `Scrollable.getScrollableTracksViewportWidth()=true`
> （现为 `VerticalPanel`）。
> ② 主按钮曾用「深蓝底 + 白字」，但 Windows LAF 忽略 `setBackground`，结果白底白字变成空白按钮；
> 现在只加粗、不改配色。

「开发启动」按钮是给改代码时用的：走 `gradlew runClient`，并通过 `JAVA_TOOL_OPTIONS` 注入
`-Dstar_sword.mech.file=<配置绝对路径>` 与 `-Dstar_sword.mech.startupPanel=false`。

启动方式优先级（仅开发启动路径）：`-Dstar_sword.launch.command=...` → 工程根
`star_sword-launch.bat` → `gradlew(.bat) runClient`。

#### 编辑器怎么知道游戏在不在（心跳，2.3.4）

编辑器是独立进程，不能直接查游戏内部状态；只靠「我 spawn 了进程」也回答不了「模组加载了吗 /
进世界了吗 / 预设被接受了吗」。所以采用**单向文件心跳 + 进程句柄**双证据：

| 证据 | 谁写 | 谁读 | 能回答什么 |
|---|---|---|---|
| `*.mechanics.state.json`（每 2s 原子重写；标题界面也写） | mod 客户端 `MechanicStateWriter` | 编辑器 `GameStateReader`（1s 轮询） | 游戏在跑吗、模组加载了吗、进世界了吗、玩家/维度、本次是否已下发预设、**服务端权威值**、最近错误 |
| spawn 出来的 `Process` 句柄 | 编辑器自己 | 编辑器 | 进程是否还活着/已退出（区分「没启动」与「启动了但模组没装」） |

状态文件字段：

```json
{
  "mod_version": "2.3.4",
  "loaded_at": 1700000000000,
  "heartbeat": 1700000001234,
  "in_world": true,
  "player": "Steve",
  "dimension": "minecraft:overworld",
  "presets_pushed": true,
  "presets_pushed_at": 1700000000000,
  "last_error": "",
  "values": { "anti_disarm": true, "...": false }
}
```

编辑器据此给出 6 种状态：未启动 / 正在启动（进程活着但无心跳）/ 运行中（尚未进入世界）/
运行中（显示玩家与维度）/ 心跳停止（>8s 无更新）/ 进程已退出。并做**预设对账**：
把界面上勾选的 6 项与 `values`（服务端权威值）逐项比较，显示「预设已全部生效 6/6」或
「未完全生效 5/6：禁止生成 —— 全服项需要 op2，且写入要求手持星辉剑」。

> 这是单向的：游戏只写状态、不读编辑器；编辑器只读状态、不往游戏里塞指令。
> 好处是不需要端口、不穿防火墙、不会因为 socket 被拦而让游戏卡住；代价是最长 2 秒延迟。

### B. 启动拦截（2.3.2）

启动客户端后，**第一帧标题界面就会被机制配置面板顶掉**；面板在启动模式下不允许 Esc 关闭，
必须点底部的「启动游戏」，主菜单（真正进入标题界面）才会出现。可用
`-Dstar_sword.mech.startupPanel=false` 关掉这个拦截。

为什么是这个时机：MC 的窗口与 Screen 系统在标题界面之前就已就绪，这是「能画 GUI」的最早点；
更早的 mod 构造期/premain 根本没有窗口，无法显示界面，也不存在「阻塞主线程还能点按钮」——
界面必须由游戏主循环绘制，所以这里的「冻结启动」= 用面板顶住标题界面、不放行主菜单。
（要真正「早于类加载」，只能像 A 那样做成独立进程。）

启动阶段没有服务器连接，读不到玩家 NBT 与世界存档，因此面板显示并修改的是**本地预设**
（`config/star_sword_mod_1785589476_mechanics.json`）。点「启动游戏」后进入标题界面；
第一次**手持星辉剑**进入世界时，客户端把预设逐项下发给服务端复核（持剑 / op / 限频照旧），
成功后回发权威快照。撤销：删掉那个 json 即可回到默认。

### C. 游戏内面板

| 操作 | 说明 |
|---|---|
| `M`（默认） | 打开/关闭机制面板；键位可在「选项 → 控制 → 按键绑定 → 星辉剑」里改 |
| `Esc` / 再按一次绑定键 / 右上角 `×` | 关闭 |
| 条件 | 与命令面板一致：必须手持星辉剑、且当前没有其它界面打开 |

面板打开期间每 20 tick 向服务端拉一次快照，用来跟上「面板之外发生的状态变化」
（例如其他玩家改了全服项「禁止生成」）。

> **快捷键已取消**：`Shift+Z`（防缴械）与 `Shift+C`（移除模式）两条快捷键已删除，
> 这两个开关现在只能从面板切换；符咒右键仍然保留。

## 面板里的六项

| 面板项 | 作用域 | 存储（持久化） |
|---|---|---|
| 防缴械 | 按玩家 | 玩家 PersistentData 老键 `AntiDisarmEnabled` |
| 移除模式 | 按玩家 | 玩家 PersistentData 老键 `StarSwordRemoveMode` |
| 反击之符 | 按玩家 | 玩家 PersistentData 新键 `StarSwordCounterAttack`（同时镜像进内存表） |
| 反射之符 | 按玩家 | 玩家 PersistentData 新键 `StarSwordReflection`（同时镜像进内存表） |
| 禁止生成 | **全服** | 世界存档 `data/star_sword_mechanic_settings.dat` |
| 严格写入语义 | **全服** | 同上；启动初值仍可被 `-Dstar_sword.health.lockStrict` 指定 |

「禁止生成」「严格写入语义」是全服项，标注 `GLOBAL` 并要求 **op2**；其余四项持剑即可切换。

每行右侧的「删档」按钮 = **删除该开关的存档项并回到默认**（启动模式下叫「默认」，只重置本地预设）。

### 删档是否即时生效

| 层面 | 何时生效 |
|---|---|
| 内存值（本局行为） | **立刻**。`MechanicRegistry.reset` 删完马上把值改回默认（全服项调 setter，玩家项清 NBT 后读到的就是默认） |
| 玩家 NBT | 立刻从 `PersistentData` 移除；随玩家下一次保存/下线写盘 |
| 世界存档 | 立刻从内存的 `values` 移除并 `setDirty()`；**落盘在下一次世界保存/停机** |
| 跨保存是否保持 | 是。`MechanicGlobalState.cleared` 会跳过被删的项，自动保存不会把默认值重新写回去；只有再次切换该项才会重新写入 |

所以「删档」的语义是：**这一秒行为就变了，且重启后仍是默认/`-D`**，而不是「等重启才生效」。

## 持久化的读写时机

| 时机 | 动作 |
|---|---|
| 面板切换成功 | 玩家项写玩家 NBT；全服项写存档并 `setDirty()` |
| 玩家登录 | `MechanicPlayerState.syncFromNbt` 把反击/反射灌回内存表 |
| 玩家死亡克隆 | `MechanicPlayerState.copyTo` 搬运 `StarSwordCounterAttack` / `StarSwordReflection` |
| 服务端启动 | `MechanicGlobalState.get(server)` 读档并**逐项套用**（`ServerStartedEvent`） |
| 世界保存/停机 | 原版 `SavedData` 机制把 `values` 写盘，写盘前按内存当前值刷新 |

「严格写入语义」对应 `HealthWritePolicy`：开启后只要存在权威值，任何血量写入都被替换为该权威值
（治疗也突破不了）；关闭则只拦减血与非法值，允许合法抬高。

## 界面样式

独立的白底小卡片（HMCL 风格）：固定 360×254 GUI 像素、居中显示；纯白卡片 + 浅灰标题栏
（30px，含标题、副标题与右上角 `×`）+ 深灰正文 + 蓝色开关滑轨；卡片四周只叠一层很淡的纱
（`0x26` 黑），不整屏压暗，也不调用 `renderBackground`（保留世界画面）。字体一律不描边。

## 权限与校验（服务端）

1. 手持星辉剑（`ModConstants.isHoldingSword`）；
2. 该开关存在（`MechanicRegistry.byId`）；
3. `player.hasPermissions(kind.opLevel)` —— 玩家项 0，全局项 2；
4. 同一玩家两次写入间隔 ≥ 80ms（防拖拽刷包）。

任何一条不通过都会记 `ModLog.defense` 并丢弃，**不会**回发假状态。通过后回发全量快照，
界面永远显示服务端权威值（这也顺带补上了「移除模式原本没有回同步」的缺口）。

## 网络协议（追加式，既有 packet id 不变）

| id | 包 | 方向 | 内容 |
|---|---|---|---|
| 10 | `MechanicSnapshotPacket` | S2C | 全量快照：id / 类型 / 值 / 是否可改 |
| 11 | `MechanicSetPacket` | C2S | id + 目标值（幂等，不是取反） |
| 12 | `MechanicSnapshotRequestPacket` | C2S | 空包，请求一份快照 |

## 新增文件

```
src/main/java/.../helper/MechanicRegistry.java          开关声明（id/作用域/权限/默认值/读写器）
src/main/java/.../helper/MechanicService.java           服务端：快照、校验、限频、写入
src/main/java/.../client/ClientMechanicState.java       客户端权威快照缓存
src/main/java/.../client/gui/MechanicPanelScreen.java   面板界面
src/main/java/.../networking/packet/MechanicSnapshotPacket.java
src/main/java/.../networking/packet/MechanicSetPacket.java
src/main/java/.../networking/packet/MechanicSnapshotRequestPacket.java
```

改动（均为追加，无行为改写）：

- `networking/ModMessages.java`：注册 3 个新包（id 10–12）+ 3 个发送方法；
- `helper/MechanicRegistry.java`：面板写「防缴械」时补发一次 `AntiDisarm.syncToClient`。这是
  既有入口 `ToggleAntiDisarmPacket` 的副作用（写完 NBT 后同步「开关 + 备份」给客户端），
  客户端那半条防缴械（本地预判回填 / 缺剑通知）以它为总闸，漏了就变成「面板显示已开、
  客户端逻辑仍按关闭跑」；
- `event/ModForgeEvents.java`：登出时清掉面板写入限频表（`MechanicService.forget`）；
- `client/ModKeybinds.java`：新增 `OPEN_MECHANIC_PANEL`（默认 `M`）；
- `client/ModClientForgeEvents.java`：新增面板开关键处理；把 `MechanicPanelScreen` 加进
  `forceClientScreenRecovery` 的白名单（否则受保护玩家打开即被每 tick 关闭）；
- `resources/assets/.../lang/{zh_cn,en_us}.json`：各新增 19 个面板文案键（五项各 2 条 + 9 条通用）。

## 想继续扩的话

加一个开关只需在 `MechanicRegistry` 里加一行 `player(...)` 或 `global(...)`，
再补两行语言键（`gui.<modid>.mech.<id>` 与 `.desc`）——网络层与界面都不用动。
