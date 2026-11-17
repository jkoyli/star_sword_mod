package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

public final class EntitySearchHelper {

    public static EntityHitResult raycastEntity(Player player, Level level, double reach) {
        HitResult hit = player.pick(reach, 1.0F, false);
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof LivingEntity && entity != player) {
                ModLog.attack("raycastEntity[层=原版pick] 命中实体", player, entity, "reach=" + reach);
                return entityHit;
            }
            ModLog.attack("raycastEntity[层=原版pick] 命中实体但不是有效活体目标（自身/非LivingEntity），回退准星扫描",
                    player, entity, "reach=" + reach);
        } else {
            ModLog.attack("raycastEntity[层=原版pick] 未命中实体，回退准星扫描", player, null,
                    "reach=" + reach + " hitType=" + hit.getType());
        }
        LivingEntity fallbackTarget = findEntityInCrosshair(player, level, reach);
        if (fallbackTarget != null) {
            ModLog.attack("raycastEntity[层=准星回退] 命中实体", player, fallbackTarget);
            return new EntityHitResult(fallbackTarget);
        }
        ModLog.attack("raycastEntity[层=准星回退] 仍未命中目标", player, null);
        return null;
    }

    public static LivingEntity findEntityInCrosshair(Player player, Level level, double reach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 lookVec = player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(lookVec.scale(reach));

        AABB searchBox = new AABB(eyePos, endPos).inflate(1.5);

        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, searchBox, e -> e != player)) {
            Optional<Vec3> hit = entity.getBoundingBox().inflate(0.3).clip(eyePos, endPos);
            if (hit.isPresent()) {
                double dist = eyePos.distanceToSqr(hit.get());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = entity;
                }
            }
        }

        return best;
    }

    public static void aoeAttackInRadius(Level level, Player player, double radius) {
        AABB area = player.getBoundingBox().inflate(radius);
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e != player && e.isAlive());

        ModLog.attack("aoeAttackInRadius 范围攻击", player, null,
                "radius=" + radius + " 目标数=" + targets.size());
        for (LivingEntity target : targets) {
            ModLog.attack("aoeAttackInRadius 逐目标调用 executeFullAttack", player, target);
            KillHelper.executeFullAttack(target, player, level);
        }
        EffectHelper.spawnAoEParticles((net.minecraft.server.level.ServerLevel) level, player, radius);
    }

    public static void clearMobsInRadius(Level level, Player player, double radius) {
        AABB area = player.getBoundingBox().inflate(radius);
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e != player && e.isAlive() && !(e instanceof Player));

        // 移除模式开启时绕过 Entity.remove（可能被其他模组拦截），直接摘实体存储。
        boolean bypassRemove = RemoveMode.isEnabled(player);
        ModLog.attack("clearMobsInRadius shift 清场", player, null,
                "radius=" + radius + " 目标数=" + targets.size() + " 移除模式=" + bypassRemove);
        for (LivingEntity target : targets) {
            ModLog.attack("clearMobsInRadius 逐目标调用 executeFullAttackWithHealthZero + "
                            + (bypassRemove ? "EntityRemover.hardRemove" : "forceRemoveAndDrop"),
                    player, target);
            KillHelper.executeFullAttackWithHealthZero(target, player, level);
            if (bypassRemove && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                EntityRemover.hardRemove(target, serverLevel);
            } else {
                LootHelper.forceRemoveAndDrop(target, level);
            }
        }

        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, area);
        for (ItemEntity item : items) {
            item.discard();
        }
    }

    public static void attractItemsInRadius(Level level, Player player) {
        // A11（可开关）：关闭后右键不再远距离拾取，只保留 AoE 攻击。
        if (!AttackOptions.itemAttraction()) {
            ModLog.inventory("attractItemsInRadius 跳过：远距离拾取开关已关闭", player, "半径=128");
            return;
        }
        AABB area = player.getBoundingBox().inflate(128);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, area);
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) continue;
            ModLog.inventory("attractItemsInRadius 吸引掉落物入背包", player,
                    "物品=" + stack.getItem() + " x" + stack.getCount()
                            + " 掉落物=" + ModLog.describe(itemEntity));
            player.getInventory().add(stack);
            if (stack.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setPos(player.getX(), player.getY(), player.getZ());
            }
        }
    }
}
