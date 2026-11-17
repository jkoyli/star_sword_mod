package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.*;

public final class ModConstants {

    /**
     * 主动掉落单次保证集合：凡已调用过 {@code dropAllDeathLoot} 的实体（原版 die() 掉过，
     * 或本模组强制归零血量绕过 die() 后补掉）都记在这里，避免重复掉落。
     * 随死亡锁 {@code releaseLock} 清理。
     */
    public static final Set<UUID> LOOT_DROPPED = new HashSet<>();
    public static final Set<EntityType<?>> PREVENTED_ENTITY_TYPES = new HashSet<>();
    public static boolean PREVENT_SPAWN_ENABLED = false;
    // [RESPAWN-MARK] 重生：死亡前位置记录，用于受保护玩家死亡克隆/重生后回填位置。
    public static final Map<UUID, BlockPos> DEATH_LOCATIONS = new HashMap<>();
    public static final Set<UUID> B1_PROCESSING = new HashSet<>();
    public static final Map<UUID, Boolean> FLY_STATES = new HashMap<>();
    /**
     * 反击之符（{@code COUNTERATTACK_ENABLED}）的默认值：<b>默认关闭</b>。
     *
     * <p>历史默认是“开”。实战日志（`latest.log` + `star_sword_mod_*.log`）显示：玩家被持续
     * 命中时，{@code CounterAttack.onHurt} 每次都会对攻击者连打 3 下（含
     * {@code Float.MAX_VALUE} 的绝对伤害），对 TrialMonolith 这类“血量恒为 Infinity、
     * hurt() 恒返回 false”的 Boss 毫无效果，却会持续触发对方的受击生成逻辑，
     * 2 分钟堆出约 9,100 个 SmallBeam/DamageCube 实体，把主线程拖到 "Can't keep up 78 ticks"。
     * 因此改为默认关闭，需要时用反击之符右键手动开启（{@code TalismanHelper.toggleCounterattack}）。
     *
     * <p>这里只给出“没有显式设置过”时的取值；玩家一旦切换过，{@code COUNTERATTACK_ENABLED}
     * 里的记录优先。
     */
    public static final boolean COUNTERATTACK_DEFAULT_ENABLED = false;
    public static final Map<UUID, Boolean> COUNTERATTACK_ENABLED = new HashMap<>();
    public static final Map<UUID, Boolean> REFLECTION_ENABLED = new HashMap<>();
    public static final Map<UUID, KillHelper.ScheduledKill> SCHEDULED_KILLS = new HashMap<>();
    public static final Set<UUID> KILLED_BY_STAR_SWORD = new HashSet<>();
    public static final Map<UUID, Integer> KILL_LOCK_TTL = new HashMap<>();
    public static final Map<UUID, Integer> KILL_LOCK_FORCE_LEFT = new HashMap<>();
    public static final Map<UUID, Integer> KILL_LOCK_DEAD_TICKS = new HashMap<>();
    public static final Set<UUID> KILL_SETHEALTH_GUARD = new HashSet<>();
    public static final Set<UUID> ATTACK_TARGETS = new HashSet<>();
    /** 正在执行“星辉剑回收”的玩家：这期间的主动掉出（背包塞不下时掉脚下）要放行，不被丢出拦截逻辑拦下。 */
    public static final Set<UUID> RECOVERING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static final ResourceKey<DamageType> STAR_SWORD_ABSOLUTE_KEY =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    new ResourceLocation(StarSwordMod.MOD_ID, "star_sword_absolute"));

    public static DamageSource createStarSwordAbsoluteDamageSource(Level level, @Nullable LivingEntity attacker) {
        return new DamageSource(
                level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(STAR_SWORD_ABSOLUTE_KEY),
                attacker);
    }

    public static boolean hasSwordInInventory(net.minecraft.world.entity.player.Player player) {
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof cn.autoforged.star_sword_mod_1785589476.item.StarSwordItem)
                return true;
        }
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().armor) {
            if (stack.getItem() instanceof cn.autoforged.star_sword_mod_1785589476.item.StarSwordItem)
                return true;
        }
        if (player.getInventory().offhand.get(0).getItem() instanceof cn.autoforged.star_sword_mod_1785589476.item.StarSwordItem)
            return true;
        return false;
    }

    public static boolean isHoldingSword(net.minecraft.world.entity.player.Player player) {
        return player.getMainHandItem().getItem() instanceof cn.autoforged.star_sword_mod_1785589476.item.StarSwordItem
                || player.getOffhandItem().getItem() instanceof cn.autoforged.star_sword_mod_1785589476.item.StarSwordItem;
    }
}
