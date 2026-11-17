# 防御侧改动记录（star_sword_mod，2.1.0 → 2.2.20）

> 本文件记录**本会话**在防御侧做的全部改动，供接手攻击侧的会话阅读。
> 每条都写清：**问题 → 改法 → 涉及文件 → 开关 → 验证 → 边界**。
> 声明：**没有跑过游戏**。下文所有"验证"都是编译 + 离线断言 + 代码/字节码阅读；
> 运行期行为仍需实机确认，未验证项集中列在 §8。

---

## 0. 版本归属（先分清哪些是谁做的）

| 版本 | 内容 | 归属 |
|---|---|---|
| 2.1.0 | 阶段 4（注入自检 `mixinmon/`）+ 阶段 2（权威血量状态 `hijack/authority/`） | 本会话 |
| 2.2.0 | 阶段 3（死亡语义解耦：`HealthJudgement` + agent `Position.BODY`） | 本会话 |
| 2.2.1 ~ 2.2.14 | `ForeignChannelGuard`、`VanillaStateLock`、`AttributeGuard`、`SanitizedFloatItemProxy`、`EntityIntegrityProbe`、`RejectStats`、`MethodLayerState`、`SelfTest`、`LocalPlayerMixin`、`Rules` 等 | **其它迭代**，本会话未改动，仅在其上接线 |
| 2.2.15 | P0-1 `defaultRequire`、P0-3 生命周期安装、P1-1 restore/forget | 本会话 |
| 2.2.16 | P1-2 写入语义开关（`HealthWritePolicy`） | 本会话 |
| 2.2.17 | P1.5 负血/NaN 读取回退（`HealthReadPolicy`） | 本会话 |
| 2.2.18 | P2 逐钩子存活（`HookLiveness`） | 本会话 |
| 2.2.19 | P3 通道趋势（`ChannelTrend`） | 本会话 |
| 2.2.20 | P1-1 尾巴：数据项换装加原版写锁（`f_135346_`） | 本会话 |

> **版本口径提醒**：`docs/client-evidence-checklist.md` 里写的是"目标版本 2.9.6"，与本文件的 2.2.x
> **不一致**。以 `gradle.properties` 的 `mod_version` 为准（当前 2.2.20）；那份清单来自另一条线，
> 阅读时注意它提到的"2.9.6 已加载"在当前构建里不会出现。

---

## 1. 阶段 4：注入自检（`mixinmon/` 包，4 类）

### 问题
Mixin 配置改成 `required:false` 之后，`@At` 未命中的注入**只记一条 WARN**甚至完全静默：
目标类里 `handler` 方法名照样存在（MAIN pass 就合并了），所以"按名字探测"看不见注入点失败。
排查时无法判断"我们的防线到底在不在"。

### 改法
| 类 | 职责 |
|---|---|
| `MixinFailureRegistry` | 失败记录表：按字段去重、只记本模组、上限 256 条、`Never throws` |
| `StarSwordMixinErrorHandler` | 全局错误处理器，抄送 `onPrepareError`/`onApplyError`；**始终返回 `null`**，绝不把 WARN 抬成崩溃 |
| `StarSwordMixinConfigPlugin` | 在 mixin 配置被选中时（早于任何注入）注册错误处理器，并写系统属性 `star_sword.mixin.plugin=ok` |
| `MixinProbe` | `FMLLoadCompleteEvent` 时逐条检查 27 个 `starSword$*` handler 是否真在目标类里，缺失逐条列出 |

接线：`star_sword_mod_1785589476.mixins.json` 增加 `plugin`；`ModBusEvents.onLoadComplete` 里**先跑 `MixinProbe::run` 再装 agent**（这样量到的是 Mixin 单独的结果）；`AgentStatus.describe()` 追加失败明细（最多 8 条）。

### 硬约束
这四个类里前三个会在 MC 类加载**之前**被 Mixin 装载，**只允许引用** `java.*` / `sponge` / `asm` / `log4j`。
引一个 `net.minecraft.*` 或 `ModLog` 就是 `NoClassDefFoundError`（`ModLog` 会拉进 `Entity` 与 `FMLEnvironment`）。
`MixinProbe` 是唯一可以碰 MC 的（它跑在加载完成之后）。

### 验证
三件套在**只含 log4j + mixin + asm、没有 MC/Forge** 的 classpath 上编译并运行，
断言"去重 / 丢弃外部失败 / 探针记录"三项 → `SIZE=3`、`RESULT=PASS`。

---

## 2. 阶段 2：权威血量状态 + 数据项代理（`hijack/authority/`）

### 问题
此前只在**外围**打补丁（`SynchedEntityData.set` 闸门、`assignValues` 钳制、`Connection.send` 改写），
三条路各自漏一条，且注入点会被外部实现整体覆盖。

### 改法
| 类 | 职责 |
|---|---|
| `OffHeapFloatStore` | 权威值放**堆外**（`ByteBuffer.allocateDirect` + XOR 掩码），防"扫堆找跟着变的 float"；槽满/不可用退堆内兜底。**与母本差异**：星-穹用 `sun.misc.Unsafe` + `core.unsafe`，本实现用 ByteBuffer 等价替代（已写在类注释里） |
| `AuthorityHealthStore` | 门面 + `asMap()` 适配，使 `PlayerDefense.REAL_HEALTH` 的 37 处调用**零改写**即换掉存储 |
| `HealthAuthority` | 读/写裁决单一出口：`read` / `renderRead` / `renderWrite` / `write` |
| `HealthDataItemProxy` | 继承 `SynchedEntityData.DataItem<Float>`，覆写 `setValue/getValue/value/isSetToDefault` |
| `HealthDataItemInstaller` | 把 `itemsById` 里的血量 `DataItem` 换成代理项 / 还原；幂等；诊断计数 |

**关键设计：存储只有一份**。值存在代理**继承来的** `value` 字段里，被替换的原始项只用于还原。
这样 `RawState.rawFloatOf` / `writeRawFloat` 这类"反射直读直写字段"的代码读写的也是同一份数据，不会漂移。

**`isSetToDefault()` 恒 false**：`ServerEntity` 用 `getNonDefaultValues()` 给新追踪者做全量同步，按
`isSetToDefault()`（比较 `initialValue` 与字段）过滤。代理安装时复制了当时的血量（如 20），满血时会被判为
default、整条血量项被省略 → 新追踪的客户端停在 `LivingEntity` 默认 1.0F，且等值写入又被 `set` 的
`Objects.equals` 短路、永不纠正。

### 验证
`AuthorityStoreProbe`：11 条断言（put/get/containsKey/size/旧值返回/NaN 即移除/remove/clear/entrySet）
全 PASS，`STORE=direct=true slots=4096 used=0 fallback=0`（堆外确实可用）。

---

## 3. 阶段 3：死亡语义解耦（2.2.0）

### 问题
原版链条是 `getHealth() <= 0 → isDeadOrDying() → tickDeath() → deathTime++`。
外部实现替换 `getHealth()` 后，这条链的**输入**就是它的公式；此前只能在 `deathTime` 已被推进之后
每 tick 复位，形成两个写者的拉锯（实测：`deathTime` 恒为 1，模型立正、背包打不开）。

### 改法
- `HealthJudgement`：唯一死亡判定。`isDeathBlocked`（受保护 **或** 有权威血量）为真时一律"没死"；
  否则按权威血量 `<= 0` 判。**绝不调用 `isDeadOrDying()`**（会递归）。
- `LivingEntityMixin.starSword$isDeadOrDying`：改为委托 `HealthJudgement`。
- agent：`GuardTransformer` 新增 `Position.BODY`，用 javassist `setBody` 把 `isDeadOrDying()` **整段替换**成
  `return AgentHooks.isDeadOrDying($0);`。替换失败自动退回 HEAD 注入（`Injection.fallback`）。

### 验证
在**真实 `LivingEntity` 字节码**上跑 `setBody`：`BEFORE bodyLen=15` → `AFTER bytes=126228`、`RESULT=PASS`。

### 边界
只解决"防御态下不再进死亡流程"。**不解决**"外部把 `getHealth()` 换成公式后，其它模组读到负数"——
那由读侧钩子（agent HEAD / Mixin RETURN）与 §6 的读取回退兜。

---

## 4. P0-1：`injectors.defaultRequire`（2.2.15）

### 问题
Mixin 0.8.5 的 `defaultRequire` 默认 **0**。`@At` 未命中时，`InjectionInfo.validateTargets()` 在
`requiredCallbackCount==0` 时**直接 return，无异常无日志**。所以 `required` 设成 true 也没用——
它只决定"已经产生的异常"是 ERROR 还是 WARN，**不负责发现未命中**。

| 组合（`@At` 未命中） | 结果 |
|---|---|
| `required:false` + `defaultRequire:0` | 完全静默（改前状态） |
| `required:true` + `defaultRequire:0` | 同样完全静默 |
| `required:false` + `defaultRequire:1` | 抛异常 → 捕获 → **一条 WARN**，不崩 |
| `required:true` + `defaultRequire:1` | 升级为 `MixinApplyError`，**崩启动** |

### 改法
`star_sword_mod_1785589476.mixins.json` 增加：

```json
"injectors": { "defaultRequire": 1 }
```

### 约束
- **必须与 `required:false` 成对**。谁把 `required` 改回 true，任一 `@At` 漂移会崩启动。
- 只对 default group 且未显式写 `require` 的注入器生效；`@Accessor/@Invoker` 不受影响。
- 允许"目标方法可选"的注入点应单独写 `@Inject(require = 0)` 豁免，**不要**把 `defaultRequire` 改回 0。

---

## 5. P0-3：生命周期里补装数据项代理（2.2.15）

### 问题
改前唯一的安装点在 `PlayerDefense.forceWriteVanillaHealth`，而它只在 `serverTick` 的 `isProtected`
分支里执行。**死亡 → 重生后通常已丢剑 → 保护为 false → 新 `ServerPlayer` 上完全没装代理**；
登录瞬间、跨维度同样如此。这些正是 `SynchedEntityData` 刚重建、外部最容易写进去的窗口。

### 改法
`ModForgeEvents`：
- `onPlayerLoggedIn`：在发剑 / `syncRealHealth` / 广播保护状态**之后**补装；
- `onPlayerClone`：`HealthDataItemInstaller.restore(旧对象)` + 给新对象补装；
- `onPlayerRespawn`：**放在 `isProtected` 早退之前**补装；
- 统一入口 `ensureAuthorityProxy(Player)`，门控 `SwordGuard.isProtected(p) || PlayerDefense.hasRealHealth(p)`。

**为什么门控而不是无条件装**：`onProtectionExpired` 会还原代理（见 §7），
若给所有玩家无条件装，未受保护玩家每 tick 会形成"装—还原"往返。未受保护玩家本就不需要裁决，
一旦进入防御态，`serverTick` 的安装点会接手。

---

## 6. P1-2：写入语义开关（2.2.16）

### 问题
改前 `HealthAuthority.renderWrite` 只拦"减血"，允许任意抬高且**无上限**；
而 `PlayerDefense.filterHealthWrite` 对有权威记录的写入**无条件 `return real`**。
两层口径不一致 → 只在代理层加开关会形同虚设。

### 改法
新增 `helper/HealthWritePolicy`（纯 JDK）：

| 口径 | 行为 | 触发 |
|---|---|---|
| 宽松（**默认**） | 只拦「减血 + 非法值」，**允许**合法抬高（治疗 / 重生回满 / 登录 `setHealth(20)`） | 不设属性 |
| 严格 | 只要存在锁定值就**忽略入参**，一律写锁定值 | `-Dstar_sword.health.lockStrict=true`（开发运行 `-PssLockStrict=true`） |

两层**同口径**委托：`HealthAuthority.renderWrite` 与 `PlayerDefense.filterHealthWrite`。
两种口径都做**上限夹取**（超过 `maxHealth` 收到上限），防止外部写 `1e9`。

### 语义细节（易被误解）
宽松模式下，"治疗"只能把血**抬到锁值之上**，不能停在锁值之下：锁 20 时被写 18 属于"减血"，会被压回 20。
这是刻意的——否则"锁 20 时被写 18"会漏过去。若要让"锁值以下也允许治疗"，需要区分写入来源，属于另一套语义。

### 验证
离线断言 14 条（宽松/严格各 7），覆盖：减血、等值、抬高、NaN、负数、治疗（上限 40）、无锁+上限。

---

## 7. P1.5：负血 / NaN 的读取回退（2.2.17）

### 问题（本轮的起因）
外部把**算式**（而不是值）写进血量，例如 `20 × (1 − 累加值)`，结果是负数。
改前三处都把负数"净化成 0"：`HealthAuthority.read`（→ `HealthJudgement.isDead`）、
数据项代理 `getValue`（→ HUD 与发包）、`AgentHooks` 兜底（→ 原样外泄）。
**而 0 在死亡语义里就是"死"**（`isDeadOrDying()` 就是 `<= 0`），于是"负血"被消毒成"零血"，
客户端照样判死、弹死亡界面、锁住移动——就是此前遇到的"客户端单方面认为玩家死了"。

### 改法
- 新增 `helper/HealthReadPolicy`：合法读数 = 有限且 `>= 0`（**0 合法**，真实死亡不受影响）；
  非法读数（负数 / NaN / 无穷）**不当作血量**，回退到「最近一次合法正值」→ 没有则 `maxHealth` → 最后才 0。
- `HealthAuthority` 增加 `LAST_GOOD`（每 UUID 的最近合法值）+ `forget/forgetAll`；
  `read` / `renderRead` 走该策略，只有合法正值才更新基线（0 不更新，避免"把尸体救活"）。
- 写入侧同步收紧：`HealthWritePolicy.resolve` 增加 `current` 参数——**非法写入保持原值**，不再落成 0。
- 决策点改用带裁决的读数：`PlayerDefense.clientRecoverDeathStateIfNeeded`、`AgentHooks.getHealth` 兜底。
- `HealthDataItemInstaller.forget/forgetAll` 同时清 `LAST_GOOD`。

### 验证
离线断言 14 条（宽松/严格各 14）含关键项：

| 用例 | 期望 |
|---|---|
| 负数 + 最近合法 20 | 20 |
| 负数 + 无最近合法 + 上限 20 | 20 |
| NaN + 最近合法 12 | 12 |
| 负数 + 锁定 20 | 20 |
| **真实死亡 0** | **0（不回退）** |
| 无锁写入负数 / NaN | 保持当前 20（不写成 0） |

### 边界
只治"值已落在血量项里"的情形。**不治**"外部把 `getHealth()` 整体换成算式"——那时负数没经过数据项，
仍取决于 agent BODY 替换、Mixin 的 `getHealth` 钩子、客户端死亡态恢复这三层是否还活着。

---

## 8. P2：逐钩子存活率（2.2.18）

### 为什么不走"字节码哈希巡检"
`Instrumentation.retransformClasses` / JVMTI `RetransformClasses` 交给 transformer 的**永远是类加载时的
initial 字节**，别人的 `redefineClasses` 不会反映进去。所以"抓当前字节再比对"永远等于基线 → 假绿灯
（星-穹的 `MethodIntegrityGuard` 正是这个形态：登记表恒空、`verifyAll()` 恒返回空、每 100 tick 打"无异常"）。

唯一可靠且不需要 native 的信号是**行为**：注入还在，方法就会被调用、计数就涨；被整体替换，计数立刻停。

### 改法
`helper/HookLiveness`：
- 每钩子一个 `LongAdder` + 最后调用时间；`mark()` 只做查表 + 自增 + 一次普通 long 写；
- `tickWindow()` 折算窗口增量：**先涨过、随后整窗口不涨** → 记静默；
- 只有 `getHealth / isDeadOrDying / isAlive` 参与静默判定（"世界在跑就必然被调用"）；
- 从未涨过**不算**静默（那属于"没装上"，由 `MixinProbe` 与 agent 注入日志负责）。

接线：`AgentHooks` 6 个入口打点（`getHealth` / `isDeadOrDying` / `isAlive` / `hurt` / `isRemoved` / `synchedSet`）；
`ModForgeEvents.onServerTick` 每 100 tick（≈5s）推进窗口，新静默时打一条
`HookLiveness[层=方法层] 受监控钩子进入静默，注入可能已被改写`；`AgentStatus` 增加 `逐钩子存活:` 一行。

### 验证
9 条断言（首窗口无静默、describe 含增量、整窗口无打点→3 个静默、非受监控钩子不误报、
恢复后解除、`reset` 后从未触发不报静默）全 PASS。

### 边界
只发现"注入不再被调用"，发现不了"注入还在但返回值被别的层改写"。判定有 ~5 秒延迟（一个窗口），是刻意的。

---

## 9. P3：外部通道趋势（2.2.19）

### 前置核查
`ForeignChannelGuard`（其它迭代）已经完成了"枚举 `itemsById` 全部 Float 项 + 白名单（血量/吸收量）排除 +
`decide()` 四段门控 + 灰度观测"。P3 只补**趋势/相关性**，**没有改动**它的判定。

### 改法
`helper/ChannelTrend`：
- 按（实体, 通道 id）累计：首值 / 末值 / 峰值 / 增量总和 / 连续递增次数 / 样本数 / 是否越线；
- **首次越过 1.0** 返回 `true`（`20 × (1 − v)` 在 `v > 1` 后为负），调用方立即打日志；
- `accumulators()`：连续递增 ≥3 次；`describe()`：按增量排序前 5 条；
- `pearson(x, y)`：**零方差返回 `NaN` 而不是 0**（锁血时血量是常数，必须得到"算不了"，否则被误读成"无关"）。

接线：`ForeignChannelGuard.neutralize` 记录趋势并在首次越线时打
`外部通道首次越过负值临界点（v>1 ⇒ 换算式变负） id=8 值=1.19｜外部通道趋势：…`；
`AgentStatus` 增加 `外部通道趋势:` 一行；退服 `resetOwner(uuid)`、停服 `reset()`。

### 验证
20 条断言（用实测 id=8 的走势 `0.03→1.19→3.28→8.58`）：首次越线正好发生在 1.19、首末峰值/样本数、
连续递增 3 次、增量总和、列入累加器、回落打断、另一通道不越线、Pearson ±1 与 NaN 语义、`resetOwner` 全 PASS。

---

## 10. P1-1 尾巴：数据项换装的**原版写锁**（2.2.20）

### 问题
`SynchedEntityData.itemsById` 不是并发容器，原版用**它自己的** `ReadWriteLock lock`（SRG `f_135346_`）
保护全部读写（`set` / `assignValues` / `get` / `getNonDefaultValues` / `packDirty`）。
我们直接 `items.put(...)` 换代理项，没有拿这把锁，可能造成：

1. **换装被写回孤儿项**：原版 `set` 在写锁下"先取项、再写值"，我们在两者之间换表项 →
   值写进即将丢弃的旧项，这次写入彻底丢失且不报错；
2. **遍历中改表**：`assignValues` 在写锁下遍历 `itemsById`，并发 `put` 可能让它看到结构性改动
   （`ConcurrentModificationException` 会被包处理层吞掉，那一包实体数据丢失）；
3. **可见性**：`Int2ObjectOpenHashMap` 非并发，无锁 `put` 不保证读者能看到新项 → 代理"像没装上"。

客户端确实有真并发：`onClientTick`（渲染线程）安装，同一条实体数据正被 netty 线程 `assignValues` 写。

### 改法
`HealthDataItemInstaller`：
- 反射 `SynchedEntityData.lock`（`f_135346_` / `lock`），`underItemsLock(...)` 在**写锁内**执行安装/还原；
- 锁内**重新** `items.get(id)`（消除 TOCTOU）；
- 拿不到锁字段时退化为直接执行（等价改动前行为），`lockAvailable()` 在状态里显示"缺失（退回无锁）"。

**必须是同一把锁**：`synchronized` 不行——那是另一把锁，与原版互不排除。

### 验证
`javap` 核对两个 jar（`forge-1.20.1-47.4.10.jar` 与 `-merged.jar`）均有
`private final Int2ObjectMap<...DataItem<?>> itemsById` 与 `private final ReadWriteLock lock`；
SRG 名 `f_135346_` 取自 `namedToIntermediate.tsrg`。
**并发收益无法离线证明**——需要实机。

---

## 11. 开关总表

| 属性 | 默认 | 作用 |
|---|---|---|
| `-Dstar_sword.log`（`-PssLog`） | 开发 true / 发布 false | 日志总开关（发布版默认全静默） |
| `-Dstar_sword.health.lockStrict`（`-PssLockStrict`） | **false** | 写入语义：false=允许合法抬高/治疗；true=锁定值压过一切 |
| `-Dstar_sword.foreignChannelGuard`（`-PssForeignGuard`） | `observe` | 外部浮点通道：`observe` 只观测 / `clear` 真中和 / `off` 关闭 |
| `-Dstar_sword.stats`（`-PssStats`） | 开发 true | 对账采样 |
| `-Dstar_sword.poc.agent`（`-PssAgent`） | `1`（发布版也开） | 自附加 agent |

---

## 12. 观测点（接手方先看这些）

| 位置 | 文本（片段） | 含义 |
|---|---|---|
| 启动横幅 | `星辉剑 <version> 已加载` | 版本确认 |
| 启动横幅 | `血量数据键 id=N` | 原版血量通道 id（会因其它模组定义同步项而推移） |
| 启动横幅 | `权威血量存储 direct=true slots=4096 …` | 堆外存储是否可用 |
| 启动横幅 | `写入语义 strict=false（允许合法抬高/治疗）` | §6 当前口径 |
| 启动横幅 | `Mixin 注入自检通过：handler N 个全部在目标类里（…）` | §1 自检；有缺失会逐条列出 |
| 运行日志 | `血量数据项已换成代理项：读/写/发包三处收口` | 每个玩家一次，代理真装上了的直接证据 |
| 运行日志 | `外部通道首次越过负值临界点（v>1 ⇒ 换算式变负）` | §9 越线（这是"公式即将变负"的预警） |
| 运行日志 | `HookLiveness[层=方法层] 受监控钩子进入静默` | §8 某注入疑似被替换 |
| `/starsword agent status` | `逐钩子存活:` / `外部通道趋势:` / `权威血量存储 … 换装写锁=` | 分别对应 §8 / §9 / §10 |

---

## 13. 不要改的契约（给接手方的硬约束）

1. **0 是合法的死亡读数**。`HealthReadPolicy` 只把负数/NaN/无穷当非法；把 0 也纳入回退会制造"打不死的尸体"。
2. **写侧用严格小于**（`incoming < locked`），不要改成 `<=`：那会连原版"血量归零 → 死亡"的收尾一起拦住。
3. **读侧绝不调用 `getHealth()`**：既可能被替换，也会与代理/钩子形成递归。
4. **`HealthJudgement.isDead` 不调 `isDeadOrDying()`**：会无限递归。
5. **代理只存一份值**（继承字段）。不要改成"值留在原始项、靠覆写同步"——任何一处直写字段就会漂移，
   而 `RawState` 的反射直读直写依赖这一口径。
6. **`injectors.defaultRequire:1` 必须与 `required:false` 成对**。
7. **换装/还原必须在原版写锁内，并在锁内重读**。
8. **`AgentHooks` 的方法名是 ABI**：它们被写进注入的字节码里，改名会打断已注入的类（`className.method` 都写死）。
9. **`mixinmon/` 前三个类只许引用 `java.*` / `sponge` / `asm` / `log4j`**。

---

## 14. 未验证项（如实列出）

1. **没有跑过游戏**：所有验证都是编译 + 离线断言 + 代码/字节码阅读。
2. `agent` 的 `Position.BODY`（整段替换 `isDeadOrDying`）在真实运行期是否触发 `VerifyError` 只做了
   字节码层面的 `setBody` 验证，**没有跑过 JVM 的类校验**；失败会退回 HEAD 注入（有 fallback），但没实测。
3. 写锁（§10）的并发收益无法离线证明。
4. `defaultRequire:1` 上线后**可能出现新的 WARN**——这是预期行为（把此前静默的 `@At` 未命中暴露出来）。
5. 负血回退（§7）只在"值进了数据项"时生效；"方法体被整体替换"那条路仍依赖三层注入存活。

---

## 15. 构建与验证命令

```powershell
# 本仓库 build.ps1 在受限执行策略下会被拒（"not digitally signed"），用 gradlew 直调：
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot'
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'   # 让 javac 报英文错误，便于读日志
.\gradlew.bat clean build --console=plain
```

或：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 clean build
```

产物：`build\libs\star_sword_mod-<mod_version>.jar`（当前 2.2.20，约 1.14 MB）。

离线断言脚本（临时目录，不随仓库分发）：
`%TEMP%\writepolicy\HealthPolicyProbe.java`（读写策略）、`%TEMP%\hooks\HookLivenessProbe.java`、
`%TEMP%\hooks\ChannelTrendProbe.java`、`%TEMP%\mixinlogprobe\MixinLogProbe.java`、
`%TEMP%\authorityprobe\AuthorityStoreProbe.java`、`%TEMP%\bodyprobe\BodyReplaceProbe.java`。
它们只依赖对应文件 + 少量 jar，可以脱离 MC/Forge 单独 `javac` + `java`。

---

## 16. 文件清单（本会话新增 / 修改）

**新增**
- `src/main/java/.../mixinmon/MixinFailureRegistry.java`
- `src/main/java/.../mixinmon/StarSwordMixinErrorHandler.java`
- `src/main/java/.../mixinmon/StarSwordMixinConfigPlugin.java`
- `src/main/java/.../mixinmon/MixinProbe.java`
- `src/main/java/.../hijack/authority/OffHeapFloatStore.java`
- `src/main/java/.../hijack/authority/AuthorityHealthStore.java`
- `src/main/java/.../hijack/authority/HealthAuthority.java`
- `src/main/java/.../hijack/authority/HealthDataItemProxy.java`
- `src/main/java/.../hijack/authority/HealthDataItemInstaller.java`
- `src/main/java/.../hijack/authority/HealthJudgement.java`
- `src/main/java/.../helper/HealthWritePolicy.java`
- `src/main/java/.../helper/HealthReadPolicy.java`
- `src/main/java/.../helper/HookLiveness.java`
- `src/main/java/.../helper/ChannelTrend.java`

**修改**
- `src/main/resources/star_sword_mod_1785589476.mixins.json`（`plugin` / `required:false` / `injectors.defaultRequire`）
- `src/main/java/.../mixin/LivingEntityMixin.java`（`isDeadOrDying` 委托 `HealthJudgement`）
- `src/main/java/.../agent/AgentHooks.java`（`isDeadOrDying` 实现、读侧兜底走 `renderRead`、6 处 `HookLiveness.mark`）
- `agent/src/main/java/.../GuardTransformer.java`（`Position.BODY` + `setBody` + fallback）
- `src/main/java/.../event/ModBusEvents.java`（`MixinProbe::run`）
- `src/main/java/.../event/ModForgeEvents.java`（登录/克隆/重生安装、退服还原、停服清理、钩子窗口）
- `src/main/java/.../hijack/PlayerDefense.java`（`REAL_HEALTH` 换存储、`filterHealthWrite` 走策略、
  客户端恢复改走 `HealthAuthority.read`、生命周期 `restore`/`forget`）
- `src/main/java/.../helper/AgentStatus.java`（新增若干状态行）
- `src/main/java/.../helper/RawState.java`（`itemsOf`）
- `src/main/java/.../helper/ModLog.java`（启动横幅两行）
- `build.gradle`（`star_sword.health.lockStrict` 运行期属性）
- `README.md`（日志 / 注入自检 / 权威血量 / 死亡语义 / 架构取舍 五节）
