package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public final class EffectHelper {

    public static void spawnRangeVisualEffects(ServerLevel level, Player player, boolean withSound) {
        double groundY = player.getY();
        for (double dx = -20; dx <= 20; dx += 3) {
            for (double dz = -20; dz <= 20; dz += 3) {
                double px = player.getX() + dx;
                double pz = player.getZ() + dz;
                level.sendParticles(ParticleTypes.END_ROD, px, groundY, pz,
                        1, 0, 0, 0, 0);
            }
        }

        for (double dx = -128; dx <= 128; dx += 24) {
            for (double dz = -128; dz <= 128; dz += 24) {
                double bx = player.getX() + dx;
                double bz = player.getZ() + dz;
                LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
                bolt.setPos(bx, groundY, bz);
                bolt.setVisualOnly(true);
                level.addFreshEntity(bolt);
                if (withSound) {
                    level.playSound(null, bx, groundY, bz,
                            SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10000.0F, 0.8F);
                }
            }
        }
    }

    public static void spawnAoEParticles(ServerLevel level, Player player, double radius) {
        for (int i = 0; i < 360; i += 15) {
            double rad = Math.toRadians(i);
            double x = player.getX() + Math.cos(rad) * radius;
            double z = player.getZ() + Math.sin(rad) * radius;
            level.sendParticles(ParticleTypes.END_ROD, x, player.getY() + 1, z,
                    1, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, player.getY() + 1.5, z,
                    1, 0, 0, 0, 0);
        }
    }

    public static void spawnLightningAt(Level level, BlockPos pos, boolean visualOnly) {
        if (level.isClientSide) return;
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        bolt.setPos(pos.getX(), pos.getY(), pos.getZ());
        bolt.setVisualOnly(visualOnly);
        level.addFreshEntity(bolt);
    }

    public static void spawnLightningWithSound(ServerLevel level, BlockPos pos) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        bolt.setPos(pos.getX(), pos.getY(), pos.getZ());
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
        level.playSound(null, pos.getX(), pos.getY(), pos.getZ(),
                SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10000.0F, 1.0F);
        level.playSound(null, pos.getX(), pos.getY(), pos.getZ(),
                SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 2.0F, 1.0F);
    }

    public static void spawnPlayerParticles(Level level, Player player) {
        double baseAngle = player.tickCount * 0.3;
        double yOff = player.getEyeHeight() * 0.5;
        double[] radii = {1.0, 1.5, 2.0};
        double angleStep = Math.toRadians(120);
        for (int i = 0; i < 3; i++) {
            double angle = baseAngle + angleStep * i;
            double r = radii[i];
            double x = player.getX() + Math.cos(angle) * r;
            double y = player.getY() + yOff + Math.sin(player.tickCount * 0.2) * 0.3;
            double z = player.getZ() + Math.sin(angle) * r;
            level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0.02, 0);
        }
    }

    /** 正在播放的“粒子立方体”特效（取代左键命中点从天空掉落的落雷视觉）。 */
    private static final List<StrikeCube> ACTIVE_STRIKE_CUBES = new ArrayList<>();

    /** 持续 ~1.4 秒（28 tick）的闪烁彩虹渐变粒子正方体。 */
    private static final class StrikeCube {
        final ServerLevel level;
        final double x, y, z;
        final long seed;
        int ticksLeft;

        StrikeCube(ServerLevel level, double x, double y, double z, int ticksLeft) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.seed = level.random.nextLong();
            this.ticksLeft = ticksLeft;
        }
    }

    public static void spawnStrikeCube(ServerLevel level, BlockPos pos) {
        spawnStrikeCube(level, Vec3.atCenterOf(pos));
    }

    public static void spawnStrikeCube(ServerLevel level, Vec3 center) {
        if (level == null || center == null) return;
        ACTIVE_STRIKE_CUBES.add(new StrikeCube(level, center.x, center.y, center.z, 28));
    }

    /** 服务端每 tick 调用一次：驱动所有活跃粒子立方体的“闪烁”。 */
    public static void tickStrikeCubes() {
        Iterator<StrikeCube> it = ACTIVE_STRIKE_CUBES.iterator();
        while (it.hasNext()) {
            StrikeCube cube = it.next();
            if (--cube.ticksLeft <= 0) {
                it.remove();
                continue;
            }
            emitStrikeFrame(cube);
        }
    }

    /** 每帧在立方体的 12 条棱上随机取样发光粒子：位置随机跳跃(闪烁)+颜色随时间/位置循环(彩虹渐变)。 */
    private static void emitStrikeFrame(StrikeCube cube) {
        ServerLevel level = cube.level;
        if (level.getServer() == null || !level.getServer().isRunning()) return;
        Random rnd = new Random(cube.seed ^ (cube.ticksLeft * 0x9E3779B97F4A7C15L));
        double half = 1.25;
        double[] corner = {-half, half};
        for (double dy : corner) {
            for (double dz : corner) {
                emitStrikeEdge(cube, rnd,
                        cube.x - half, cube.y + dy, cube.z + dz,
                        cube.x + half, cube.y + dy, cube.z + dz);
            }
        }
        for (double dx : corner) {
            for (double dz : corner) {
                emitStrikeEdge(cube, rnd,
                        cube.x + dx, cube.y - half, cube.z + dz,
                        cube.x + dx, cube.y + half, cube.z + dz);
            }
        }
        for (double dx : corner) {
            for (double dy : corner) {
                emitStrikeEdge(cube, rnd,
                        cube.x + dx, cube.y + dy, cube.z - half,
                        cube.x + dx, cube.y + dy, cube.z + half);
            }
        }
    }

    private static void emitStrikeEdge(StrikeCube cube, Random rnd,
                                       double x1, double y1, double z1,
                                       double x2, double y2, double z2) {
        ServerLevel level = cube.level;
        int samples = 2;
        double sx = (x2 - x1) / samples;
        double sy = (y2 - y1) / samples;
        double sz = (z2 - z1) / samples;
        for (int i = 0; i <= samples; i++) {
            if (rnd.nextFloat() < 0.55F) continue; // 随机跳过 = 闪烁感
            double px = x1 + sx * i;
            double py = y1 + sy * i;
            double pz = z1 + sz * i;
            // hue 同时由“存活时间”与“棱上位置”决定 → 整体随时间流动的彩虹渐变
            float hue = ((float) cube.ticksLeft * 0.03F + (float) i / (samples + 1) * 0.6F) % 1.0F;
            Vector3f color = hsvToRgb(hue);
            float scale = 0.5F + rnd.nextFloat() * 1.3F;
            double jx = px + (rnd.nextDouble() - 0.5) * 0.3;
            double jy = py + (rnd.nextDouble() - 0.5) * 0.3;
            double jz = pz + (rnd.nextDouble() - 0.5) * 0.3;
            DustParticleOptions options = new DustParticleOptions(color, scale);
            // longDistance=true：保证 80 格外触发的左键玩家本人也能看到
            for (ServerPlayer player : level.players()) {
                level.sendParticles(player, options, true, jx, jy, jz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private static Vector3f hsvToRgb(float hue) {
        float h = hue - (float) Math.floor(hue);
        int i = (int) (h * 6.0F);
        float f = h * 6.0F - i;
        float q = 1.0F - f;
        float r, g, b;
        switch (i) {
            case 0: r = 1.0F; g = f; b = 0.0F; break;
            case 1: r = q; g = 1.0F; b = 0.0F; break;
            case 2: r = 0.0F; g = 1.0F; b = f; break;
            case 3: r = 0.0F; g = q; b = 1.0F; break;
            case 4: r = f; g = 0.0F; b = 1.0F; break;
            default: r = 1.0F; g = 0.0F; b = q; break;
        }
        return new Vector3f(r, g, b);
    }
}
