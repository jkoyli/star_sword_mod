package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 防缴械子系统（开关 + 快捷栏第 0 格备份/回填）。
 *
 * <p>开关状态与备份都写进玩家 {@code getPersistentData()}（NBT，跟着玩家存档走），
 * 死亡后由 {@code PlayerEvent.Clone} 手动拷贝到新实体；同时维护一份服务端内存备份
 * 避免每 tick 读写 NBT。
 *
 * <p>服务端每 tick 逻辑（仅开关开启时执行）：<ol>
 *   <li>第 0 格是自己的星辉剑 → 立即备份（先 {@code bindIfAbsent} 保证带 owner），
 *       仅当 NBT 变化时才写 PersistentData。</li>
 *   <li>第 0 格是别人的星辉剑 → 归还原主（在线塞背包，离线存待回收），清空第 0 格。</li>
 *   <li>其他槽位有自己的星辉剑 → 迁移到第 0 格，清除重复。</li>
 *   <li>背包里没有自己的剑 → 用备份回填第 0 格（旧物品转移/掉落）。</li>
 *   <li>清除其他槽位的同 owner 剑；校对备份与第 0 格是否一致。</li>
 * </ol>
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// 防缴械：Q 键丢出拦截 + 快捷栏第 0 格备份/回填 + 跨玩家回收。
// ============================================================================
public final class AntiDisarm {

    /** PersistentData 里的开关键。 */
    public static final String NBT_ENABLED = "AntiDisarmEnabled";
    /** PersistentData 里的备份键（ItemStack 的 CompoundTag）。 */
    public static final String NBT_BACKUP = "StarSwordBackup";

    /** 服务端内存备份（UUID -> 剑的副本），避免每 tick 读写玩家 NBT。 */
    private static final Map<UUID, ItemStack> MEMORY_BACKUP = new ConcurrentHashMap<>();
    /** 主人离线时待归还的剑（UUID -> 剑列表），主人上线后塞回。 */
    private static final Map<UUID, List<ItemStack>> PENDING_RETURN = new ConcurrentHashMap<>();

    /** 全局低频兜底扫描间隔（tick）。 */
    private static final int GLOBAL_SCAN_INTERVAL = 40;
    /** 上次全局兜底扫描的世界 gameTime。 */
    private static long lastGlobalScanTick = Long.MIN_VALUE;

    private AntiDisarm() {
    }

    // ===================== 开关 =====================

    public static boolean isEnabled(Player player) {
        return player != null && player.getPersistentData().getBoolean(NBT_ENABLED);
    }

    public static void setEnabled(Player player, boolean value) {
        player.getPersistentData().putBoolean(NBT_ENABLED, value);
        ModLog.defense("AntiDisarm.setEnabled 切换防缴械开关", player, "enabled=" + value);
    }

    // ===================== 备份读写 =====================

    /** 读取内存备份；内存为空则回落到 PersistentData。返回副本。 */
    public static ItemStack getBackup(Player player) {
        ItemStack memory = MEMORY_BACKUP.get(player.getUUID());
        if (memory != null && !memory.isEmpty()) {
            return memory.copy();
        }
        CompoundTag data = player.getPersistentData();
        if (data.contains(NBT_BACKUP, Tag.TAG_COMPOUND)) {
            ItemStack stored = ItemStack.of(data.getCompound(NBT_BACKUP));
            if (!stored.isEmpty()) {
                MEMORY_BACKUP.put(player.getUUID(), stored.copy());
                return stored;
            }
        }
        ModLog.inventoryThrottled("AntiDisarm.getBackup.empty:" + player.getUUID(), ModLog.TICK_INTERVAL,
                "AntiDisarm.getBackup[层=内存/PersistentData] 备份为空，返回 EMPTY", player, "");
        return ItemStack.EMPTY;
    }

    /** 用当前剑刷新内存备份；仅当 NBT 变化时才写 PersistentData。返回是否发生变化。 */
    private static boolean persistBackup(Player player, ItemStack sword) {
        ItemStack copy = sword.copy();
        MEMORY_BACKUP.put(player.getUUID(), copy);
        CompoundTag data = player.getPersistentData();
        ItemStack old = data.contains(NBT_BACKUP, Tag.TAG_COMPOUND)
                ? ItemStack.of(data.getCompound(NBT_BACKUP)) : ItemStack.EMPTY;
        if (ItemStack.matches(old, copy)) {
            return false;
        }
        data.put(NBT_BACKUP, copy.save(new CompoundTag()));
        ModLog.inventory("AntiDisarm 更新剑备份到 PersistentData", player,
                "备份物品=" + copy.getItem() + " count=" + copy.getCount());
        return true;
    }

    // ===================== 服务端每 tick =====================

    /** 服务端玩家每 tick 调用一次；开关关闭时不做任何扫描。 */
    public static void serverTick(ServerPlayer player) {
        if (player == null || !isEnabled(player)) {
            if (player != null) {
                // 每 tick 都会走到这里：开关关闭属正常，40t 报一次即可。
                ModLog.inventoryThrottled("AntiDisarm.serverTick.disabled:" + player.getUUID(),
                        ModLog.TICK_INTERVAL,
                        "AntiDisarm.serverTick[层=每tick防缴械] 开关关闭，跳过扫描", player, "");
            }
            return;
        }
        ModLog.inventoryThrottled("AntiDisarm.serverTick.normal:" + player.getUUID(),
                ModLog.TICK_INTERVAL,
                "AntiDisarm.serverTick[层=每tick防缴械] 正在维护第0格", player,
                "slot0=" + player.getInventory().items.get(0).getItem());
        // 3.3 全局低频兜底：每 GLOBAL_SCAN_INTERVAL tick 扫一遍所有在线玩家背包，
        // 把属于“已开启防缴械的在线原主”的星辉剑收走归还。开关关闭的玩家在上面已 return，
        // 所以至少有一个在线主人开启防缴械时该扫描才会被驱动。
        maybeGlobalReclaim(player);
        Inventory inv = player.getInventory();
        ItemStack slot0 = inv.items.get(0);

        // 第 2 步：第 0 格是自己的星辉剑 -> 立即备份。
        if (SwordGuard.isStarSword(slot0)) {
            UUID owner = SwordGuard.getOwner(slot0);
            if (owner == null) {
                SwordGuard.bindIfAbsent(slot0, player.getUUID());
                owner = player.getUUID();
            }
            if (player.getUUID().equals(owner)) {
                if (persistBackup(player, slot0)) {
                    syncToClient(player);
                }
                return;
            }
            // 第 3 步：第 0 格是别人的星辉剑 -> 归还原主。
            ModLog.inventory("AntiDisarm 第0格是他人星辉剑，清空并归还", player,
                    "owner=" + owner + " 物品=" + ModLog.describe(player));
            returnForeignSword(player, slot0, owner);
            inv.items.set(0, ItemStack.EMPTY);
            inv.setChanged();
            syncSlot(player, 0);
        }

        // 第 4 步：其他槽位有自己的星辉剑 -> 迁移到第 0 格并清除重复。
        for (int i = 1; i < inv.items.size(); i++) {
            ItemStack candidate = inv.items.get(i);
            if (!SwordGuard.isStarSword(candidate)) {
                continue;
            }
            UUID owner = SwordGuard.getOwner(candidate);
            if (owner == null) {
                SwordGuard.bindIfAbsent(candidate, player.getUUID());
                owner = player.getUUID();
            }
            if (!player.getUUID().equals(owner)) {
                continue;
            }
            ItemStack move = candidate.copy();
            ModLog.inventory("AntiDisarm 将其他槽位的星辉剑迁移到第0格", player,
                    "fromSlot=" + i + " 物品=" + move.getItem());
            clearOwnDuplicates(player, -1);
            // 第 0 格若压着普通物品，挪到刚腾出的槽位，避免丢失。
            moveSlot0Aside(player, i);
            inv.items.set(0, move);
            inv.setChanged();
            persistBackup(player, move);
            syncSlot(player, 0);
            syncToClient(player);
            return;
        }

        // 第 5 步：背包里没有自己的剑 -> 从备份回填。
        replenish(player);

        // 第 6 步：清除其他槽位的同 owner 剑。
        clearOwnDuplicates(player, 0);

        // 第 7 步：校对备份与第 0 格是否一致。
        ItemStack now0 = inv.items.get(0);
        if (SwordGuard.isStarSword(now0)) {
            UUID owner = SwordGuard.getOwner(now0);
            if (owner == null || player.getUUID().equals(owner)) {
                if (persistBackup(player, now0)) {
                    syncToClient(player);
                }
            }
        }
    }

    /**
     * 第 5 步：从备份把星辉剑回填到第 0 格。返回是否真的补了剑。
     * 也供“缺剑通知包”在服务端校验后调用。
     */
    public static boolean replenish(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        ItemStack backup = getBackup(player);
        if (backup.isEmpty()) {
            ModLog.inventory("AntiDisarm.replenish[层=回填] 备份为空，无法补剑", player, "");
            return false;
        }
        Inventory inv = player.getInventory();
        ItemStack slot0 = inv.items.get(0);
        if (!slot0.isEmpty()) {
            if (SwordGuard.isStarSword(slot0)) {
                UUID owner = SwordGuard.getOwner(slot0);
                if (owner != null && !owner.equals(player.getUUID())) {
                    // 第 0 格是别人的剑：按第 3 步先归还。
                    returnForeignSword(player, slot0, owner);
                }
            } else {
                // 普通物品：先尝试移到其他槽位，放不下就丢出。
                ItemStack existing = slot0.copy();
                inv.items.set(0, ItemStack.EMPTY);
                if (!addToNonZeroSlots(inv, existing)) {
                    ModLog.inventory("AntiDisarm.replenish[层=回填] 第0格普通物品放不下，掉落到脚下", player,
                            "物品=" + existing.getItem() + " x" + existing.getCount());
                    player.drop(existing, false);
                }
            }
        }
        inv.items.set(0, backup.copy());
        // 补剑后清除其他槽位/副手的同 owner 剑，避免副手仍持有造成复制。
        clearOwnDuplicates(player, 0);
        inv.setChanged();
        syncSlot(player, 0);
        syncToClient(player);
        ModLog.inventory("AntiDisarm.replenish 从备份回填星辉剑到第0格", player,
                "物品=" + backup.getItem() + " count=" + backup.getCount());
        return true;
    }

    /** 第 6 步：清除 items[1..35] 与副手里属于本玩家的星辉剑；keepSlot>=0 时保留该槽。 */
    public static void clearOwnDuplicates(ServerPlayer player, int keepSlot) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (i == keepSlot) {
                continue;
            }
            ItemStack stack = inv.items.get(i);
            if (isOwnSword(stack, player)) {
                inv.items.set(i, ItemStack.EMPTY);
                syncSlot(player, i);
                ModLog.inventory("AntiDisarm 清除重复星辉剑", player, "slot=" + i + " keepSlot=" + keepSlot);
            }
        }
        ItemStack offhand = inv.offhand.get(0);
        if (isOwnSword(offhand, player)) {
            inv.offhand.set(0, ItemStack.EMPTY);
            ModLog.inventory("AntiDisarm 清除副手重复星辉剑", player, "keepSlot=" + keepSlot);
        }
        inv.setChanged();
    }

    /** 是否是本玩家（或尚未绑定）的星辉剑。 */
    private static boolean isOwnSword(ItemStack stack, Player player) {
        if (!SwordGuard.isStarSword(stack)) {
            return false;
        }
        UUID owner = SwordGuard.getOwner(stack);
        return owner == null || owner.equals(player.getUUID());
    }

    /** 第 0 格是否有自己的星辉剑。 */
    public static boolean hasOwnSwordInSlot0(Player player) {
        return isOwnSword(player.getInventory().items.get(0), player);
    }

    /** 把第 0 格压着的普通物品挪到刚腾出的槽位，避免补剑时丢失。 */
    private static void moveSlot0Aside(ServerPlayer player, int freeSlot) {
        Inventory inv = player.getInventory();
        ItemStack existing = inv.items.get(0);
        if (existing.isEmpty() || SwordGuard.isStarSword(existing)) {
            return;
        }
        ItemStack copy = existing.copy();
        inv.items.set(0, ItemStack.EMPTY);
        if (freeSlot > 0 && freeSlot < inv.items.size() && inv.items.get(freeSlot).isEmpty()) {
            inv.items.set(freeSlot, copy);
            ModLog.inventory("AntiDisarm.moveSlot0Aside[层=迁移] 第0格普通物品挪到空槽", player,
                    "toSlot=" + freeSlot + " 物品=" + copy.getItem());
        } else if (!addToNonZeroSlots(inv, copy)) {
            ModLog.inventory("AntiDisarm.moveSlot0Aside[层=迁移] 放不下，掉落到脚下", player,
                    "物品=" + copy.getItem() + " x" + copy.getCount());
            player.drop(copy, false);
        }
    }

    /**
     * 把物品塞进除第 0 格以外的槽位（先并堆、再找空槽）。成功返回 true。
     * 不使用 {@code Inventory.add}：它会在物品恰好就是第 0 格本身时产生别名错乱，
     * 且第 0 格刚被腾空时可能把物品又放回第 0 格。
     */
    private static boolean addToNonZeroSlots(Inventory inv, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        for (int i = 1; i < inv.items.size() && !stack.isEmpty(); i++) {
            ItemStack slot = inv.items.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, stack)) {
                continue;
            }
            int room = slot.getMaxStackSize() - slot.getCount();
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, stack.getCount());
            slot.grow(move);
            stack.shrink(move);
        }
        for (int i = 1; i < inv.items.size() && !stack.isEmpty(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, stack.copy());
                stack.setCount(0);
            }
        }
        return stack.isEmpty();
    }

    // ===================== 跨玩家回收（3.1 / 3.2 / 3.3） =====================

    /**
     * 入口 3.1：玩家关闭容器时扫描其背包，把“原主已开启防缴械”的他人星辉剑归还。
     * 只扫玩家背包（主背包/盔甲/副手），绝不碰容器内容，因此“容器内可以存放”不受影响。
     *
     * @return 归还数量
     */
    public static int reclaimForeignSwordsOnContainerClose(ServerPlayer holder) {
        int returned = reclaimForeignSwords(holder);
        if (returned > 0 && holder != null) {
            // 关闭容器事件在 containerMenu 切回 inventoryMenu 之前触发，单槽同步会被容器判定跳过，
            // 这里全量刷新玩家背包内容（containerId=0），消除客户端残留的他人剑。
            syncInventory(holder);
            // 需求“可选：给当前玩家发一条提示”。
            holder.displayClientMessage(Component.literal("已归还 " + returned + " 把他人已开启防缴械的星辉剑")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return returned;
    }

    /**
     * 扫描 holder 的主背包/盔甲/副手，把属于“在线且已开启防缴械的原主”的星辉剑取出来归还。
     *
     * <p>统一判断顺序（与 {@code returnForeignSword} 一致，只是触发点扩展到其他玩家背包）：
     * 是星辉剑 → {@link SwordGuard#getOwner} → owner 为 null 跳过 → owner 就是 holder 跳过
     * → owner 未开启防缴械（或不在线）跳过 → 归还。
     *
     * @return 实际归还的数量
     */
    public static int reclaimForeignSwords(ServerPlayer holder) {
        if (holder == null || holder.getServer() == null) {
            return 0;
        }
        Inventory inv = holder.getInventory();
        int returned = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            returned += reclaimOne(holder, inv.items, i, i) ? 1 : 0;
        }
        for (int i = 0; i < inv.armor.size(); i++) {
            // Inventory 内部索引：36-39 为盔甲槽。
            returned += reclaimOne(holder, inv.armor, i, 36 + i) ? 1 : 0;
        }
        // Inventory 内部索引：40 为副手槽。
        returned += reclaimOne(holder, inv.offhand, 0, 40) ? 1 : 0;
        if (returned > 0) {
            inv.setChanged();
        }
        return returned;
    }

    /** 从 holder 的某个库存列表槽位取走一把“应归还”的剑并交还其在线原主。 */
    private static boolean reclaimOne(ServerPlayer holder, NonNullList<ItemStack> list,
                                      int listIndex, int invIndex) {
        ItemStack stack = list.get(listIndex);
        if (!shouldReclaim(holder, stack)) {
            return false;
        }
        ItemStack taken = stack.copy();
        UUID owner = SwordGuard.getOwner(taken);
        list.set(listIndex, ItemStack.EMPTY);
        ModLog.inventory("AntiDisarm.reclaimForeignSwords 取走他人星辉剑并归还原主", holder,
                "invIndex=" + invIndex + " owner=" + owner);
        returnForeignSword(holder, taken, owner);
        syncInvSlot(holder, invIndex, ItemStack.EMPTY);
        return true;
    }

    /** 统一判断：该剑是否应从 holder 背包拿走、归还给在线且已开启防缴械的原主。 */
    private static boolean shouldReclaim(ServerPlayer holder, ItemStack stack) {
        if (!SwordGuard.isStarSword(stack)) {
            return false;
        }
        UUID owner = SwordGuard.getOwner(stack);
        if (owner == null) {
            return false;
        }
        if (owner.equals(holder.getUUID())) {
            return false;
        }
        MinecraftServer server = holder.getServer();
        if (server == null) {
            return false;
        }
        ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
        if (ownerPlayer == null) {
            // 原主离线：无法读取其防缴械开关，暂不回收；上线后由登录/每 tick 逻辑处理。
            return false;
        }
        return isEnabled(ownerPlayer);
    }

    /**
     * 3.3 全局低频兜底：每 {@link #GLOBAL_SCAN_INTERVAL} tick 遍历所有在线玩家，
     * 把“属于在线且已开启防缴械的原主”的星辉剑收走归还。覆盖其它模组直接改背包、
     * 容器关闭前死亡/掉线等漏网路径。用 gameTime 计数节流，避免每 tick 全量扫描。
     */
    private static void maybeGlobalReclaim(ServerPlayer ticked) {
        MinecraftServer server = ticked.getServer();
        if (server == null) {
            return;
        }
        long now = ticked.level().getGameTime();
        if (lastGlobalScanTick != Long.MIN_VALUE
                && now - lastGlobalScanTick < GLOBAL_SCAN_INTERVAL) {
            return;
        }
        lastGlobalScanTick = now;
        int total = 0;
        for (ServerPlayer holder : server.getPlayerList().getPlayers()) {
            // 开着非玩家背包容器时不扫（容器槽位同步会被客户端按 containerId 丢弃，易留残影）；
            // 这类玩家在关闭容器时由入口 3.1 处理。
            if (!(holder.containerMenu instanceof InventoryMenu)) {
                continue;
            }
            total += reclaimForeignSwords(holder);
        }
        if (total > 0) {
            ModLog.inventory("AntiDisarm.maybeGlobalReclaim 兜底扫描归还他人星辉剑", ticked,
                    "总数=" + total);
        }
    }

    // ===================== 归还原主 =====================

    /** 把别人的星辉剑还给原主：在线且原主没有自己的剑则塞背包；离线存待回收。 */
    private static void returnForeignSword(ServerPlayer holder, ItemStack foreign, UUID owner) {
        ItemStack copy = foreign.copy();
        ServerPlayer ownerPlayer = holder.getServer() != null
                ? holder.getServer().getPlayerList().getPlayer(owner) : null;
        if (ownerPlayer == null) {
            PENDING_RETURN.computeIfAbsent(owner, k -> new ArrayList<>()).add(copy);
            ModLog.inventory("AntiDisarm.returnForeignSword 原主离线，暂存待归还", holder,
                    "owner=" + owner);
            return;
        }
        if (hasOwnSwordInSlot0(ownerPlayer)) {
            // 原主已有自己的剑（通常由备份回填），这颗是重复品，丢弃防复制。
            ModLog.inventory("AntiDisarm.returnForeignSword 原主已有剑，丢弃重复品", holder,
                    "owner=" + owner);
            return;
        }
        if (!ownerPlayer.getInventory().add(copy)) {
            ownerPlayer.drop(copy, false);
            ModLog.inventory("AntiDisarm.returnForeignSword 原主背包已满，掉落归还", holder,
                    "owner=" + owner);
        } else {
            ModLog.inventory("AntiDisarm.returnForeignSword 归还他人星辉剑给原主", holder,
                    "owner=" + owner);
            // 归还是服务端直接改背包，主动刷新原主客户端，避免其界面看不到剑。
            syncInventory(ownerPlayer);
        }
    }

    // ===================== 同步与生命周期 =====================

    /** 把开关与备份同步给客户端，供客户端预测性回填。 */
    public static void syncToClient(ServerPlayer player) {
        if (player == null) {
            ModLog.inventory("AntiDisarm.syncToClient 被拒：player 为 null", null, "");
            return;
        }
        ModMessages.sendAntiDisarmSync(isEnabled(player), getBackup(player), player);
        ModLog.inventory("AntiDisarm.syncToClient[层=服务端->客户端同步]", player,
                "enabled=" + isEnabled(player));
    }

    /** 若当前打开的是玩家背包，发槽位同步包；开着别的容器则跳过后由原版在下 tick 同步。 */
    private static void syncSlot(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= player.getInventory().items.size()) {
            return;
        }
        syncInvSlot(player, slot, player.getInventory().items.get(slot));
    }

    /**
     * 按 {@code Inventory} 内部索引（0-35 主背包、36-39 盔甲、40 副手）找到当前菜单里对应的槽位，
     * 发单槽同步包。玩家背包菜单槽位顺序与原版 {@code InventoryMenu} 一致但重新编号
     * （盔甲/快捷栏/副手不在同一编号段），直接拿内部索引当菜单槽位会错位，
     * 故通过 {@code Slot.container + getContainerSlot()} 反查。
     */
    private static void syncInvSlot(ServerPlayer player, int invIndex, ItemStack stack) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
            ModLog.inventory("AntiDisarm.syncInvSlot[层=槽位同步] 当前打开的容器不是玩家背包，跳过即时同步（交由原版下 tick 关容器后同步）",
                    player, "invIndex=" + invIndex);
            return;
        }
        int menuIndex = -1;
        for (int i = 0; i < player.inventoryMenu.slots.size(); i++) {
            Slot slot = player.inventoryMenu.slots.get(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == invIndex) {
                menuIndex = i;
                break;
            }
        }
        if (menuIndex < 0) {
            ModLog.inventory("AntiDisarm.syncInvSlot[层=槽位同步] 未找到内部索引对应的菜单槽位", player,
                    "invIndex=" + invIndex);
            return;
        }
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                player.inventoryMenu.containerId, player.inventoryMenu.getStateId(), menuIndex, stack));
    }

    /** 全量刷新某玩家的背包菜单内容到其客户端（归还他人剑后调用，避免客户端显示残留）。 */
    public static void syncInventory(ServerPlayer player) {
        if (player == null) {
            return;
        }
        player.inventoryMenu.sendAllDataToRemote();
    }

    /** 死亡/维度变更克隆玩家：沿用 PersistentData 里的开关与备份（NBT 已由事件拷贝）。 */
    public static void onClone(ServerPlayer newPlayer) {
        MEMORY_BACKUP.remove(newPlayer.getUUID());
        ItemStack backup = getBackup(newPlayer);
        if (!backup.isEmpty()) {
            MEMORY_BACKUP.put(newPlayer.getUUID(), backup);
        }
        ModLog.defense("AntiDisarm.onClone 克隆后恢复备份", newPlayer,
                "有备份=" + !backup.isEmpty());
    }

    /** 玩家登录：先归还离线期间待回收的剑，再同步开关与备份。 */
    public static void onLogin(ServerPlayer player) {
        List<ItemStack> pending = PENDING_RETURN.remove(player.getUUID());
        if (pending != null) {
            ModLog.inventory("AntiDisarm.onLogin 归还离线待回收剑", player, "数量=" + pending.size());
            for (ItemStack stack : pending) {
                if (hasOwnSwordInSlot0(player)) {
                    break;
                }
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                    ModLog.inventory("AntiDisarm.onLogin 背包满，掉落到脚下", player,
                            "物品=" + stack.getItem());
                }
            }
        }
        syncToClient(player);
        ModLog.defense("AntiDisarm.onLogin 同步开关/备份给客户端", player, "enabled=" + isEnabled(player));
    }
}
