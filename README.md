任何情况，我们不会知道对方的mod名，包名，文件，源码，其只作为假想敌而存在。


Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
{this does not affect your code} and then start the process again.

Mapping Names:
============
The MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

MDG Legacy:
==========
This template uses [ModDevGradle Legacy](https://github.com/neoforged/ModDevGradle). Documentation can be found [here](https://github.com/neoforged/ModDevGradle/blob/main/LEGACY.md).

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/

Building (Windows, JDK 17)
========
This mod targets Minecraft 1.20.1 / Forge 47.x, so it must be built with **JDK 17**.
Gradle itself is pointed at a local JDK 17 in `gradle.properties`
(`org.gradle.java.home`), because Gradle 8.14.x cannot run on newer JDKs such as 25.

To build, use the helper script (it also puts the Gradle launcher on JDK 17):

```
.\build.ps1 clean build
```

The mod jar is written to `build\libs\star_sword_mod-<version>.jar`.
Set `STAR_SWORD_JDK17` if JDK 17 lives somewhere else.

Once every dependency is cached, `.\build.ps1 --offline build` skips all remote
lookups and finishes in a few seconds; a plain build re-checks remote metadata and
takes about a minute because `maven.neoforged.net` is unreachable here.

`gradle/offline-pins.gradle` is applied automatically. It exists for networks that
cannot reach `maven.neoforged.net`: it pins the dependency versions Forge requests
dynamically and serves the few missing artifacts from `gradle/local-maven`.
On a machine with unrestricted network access it resolves to the same versions and
changes nothing.

日志 / Logging
========本模组只有一个日志出口 `helper/ModLog.java`：攻击 / 防御 / 血量 / 背包 / 星辉剑 / 网络 / 解析 / agent /
统计 / 初始化 / 测试，代码里不再有散落的 `System.out.println`；每行统一为
`[分类] 内容 | 调用层: 类.方法:行 <- …`，便于定位是哪个调用写的。

**只在开发环节启用**，按运行环境自动判定，不需要改代码也不需要加参数：

| 运行环境 | `FMLEnvironment.production` | 行为 |
| --- | --- | --- |
| `runClient` / `runServer`、IDE 启动 | `false` | 全开：log4j（控制台 + `logs/latest.log`，logger 名 `StarSword`）**和**独立文件 `logs/star_sword_mod_1785589476.log` |
| 构建出的 jar 在正式环境运行 | `true` | 全关：不写 latest.log、不创建独立日志文件，热路径连 UUID / 实体描述都不拼 |

覆盖开关（运行时 `-D`；开发运行也可用 Gradle `-P`）：

* `-Dstar_sword.log=true|false`，或 `-PssLog=false` —— 发布版临时开 / 开发环境彻底关；
* `-Dstar_sword.stats=true|false`，或 `-PssStats=false` —— 单独控制 `ReconcileStats` 的对账采样，
  默认跟随日志开关。

约定：正常生效、每 tick 都会检查的行为走 `*Throttled`（同一个 key 每 40 tick 最多一行）；异常 / 回退 /
被拒绝等事件走非限频方法立即输出；`AgentStatus` 的全服播报属于玩法逻辑而不是日志，两种环境都照常发送。

`agent/` 子项目（嵌入 jar 的 `star_sword_agent.jar`）和 attach 辅助进程同样跟随这个开关：mod 在静态
初始化时把最终决定写进 `star_sword.log.active`，并作为 JVM 参数传给辅助进程，因此发布版控制台不会出现
`[ss-agent]` 行。`attacker/` 是手动运行的测试工具，不嵌入 jar，不受影响。

注入自检（Mixin 到底有没有生效）
========
移植自星-穹工程的 mixin 三件套（`mixinmon/` 包），补的是「注入失败只 WARN、没人看得见」这个盲区：

| 组件 | 作用 |
| --- | --- |
| `MixinFailureRegistry` | Mixin 自报失败的记录表（去重、只记本模组、最多 256 条） |
| `StarSwordMixinErrorHandler` | 全局错误处理器，把 `onPrepareError` / `onApplyError` 抄送给上表；**返回 null，绝不把 WARN 抬成崩溃** |
| `StarSwordMixinConfigPlugin` | 在 mixin 配置被选中时（早于任何注入）注册上面的处理器；并在系统属性里留下 `star_sword.mixin.plugin=ok` |
| `MixinProbe` | 在 `FMLLoadCompleteEvent` 直接检查目标类上有没有对应的 `starSword$*` 方法，逐条报告缺失 |

三点约定：

* mixin 配置因此改为 `"required": false`——注入失败从「崩游戏」降级为「记一条 WARN + 进失败表」，
  是否可用由自检回答，而不是由崩溃回答；
* 启动横幅会打印一行 `Mixin 注入自检通过：handler N 个全部在目标类里（…）`；有缺失时逐条列出缺哪个；
* `/starsword agent status` 的末尾追加 `Mixin 注入自检` 与失败明细（最多 8 条）。

三件套只允许引用 `java.*` / `sponge` / `asm` / `log4j`：它们在 MC 类加载之前就被 Mixin 装载，引一个
`net.minecraft.*` 就是 `NoClassDefFoundError`。`MixinProbe` 是唯一可以碰 MC 的（它跑在加载完成之后）。

权威血量状态（语义层单一出口）
========
移植自星-穹工程的 `health` 包，落在 `hijack/authority/`：

| 组件 | 作用 |
| --- | --- |
| `OffHeapFloatStore` | 权威值放堆外（`ByteBuffer.allocateDirect` + XOR 混淆），外部模组「扫堆找跟随变化的 float」不再一击命中；槽满/不可用时退回堆内兜底 |
| `AuthorityHealthStore` | 权威状态的门面，并提供 `asMap()` 适配，`PlayerDefense.REAL_HEALTH` 的 37 处调用无需改写即可改用新存储 |
| `HealthAuthority` | 读/写裁决：读优先锁定值，写拒绝「非法值」与「低于锁定值」（严格小于，刻意不是 `<=`，否则会拦住原版归零死亡） |
| `HealthDataItemProxy` + `HealthDataItemInstaller` | 把 `SynchedEntityData.itemsById` 里的血量数据项换成代理项，使**读、写、发包**三处天然收口，不再只靠外围补丁 |

代理项只存一份值（继承字段），因此 `RawState` 的反射直读直写看到的也是同一份数据；原始数据项保留用于
`restore`，还原前会先把当前值写回去。

协议层（`OutgoingFilter` / `handleSetHealth` 改写）从此只作**显示对齐**，不再是防线：数据项代理的
`value()` 就是发包载荷，原版 `packDirty` 发出去的天然是权威值。

死亡语义解耦（机制层）
========
原版链条是 `getHealth() <= 0 → isDeadOrDying() → tickDeath() → deathTime++`。外部实现替换 `getHealth()`
后，这条链的输入就是它给出的读数；此前只能在 `deathTime` 已被推进之后每 tick 复位，形成两个写者的拉锯
（日志实测：`deathTime` 恒为 1，模型立正、背包打不开）。现在把判定从那条链上摘出来：

| 组件 | 作用 |
| --- | --- |
| `HealthJudgement` | 唯一死亡判定：防御态（受保护 或 有权威血量）一律「没死」，否则按权威血量 `<= 0`；**绝不调用 `isDeadOrDying()`**（会递归） |
| `LivingEntityMixin.starSword$isDeadOrDying` | Mixin 兜底，改为委托 `HealthJudgement` |
| agent `Position.BODY` | `GuardTransformer` 用 javassist `setBody` 把 `isDeadOrDying()` **整段替换**成 `return AgentHooks.isDeadOrDying($0)`，不再依赖注入点存活；替换失败自动退回 HEAD 注入 |

于是 `LivingEntity.tick()` 里的 `if (isDeadOrDying()) tickDeath();` 不再执行，`deathTime` 稳定为 0，
客户端不再同时扮演「死亡」和「复活」两个写者。

### 覆写漏网：`LocalPlayer.tickDeath`（实测 `deathTime` 恒为 1 的根因）

`LocalPlayer` **覆写**了 `tickDeath()`，而 Mixin 只织入父类方法：

```java
protected void tickDeath() {          // net.minecraft.client.player.LocalPlayer
    ++this.deathTime;
    if (this.deathTime == 20) this.remove(Entity.RemovalReason.KILLED);
}
```

所以 `LivingEntityMixin` 的取消对它**完全无效**：客户端每 tick 把 `deathTime` 推到 1，我们每 tick 再清零，
日志里就是 `deathTime=1` 反复出现（`clientState` 采样在实体 tick 之后、清零之前，看到的必然是 1）。
这是与 `ServerPlayer.die` 同类的"覆写不调 super"陷阱。现在两层都补上：

| 层 | 位置 | 行为 |
| --- | --- | --- |
| agent 规则 | `local_player.tickDeath` → `m_6153_` | HEAD 注入，`shouldSuppressDeath` 为真即 `return` |
| Mixin 兜底 | `LocalPlayerMixin.starSword$guardLocalTickDeath` | HEAD `cancel()` + `PlayerDefense.clearDeathFieldsIfProtected`（清 `dead`/`deathTime`/`hurtTime`/`hurtDuration`，不写血量） |

只声明 `LocalPlayer`，不给 `Player`/`ServerPlayer` 加这两条——它们没覆写该方法是继承来的，
加了只会让对账多出两条必然的"方法不存在"。

**这个漏网是断言抓出来的，不是靠读代码猜的**：`AgentBundleTest` / `MappingTableTest` 的内置表比对
直接报 `LocalPlayer.tickDeath 期望=m_6153_ 实际=tickDeath`——如果不补映射，生产环境这条规则会静默
变成"方法不存在"，客户端行为与开发环境不一致。

客户端每 tick 的兜底日志也改了口径：打印**清零后**的值，并给出本窗口清零次数
（`原deathTime=N -> 0 本窗口清零=K次`）。持续 `K>1` 说明还有写者在推进它，`K==1` 说明只是一次性残留。

## 边界与盲区（明确取舍，不是能力清单）

**1. 观测点在被调用方，不在调用方。** 我们的返回值裁决发生在 `getHealth()` / `isDeadOrDying()` /
`isAlive()` **方法体内**。如果外部实现不改被调用方，而是用 `@Redirect` / `@At("INVOKE")` 改**调用方**
对这几个方法的调用点（忽略返回值、换成它自己的值），我们的裁决会被绕过——不是裁决错了，而是裁决点
不在那条调用路径上。当前判断：我们面对的实现走的是"替换被调用方"，所以**不补**这类干预；这是
**基于对手形态的取舍**，不等于 `REDIRECT` 不重要。若要覆盖，方向是"观测型 `@At(INVOKE)`"（只记录谁在
调、返回值被怎么用），而不是用 `@Redirect` 去干预。

**2. 方法体层的一切，都对抗不过"重定义 + 重放"。** BODY 替换、HEAD 早退、Mixin 注入都建立在"我们的
字节码是最后写入的"这个前提上。外部实现若在 `redefineClasses` 层反复重放，我们只能靠 watchdog
定期抢回，抢不回的窗口里所有方法级防线都是空的——真正稳定的只剩数据层（权威存储、数据项代理、
按值中和）。

**3. 注入点与实际生效不是一回事。** agent 侧的 `insertAfter`（TAIL）在本工程实测**从未成功**：
`getHealth` 上的 insertAfter 每次 retransform 都 `VerifyError`，整类注入回滚后降级重试。因此
`getHealth` 的返回值改由整段替换（BODY）承载；`retransform` 也**禁止新增方法**，所以"把原方法
另存一份"这条路走不通。

**4. 失败降级的粒度是"类 × 注入条"，不是整张表。** 早先的实现是全局缩表：任一目标类
`retransform` 失败就把整张注入表砍成"只留 `getHealth` HEAD"，`SynchedEntityData` 写闸门、
`Connection` 发包过滤、`Entity.remove` 白名单会一起消失，且对之后所有类和所有 watchdog 轮永久生效。
现在降级按类隔离：先逐条剔除定位（找出那一条坏的，其余全保住），再退到去掉 BODY，再退到只留
`getHealth` HEAD，最后让这个类保持原样；其它类完全不受影响。`/starsword agent status` 的
"声明 / 实际"对账可以据此判断哪个类是降级态。

**5. `setHealth` 入参归一的执行者是 agent，Mixin 是兜底。** 判据只有一份
（`AgentHooks.filterSetHealthArg`，内部归一 NaN/无限后交给 `PlayerDefense.filterHealthWrite`）：
agent 在方法 HEAD 就地改写 `$1`；Mixin 的 `@ModifyVariable` 只在 agent **没有实际接管**这个入口时
才调用同一方法（依据 agent 每轮上报的"已生效注入"表），避免同一次写入被过滤两次、
`countWrite` 翻倍提前触发高频写入锁定。

观测与对账（P0）
========
`/starsword agent status` 多两行，都是"运行时事实"，不是手写声明：

```
注入对账: 声明 24 条 / 已应用 12 条 / 已剔除 1 条；剔除: living.getHealth
未知浮点通道: mode=observe，命中 1 条通道 [id=31 值=0.5 ×12 字段=0]
```

* **声明 / 已应用 / 已剔除**由 agent 每轮（启动一次 + 每个 watchdog 轮）上报，覆盖式同步。
  `已剔除` 就是单类降级剔掉的那些条目；`方法不存在` 是"规则声明的覆写这个类没写"（子类继承时很常见）。
  四个数字加起来才等于声明数——只报"声明 / 已应用"的话，差额永远说不清是没装上还是没这个类。
* 三个数字都是**可观测的事实**，没有"跑了多少次 default"这种计数——如果外部实现把我们的注入
  整段抹掉，我们的计数器根本不会被执行，`default` 永远不会自增。能判断的只有"装上了却长期 0 命中"，
  那是 `AgentStatus` 已有的 hookCalls 停滞判定的推广。

未知浮点通道：默认只观测，不清零

| `-Dstar_sword.foreignChannelGuard` | 行为 |
| --- | --- |
| `observe`（**默认**，兼容旧值 `dry` / `record`） | **只记录**：通道 id、原值、会清零的字段数、玩家名、判据理由、端；不改写任何数据 |
| `clear`（兼容旧值 `on` / `true`） | 清零通道并把按值匹配的浮点字段一并清零 |
| `off`（兼容旧值 `false`） | 关闭 |

默认从 `on` 改成 `observe` 是被实测逼出来的：外部通道语义不可知，按值清零会静默清掉别的模组的合法资源条；
而且清零对"对方已把累加量锁存进自己字段"的实现无效——TTM 的 soul=500 被清回 0，胜负并不靠它，靠的是
字节码 BODY 裁决与移除闸门。真正要拦的是**写入**，那已经由下面的状态锁硬拒承担。

通道处置本身也收敛了：判定产出 `ForeignChannelGuard.Decision`（布尔 + 理由），写值只在 `clear` 模式下
由 `writeNeutralized` 执行。

一份判据，三处共用
========
外部浮点通道的处置原本分散在三处、判据各自演化：每 tick 中和、`assignValues` 收包过滤、包级
`ClientboundSetEntityDataPacket` 过滤。其中包级那处只认血量键；`assignValues` 那处的门控
（`READ_TAMPERED`）在"客户端 + agent 生效"时恒为假——于是客户端只剩"下一 tick 才清零"的滞后兜底，
留下一个 tick 的窗口。

现在三处共用 `ForeignChannelGuard.decide`，**四段门控缺一不可**：

| 段 | 条件 | 为什么 |
| --- | --- | --- |
| 1 | 权威值由本进程持有 | 服务端恒成立；客户端只有本地玩家有权威值，拿别人的值去判定就会清掉他们的法力/口渴/温度条 |
| 2 | 受保护 | 只有保护期间才接管 |
| 3 | 有可用权威值 | 没有基准就无从"压回" |
| 4 | 非白名单（血量 / 吸收量）且非血量镜像 | 动手前最后一道护栏；白名单不完整时整体不动（fail-closed） |

包级 / `assignValues` 只改**同步项的值**；通道背后按值匹配的实体字段仍由每 tick 的 `neutralize`
（仅 `clear` 模式）清理，两者互补而不是替代。这两处收包过滤现在共用 `filterIncomingData`，对非白名单
浮点项按模式分三种处理：`observe` 原样放过、`clear` 压回 0、状态锁硬拒则**把条目从包里摘掉**（既不写
也不清零）。共用一份实现是为了避免"服务端包已改、客户端没改"的半同步。

状态锁：原版状态值硬锁（默认只作用于客户端）
========
**只作用于星辉剑绑定的那个玩家实体**：门控是 `SwordGuard.isProtected`（NBT 主人在背包，或剑离开
不超过 20 秒宽限），别的玩家/实体一律不碰。

受保护期间**不再兼容任何外部状态语义**，四个原版值每 tick 钉回满值：

| 状态 | 锁定值 | 来源 |
| --- | --- | --- |
| 血量 | 20 | `PlayerDefense.lockHealth` → 权威存储 + `setHealth` |
| 最大生命 | 20 | `Attributes.MAX_HEALTH` baseValue（不锁它，`setHealth(20)` 会被夹住来回抖） |
| 护甲 | 原版属性上限（30） | `((RangedAttribute) Attributes.ARMOR).getMaxValue()`，不写死数字 |
| 饱食度 / 饱和度 | 20 / 20 | `FoodData.setFoodLevel` / `setSaturation` |

**作用域默认是 `client`：服务端逻辑保持不变**（服务端血量本来就由既有权威存储 + 写闸门负责，
护甲与食物不动）。客户端三个落点，缺一个就会被服务端的包改回去：

1. `ModClientForgeEvents.onClientTick` 每 tick 收口（`applyOn(player, true)`）；
2. `ClientPacketListenerMixin.handleSetHealth`：食物/饱和度不跟随包，直接写锁定值；
3. 血量走 `SyncRealHealthPacket` + 数据项代理，护甲靠每 tick 钳 `AttributeInstance.getBaseValue()`。

| `-Dstar_sword.stateLock` | 行为 |
| --- | --- |
| `on`（默认） | 按 `scope` 锁值 + 写侧硬拒 |
| `off` | 完全关闭，恢复原版行为 |

| `-Dstar_sword.stateLock.scope` | 行为 |
| --- | --- |
| `client`（默认） | 只改客户端那份，服务端不加任何新逻辑 |
| `both` | 两端都锁（服务端也会改护甲/食物） |

**写侧硬拒与锁值的范围是刻意分开的**：锁值只上客户端，拒绝写入两端都要。判据只有一份
（`PlayerDefense.rejectStateWrite`），由 mixin 与 agent 两条执行路径共用——agent 生效时 mixin 会按
"执行者唯一"让位，如果硬拒只写在其中一处，正式环境就会静默失效。

对手的写入实测可以超过 **100 次/秒**，所以热路径**不打日志**：只做一次 map 查表 + 一次原子自增，
日志由 `flushRejectedWrites` 每 40 tick 汇总。汇总**按通道聚合**，两行：

```
状态锁[层=SynchedEntityData.set] 首次拒绝该通道的写入 玩家=per2(...) id=20 值=-69.0 本窗口次数=1
状态锁[层=SynchedEntityData.set] 本窗口拒绝外部状态写入 玩家=per2(...) 共123次 次数=123 通道数=1 top=id=20×123(值=-69.0)
```

- **首现行**（每通道每会话一次）替代了被硬拒提前 return 遮蔽的 `通道探测`：只要某个通道被拒过，
  就一定能看到它的 id 与值；
- **窗口汇总**按次数降序取 top-3（`MAX_REJECT_CHANNELS_SHOWN`），其余折叠成 `…`，同时给出真实通道数；
  次数相同时按通道 id 升序，保证日志可重复、回归可断言；
- **空写不计**：写入值等于权威血量（`filterHealthWrite` 归一后的 20.0）或等于该通道当前原始值时，
  直接放行且不计数。没有这条，UOM 一轮会把自己归一后的 20.0 记成每窗口 120~171 次"拒绝"，
  真正来自对手的写入会被淹没。

聚合/格式化是纯函数（`helper/RejectStats`），由 `tools/RejectStatsTest.java` 离线断言
（排序、折叠、同分 tie-break、非法 `maxShown`）：
`/starsword agent status` 的 `状态锁:` 一行会打印作用域、目标值与写侧策略。

**纯客户端锁的边界（必须知道）**：服务端仍然会照常掉血、判死、触发死亡流程——客户端锁只保证你
本地看到的血条/护甲/食物不被改坏、本地模拟不被冻死。真正阻止"服务端也死"的仍是 agent 的
`die`/`tickDeath`/`remove` 闸门与权威血量存储，那些是既有逻辑。要连服务端的护甲/食物一起锁，
把 `scope` 改成 `both`。

**注意：日志里的 `id=` 不是原版常量。** Forge 的 `SynchedEntityData.defineId` 用一条按继承链单调递增的
id 池，任何在 `Entity` / `LivingEntity` / `Player` 上多定义几个同步项的模组（包括在 `<clinit>` 头部插注入
的实现）都会把后续 id 整体推移。实测整合包里**血量键是 14、吸收量是 41**，而不是原版序号下的 9 / 15；
于是"`已中和未知浮点通道：id=8`"里那个 8 根本不是血量，而是排在它前面的某个别的通道。代码一律按
accessor 对象比较、从不写字面 id，`/starsword agent status` 的 `同步键` 一行会打印真实 id，
避免把 id 当常量误读。

方法层心跳
========
"我们知不知道自己被覆盖"以前靠 `READ_TAMPERED`（读取返回值与权威值矛盾）。这个信号在客户端 +
agent 生效时是死的：两个置位点分别随 `getHealth` 方法体整段替换、agent 的 BODY 注入一起消失，
第三个只在服务端 tick 里跑。

现在改用两条互相独立的心跳（`helper/MethodLayerState`）：

* **事件侧**：客户端 tick 每 tick 走一步，走 Forge 事件，与我们的字节码无关；
* **字节码侧**：`AgentHooks.hookCallCount()`，只在 agent 的 `getHealth` 整段替换真的被调用时增长。

每 100 tick（约 5 秒）比一次：事件侧在涨、字节码侧不涨 → 注入已不在（被覆盖或从未应用）。
判据不依赖任何血量数值，也不会因为"这次没把值改坏"而误判。`/starsword agent status` 的
`方法层心跳` 一行就是它。

为什么不用 `isAlive` 这种更热的方法当心跳：外部实现只替换 `getHealth` 时，`isAlive` 的注入仍会正常跑，
拿它当心跳会得出"方法层在"的错误结论。

规则表（P2）
========
`agent/Rules.java` 是"往哪个方法里写什么"的唯一声明处。一条规则 = `id` + 目标 + 方法 + 描述符 +
`Kind` + javassist 片段模板；transformer 只负责"怎么落"：

| `Kind` | 落法 | 用途 |
| --- | --- | --- |
| `REPLACE` | `setBody`，整段替换（片段必须自己 `return`） | `getHealth` / `isDeadOrDying` / `isAlive` |
| `HEAD` | `insertBefore`，早退或守卫 | `hurt` / `die` / `set` / `save` 等 |
| `WRAP` | `insertBefore` + 赋值 `$1`，不接管方法体 | `setHealth` 入参归一、`send` 包改写 |
| `OBSERVE` | `insertBefore` + `noteObserved(id, $0)`，原逻辑照常执行 | 灰度：先量命中次数再决定要不要接管 |

* 加一条覆盖 = 表里加一行 + 一个桥接方法，不再改 transformer；
* 单类降级与对账用的都是规则 `id`（`living.getHealth`、`synched.set`……），不是下标——表里增删规则
  不会让降级结论张冠李戴；
* `OBSERVE` 的命中数在 `/starsword agent status` 的 `观察规则命中` 一行；
* **等价性有字节级证据**：改造成规则表前后，7 个目标类（`LivingEntity` / `Entity` / `Player` /
  `ServerPlayer` / `LocalPlayer` / `SynchedEntityData` / `Connection`）生成的补丁字节 SHA256 完全一致。

顺带修了一个真实缺陷：补丁缓存的键原本只有"类名 + 输入字节"，不同规则表（watchdog 重建的实例、
灰度规则集、自定义测试表）会命中同一份旧补丁。现在缓存键里带规则表指纹，这类串味不会再发生。

实体完整性观测（P0，只观测）
========
"实体到底是被<b>移除</b>（removalReason 被写、从引擎结构里摘掉）还是被<b>跳过</b>（tick 不再调用它，
但没被移除）"——这两种情况的解法完全不同，而此前没有这个事实。`EntityIntegrityProbe` 每 40 tick
在<b>服务器 tick</b> 采样一次（放在玩家 tick 里采不到路径 B：实体被跳过时玩家 tick 根本不发生），
对每个在线的、曾经受保护过的玩家记录：

* `Entity.removalReason`（`f_146795_`）——反射直读，绕开我们的 Mixin 与 agent 注入；
* 引擎是否仍认<b>这个实例</b>：`EntityLookup.byId / byUuid` 做**身份比较** `map.get(id) == entity`，
  不是 `containsKey`——实体 id 会被复用，玩家重生后是同 UUID 的新实例，只查 key 会误判；
* 是否还在 `EntityTickList` 与区块 section 里；
* 本窗口我们压制了几次 `isRemoved()`（按玩家 UUID 计数）。

判读：`removalReason` 非空 + 不在查询表 = **A 被移除**；理由非空但还在表里 = **REASON_ONLY**；
理由为空 + 不在表里 = **UNLINKED**；理由为空 + 在表里但不在 tick 表 / tickCount 停滞 = **B 被跳过**。

服务端只是半边。**"立正 / 空血"最常见的组合是服务端正常、客户端本地实体坏了**，所以客户端也有对等
观测（`ClientVerdict`：`OK` / `REMOVED` / `MISSING`，即本地玩家还在不在本地世界、有没有 removalReason）。

日志策略（这一版改过，之前的实现只在"结论变化"时打一行，一整段会话只剩一行，分不清"一直正常"和
"探针没跑"）：

* 异常（REMOVED / SKIPPED / REASON_ONLY / UNLINKED / 客户端 REMOVED·MISSING）**持续期间每 2 秒打一行**；
* 每 60 秒打一行**存活汇总**（`实体完整性 汇总 …`），证明采样还在跑；
* `/starsword agent status` 的 `实体完整性` 一行同时给服务端计数与客户端结论。

读不到字段一律记 `UNKNOWN`——把"读不到"当成"不在表里"会把正常情况误判成路径 A。所有读取都是纯读，
不触发实体/区块加载。

出站过滤的两条止损（P2）
========
`OutgoingFilter` 新增两类包的处理：

| 包 | 处理 |
| --- | --- |
| `ClientboundRemoveEntitiesPacket` | 剔除受保护玩家 id；剔空则**丢包**；否则重建包 |
| `ClientboundPlayerCombatKillPacket` | 目标是受保护玩家则**丢包**（客户端死亡界面） |

丢包用"返回 `null`"表示，且注入片段必须在赋值后 `if ($1 == null) { return; }`：`Connection.send`
是 `void`，把 null 交给原方法会在 `ConnectionProtocol.getProtocolForPacket` 的 `Packet.getClass()`
处直接 NPE。这一段已经用反汇编确认过（`filterOutgoing` → `astore_1` → `if_acmpne` → `return`）。

**边界**：只覆盖 `Connection.send`。直接 `channel.writeAndFlush(...)`、或把这两个包塞进
`ClientboundBundlePacket` 的内层，都会绕过。`ClientboundPlayerCombatKillPacket` 只压客户端死亡界面，
聊天栏的死亡消息由 `PlayerList.broadcastSystemMessage` 另发，不受影响。

架构层（进程隔离）的取舍
========

按评估**不做**独立进程/权威服务：伤害发生在服务端进程内，跨进程权威只能防客户端被改，防不了同类 mod
在服务端进程里改。可取的子集是「把重定义通道独立成 agent」——这条本来就有（`star_sword_agent` 由
`FMLLoadComplete` 自附加，不依赖 Mixin 注入位），本轮把 `isDeadOrDying` 的整段替换交给它承载。

读口防御：方法体被整体替换时
========背景：外部模组用外部的运行期重定义器 + 外部的类变换器，在类加载期把
`LivingEntity.getHealth()` 等方法体整体替换：返回值取原方法与「按外部私有状态换算出的读数」中的
较小者，生效条件是外部私有累加量越过阈值，**完全不看**本模组的保护状态。
写入侧（setHealth 过滤 / 内部血量 / DeathTime / 实体移除）全部赢了也没用——所有消费者读到的都是被
替换后的返回值，负数一旦落盘就是死循环。

现在的应对分四层，全部不依赖"报告值与内部权威值矛盾"这类值语义（那条路径随 `getHealth` 方法体被
整段替换一起失效过，已删除）：

| 层 | 位置 | 行为 |
| --- | --- | --- |
| 方法层接管 | agent 的 `REPLACE` 规则（`Rules` 里 `living.getHealth` / `living.isDeadOrDying` / `entity.isAlive`）→ `AgentHooks.getHealthBody` 等 | 防御态直接给权威读数与判定；方法体里不再有原版 `getHealth()` 读取，外部实现替换方法体也不会让旧公式复活 |
| 数据项读路径 | `HealthDataItemProxy.getValue` → `HealthAuthority.renderRead` | 仍然通过同步项读血量的路径会看到权威值 |
| 数值兜底 | `AgentHooks.getHealthBody` 无权威时走 `PlayerDefense.rawSyncedHealth`（字段直读） | 不依赖任何方法体，也不递归调用 `getHealth` |
| 被覆盖检测 | `MethodLayerState`（事件心跳 vs `.getHealth` 注入计数） | 心跳停了说明方法层已不在，此时只剩数据层 |

消费端不再读可被替换的方法：`PlayerDefense.retainWhileAlive` 改用 `dead` 字段 + `removalReason` 字段，
不再用 `isRemoved()` / `isDeadOrDying()`——它们被别人替换成恒 `true` 会让还活着的玩家记录被清掉。

**客户端为什么必须单独处理**：`REAL_HEALTH` 是服务端状态，客户端那份要靠 `SyncRealHealthPacket` 才存在，
可能为空或尚未送达；而外部实现把换算逻辑编译进方法体、双端生效，外部私有累加量又是同步字段，所以客户端
`getHealth()` 会被算成一条随累加量下降的读数并逐格掉心。客户端因此改为**直接读原版数据项**：

```java
float raw = entity.getEntityData().get(LivingEntity.DATA_HEALTH_ID);   // 等价于 vanilla getHealth 本体
```

服务端每 tick 把权威值写回这个数据项再同步，客户端手上的数据项天然是权威值，不需要任何服务端静态状态。
落地为两处：

| 位置 | 行为 |
| --- | --- |
| `PlayerDefense.forceGetHealth` | 旁路为空时**不再原样放行**：数据项可用（有限、`> 0`）就直接采用它；数据项也不可用（`0` 或读不到）时，客户端受保护状态兜底返回最大血量，服务端保留原版死亡流程（`getHealth()==0` 原样返回） |
| `RawState.rawFloatOf`（数据项深读） | 读取顺序是**先字段、后方法**：`Entity.entityData`（`f_19804_`）→ `itemsById`（`f_135345_`）→ `DataItem.value`（`f_135391_`）全部直读字段，完全不经过 `SynchedEntityData.get(...)`——外部实现即使连 `get()` 一起替换也读不到假值；字段路径不可用（映射漂移）时自动退回普通读取 |
| `ClientPacketListenerMixin` 的 `handleSetEntityData`（`m_6455_`） | 日常血量同步走的是 `ClientboundSetEntityDataPacket` → `SynchedEntityData.assignValues`，**不是** `ClientboundSetHealthPacket`；包内血量项非法则换成权威值/当前数据项，有权威记录时不允许被改小 |

原始数据项不是无条件采用的：只有数据项本身可用（有限、非负）才采用，且"什么时候该采用它"这套策略写成了
`ReadSideGuard` 里的纯函数（`preferRaw` / `correct` / `isIllegalReport` / `isUsableRaw`）——它们不依赖
Minecraft 类型，由 `tools/ReadSideGuardTest.java` 直接覆盖。生产路径当前由 agent 的 BODY 接管
（`AgentHooks.getHealthBody`），这几个纯函数保留为策略原语与回归基线，不再有别的调用者。
完整优先级：`REAL_HEALTH` → 数据项字段直读 → `SynchedEntityData.get` → 最大血量兜底。

（原来只守 `ClientboundSetHealthPacket`，那条路只在登录/重生/指令时才走，日常更新等于没有纠正。）

**主动取证**：本模组自己的钩子会把证据一起遮住（永远读到 20），所以 `PlayerDefense.serverTick` 每 tick 用
`probeRawReportedHealth` 绕过 HEAD/RETURN 钩子读一次**原始**返回值，专门判定「读口是否仍被替换」；
连续 100 tick 正常后自动撤销标记。

外部私有累加量的源头
========
外部模组把该累加量放在自己的 `EntityDataAccessor<Float>` 里，与原版 `DATA_HEALTH_ID` 无关，只认血量键的
写侧拦截必然漏掉。`PlayerDefense.guardPrivateFloatChannel` 改为**按行为模式**判断，不认 key 名字：

* 只对持有权威血量的玩家生效；
* 只对非原版血量键的 Float 通道生效；
* 连续 3 次写入「只增不减」→ 判为可疑累加器，拒绝该次递增写入；任何一次回落会清零计数，合法通道不会被
  长期误判；
* 只有在读取侧**已被证明**篡改（`READ_TAMPERED`，来自上面的主动取证）时才真的冻结，正常玩家不受影响。

持久化补校验
========
`LivingEntityMixin` 在 `addAdditionalSaveData` / `readAdditionalSaveData` 的 TAIL 各加一层：落盘前把
`Health` 改写成权威值（并做 `>= 0` 校验），读档时修复 `Health <= 0` 或 `DeathTime != 0` 的污染存档。
agent 侧的 `AgentHooks.beforeSave` / `afterLoad` 复用同一份实现
（`PlayerDefense.afterVanillaSave` / `repairPersistedHealth`），两条路径不会各写一套。

移除理由白名单（原来的黑名单太窄）
========
原来的拦截是黑名单：只拒绝 `KILLED` / `DISCARDED`。外部实现换个理由就能移除玩家（`UNLOADED_TO_CHUNK`、
`UNLOADED_WITH_PLAYER`，甚至未来版本新增的枚举）。现在改为**默认拒绝的白名单**，判定集中在
[`RemovalPolicy`](src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/RemovalPolicy.java)（纯函数）：

| 理由 | 原版调用点 | 当前会话玩家 | 旧实体 |
| --- | --- | --- | --- |
| `UNLOADED_WITH_PLAYER` | `PlayerList.remove`（退服 / 踢出） | 放行 | 放行 |
| `CHANGED_DIMENSION` | `ServerPlayer.changeDimension`（传送门 / 跨维度） | 放行 | 放行 |
| `KILLED` | `Entity.kill()` / 死亡流程 | **拒绝** | 放行 |
| `DISCARDED` | `Entity.discard()`（也用于重生 / 重复登录时清理旧实体） | **拒绝** | 放行 |
| `UNLOADED_TO_CHUNK` | 区块卸载 | **拒绝** | 放行 |
| 未知 / 未来新增 | — | **拒绝** | 放行 |

「当前会话 vs 旧实体」用 `PlayerList.getPlayer(uuid) == 该实体` 判断：重生（`PlayerList.respawn`）和
重复登录清理（`ServerLevel.addPlayer`）都是先把这个实体从玩家列表里摘掉（或还没加进去）再移除它，
拦下来只会制造同 UUID 的幽灵玩家。枚举名通过字符串映射（`RemovalPolicy.reasonOf`），新增理由自动落到
`UNKNOWN` 被拒绝，不需要改代码。

两个入口都拦：`Entity.remove`，以及 `public final` 的 `Entity.setRemoved`——`remove()` 只是转调它，
直调 `setRemoved(DISCARDED)` 能完全绕过 `remove()` 的钩子。另外 `serverTick` 每 tick 会把白名单外的
`removalReason` 字段直接还原成 `null`（原版 `setRemoved` 只在字段为 null 时才赋值，顺手把状态洗干净）。

客户端关屏：不要在 Opening handler 里调 setScreen
========
一次真实的自递归：`Minecraft.setScreen(null)` 在 `player.isDeadOrDying()` 为真时，会把 `null` 换成新的
`DeathScreen`，**然后**才 post `ScreenEvent.Opening`（mc-src `Minecraft.java:988-1000`，本工程 47.4.10
已核对）。所以如果 Opening 的 handler 里再调用 `setScreen(null)`，就是
`setScreen → Opening → setScreen` 的无限递归，直到爆栈。

两条规则：

1. **Opening handler 内只改事件本身**：`event.setNewScreen(null)` 表示关闭（Forge 随后会把
   `this.screen = null` 并跳过 `init`），`event.setCanceled(true)` 表示保持原屏。**绝不调用 `setScreen`**。
2. **其它所有关屏调用点统一走 `ModClientForgeEvents.safeCloseScreen(mc)`**：死亡态下先挂起，等客户端
   tick 里死亡态复位后再关；挂起超过 `MAX_DEFER_TICKS`（40 tick）强制关一次——此时即使仍在死亡态也安全，
   因为规则 1 已经断了环。方法内部另有 `IN_SET_SCREEN` 重入保护，只用于防止任何残留嵌套爆栈。

已接入的调用点：`ScreenEvent.Opening`（改为 `setNewScreen(null)`）、Esc 强制关屏、命令面板关闭、
每 tick 的界面恢复，以及 `ClientPacketListenerMixin` 的死亡包处理。延迟判定在
[`ScreenClosePolicy`](src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/ScreenClosePolicy.java)
（纯逻辑，可离线验证）。

判定逻辑的离线验证
========
`ReadSideGuard`、`RemovalPolicy`、`ScreenClosePolicy` 与 `RejectStats` 刻意不依赖 Minecraft 类型，
可以脱离游戏直接跑：

```
javac -encoding UTF-8 -d build/tools-test ^
  src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/ReadSideGuard.java ^
  src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/RemovalPolicy.java ^
  src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/ScreenClosePolicy.java ^
  src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/RejectStats.java ^
  tools/ReadSideGuardTest.java tools/RemovalPolicyTest.java tools/ScreenClosePolicyTest.java ^
  tools/RejectStatsTest.java
java -cp build/tools-test ReadSideGuardTest
java -cp build/tools-test RemovalPolicyTest
java -cp build/tools-test ScreenClosePolicyTest
java -cp build/tools-test RejectStatsTest
```

`RejectStatsTest` 覆盖：空/null 输入、单通道格式、按次数降序、同次数按通道 id 升序（保证日志可重复）、
top-N 折叠但通道数仍为真实值、非法 `maxShown` 至少列 1 条、`total()` 与汇总一致。

`ReadSideGuardTest` 覆盖：负数 / NaN / 无限的纠正、无权威值时不覆盖、合法但不一致仍以权威值为准、
私有通道「只增不减」判定、回落清零、多通道隔离，以及一条完全按实战场景构造的私有累加量攻击序列。

`RemovalPolicyTest` 覆盖：白名单矩阵（当前会话 vs 旧实体 × 全部理由）、未知理由被拒绝、`reasonOf`
的字符串映射，以及「旧黑名单会放行、新白名单必须拒绝」的两条对照。

`ScreenClosePolicyTest` 覆盖：未请求时不关屏、死亡态下逐 tick 延迟到上限才强制关、死亡态提前复位则
下一 tick 立刻关、挂起期间重复请求**不重置**计时（否则强制关永远到不了）、默认上限 40 tick、
非法构造参数兜底。

还有一条专门盯 **agent 内置 SRG 表**的回归（生产环境没有 tsrg，走的就是它；名字写错会让规则静默变成
"方法不存在"）：

```
javac -encoding UTF-8 -d build/tools-test -cp agent/build/classes/java/main tools/MappingTableTest.java
java -cp "build/tools-test;agent/build/classes/java/main" MappingTableTest ^
  build/moddev/artifacts/namedToIntermediate.tsrg
```

它把每条规则（类 / 方法 / 描述符）在映射文件里的 SRG 名，与内置表逐条比对；
`LivingEntity.setHealth` 曾经写成 `m_21150_`（真值是 `m_21153_`）、`Entity.isAlive` 与
`Player.die` / `ServerPlayer.die` 缺条目，正是这条回归抓出来的。

规则列表从 `Rules.bridge()` 推导，不再手抄一份——手抄的那份在新增规则时不会变长，于是"新规则没人校验"
本身没有失败信号。加一条规则，两条回归都会自动把它纳入。

还有一条**产物级**回归，盯的是「两个命名空间有没有对齐」：mod 侧被 reobf 成 SRG，而内嵌的
`agent/star_sword_agent.jar` 不参与 reobf，它靠 `MappingTable` 自己写 SRG 名、再通过 Javassist
片段调用 mod 侧的桥接类。任何一处错位都只在正式包里炸：

```
javac -encoding UTF-8 -d build/tools-test -cp agent/build/classes/java/main tools/AgentBundleTest.java
java -cp "build/tools-test;agent/build/classes/java/main" AgentBundleTest ^
  build/libs/star_sword_mod-<version>.jar build/moddev/artifacts/namedToIntermediate.tsrg
```

它检查四件事：

1. 内嵌 agent jar 存在，manifest 带 `Agent-Class` 与 `Can-Retransform-Classes: true`；
2. 内嵌 agent jar **没有** Class / Fieldref / Methodref 级的 Minecraft 引用——也就是说它不需要 reobf，
   它对 Minecraft 的耦合只允许是字符串类名 + Javassist 片段（`Class` 常量级引用一出现就会红）；
3. 规则片段里调用的每个桥接符号，在**已 reobf 的** `AgentHooks` 里都存在 `public static` 且实参个数一致；
   反射调用的 `reportAttached` / `reportInjectionState` / `reportTamper` / `reportReplay` / `hookCallCount`
   同样要在场；
4. 给 tsrg 时逐条比对内置 SRG 表（与上一条同源，但用的是真实规则表）。

已经用两组负向对照验过它抓得住错：把 `AgentHooks.class` 从 jar 里摘掉会红（桥接类缺失），把
`filterSetHealthArg` 改名会红（该符号"名字不存在"）。验不到失败的断言不值得留在仓库里。

Self-attaching agent (proof of concept)
========
`agent/` is a separate, Javassist-shaded jar that the build embeds into the mod jar
as `agent/star_sword_agent.jar`. At runtime `PocAgentBootstrap` extracts it and asks a
short-lived helper process to attach it to the running JVM. Attaching from a separate
process is required because the JVM refuses self-attachment unless it was started with
`-Djdk.attach.allowAttachSelf=true`, which a mod cannot add to the launcher.

The agent is **on by default** and runs in **`bridge` mode** (the one that actually injects the
method-layer guards). Nothing needs to be enabled; only an explicit opt-out turns it off. If it
cannot attach (no `jdk.attach`, helper process blocked), the failure is logged and
`/starsword agent status` reports `NOT_ATTACHED` instead of pretending the method layer is active;
the data layer keeps working.

```
.\gradlew.bat runClient -PssAgent=0                         # 唯一的关闭方式：方法层关闭
.\gradlew.bat runClient -PssAgentMode=probe                 # 只看不改（诊断用，需显式指定）
.\gradlew.bat runClient -PssQuickPlay=poc                   # 默认就是 bridge
.\gradlew.bat runClient -PssQuickPlay=poc -PssAgentForce=1
.\gradlew.bat runClient -PssQuickPlay=poc -PssAgentForceDie=1 -PssAgentForce=1
```

`-PssAgentForceDie=1` enables `ForceDieTest`: 60 ticks after joining it pushes the player
through `hurt` → `die` → `dropAllDeathLoot` → `remove(KILLED)` → `isRemoved()` once, so the
death-path gates can be observed. Without it those gates never run in a peaceful dev world
(the forced-protection mode keeps the player alive and nothing deals damage).

`-PssQuickPlay=<saveName>` makes the dev client load `run/saves/<saveName>` directly,
which is how the hook gets a real `LivingEntity` without clicking through the GUI
(`run/saves/poc` is a copy of a server world kept for exactly this).

* `probe` logs the class, its loader and whether the incoming bytes already contain
  this mod's Mixin handlers (`own-mixin-handlers=true` proves the agent transforms the
  *post-Mixin* class, i.e. it runs after every Mixin regardless of priority), and which
  runtime name the target member has (Mojang name in dev, SRG in production).
* `guard` injects an early-return guard at the head of `LivingEntity.getHealth()F` that
  returns the sentinel `42.0F`; it exists to prove the rewrite, not to defend anything.
  `-PssAgentDump=true` writes the before/after class files to `%TEMP%\ss-agent-dump`.
* `bridge` is the real one: every injected snippet only calls
  [`AgentHooks`](src/main/java/cn/autoforged/star_sword_mod_1785589476/agent/AgentHooks.java),
  which consults the same `SwordGuard` / `PlayerDefense` predicates the Mixin handlers use.
  Players the mod does not consider protected fall through to the normal
  (Mixin-instrumented) body, so the hook is inert in regular play.
  `-PssAgentForce=1` makes every player "protected" (and `getHealth` return `7.0F`), which is
  how the bridge can be exercised without a real star sword.

  Injected points (`GuardTransformer` holds the table; each is an early return at method head):

  | class | method |
  |---|---|
  | `LivingEntity` | `getHealth`, `hurt`, `isDeadOrDying`, `tickDeath`, `die`, `dropAllDeathLoot` |
  | `Player`, `ServerPlayer` | `die` |
  | `Entity` | `remove(RemovalReason)`, `setRemoved(RemovalReason)`, `isRemoved` |
  | `SynchedEntityData` | `set(accessor, value, force)` — the single write entry for all synced data |
  | `LivingEntity` | `addAdditionalSaveData`, `readAdditionalSaveData` |

  `hurt` cancels the damage *and* keeps the counter-attack (反击之符) working: that side
  effect lives in [`CounterAttack`](src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/CounterAttack.java),
  which the Mixin handler and `AgentHooks.shouldCancelHurt` both call — an agent guard at
  `HEAD` would otherwise skip the Mixin body entirely.

  The synced-data write gate deliberately does **not** match an accessor: it decides from the
  serializer (`EntityDataSerializers.FLOAT`) plus the raw stored value, so it keeps working
  when a mod stores health under a custom key. All comparisons read raw fields through
  [`RawState`](src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/RawState.java)
  (`SynchedEntityData.entity`, `itemsById`, `DataItem.value`, `accessor.serializer`), never a
  getter, so overriding `getHealth()` cannot fake what we compare against.

  A watchdog (default 15 s, `-Dstar_sword.poc.watchdog=N`, `0` disables) periodically
  retransforms the target classes. Retransformation always restarts from the bytes the JVM
  received at definition time, so it re-establishes our injections on top of anything another
  agent wrote after us. Replays are cheap after the first pass: [`GuardTransformer`](agent/src/main/java/cn/autoforged/star_sword_mod_1785589476/agent/GuardTransformer.java)
  caches the patched bytes keyed by an FNV hash of the input, and the interval backs off
  (doubling up to 120 s) while nothing looks wrong.

  **The watchdog is best-effort, not a guarantee.** It cannot detect a foreign redefinition by
  byte comparison: JVMTI always hands a retransform the *initial* define-time bytes, so the
  input hash is identical whether or not somebody overwrote us in between. Detection is
  therefore behavioural — `AgentHooks.hookCallCount()` stalling means our injected call is no
  longer running. Restoration also does not always win: with an external agent that keeps
  re-applying, this setup detects the tamper and re-registers its transformer at the tail of the
  chain, but the guard can still end up overwritten (measured: 0 guard hits while an attacker
  re-applied every 10 s). Treat the agent as one layer, never as the authority itself.

### Failure visibility

Best-effort is only acceptable if a failure is *visible*. [`AgentStatus`](src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/AgentStatus.java)
turns the behavioural probe into an explicit state:

| state | meaning |
|---|---|
| `ACTIVE` | the `getHealth` hook counter is advancing |
| `DEGRADED` | stalled for 3 s — the method layer is being overwritten, the data layer still works |
| `FAILED` | stalled for 120 s — restoration is not succeeding |

Every transition is broadcast to the server (rate-limited to one notice per 60 s) and written
through `ModLog` — visible in a dev run's log, silent in a published build:

```
[星辉剑] 检测到持续字节码覆盖，方法层防御已降级，数据层防御仍生效（写侧闸门/观察者广播/发包过滤不受影响）
```

`/starsword agent status` prints the current state, the last tamper time, replay count and the
last replay's class count / duration.

### Known limitations

**Pseudo-revive does not compensate side effects that already happened.** `LivingDeathEvent`
cannot be un-posted, so anything another mod did in response (score deductions, quest failures,
statistics, cleared buffs, death messages) stays done. The pseudo-revive only repairs our own
state: `dead`/`deathTime`/`hurtTime`/`hurtDuration`, health, the `KILLED` removal reason, and the
client-side death screen. Vanilla's respawn flow is intentionally not triggered.

**Multi-observer consistency is only as good as the packet that got out.** [`ObserverSync`](src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/ObserverSync.java)
rebroadcasts authoritative health to the tracked observers, and [`OutgoingFilter`](src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/OutgoingFilter.java)
rewrites health inside `ClientboundSetHealthPacket` / `ClientboundSetEntityDataPacket` on the way
out, but a packet sent through a channel that bypasses `Connection.send` (or a value inside an
opaque mod payload) is outside both.

### Death side effects and persistence

[`DeathWatchdog`](src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/DeathWatchdog.java)
runs once per player tick and covers the one thing bytecode rewriting cannot undo: a
`LivingDeathEvent` that was already posted. When a protected player is observed with
`dead = true` or `deathTime > 0` while their state has already been repaired, it performs a
**pseudo-revive**: `PlayerDefense.forceRevive`, an explicit `deathTime/hurtTime/hurtDuration`
reset (forceRevive does not clear `deathTime`), and one authoritative-health sync to the
client so the death screen goes away. Vanilla's respawn flow is never involved. It is
rate-limited to one trigger per 20 ticks per player.

Persistence is handled by injecting the save methods themselves:

| hook | behaviour |
|---|---|
| `LivingEntity.addAdditionalSaveData(tag)` | overwrites `Health` / `DeathTime` in the outgoing tag with the authoritative values |
| `LivingEntity.readAdditionalSaveData(tag)` | repairs an already-poisoned save (`Health <= 0` or non-zero `DeathTime`) before it reaches the entity |

Notes for extending it:
* HotSwap cannot add fields or methods, so injected code may only rewrite method bodies;
  keep state in existing statics (e.g. `ProtectedState`) or in the injected snippet.
* Build the `CtClass` with `ClassPool.makeClass(InputStream)` from the transformer's
  bytes. `ClassPool.get(name)` may answer from ModLauncher's class loader, which serves
  the *untransformed* class; using it silently drops every Mixin-added method and makes
  retransformation fail with "attempted to delete a method".
* Pointcuts are hardcoded names (`m_21223_` for production, `getHealth` in dev); the
  agent reads `build/moddev/artifacts/namedToIntermediate.tsrg` when available and falls
  back to a built-in SRG table.
* `attacker/` is a standalone test harness (not embedded in the mod jar) that replaces a method
  at runtime to simulate an opponent overtaking us. Attach it with:

  ```
  java -jar attacker/build/libs/attacker.jar <gamePid> <abs path to attacker.jar> \
    "target=net.minecraft.world.entity.LivingEntity,intercept=getHealth,descriptor=()F,value=1.0,reapplySeconds=10,mode=replace"
  ```

* `ForceDieTest` is excluded from the shipped jar. **Never reference an excluded class
  directly**: the JVM resolves it at the call site, so a missing class becomes a
  `NoClassDefFoundError` inside the tick loop (this actually shipped once and kicked players).
  `ModForgeEvents.maybeRunForceDieTest` therefore reaches it via `Class.forName` and swallows
  `ClassNotFoundException`, and the call is additionally gated on `-Dstar_sword.poc.forceDie`.
