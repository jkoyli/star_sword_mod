package cn.autoforged.star_sword_mod_1785589476.client;

import cn.autoforged.star_sword_mod_1785589476.helper.MechanicRegistry;
import cn.autoforged.star_sword_mod_1785589476.helper.MechanicService;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端缓存的机制开关权威快照（来自 {@code MechanicSnapshotPacket}）。
 *
 * <p>客户端不做任何本地推断：点一下只是把请求发给服务端，界面上的值永远等快照回来再变。
 * 这样「移除模式」这类原本没有回同步的开关也不会显示出错误的 ON/OFF。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientMechanicState {

    /** id -> 快照项；只在客户端主线程读写。 */
    private static final Map<String, MechanicService.Entry> ENTRIES = new LinkedHashMap<>();

    private ClientMechanicState() {
    }

    /** 网络线程收到快照后调用（handler 已 enqueueWork，实际在主线程执行）。 */
    public static void accept(List<MechanicService.Entry> entries) {
        ENTRIES.clear();
        if (entries != null) {
            for (MechanicService.Entry entry : entries) {
                ENTRIES.put(entry.id, entry);
            }
        }
    }

    /** 当前值；未收到快照时回落到注册表默认值。 */
    public static boolean value(String id) {
        MechanicService.Entry entry = ENTRIES.get(id);
        if (entry != null) {
            return entry.value;
        }
        MechanicRegistry.Def def = MechanicRegistry.byId(id);
        return def != null && def.defaultValue;
    }

    /** 该玩家是否有权改这一项（未收到快照时按 false 处理，宁可灰掉也不误导）。 */
    public static boolean editable(String id) {
        MechanicService.Entry entry = ENTRIES.get(id);
        return entry != null && entry.editable;
    }

    /** 是否已经收到过任意一份快照。 */
    public static boolean hasData() {
        return !ENTRIES.isEmpty();
    }

    /** 断线时清空，避免换服/重进后显示上一局的状态。 */
    public static void clear() {
        ENTRIES.clear();
    }
}
