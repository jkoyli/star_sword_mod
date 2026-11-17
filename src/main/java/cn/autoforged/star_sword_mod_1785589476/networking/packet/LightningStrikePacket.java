package cn.autoforged.star_sword_mod_1785589476.networking.packet;

import cn.autoforged.star_sword_mod_1785589476.helper.EffectHelper;
import cn.autoforged.star_sword_mod_1785589476.helper.KillHelper;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class LightningStrikePacket {

    private final BlockPos pos;
    private final boolean isShift;
    private final int entityId;

    public LightningStrikePacket(BlockPos pos, boolean isShift, int entityId) {
        this.pos = pos;
        this.isShift = isShift;
        this.entityId = entityId;
    }

    public static void encode(LightningStrikePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeBoolean(msg.isShift);
        buf.writeVarInt(msg.entityId);
    }

    public static LightningStrikePacket decode(FriendlyByteBuf buf) {
        return new LightningStrikePacket(buf.readBlockPos(), buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(LightningStrikePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                ModLog.attack("LightningStrikePacket.handle 被拒：sender 为 null", null, null);
                return;
            }
            if (player.level().isClientSide) {
                ModLog.attack("LightningStrikePacket.handle 被拒：非服务端", player, null);
                return;
            }
            if (!ModConstants.isHoldingSword(player)) {
                ModLog.attackThrottled("LightningStrikePacket.notHolding:" + player.getUUID(),
                        ModLog.TICK_INTERVAL,
                        "LightningStrikePacket.handle 被拒：未手持星辉剑", player, null);
                return;
            }

            ServerLevel level = player.serverLevel();

            LivingEntity target = null;
            if (msg.entityId >= 0) {
                Entity entity = level.getEntity(msg.entityId);
                if (entity instanceof LivingEntity le && entity != player) {
                    target = le;
                }
            }

            if (target != null) {
                // 取消“从天空掉落”的落雷实体，改为在命中实体处生成闪烁的彩虹渐变粒子正方体
                Vec3 center = target.getBoundingBox().getCenter();
                EffectHelper.spawnStrikeCube(level, center);

                if (msg.isShift) {
                    ModLog.attack("LightningStrikePacket 左键射线攻击(shift)", player, target,
                            "调用=executeFullAttackWithHealthZero targetId=" + msg.entityId);
                    level.playSound(null, center.x, center.y, center.z,
                            SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10000.0F, 1.0F);
                    KillHelper.executeFullAttackWithHealthZero(target, player, level);
                    if (!(target instanceof Player)) {
                        KillHelper.scheduleDelayedKill(target, player, level);
                    }
                } else {
                    ModLog.attack("LightningStrikePacket 左键射线攻击", player, target,
                            "调用=executeFullAttack targetId=" + msg.entityId);
                    KillHelper.executeFullAttack(target, player, level);
                }
            } else {
                ModLog.attack("LightningStrikePacket 射线未命中实体，仅生成粒子", player, null,
                        "pos=" + msg.pos + " shift=" + msg.isShift);
                EffectHelper.spawnStrikeCube(level, msg.pos);
                if (msg.isShift) {
                    level.playSound(null, msg.pos.getX() + 0.5, msg.pos.getY() + 0.5, msg.pos.getZ() + 0.5,
                            SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10000.0F, 1.0F);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
