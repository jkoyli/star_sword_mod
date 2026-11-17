package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.item.StarSwordItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 星辉剑主人绑定与宽限追踪工具（供无敌防御子系统门控 + 防缴械复用）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>主人绑定</b>：星辉剑在背包里时把该玩家 UUID 写进 ItemStack NBT
 *       （{@link #NBT_OWNER} / 签名 {@link #NBT_OWNER_SIG}）。NBT 跟着物品走，跨箱/跨维度/跨存档
 *       都不丢。其余玩家无法把别人的剑据为己有。</li>
 *   <li><b>延迟失效</b>：每 tick 扫描持剑玩家的背包，记录 {@code lostSinceTick}。剑离开背包后
 *       防御仍保留 {@link #GRACE_TICKS}=400 tick（20 秒），期间只要剑回到背包就永久续期。</li>
 * </ul>
 *
 * <p>“防御状态”统一由 {@link #isProtected(Player)} 给出，所有防御判断都改用它。
 * 状态存服务端内存 Map（键 = 玩家 UUID），不写 NBT——这是“玩家状态”而非“物品状态”。
 * 真正的“防缴械 + Q 键丢出拦截”逻辑在 {@link AntiDisarm}，本类只提供绑定/宽限与
 * {@link #giveTo(ServerPlayer, ItemStack)} 归还工具。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 星辉剑主人绑定 / 宽限追踪，是所有防御判定的门控（isProtected / shouldBlockDirectDie）。
// ============================================================================
public final class SwordGuard {

    /** ItemStack NBT 里的主人键（明文）。 */
    public static final String NBT_OWNER = "star_sword_mod_1785589476:owner";
    /** ItemStack NBT 里的主人签名键（防止明文被篡改后仍被信任）。 */
    public static final String NBT_OWNER_SIG = "star_sword_mod_1785589476:owner_sig";

    /** 剑离开背包后防御保留的时长（tick）：20 秒。 */
    public static final long GRACE_TICKS = 400L;

    private static final String SIG_SALT = "star_sword_mod_1785589476";

    /** 玩家 UUID -> 该玩家与星辉剑的绑定/丢失状态。 */
    private static final Map<UUID, PlayerSwordState> STATES = new ConcurrentHashMap<>();

    private SwordGuard() {
    }

    // ===================== 物品判定与查找 =====================

    public static boolean isStarSword(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof StarSwordItem;
    }

    /** 扫描主背包 + 装甲 + 副手，返回第一个星辉剑（原引用），否则 null。 */
    public static ItemStack findSword(Player player) {
        Inventory inv = player.getInventory();
        // 玩家构造期间 inventory 字段尚未赋值，getInventory() 会返回 null：此时视为“无剑”。
        if (inv == null || inv.items == null || inv.armor == null || inv.offhand == null) {
            return null;
        }
        for (ItemStack stack : inv.items) {
            if (isStarSword(stack)) {
                return stack;
            }
        }
        for (ItemStack stack : inv.armor) {
            if (isStarSword(stack)) {
                return stack;
            }
        }
        ItemStack offhand = inv.offhand.get(0);
        if (isStarSword(offhand)) {
            return offhand;
        }
        return null;
    }

    /** 玩家的主背包/装甲/副手里是否存在与该对象完全相同的引用（用于判断物品是否已被移出背包）。 */
    public static boolean inventoryContainsIdentity(Player player, ItemStack stack) {
        Inventory inv = player.getInventory();
        // 与 findSword 同理：构造期间 inventory 及其内部数组都可能为 null，视为“不在背包里”。
        if (inv == null || inv.items == null || inv.armor == null || inv.offhand == null) {
            return false;
        }
        for (ItemStack s : inv.items) {
            if (s == stack) {
                return true;
            }
        }
        for (ItemStack s : inv.armor) {
            if (s == stack) {
                return true;
            }
        }
        return inv.offhand.get(0) == stack;
    }

    // ===================== 主人 NBT =====================

    /** 读取主人 UUID；无主人、格式非法或签名不符（被篡改）时返回 null。 */
    public static UUID getOwner(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(NBT_OWNER)) {
            return null;
        }
        String raw = tag.getString(NBT_OWNER);
        if (raw.isEmpty()) {
            return null;
        }
        UUID owner;
        try {
            owner = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            ModLog.sword("SwordGuard.getOwner[层=主人解析] owner UUID 格式非法，视为未绑定", null,
                    "raw=" + raw);
            return null;
        }
        if (tag.contains(NBT_OWNER_SIG)) {
            String expected = signature(raw);
            if (!expected.equals(tag.getString(NBT_OWNER_SIG))) {
                // 签名不匹配 = NBT 被篡改：不信任该主人，视为未绑定。
                ModLog.swordThrottled("SwordGuard.getOwner.badsig", ModLog.TICK_INTERVAL,
                        "SwordGuard.getOwner[层=主人解析] 签名不匹配（疑似篡改），视为未绑定", null,
                        "raw=" + raw);
                return null;
            }
        }
        return owner;
    }

    /** 写入主人（明文 + 签名）。 */
    public static void bind(ItemStack stack, UUID owner) {
        CompoundTag tag = stack.getOrCreateTag();
        String raw = owner.toString();
        tag.putString(NBT_OWNER, raw);
        tag.putString(NBT_OWNER_SIG, signature(raw));
    }

    /** 仅当没有可信主人时才写入。 */
    public static void bindIfAbsent(ItemStack stack, UUID owner) {
        if (getOwner(stack) == null) {
            bind(stack, owner);
            ModLog.sword("bindIfAbsent 绑定星辉剑主人", null, "owner=" + owner);
        }
    }

    private static String signature(String owner) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((SIG_SALT + ":" + owner).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "invalid";
        }
    }

    // ===================== 防御状态 =====================

    /**
     * 玩家是否处于“防御状态”：当前持有星辉剑，或剑离开背包不超过 20 秒。
     * 所有防御判断统一调它。
     */
    // [DEFENSE-MARK] 防御：玩家是否处于“防御状态”（所有防御判断的门控）。
    public static boolean isProtected(Player player) {
        if (player.level().isClientSide) {
            // 客户端不再依赖本地物品栏：重进时物品栏同步包可能尚未到达，findSword 会误判为“无剑”。
            // 一律以服务端广播的权威状态为准；尚未收到时按方向 B 暂时保护本地玩家（见 ProtectedState）。
            return ProtectedState.clientIsProtected(player.getUUID());
        }
        boolean result;
        // 硬条件兜底：背包里有星辉剑即受保护，不依赖 STATES 状态追踪。
        // （/give 直接给剑不走 ItemPickupEvent、或 serverTick 因故未运行/被延迟时，STATES 可能没建立。）
        if (findSword(player) != null) {
            result = true;
        } else {
            PlayerSwordState state = STATES.get(player.getUUID());
            result = state != null && (state.lostSinceTick < 0L || now(player) - state.lostSinceTick <= GRACE_TICKS);
        }
        return result;
    }

    /**
     * 是否应拦截“绕过 hurt 的直接 die 调用”。由 {@code LivingEntityMixin}/{@code PlayerMixin}/
     * {@code ServerPlayerMixin} 共用的 die 注入调用：受保护玩家且调用栈不经过 hurt 时返回 true。
     *
     * <p>调用栈匹配同时兼容开发域 {@code hurt} 与生产域 SRG {@code m_6469_}。正常伤害管道的死亡
     * （栈中含 hurt）放行；因 hurt 已被本模组取消，持剑玩家的放行路径实际不会真正死亡。
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：是否拦截绕过 hurt 的直接 die。
    public static boolean shouldBlockDirectDie(Player player) {
        boolean block = isProtected(player) && !calledFromHurt();
        if (block) {
            ModLog.defense("shouldBlockDirectDie 拦截直接 die 调用", player);
        }
        return block;
    }

    /** 调用栈里是否经过 hurt（开发域 "hurt" / 生产域 SRG "m_6469_"）。 */
    // [DEFENSE-MARK] 防御：die 调用栈是否经过 hurt。
    private static boolean calledFromHurt() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String name = element.getMethodName();
            if ("hurt".equals(name) || "m_6469_".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static long now(Player player) {
        return player.level().getGameTime();
    }

    // ===================== 每 tick 状态推进 =====================

    /** 每个服务端玩家每 tick 调用一次（无论是否持有剑）。 */
    // [DEFENSE-MARK] 防御：每 tick 维护主人绑定 + 丢失宽限，驱动防御状态。
    public static void serverTick(Player player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        UUID id = player.getUUID();
        ItemStack sword = findSword(player);

        if (sword != null) {
            // 背包里有星辉剑：确保带主人绑定，并标记为“当前持有”。
            bindIfAbsent(sword, id);
            PlayerSwordState state = STATES.computeIfAbsent(id, k -> new PlayerSwordState());
            if (state.lostSinceTick >= 0L) {
                ModLog.defense("SwordGuard 星辉剑找回，恢复防御持有状态", player);
            }
            state.ownerUuid = id;
            state.lastOwnedTick = now(player);
            state.lostSinceTick = -1L;
            ModLog.defenseThrottled("SwordGuard.serverTick.holding:" + id, ModLog.TICK_INTERVAL,
                    "SwordGuard.serverTick[层=每tick绑定] 持有星辉剑，防御生效", player,
                    "lastOwnedTick=" + state.lastOwnedTick);
            return;
        }

        PlayerSwordState state = STATES.get(id);
        if (state == null) {
            // 从未持有过星辉剑，不给予防御。
            return;
        }
        if (state.lostSinceTick < 0L) {
            state.lostSinceTick = now(player);
            ModLog.defense("SwordGuard 星辉剑离开背包，进入宽限期", player, "graceTicks=" + GRACE_TICKS);
        }
        if (now(player) - state.lostSinceTick > GRACE_TICKS) {
            // 20 秒内没找回，防御失效。
            STATES.remove(id);
            PlayerDefense.onProtectionExpired(player);
            ModLog.defense("SwordGuard 宽限期结束，防御失效", player);
        }
    }

    // ===================== 回收 =====================

    /**
     * 塞进主人背包；背包满时掉在主人脚下。掉落经 {@code RECOVERING} 标志放行，
     * 不会被本模组自己的丢出拦截逻辑再次拦下。
     */
    // [DEFENSE-MARK] 防御（防缴械）：归还星辉剑到主人背包。
    public static void giveTo(ServerPlayer owner, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ModConstants.RECOVERING.add(owner.getUUID());
        try {
            if (!owner.getInventory().add(stack)) {
                ModLog.inventory("SwordGuard.giveTo 背包已满，掉落剑", owner,
                        "物品=" + stack.getItem() + " 位置=" + ModLog.describe(owner));
                owner.drop(stack, false);
            } else {
                ModLog.inventory("SwordGuard.giveTo 归还星辉剑到背包", owner, "物品=" + stack.getItem());
            }
        } finally {
            ModConstants.RECOVERING.remove(owner.getUUID());
        }
    }

    // ===================== 状态对象 =====================

    private static final class PlayerSwordState {
        long lastOwnedTick = -1L;
        long lostSinceTick = -1L;
        UUID ownerUuid;
    }
}
