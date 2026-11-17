# 客户端对抗日志采集清单（star_sword_mod，目标版本 2.9.6）

> 本文件是对 `client-evidence-checklist` 的修订版：原清单里有 3 处与 2.9.6 实际行为不符，
> 按原样采集会漏证据，已在下方标注 **[修订]**。

## 背景

守方 mod：star_sword_mod（Minecraft Forge 1.20.1）。服务端侧已实测守住血量/实体移除，
现在要判定**客户端侧**是否被对手覆盖。客户端被覆盖的典型表现：血条空、人站在原地说不动、
不弹死亡屏、攻击无效。

## 前置

1. 确认版本：`latest.log` 里应有 `星辉剑 2.9.6 已加载`（`StarSwordMod.<init>:16`）。
2. 正式环境默认不写日志，启动参数加 `-Dstar_sword.log=true`（JVM args，不是游戏参数）。
3. 对手模组要在场（`the_trial_monolith` 或 `fantasy_ending` 任一）。
4. 需要一次“被攻击时正卡住”的现场：进游戏站定 → 触发攻击 → **卡住期间**执行下面的命令并存日志。

## 要采集的四样东西

### 1) 游戏内命令输出（卡住时立刻执行，整段复制）

```
/starsword agent status
```

该子命令在 2.9.6 存在（`ModForgeEvents` 注册 `starsword` → `agent` → `status`），输出包含：
方法层防御状态、权威血量存储、同步键、Mixin 注入自检、注入对账、方法层心跳、实体完整性。
命令不存在时改试 `/starsword status`。

**[修订]** 这份输出**不会**写进日志文件，只有命令回显。所以第 1 步必须人工复制，
日志采集无法替代它。

### 2) 守方日志相关行

文件：`logs/star_sword_mod_1785589476.log`（该文件**跨会话追加**，开头每段有
`===== YYYY-MM-DD HH:MM:SS 星辉剑日志会话开始 =====`，取最后一段；同内容也会以
`(StarSword)` 名义进 `latest.log`）。

命中以下关键字，**覆盖卡住前后各 30 秒**：

```
getHealth BODY | clientState | 实体完整性（客户端） | 方法层心跳 | 注入对账 |
已中和 | 通道探测 | agent remove | OutgoingFilter | 存档前修正 | 同步项清单
```

**[修订]** 原清单里的 `同步键` 一行**不会**出现在日志里（只在 `/starsword agent status`
的输出里），抓它永远 0 命中；真正能在日志里看到同步项的是 `同步项清单`
（`HealthDataItemInstaller.dumpFloatChannels`）。
同理 `agent remove` 在 2.9.6 也没有日志点，实体移除相关请改抓 `硬摘除|EntityRemover|removeFromStorage`。

PowerShell（把 `$log` 换成实际路径）：

```powershell
$log = "$env:APPDATA\.minecraft\logs\star_sword_mod_1785589476.log"
Select-String -Path $log -Pattern 'getHealth BODY|clientState|实体完整性（客户端）|方法层心跳|注入对账|已中和|通道探测|OutgoingFilter|存档前修正|同步项清单' |
  ForEach-Object { $_.Line }
```

**[修订]** `star_sword_mod_*.log` 是 **UTF-8**，而 `latest.log` 是**平台默认编码（中文 Windows = GBK）**。
用 UTF-8 去读 `latest.log` 会得到乱码并让中文关键字全部不命中，读之前先统一编码：

```powershell
$enc = [System.Text.Encoding]::GetEncoding(936)   # latest.log 用 GBK
$lines = $enc.GetString([System.IO.File]::ReadAllBytes("$env:APPDATA\.minecraft\logs\latest.log")) -split "`r?`n"
$lines | Where-Object { $_ -match '星辉剑|\[ss-agent\]' }
```

### 3) agent 侧启动行

**[修订]** `[ss-agent]` 这些行**不在 `latest.log` 里**，而是独立的 `logs/starsword-agent.log`
（同样跨会话追加，每段以 `===== StarSword agent log session =====` 开头）。
要抓的是：

- `mapping=file(...)` 或 `mapping=builtin-fallback`
- `injected into ...` / `no ... skipped`
- `ready (retransformed=N)`
- `watchdog round: ... hookCalls=N`

```powershell
$agent = "$env:APPDATA\.minecraft\logs\starsword-agent.log"
Select-String -Path $agent -Pattern '\[ss-agent\]' | ForEach-Object { $_.Line }
```

### 4) 现场行为（文字回答 4 个问题，不要只看日志）

1. 卡住时能走动吗？按 W/A/S/D 有没有位移？
2. 能攻击/挥剑吗？左键有没有动作？
3. 屏幕上有没有死亡屏（深红半透明 + “你死了”按钮）？
4. HUD 血量显示是多少？被攻击后有没有跳回满血？

## 判定表

| 观察 | 结论 |
|---|---|
| 日志有 `AgentHooks[层=agent getHealth BODY]`（客户端侧也在打） | 客户端读血量被守方接管；如果仍不能动，问题在输入/tick 层，另查 |
| 没有该行，且 `clientState` 里 `deadOrDying=true` / `deathTime>0` | 客户端被对手覆盖：这就是“行动被剥夺”的根因 |
| `实体完整性（客户端）` 是 `MISSING` 或 `REMOVED` | 客户端实体被摘掉，需要“通用客户端移除守卫” |
| `方法层心跳` 里 agent 调用计数为 0，但客户端 tick 在涨 | agent 没挂上客户端，方法层在客户端完全失效 |
| `注入对账` 的“方法不存在”里出现 `living.getHealth` / `living.isAlive` | 该规则没注入，读侧裁决缺席 |

**[修订]** 判 `getHealth BODY` 是哪一侧，看行尾 `调用层:` 里有没有
`ModClientForgeEvents` / `tickClient` / `ClientPacketListener`；**不要**用 `方法层心跳` 的计数判断
——单机整合服里两侧同 JVM、计数器是全局的，254k 次调用基本都来自服务端实体 tick。

## 交付格式

按顺序贴：

1. `/starsword agent status` 全文
2. 第 2 步抓到的行（保留时间戳，按时间排序）
3. 第 3 步的 `[ss-agent]` 行
4. 第 4 步四个问题的回答
5. 版本行 `星辉剑 x.y.z 已加载`
