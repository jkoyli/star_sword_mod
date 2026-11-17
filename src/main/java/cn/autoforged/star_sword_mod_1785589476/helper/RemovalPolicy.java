package cn.autoforged.star_sword_mod_1785589476.helper;

/**
 * 移除理由白名单 —— 纯逻辑，不依赖 Minecraft 类型，便于离线验证。
 *
 * <p>背景：原来的拦截是<b>黑名单</b>（只拒绝 {@code KILLED} / {@code DISCARDED}），条件太窄：
 * 外部实现换一个理由（{@code UNLOADED_TO_CHUNK}、{@code UNLOADED_WITH_PLAYER}，甚至未来版本新增的枚举）
 * 就能把玩家移除掉。现在改成白名单：<b>默认拒绝</b>，只放行原版真正需要的几条。
 *
 * <p>白名单的边界来自 1.20.1 原版调用路径（见 {@code PlayerList} / {@code ServerLevel}）：
 * <table border="1">
 *   <caption>原版为什么会用这些理由移除一个玩家</caption>
 *   <tr><th>理由</th><th>原版调用点</th><th>是否放行</th></tr>
 *   <tr><td>{@code UNLOADED_WITH_PLAYER}</td><td>{@code PlayerList.remove}（退服/踢出）</td><td>放行</td></tr>
 *   <tr><td>{@code CHANGED_DIMENSION}</td><td>{@code ServerPlayer.changeDimension}（传送门/跨维度）</td><td>放行</td></tr>
 *   <tr><td>{@code KILLED}</td><td>{@code Entity.kill()} / 死亡流程</td><td>拒绝</td></tr>
 *   <tr><td>{@code DISCARDED}</td><td>{@code Entity.discard()}（也是重生/重复登录时清理<b>旧</b>实体）</td><td>看对象：旧实体放行，当前会话拒绝</td></tr>
 *   <tr><td>{@code UNLOADED_TO_CHUNK}</td><td>区块卸载（玩家正常不会被这样移除）</td><td>拒绝</td></tr>
 *   <tr><td>未知（未来新增）</td><td>—</td><td>拒绝</td></tr>
 * </table>
 *
 * <p>“当前会话 vs 旧实体”的区分是关键：重生（{@code PlayerList.respawn}）和重复登录清理
 * （{@code ServerLevel.addPlayer}）都会先用 {@code DISCARDED} 移除<b>旧</b>实体，此时该实体已经
 * 不在 {@code PlayerList} 里了；拦下它只会制造同 UUID 的幽灵实体。所以白名单第 0 条是：
 * <b>不是当前会话实体就不拦</b>。
 */
public final class RemovalPolicy {

    /** 移除理由的稳定副本（{@code UNKNOWN} 代表未来版本新增或无法识别的理由）。 */
    public enum Reason {
        KILLED,
        DISCARDED,
        UNLOADED_TO_CHUNK,
        UNLOADED_WITH_PLAYER,
        CHANGED_DIMENSION,
        UNKNOWN
    }

    /**
     * 把运行时枚举名映射成 {@link Reason}。
     *
     * <p>故意用字符串而不是直接依赖枚举常量：这样即使外部实现/新版加入新理由，也只会落到
     * {@link Reason#UNKNOWN} 并被拒绝，不需要改代码。
     */
    public static Reason reasonOf(String enumName) {
        if (enumName == null) {
            return Reason.UNKNOWN;
        }
        try {
            return Reason.valueOf(enumName);
        } catch (IllegalArgumentException e) {
            return Reason.UNKNOWN;
        }
    }

    /**
     * 是否允许这次移除。
     *
     * @param reason            移除理由
     * @param liveSessionPlayer 被移除的实体是否就是当前会话里的那个玩家
     *                          （{@code PlayerList.getPlayer(uuid) == 该实体}）
     * @return true 放行；false 应当拒绝
     */
    public static boolean isAllowed(Reason reason, boolean liveSessionPlayer) {
        if (reason == null) {
            return true;
        }
        if (!liveSessionPlayer) {
            // 旧实体（重生替换 / 重复登录清理 / 离线残留）：原版在收拾现场，拦下来会留下同 UUID 幽灵。
            return true;
        }
        return switch (reason) {
            // 退服/踢出：不放过会留下幽灵实体，重登时变成重复玩家。
            case UNLOADED_WITH_PLAYER -> true;
            // 传送门/跨维度：不放过会永远卡在传送里。
            case CHANGED_DIMENSION -> true;
            // 死亡、主动 discard、区块卸载、以及任何未知理由：默认拒绝。
            default -> false;
        };
    }

    private RemovalPolicy() {
    }
}
