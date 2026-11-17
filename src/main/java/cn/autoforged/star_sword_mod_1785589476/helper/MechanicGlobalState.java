package cn.autoforged.star_sword_mod_1785589476.helper;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * 全服机制开关的持久化（世界存档的 {@code data/star_sword_mechanic_settings.dat}）。
 *
 * <p>「禁止生成」与「严格写入语义」本来只活在内存里，重启即回到默认/启动参数。现在落一张
 * {@link SavedData}：读档时逐项走注册表里的写入器（因此切换效果与面板点击完全一致），
 * 面板改一项就 {@link #setDirty()}，由原版在自动保存/停机时写盘。
 *
 * <p><b>「删除存档项」为什么需要 {@link #cleared}</b>：{@link #save} 会按内存当前值整体刷新，
 * 如果只把键从 {@code values} 里删掉，下一次保存又会把「默认值」写回去——删除就变成一次性的。
 * 因此被显式删除的 id 会记进 {@code cleared}，保存时跳过它们；只有再次被 {@link #remember}
 * 写入时才从 {@code cleared} 移出。这样「删除」才真正等于「下次启动回到默认 / 回到 -D」。
 */
public final class MechanicGlobalState extends SavedData {

    /** 存档数据名。 */
    public static final String DATA_NAME = "star_sword_mechanic_settings";

    private final CompoundTag values = new CompoundTag();

    /** 被面板显式删除、且尚未重新写入的项；保存时必须跳过，否则会被默认值重新写回。 */
    private final Set<String> cleared = new HashSet<>();

    public static MechanicGlobalState load(CompoundTag tag) {
        MechanicGlobalState state = new MechanicGlobalState();
        CompoundTag stored = tag.getCompound("values");
        for (String id : stored.getAllKeys()) {
            MechanicRegistry.Def def = MechanicRegistry.byId(id);
            boolean value = stored.getBoolean(id);
            if (def != null && !def.isPlayerScoped() && def.globalSetter != null) {
                // 读档即生效：与面板写入走同一个 setter，避免两套语义。
                def.globalSetter.accept(value);
            }
            state.values.putBoolean(id, value);
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        // 先按当前内存值刷新一遍，保证运行期改动（面板/命令）不会被旧快照覆盖；
        // 但被显式删除的项要跳过，否则「删除存档项」会被下一次自动保存撤销。
        for (MechanicRegistry.Def def : MechanicRegistry.all()) {
            if (def.isPlayerScoped() || def.globalGetter == null) {
                continue;
            }
            if (this.cleared.contains(def.id)) {
                this.values.remove(def.id);
                continue;
            }
            this.values.putBoolean(def.id, def.globalGetter.getAsBoolean());
        }
        tag.put("values", this.values.copy());
        return tag;
    }

    /** 服务端取实例（不存在则新建并读档）。 */
    public static MechanicGlobalState get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                MechanicGlobalState::load, MechanicGlobalState::new, DATA_NAME);
    }

    /** 记录一项并标脏；真正的落盘由原版在自动保存/停机时完成。 */
    public static void remember(MinecraftServer server, String id, boolean value) {
        MechanicGlobalState state = get(server);
        if (state == null) {
            return;
        }
        state.cleared.remove(id);
        state.values.putBoolean(id, value);
        state.setDirty();
    }

    /**
     * 删除这一项在存档里的记录（面板上的「删除存档项」）。
     *
     * <p>删除会跨保存保持：{@code cleared} 让 {@link #save} 跳过该项，于是下次启动不再覆盖
     * 启动参数（该回默认就回默认）。本局的内存值由调用方（{@link MechanicRegistry#reset}）
     * 同步改回默认，避免删了存档却还在按旧值跑。
     */
    public static void forget(MinecraftServer server, String id) {
        MechanicGlobalState state = get(server);
        if (state == null) {
            return;
        }
        state.values.remove(id);
        state.cleared.add(id);
        state.setDirty();
    }
}
