package cn.autoforged.star_sword_mod_1785589476.event;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * 开发期自测入口：玩家登录后执行一条由系统属性指定的命令。
 *
 * <p>用途：在不需要手点界面的情况下复现「任意来源对玩家状态的影响」——伤害、状态写入、
 * 第三方命令等，都只是同一条命令；本类不认识任何具体模组或命令语义。
 *
 * <p>开关：{@code -Dstar_sword.selftest.command=<命令>}（开发运行可用 {@code -PssSelftestCommand=}）。
 * 留空即完全不生效；每个玩家只执行一次。只在服务端线程、玩家登录事件里触发。
 *
 * <p>发布版无副作用：属性为空时不注册任何东西；即使有人传了属性，命令也只是服主自己的权限行为。
 */
final class SelfTest {

    /** 系统属性名（与 build.gradle 的 run 配置一致）。 */
    private static final String PROP_COMMAND = "star_sword.selftest.command";

    private SelfTest() {
    }

    /** 玩家登录后执行一次（若配置了命令）。Never throws。 */
    static void afterLogin(ServerPlayer player) {
        try {
            String command = System.getProperty(PROP_COMMAND, "");
            if (command.isBlank() || player == null) {
                return;
            }
            CommandSourceStack source = player.createCommandSourceStack()
                    .withSuppressedOutput()
                    .withPermission(4);
            int result = player.server.getCommands().performPrefixedCommand(source, command);
            ModLog.log(ModLog.INIT, "自测命令已执行（result=" + result + "）：" + command);
        } catch (Throwable throwable) {
            ModLog.log(ModLog.INIT, "自测命令执行失败：" + throwable);
        }
    }
}
