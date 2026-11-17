package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受保护玩家状态的跨端同步（服务端权威 -> 所有客户端）。
 *
 * <p>解决的问题：客户端重进时 {@code LocalPlayer} 已开始 tick、但在登录包与物品栏同步包之间
 * 物品栏为空，{@code SwordGuard.findSword} 返回 null，客户端无法区分“有剑玩家重进的空窗期”
 * 与“本来就不该保护”。因此改为：
 * <ul>
 *   <li><b>服务端权威</b>：登录时、以及保护状态发生变化时，通过 {@link ModMessages} 广播
 *       {@code SyncProtectedStatePacket}（UUID + 是否受保护）；</li>
 *   <li><b>客户端缓存</b>：收到后写入 {@link #CLIENT_STATES}，{@code SwordGuard.isProtected}
 *       在客户端直接读缓存，不再依赖本地物品栏；</li>
 *   <li><b>方向 B（未知即暂保）</b>：客户端在收到自己（或本地身份尚未标记时任意玩家）的权威
 *       状态之前，{@link #clientIsProtected} 暂时返回 true，堵住登录最初几个 tick 的空窗期；
 *       收到 {@code protected=false} 后由 {@code ModClientForgeEvents} 清理被临时保护覆盖的死亡态。</li>
 * </ul>
 *
 * <p>广播不区分对象：每个受保护玩家的状态都发给所有客户端，使其他玩家视角的血条也能显示
 * 安全血量（客户端 getHealth 兜底读该缓存）。本类只含 common 端 API，物理服务端安全。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 服务端权威“保护状态”跨端同步，客户端 SwordGuard.isProtected 的判定来源。
// ============================================================================
public final class ProtectedState {

    /** 客户端从服务端广播收到的权威保护状态（UUID -> 是否受保护）。 */
    private static final Map<UUID, Boolean> CLIENT_STATES = new ConcurrentHashMap<>();

    /** 服务端上次已经广播出去的状态（UUID -> 是否受保护），用于“变化才发”。 */
    private static final Map<UUID, Boolean> SERVER_LAST_SYNCED = new ConcurrentHashMap<>();

    /** 客户端本地玩家 UUID：由客户端每 tick 标记，用于方向 B 判定“谁是本地玩家”。 */
    private static volatile UUID clientLocalUuid;

    private ProtectedState() {
    }

    // ===================== 客户端 =====================

    /** 客户端每帧/每收包时标记本地玩家 UUID（纯 common 静态字段，不引用客户端类）。 */
    public static void setClientLocalPlayer(UUID id) {
        clientLocalUuid = id;
    }

    /** 收到服务端广播：写入权威缓存。 */
    public static void acceptClientState(UUID id, boolean protectedState) {
        if (id != null) {
            CLIENT_STATES.put(id, protectedState);
        }
    }

    /** 读取服务端广播的权威状态；未收到返回 null（表示未知）。 */
    public static Boolean getClientState(UUID id) {
        return id == null ? null : CLIENT_STATES.get(id);
    }

    /**
     * 客户端保护判定（方向 B）：
     * <ul>
     *   <li>已收到服务端权威状态 -> 完全以它为准（有剑/没剑都不再查本地物品栏）；</li>
     *   <li>尚未收到，且该 UUID 是本地玩家（或本地身份尚未标记）-> 暂时保护，避免登录空窗期
     *       因负数血量/死亡包进入死亡状态；收到 {@code false} 后自然停止。</li>
     * </ul>
     */
    // [DEFENSE-MARK] 防御：客户端保护判定（方向 B 未知即暂保）。
    public static boolean clientIsProtected(UUID id) {
        Boolean known = getClientState(id);
        if (known != null) {
            return known;
        }
        UUID local = clientLocalUuid;
        return local == null || local.equals(id);
    }

    // ===================== 服务端 =====================

    /** 服务端每 tick 调用：保护状态与上次广播不同时向所有客户端广播。 */
    // [DEFENSE-MARK] 防御：服务端每 tick 变化才广播保护状态。
    public static void serverTick(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        boolean now = SwordGuard.isProtected(player);
        Boolean last = SERVER_LAST_SYNCED.get(id);
        if (last == null || last.booleanValue() != now) {
            SERVER_LAST_SYNCED.put(id, now);
            ModMessages.sendProtectedStateToAll(id, now);
            if (last != null && last && !now) {
                // 保护状态 true -> false：客户端在“未知即暂保”期可能被覆盖成满血，补发一次原版
                // 血量包让它立刻回到服务端权威值（此时 isProtected 已为 false，handleSetHealth 放行原版）。
                resendVanillaHealth(player);
            }
            ModLog.defenseThrottled("ProtectedState.serverTick." + id, ModLog.TICK_INTERVAL,
                    "ProtectedState[层=保护状态同步] 状态变化，广播给所有客户端", player,
                    "protected=" + now);
        }
    }

    /**
     * 登录：把当前在线玩家的保护状态发给新客户端（补齐它对他人的缓存），
     * 再把新玩家自己的状态广播给所有人。必须在“按名发剑”之后调用才能拿到正确的初始状态。
     */
    // [RESPAWN-MARK] 重生：登录时补齐/广播保护状态（覆盖重生/重进空窗期）。
    public static void onPlayerLoggedIn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer other : players) {
            if (other == player) {
                continue;
            }
            boolean state = SwordGuard.isProtected(other);
            ModMessages.sendProtectedState(player, other.getUUID(), state);
            SERVER_LAST_SYNCED.put(other.getUUID(), state);
        }
        boolean self = SwordGuard.isProtected(player);
        SERVER_LAST_SYNCED.put(player.getUUID(), self);
        ModMessages.sendProtectedStateToAll(player.getUUID(), self);
        if (!self) {
            // 方向 B 收尾：客户端在收到“不受保护”之前可能被临时保护覆盖过血量，补发一次原版血量包。
            resendVanillaHealth(player);
        }
        ModLog.defense("ProtectedState.onPlayerLoggedIn[层=登录同步] 广播新玩家保护状态", player,
                "protected=" + self);
    }

    /** 登出：向所有客户端广播“不再受保护”，避免缓存里残留一个已下线的受保护 UUID。 */
    // [DEFENSE-MARK] 防御：登出广播“不再受保护”。
    public static void onPlayerLoggedOut(ServerPlayer player) {
        UUID id = player.getUUID();
        SERVER_LAST_SYNCED.remove(id);
        ModMessages.sendProtectedStateToAll(id, false);
        ModLog.defense("ProtectedState.onPlayerLoggedOut[层=登出清理] 广播下线、移除保护状态",
                player, "");
    }

    /** 向玩家补发一次原版血量包（异常静默）。 */
    // [DEFENSE-MARK] 防御：补发一次原版血量包。
    private static void resendVanillaHealth(ServerPlayer player) {
        try {
            player.connection.send(new ClientboundSetHealthPacket(
                    player.getHealth(),
                    player.getFoodData().getFoodLevel(),
                    player.getFoodData().getSaturationLevel()));
        } catch (Throwable ignored) {
        }
    }
}
