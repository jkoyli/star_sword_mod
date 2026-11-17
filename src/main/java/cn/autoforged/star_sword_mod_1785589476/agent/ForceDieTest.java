package cn.autoforged.star_sword_mod_1785589476.agent;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.RawState;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Development-only hook: walks a live {@link ServerPlayer} through the whole death /
 * removal pipeline once, so the agent-injected gates there can actually be observed.
 *
 * <p>Without it those gates never execute in a dev world: the forced-protection mode
 * ({@code -Dstar_sword.poc.forceDie} is only meaningful together with it) keeps the
 * player alive, and a peaceful test world produces no damage at all.
 *
 * <p>Enabled by {@code -Dstar_sword.poc.forceDie=1} ({@code -PssAgentForceDie=1} on the
 * Gradle run tasks). Off by default; it is a test harness, not defence code.
 */
public final class ForceDieTest {

    private static final boolean ENABLED = truthy(System.getProperty("star_sword.poc.forceDie"));

    /** Let the world settle before touching the player. */
    private static final int DELAY_TICKS = 60;

    /** {@code LivingEntity.dropAllDeathLoot} in named / SRG form. */
    private static final String[] LOOT_METHODS = {"dropAllDeathLoot", "m_6668_"};

    private static boolean done;

    private ForceDieTest() {
    }

    private static boolean truthy(String value) {
        return value != null && ("1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }

    /** Called every server tick per player; acts once. */
    public static void maybeRun(ServerPlayer player) {
        if (!ENABLED || done || player == null || player.tickCount < DELAY_TICKS) {
            return;
        }
        done = true;
        DamageSource source = player.damageSources().genericKill();
        log("stage1: hurt(source, 1000) -> expects the agent hurt gate");
        try {
            log("  result=" + player.hurt(source, 1000.0F) + " health=" + player.getHealth());
        } catch (Throwable t) {
            log("  hurt threw: " + t);
        }

        log("stage2: die(source) -> expects the agent die gate");
        try {
            player.die(source);
        } catch (Throwable t) {
            log("  die threw: " + t);
        }

        log("stage3: dropAllDeathLoot(source) via reflection -> expects the loot gate");
        invokeDropAllDeathLoot(player, source);

        log("stage4: remove(KILLED) -> expects the remove gate");
        try {
            player.remove(Entity.RemovalReason.KILLED);
        } catch (Throwable t) {
            log("  remove threw: " + t);
        }

        log("stage5: isRemoved() -> expects the isRemoved gate");
        try {
            log("  isRemoved=" + player.isRemoved());
        } catch (Throwable t) {
            log("  isRemoved threw: " + t);
        }

        log("stage6: synched-data write lowering health -> expects the serializer-level write gate");
        try {
            var accessor = ReflectionHelper.healthDataAccessor();
            var synched = player.getEntityData();
            Object before = RawState.rawValue(synched, accessor);
            synched.set(accessor, 1.0F);
            Object after = RawState.rawValue(synched, accessor);
            log("  raw slot before=" + before + " after lower-write attempt=" + after);
        } catch (Throwable t) {
            log("  synched write test threw: " + t);
        }

        log("stage7: saveWithoutId -> expects beforeSave to overwrite Health/DeathTime");
        try {
            CompoundTag tag = new CompoundTag();
            player.saveWithoutId(tag);
            log("  saved Health=" + tag.getFloat("Health") + " DeathTime=" + tag.getShort("DeathTime"));
        } catch (Throwable t) {
            log("  save test threw: " + t);
        }

        log("stage8: load a poisoned tag -> expects afterLoad to repair it");
        try {
            CompoundTag broken = new CompoundTag();
            player.saveWithoutId(broken);
            broken.putFloat("Health", 0.0F);
            broken.putShort("DeathTime", (short) 300);
            player.load(broken);
            log("  repaired Health=" + broken.getFloat("Health")
                    + " DeathTime=" + broken.getShort("DeathTime"));
        } catch (Throwable t) {
            log("  load test threw: " + t);
        }

        log("stage9: mark dead=true + deathTime=100 -> expects DeathWatchdog pseudo-revive next tick");
        try {
            Field deadField = ReflectionHelper.findFieldCompat(
                    LivingEntity.class, "f_20890_", "dead");
            if (deadField != null) {
                deadField.setBoolean(player, true);
            }
            player.deathTime = 100;
            log("  dead=" + (deadField != null && deadField.getBoolean(player))
                    + " deathTime=" + player.deathTime);
        } catch (Throwable t) {
            log("  stage9 threw: " + t);
        }

        log("stage10: send a forged ClientboundSetHealthPacket(0.0) -> expects OutgoingFilter to rewrite it");
        try {
            player.connection.send(new ClientboundSetHealthPacket(0.0F, 20, 5.0F));
            log("  forged packet sent (原血量 0.0)");
        } catch (Throwable t) {
            log("  stage10 threw: " + t);
        }

        log("stage11: save assertion -> saved Health must equal REAL_HEALTH(20), not the live value");
        try {
            // Make the player genuinely protected (a real star sword in the inventory), then set the
            // authoritative bypass record to 20 while the synced slot deliberately disagrees.
            player.getInventory().add(new net.minecraft.world.item.ItemStack(
                    cn.autoforged.star_sword_mod_1785589476.item.ModItems.STAR_SWORD.get()));
            PlayerDefense.acceptSyncedRealHealth(player, 20.0F);
            cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper
                    .setHealthDirectly(player, 7.0F);
            CompoundTag tag = new CompoundTag();
            AgentHooks.beforeSave(player, tag);
            float saved = tag.getFloat("Health");
            log("  REAL_HEALTH=20.0  live getHealth=" + player.getHealth()
                    + "  saved Health=" + saved
                    + "  ASSERT(saved==20.0)=" + (saved == 20.0F));
        } catch (Throwable t) {
            log("  stage11 threw: " + t);
        }

        log("done");
    }

    private static void invokeDropAllDeathLoot(ServerPlayer player, DamageSource source) {
        for (String name : LOOT_METHODS) {
            try {
                Method method = LivingEntity.class.getDeclaredMethod(name, DamageSource.class);
                method.setAccessible(true);
                method.invoke(player, source);
                log("  invoked " + name);
                return;
            } catch (NoSuchMethodException ignored) {
                // try the next name
            } catch (ReflectiveOperationException e) {
                log("  " + name + " failed: " + e);
                return;
            }
        }
        log("  dropAllDeathLoot not found (tried " + String.join(", ", LOOT_METHODS) + ")");
    }

    private static void log(String message) {
        ModLog.log(ModLog.TEST, message);
    }
}
