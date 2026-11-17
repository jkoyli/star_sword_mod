package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

public final class TalismanHelper {

    // [DEFENSE-MARK] 防御：反击之符开关（受保护玩家受伤时反伤攻击者）。
    public static boolean toggleCounterattack(Player player) {
        java.util.UUID playerId = player.getUUID();
        boolean current = ModConstants.COUNTERATTACK_ENABLED.getOrDefault(
                playerId, ModConstants.COUNTERATTACK_DEFAULT_ENABLED);
        ModConstants.COUNTERATTACK_ENABLED.put(playerId, !current);
        player.displayClientMessage(
                Component.literal("Counterattack: " + (!current ? "ON" : "OFF")), true);
        ModLog.defense("反击之符切换", player, "COUNTERATTACK_ENABLED=" + (!current));
        return !current;
    }

    public static boolean toggleReflection(Player player) {
        java.util.UUID playerId = player.getUUID();
        boolean current = ModConstants.REFLECTION_ENABLED.getOrDefault(playerId, false);
        ModConstants.REFLECTION_ENABLED.put(playerId, !current);
        player.displayClientMessage(
                Component.literal("Reflection: " + (!current ? "ON" : "OFF")), true);
        ModLog.defense("反射之符切换", player, "REFLECTION_ENABLED=" + (!current));
        return !current;
    }

    public static boolean toggleSpawnPrevention(Player player) {
        ModConstants.PREVENT_SPAWN_ENABLED = !ModConstants.PREVENT_SPAWN_ENABLED;
        if (!ModConstants.PREVENT_SPAWN_ENABLED) {
            ModConstants.PREVENTED_ENTITY_TYPES.clear();
        }
        player.displayClientMessage(
                Component.literal("Spawn Prevention: " + (ModConstants.PREVENT_SPAWN_ENABLED ? "ON" : "OFF")), true);
        ModLog.defense("禁生之符切换", player, "PREVENT_SPAWN_ENABLED=" + ModConstants.PREVENT_SPAWN_ENABLED);
        return ModConstants.PREVENT_SPAWN_ENABLED;
    }

    public static void handleReflectionKill(Player player) {
        EntityHitResult hit = EntitySearchHelper.raycastEntity(player, player.level(), 64.0);
        if (hit != null && hit.getEntity() instanceof LivingEntity target) {
            if (!(target instanceof Player)) {
                ModLog.attack("handleReflectionKill 反射击杀", player, target,
                        "命中射线目标，调用 executeReflectionKill");
                KillHelper.executeReflectionKill(target, player, player.level());
                player.displayClientMessage(
                        Component.literal("\u00a7bReflection kill executed on: \u00a7f"
                                + target.getName().getString()), true);
                return;
            }
        }
        ModLog.attack("handleReflectionKill 未找到有效目标", player, null);
        player.displayClientMessage(
                Component.literal("\u00a7cNo valid target in sight for reflection kill"), true);
    }
}
