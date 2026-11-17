package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端：面板开关的读取、校验与写入。
 *
 * <p>职责边界刻意收得很窄——本类<b>不改变任何机制的运行结果</b>，只做三件事：
 * <ol>
 *   <li>把五个开关的当前值汇总成一份快照（供面板显示，也顺带补上「移除模式没有回同步」的老缺口）；</li>
 *   <li>校验请求（持剑、开关存在、权限等级、限频）；</li>
 *   <li>校验通过后转调 {@link MechanicRegistry#set}，与原有快捷键/符咒走同一条写入路径。</li>
 * </ol>
 *
 * <p>所有方法都只在服务端主线程调用（包 handler 已 {@code enqueueWork}）。
 */
public final class MechanicService {

    /** 同一玩家两次写入之间的最小间隔（毫秒），防止逐帧拖拽把服务端刷满。 */
    private static final long MIN_INTERVAL_MS = 80L;

    private static final Map<UUID, Long> LAST_WRITE_AT = new ConcurrentHashMap<>();

    private MechanicService() {
    }

    /** 一条快照项。 */
    public static final class Entry {
        public final String id;
        public final MechanicRegistry.Kind kind;
        public final boolean value;
        /** 该玩家有没有权限改这一项（界面据此灰化）。 */
        public final boolean editable;

        Entry(String id, MechanicRegistry.Kind kind, boolean value, boolean editable) {
            this.id = id;
            this.kind = kind;
            this.value = value;
            this.editable = editable;
        }

        /** 供网络层解码时重建（包与服务的包不同，构造器保持不公开更容易守住不变量）。 */
        public static Entry of(String id, MechanicRegistry.Kind kind, boolean value, boolean editable) {
            return new Entry(id, kind, value, editable);
        }
    }

    /** 汇总当前玩家能看到的所有开关。 */
    public static List<Entry> snapshot(ServerPlayer player) {
        List<Entry> entries = new ArrayList<>();
        boolean holdingSword = player != null && ModConstants.isHoldingSword(player);
        for (MechanicRegistry.Def def : MechanicRegistry.all()) {
            boolean value = MechanicRegistry.get(player, def);
            boolean permitted = player != null && player.hasPermissions(def.kind.opLevel);
            entries.add(new Entry(def.id, def.kind, value, permitted && holdingSword));
        }
        return entries;
    }

    /**
     * 处理一次写入请求。
     *
     * @return true 表示已生效（调用方随后回发快照）
     */
    public static boolean set(ServerPlayer player, String id, boolean value) {
        if (player == null || player.level().isClientSide) {
            return false;
        }
        MechanicRegistry.Def def = MechanicRegistry.byId(id);
        if (def == null) {
            ModLog.defense("MechanicService.set 被拒：未知开关", player, "id=" + id);
            return false;
        }
        if (!player.hasPermissions(def.kind.opLevel)) {
            ModLog.defense("MechanicService.set 被拒：权限不足", player,
                    "id=" + id + " 需要=" + def.kind.opLevel + " 实际=" + permissionLevel(player));
            return false;
        }
        // 与既有快捷键一致：必须手持星辉剑，避免把面板当成第二个后门。
        if (!ModConstants.isHoldingSword(player)) {
            ModLog.defense("MechanicService.set 被拒：未手持星辉剑", player, "id=" + id);
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_WRITE_AT.get(player.getUUID());
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return false;
        }
        LAST_WRITE_AT.put(player.getUUID(), now);

        boolean before = MechanicRegistry.get(player, def);
        MechanicRegistry.set(player, def, value);
        if (!def.isPlayerScoped()) {
            // 全服项落世界存档（玩家项已在 MechanicPlayerState 里写 NBT）。
            MechanicGlobalState.remember(player.getServer(), def.id, value);
        }
        ModLog.defense("MechanicService.set[层=面板] 切换机制开关", player,
                "id=" + id + " " + before + " -> " + value + " 作用域=" + def.kind);
        return true;
    }

    /**
     * 处理一次「删除存档项」请求：把该项的持久化记录删掉，并把本局值改回默认。
     *
     * @return true 表示已生效（调用方随后回发快照）
     */
    public static boolean reset(ServerPlayer player, String id) {
        if (player == null || player.level().isClientSide) {
            return false;
        }
        MechanicRegistry.Def def = MechanicRegistry.byId(id);
        if (def == null) {
            ModLog.defense("MechanicService.reset 被拒：未知开关", player, "id=" + id);
            return false;
        }
        if (!player.hasPermissions(def.kind.opLevel)) {
            ModLog.defense("MechanicService.reset 被拒：权限不足", player,
                    "id=" + id + " 需要=" + def.kind.opLevel + " 实际=" + permissionLevel(player));
            return false;
        }
        if (!ModConstants.isHoldingSword(player)) {
            ModLog.defense("MechanicService.reset 被拒：未手持星辉剑", player, "id=" + id);
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_WRITE_AT.get(player.getUUID());
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return false;
        }
        LAST_WRITE_AT.put(player.getUUID(), now);

        boolean before = MechanicRegistry.get(player, def);
        boolean after = MechanicRegistry.reset(player, def);
        ModLog.defense("MechanicService.reset[层=面板] 删除存档项并回到默认", player,
                "id=" + id + " " + before + " -> " + after + " 作用域=" + def.kind);
        return true;
    }

    /** 玩家登出时清掉限频记录，避免 UUID 表只增不减。 */
    public static void forget(UUID uuid) {
        if (uuid != null) {
            LAST_WRITE_AT.remove(uuid);
        }
    }

    /**
     * 玩家当前权限等级。
     *
     * <p>原版没有公开的 getter，{@code hasPermissions(n)} 是唯一的判定入口；这里从 4 往下试探，
     * 只用于日志与界面提示（真正的鉴权仍然逐项调 {@code hasPermissions}）。
     */
    private static int permissionLevel(ServerPlayer player) {
        for (int level = 4; level >= 0; level--) {
            if (player.hasPermissions(level)) {
                return level;
            }
        }
        return 0;
    }
}
