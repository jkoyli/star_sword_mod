package cn.autoforged.star_sword_mod_1785589476.networking;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.CommandExecutePacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.CommandTreeRequestPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.CommandTreeSyncPacket;
import cn.autoforged.star_sword_mod_1785589476.helper.MechanicService;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.LightningStrikePacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.MechanicResetPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.MechanicSetPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.MechanicSnapshotPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.MechanicSnapshotRequestPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.MissingSwordPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.SyncAntiDisarmPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.SyncProtectedStatePacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.SyncRealHealthPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.ToggleAntiDisarmPacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.ToggleRemoveModePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModMessages {

    private static SimpleChannel INSTANCE;
    private static final String PROTOCOL_VERSION = "1";
    private static int packetId = 0;

    public static void register() {
        SimpleChannel channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(StarSwordMod.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals);

        channel.registerMessage(packetId++,
                LightningStrikePacket.class,
                LightningStrikePacket::encode,
                LightningStrikePacket::decode,
                LightningStrikePacket::handle);

        channel.registerMessage(packetId++,
                CommandExecutePacket.class,
                CommandExecutePacket::encode,
                CommandExecutePacket::decode,
                CommandExecutePacket::handle);

        channel.registerMessage(packetId++,
                CommandTreeRequestPacket.class,
                CommandTreeRequestPacket::encode,
                CommandTreeRequestPacket::decode,
                CommandTreeRequestPacket::handle);

        channel.registerMessage(packetId++,
                CommandTreeSyncPacket.class,
                CommandTreeSyncPacket::encode,
                CommandTreeSyncPacket::decode,
                CommandTreeSyncPacket::handle);

        channel.registerMessage(packetId++,
                ToggleAntiDisarmPacket.class,
                ToggleAntiDisarmPacket::encode,
                ToggleAntiDisarmPacket::decode,
                ToggleAntiDisarmPacket::handle);

        channel.registerMessage(packetId++,
                MissingSwordPacket.class,
                MissingSwordPacket::encode,
                MissingSwordPacket::decode,
                MissingSwordPacket::handle);

        channel.registerMessage(packetId++,
                SyncAntiDisarmPacket.class,
                SyncAntiDisarmPacket::encode,
                SyncAntiDisarmPacket::decode,
                SyncAntiDisarmPacket::handle);

        channel.registerMessage(packetId++,
                ToggleRemoveModePacket.class,
                ToggleRemoveModePacket::encode,
                ToggleRemoveModePacket::decode,
                ToggleRemoveModePacket::handle);

        // 追加式注册（保持既有 packet id 不变）：服务端 -> 客户端同步旁路真实血量。
        channel.registerMessage(packetId++,
                SyncRealHealthPacket.class,
                SyncRealHealthPacket::encode,
                SyncRealHealthPacket::decode,
                SyncRealHealthPacket::handle);

        // 追加式注册（保持既有 packet id 不变）：服务端 -> 客户端同步“玩家是否受保护”的权威状态。
        channel.registerMessage(packetId++,
                SyncProtectedStatePacket.class,
                SyncProtectedStatePacket::encode,
                SyncProtectedStatePacket::decode,
                SyncProtectedStatePacket::handle);

        // 机制面板：快照（S2C）+ 设值/请求（C2S）。同样追加在末尾，老 id 一个不动。
        channel.registerMessage(packetId++,
                MechanicSnapshotPacket.class,
                MechanicSnapshotPacket::encode,
                MechanicSnapshotPacket::decode,
                MechanicSnapshotPacket::handle);

        channel.registerMessage(packetId++,
                MechanicSetPacket.class,
                MechanicSetPacket::encode,
                MechanicSetPacket::decode,
                MechanicSetPacket::handle);

        channel.registerMessage(packetId++,
                MechanicSnapshotRequestPacket.class,
                MechanicSnapshotRequestPacket::encode,
                MechanicSnapshotRequestPacket::decode,
                MechanicSnapshotRequestPacket::handle);

        // 删除存档项（回到默认）。
        channel.registerMessage(packetId++,
                MechanicResetPacket.class,
                MechanicResetPacket::encode,
                MechanicResetPacket::decode,
                MechanicResetPacket::handle);

        INSTANCE = channel;
    }

    // ===================== 机制面板（追加式注册，保持既有 packet id 不变） =====================

    /** 客户端 -> 服务端：请求一份机制开关快照。 */
    public static void sendMechanicSnapshotRequest() {
        INSTANCE.sendToServer(new MechanicSnapshotRequestPacket());
    }

    /** 客户端 -> 服务端：把某个机制开关设成指定值（幂等，不是取反）。 */
    public static void sendMechanicSet(String id, boolean value) {
        INSTANCE.sendToServer(new MechanicSetPacket(id, value));
    }

    /** 客户端 -> 服务端：删除某个机制的存档项（回到默认）。 */
    public static void sendMechanicReset(String id) {
        INSTANCE.sendToServer(new MechanicResetPacket(id));
    }

    /** 服务端 -> 指定客户端：回发全量快照，让界面收敛到权威值。 */
    public static void sendMechanicSnapshot(ServerPlayer player) {
        if (player == null) {
            return;
        }
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                new MechanicSnapshotPacket(MechanicService.snapshot(player)));
    }

    public static void sendToggleAntiDisarm() {
        INSTANCE.sendToServer(new ToggleAntiDisarmPacket());
    }

    public static void sendToggleRemoveMode() {
        INSTANCE.sendToServer(new ToggleRemoveModePacket());
    }

    /** [DEFENSE-MARK] 防御（防缴械）：客户端 -> 服务端缺剑通知。 */
    public static void sendMissingSword() {
        INSTANCE.sendToServer(new MissingSwordPacket());
    }

    /** [DEFENSE-MARK] 防御（防缴械）：服务端 -> 客户端同步防缴械开关与备份。 */
    public static void sendAntiDisarmSync(boolean enabled, ItemStack backup, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new SyncAntiDisarmPacket(enabled, backup));
    }

    /** [DEFENSE-MARK] 防御：服务端 -> 客户端同步受保护玩家的旁路真实血量（{@code active=false} 表示清除）。 */
    public static void sendRealHealthSync(ServerPlayer player, float health, boolean active) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new SyncRealHealthPacket(health, active));
    }

    /** [DEFENSE-MARK] 防御：服务端 -> 指定客户端同步某个玩家的保护状态。 */
    public static void sendProtectedState(ServerPlayer target, java.util.UUID playerId, boolean protectedState) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> target),
                new SyncProtectedStatePacket(playerId, protectedState));
    }

    /** [DEFENSE-MARK] 防御：服务端 -> 所有客户端广播某个玩家的保护状态（其他玩家视角的血条也需要）。 */
    public static void sendProtectedStateToAll(java.util.UUID playerId, boolean protectedState) {
        INSTANCE.send(PacketDistributor.ALL.noArg(),
                new SyncProtectedStatePacket(playerId, protectedState));
    }

    public static void sendLightningPacket(BlockPos pos, boolean isShift, int entityId) {
        INSTANCE.sendToServer(new LightningStrikePacket(pos, isShift, entityId));
    }

    public static void sendToServer(Object msg) {
        INSTANCE.sendToServer(msg);
    }

    public static void sendToPlayer(Object msg, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }
}
