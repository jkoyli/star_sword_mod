package cn.autoforged.star_sword_mod_1785589476.client;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import cn.autoforged.star_sword_mod_1785589476.client.gui.CommandPanelScreen;
import cn.autoforged.star_sword_mod_1785589476.client.gui.MechanicPanelScreen;
import cn.autoforged.star_sword_mod_1785589476.client.render.BlackHoleCard;
import cn.autoforged.star_sword_mod_1785589476.client.ClientMechanicPresets;
import cn.autoforged.star_sword_mod_1785589476.helper.EntityIntegrityProbe;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.ProtectedState;
import cn.autoforged.star_sword_mod_1785589476.helper.ScreenClosePolicy;
import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import cn.autoforged.star_sword_mod_1785589476.helper.MethodLayerState;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.ForeignChannelGuard;
import cn.autoforged.star_sword_mod_1785589476.item.ModItems;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.Random;
import java.util.UUID;

// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// [REVIVE-MARK] 复活 / [RESPAWN-MARK] 重生：本类的客户端防御逻辑：
//   死亡界面拦截、Esc 关屏、每 tick 控制权/界面/死亡字段强制恢复、防缴械客户端回填。
// ============================================================================
@Mod.EventBusSubscriber(modid = StarSwordMod.MOD_ID, value = Dist.CLIENT)
public class ModClientForgeEvents {

    static {
        // "谁是本地玩家"的判定交给客户端注册：PlayerDefense 双端共用，不能直接引用 Minecraft。
        PlayerDefense.registerLocalPlayerProbe(entity -> entity == Minecraft.getInstance().player);
    }

    private static final int[] LIGHT_BLUE_COLORS = {
            0xE0F7FA, 0xB2EBF2, 0x80DEEA, 0x4DD0E1, 0x26C6DA
    };

    private static final int[] BRIGHT_COLORS = {
            0xFF5252, 0xFF4081, 0xE040FB, 0x7C4DFF, 0x448AFF,
            0x40C4FF, 0x64FFDA, 0x69F0AE, 0xB2FF59, 0xEEFF41,
            0xFFD740, 0xFFAB40, 0xFF6E40
    };

    /**
     * 客户端不再需要单独“安装防御织入”：原 ByteBuddy {@code PlayerDefenseHijack} 已移除，
     * 防御改由 Mixin（{@code LivingEntityMixin}/{@code EntityIsRemovedMixin}/{@code PlayerMixin}/
     * {@code ServerPlayerMixin}）在类加载期对所有端统一生效，物理服务端与客户端一致。
     */

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        Item item = stack.getItem();

        if (item == ModItems.STAR_SWORD.get()) {
            Player player = event.getEntity();
            if (player != null && player.level().isClientSide) {
                replaceWithDynamicTooltip(event, stack, "star_sword");
            }
        } else if (item == ModItems.COUNTERATTACK_TALISMAN.get()) {
            event.getToolTip().add(Component.translatable("tooltip.star_sword_mod_1785589476.counterattack_talisman"));
        } else if (item == ModItems.SPAWN_PREVENTION_TALISMAN.get()) {
            event.getToolTip().add(Component.translatable("tooltip.star_sword_mod_1785589476.spawn_prevention_talisman"));
        } else if (item == ModItems.REFLECTION_TALISMAN.get()) {
            event.getToolTip().add(Component.translatable("tooltip.star_sword_mod_1785589476.reflection_talisman"));
        }
    }

    private static void replaceWithDynamicTooltip(ItemTooltipEvent event, ItemStack stack, String itemKey) {
        long time = System.currentTimeMillis();
        int shift = (int) ((time / 500) % 5);
        int nameShift = (int) (time / 500);

        String nameKey = "item.star_sword_mod_1785589476." + itemKey;
        String translatedName = net.minecraft.client.resources.language.I18n.get(nameKey);

        MutableComponent nameComponent = Component.empty();
        Random nameRand = new Random(nameShift * 31L + 7);
        boolean[] usedColors = new boolean[BRIGHT_COLORS.length];
        int colorCount = 0;

        for (int i = 0; i < translatedName.length(); i++) {
            int colorIdx;
            int tries = 0;
            do {
                colorIdx = nameRand.nextInt(BRIGHT_COLORS.length);
                tries++;
            } while (tries < 100 && colorCount < BRIGHT_COLORS.length
                    && usedColors[colorIdx]);
            if (tries < 100 && colorCount < BRIGHT_COLORS.length) {
                usedColors[colorIdx] = true;
                colorCount++;
            }
            nameComponent.append(
                    Component.literal(String.valueOf(translatedName.charAt(i)))
                            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(BRIGHT_COLORS[colorIdx])))
            );
        }

        event.getToolTip().clear();
        event.getToolTip().add(nameComponent);

        String[] descKeys = {
                "tooltip.star_sword_mod_1785589476.star_sword.line1",
                "tooltip.star_sword_mod_1785589476.star_sword.line2",
                "tooltip.star_sword_mod_1785589476.star_sword.line3",
                "tooltip.star_sword_mod_1785589476.star_sword.line4"
        };

        for (int lineIdx = 0; lineIdx < descKeys.length; lineIdx++) {
            String translated = net.minecraft.client.resources.language.I18n.get(descKeys[lineIdx]);
            MutableComponent line = Component.empty();
            for (int ci = 0; ci < translated.length(); ci++) {
                int colorIdx = (ci + shift) % 5;
                line.append(
                        Component.literal(String.valueOf(translated.charAt(ci)))
                                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(LIGHT_BLUE_COLORS[colorIdx])))
                );
            }
            event.getToolTip().add(line);
        }
    }

    /**
     * 星辉剑的介绍框整体接管：右侧一颗实时黑洞，左侧是逐字彩色的名字与说明。
     *
     * <p>绘制细节全部收在 {@link BlackHoleCard} 里；这里只负责"是不是这把剑"和取消原版绘制。
     * 用 {@code -Dstar_sword.fx.tooltip=false} 可以退回原来的纯色边框版本。
     */
    @SubscribeEvent
    public static void onRenderTooltipPre(RenderTooltipEvent.Pre event) {
        ItemStack stack = event.getItemStack();
        if (stack.getItem() != ModItems.STAR_SWORD.get()) return;

        event.setCanceled(true);

        var graphics = event.getGraphics();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        BlackHoleCard.render(graphics, event.getFont(), event.getComponents(), event.getX(), event.getY());
        graphics.pose().popPose();
    }

    /**
     * 客户端死亡屏拦截（需求指定的最稳做法）：{@code Minecraft.setScreen} 在真正打开界面前
     * post {@code ScreenEvent.Opening}（mc-src Minecraft.java:1018-1021，返回 true 直接 return）。
     * 受保护玩家打开 {@link DeathScreen} 时取消打开，配合 {@code ClientPacketListenerMixin}
     * 对 {@code handlePlayerCombatKill}/{@code handleSetHealth} 的拦截，避免卡在死亡屏。
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活 / [RESPAWN-MARK] 重生：取消打开死亡界面。
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player != null) {
            // 保证方向 B 的本地玩家身份在客户端 tick 之前也已标记。
            ProtectedState.setClientLocalPlayer(player.getUUID());
        }
        // 只处理死亡界面；其它界面正常打开，由 forceClientScreenRecovery 每 tick 关闭非白名单界面。
        if (player != null && SwordGuard.isProtected(player)
                && event.getNewScreen() instanceof DeathScreen) {
            // 只改事件本身，让 setScreen 继续走完并把 this.screen 置为 null。
            // 绝不能在这里调用 mc.setScreen(null)：死亡态下 setScreen(null) 会把 null 换成新的
            // DeathScreen 并再次 post Opening，形成 setScreen → Opening → setScreen 的自递归。
            event.setNewScreen(null);
            ModLog.defense("ScreenEvent.Opening[层=死亡屏拦截] 把即将打开的死亡界面改为 null", player);
        }
    }

    /**
     * Esc 键强制关屏（本轮新增）：受保护玩家打开界面时，按下 Esc 直接关闭当前界面并取消原版处理，
     * 即使原版逻辑被干扰也能保证任何白名单界面都能退出。
     */
    // [DEFENSE-MARK] 防御：受保护玩家按 Esc 强制关屏。
    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !SwordGuard.isProtected(mc.player)) {
            return;
        }
        if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            safeCloseScreen(mc);
            event.setCanceled(true);
        }
    }

    /**
     * 星辉剑命令面板开关：默认 H 键（可在键位设置中自定义）。
     * 仅当未打开任何界面且手持星辉剑时触发；面板打开时由界面自身的按键处理负责关闭。
     * 同时监听鼠标事件，使键位绑定支持鼠标键触发。
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        handleCommandPanelToggle();
        handleMechanicPanelToggle();
    }

    @SubscribeEvent
    public static void onMouseButtonInput(InputEvent.MouseButton event) {
        handleCommandPanelToggle();
        handleMechanicPanelToggle();
    }

    /**
     * 机制面板开关：默认 M 键。
     *
     * <p>与命令面板同一套约束：未打开任何界面、且手持星辉剑时才打开；
     * 面板打开时由界面自身处理关闭。
     */
    private static void handleMechanicPanelToggle() {
        if (!ModKeybinds.OPEN_MECHANIC_PANEL.consumeClick()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!ModConstants.isHoldingSword(mc.player)) {
            return;
        }
        if (mc.screen instanceof MechanicPanelScreen) {
            safeCloseScreen(mc);
        } else if (mc.screen == null) {
            mc.setScreen(new MechanicPanelScreen());
        }
    }

    private static void handleCommandPanelToggle() {
        if (!ModKeybinds.OPEN_COMMAND_PANEL.consumeClick()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!ModConstants.isHoldingSword(mc.player)) {
            return;
        }
        if (mc.screen instanceof CommandPanelScreen) {
            safeCloseScreen(mc);
        } else if (mc.screen == null) {
            mc.setScreen(new CommandPanelScreen());
        }
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        handleStarSwordLeftClick(event.getEntity());
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        handleStarSwordLeftClick(event.getEntity());
    }

    private static void handleStarSwordLeftClick(Player player) {
        if (!ModConstants.isHoldingSword(player)) return;

        HitResult hit = player.pick(80.0, 1.0F, false);

        BlockPos strikePos;
        int entityId = -1;

        if (hit.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) hit;
            strikePos = entityHit.getEntity().blockPosition();
            entityId = entityHit.getEntity().getId();
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            strikePos = blockHit.getBlockPos().relative(blockHit.getDirection());
        } else {
            Vec3 eyePos = player.getEyePosition(1.0F);
            Vec3 lookVec = player.getViewVector(1.0F);
            Vec3 endPos = eyePos.add(lookVec.scale(80.0));
            strikePos = new BlockPos((int) endPos.x, (int) endPos.y, (int) endPos.z);
        }

        boolean isShift = player.isShiftKeyDown();
        ModMessages.sendLightningPacket(strikePos, isShift, entityId);
    }

    // ===================== 防缴械客户端 =====================

    // 原先这里的 antiDisarmToggleHeld / removeModeToggleHeld 是 Shift+Z / Shift+C 的边沿触发状态。
    // 两个快捷键已取消（开关统一收到机制面板），这两个字段随之删除。
    private static int lastMissingNotifyTick = -100;

    /** 上一次已知的本地玩家保护状态（null = 尚未收到服务端权威状态）。 */
    private static Boolean lastLocalProtectedState = null;

    // ===================== 关屏（死亡态安全） =====================

    /** 挂起的关屏请求：死亡态下不能直接设屏，等死亡态复位后再关（见 {@link #safeCloseScreen}）。 */
    private static final ScreenClosePolicy.Deferral PENDING_CLOSE = new ScreenClosePolicy.Deferral();

    /**
     * {@code setScreen} 重入保护。
     *
     * <p>只防「嵌套调用导致爆栈」这一件事，不负责「屏幕状态对不对」——真正的修法是
     * {@code ScreenEvent.Opening} handler 里只改事件、不调用 {@code setScreen}。
     */
    private static final ThreadLocal<Boolean> IN_SET_SCREEN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 死亡态下“关屏”的统一入口：所有 {@code mc.setScreen(null)} 都必须走这里。
     *
     * <p>背景（一次真实的自递归）：{@code Minecraft.setScreen(null)} 在
     * {@code player.isDeadOrDying()} 为真时会把 null 换成新的 {@code DeathScreen}
     * （mc-src {@code Minecraft.java:988-1000}），然后才 post {@code ScreenEvent.Opening}。
     * 如果 Opening 的 handler 里又调用 {@code setScreen(null)}，就会再次走这条链路：
     * {@code null → DeathScreen → Opening → setScreen(null) → …}，直到栈溢出。
     *
     * <p>因此规则有两条：
     * <ol>
     *   <li>Opening handler 内只改事件（{@code setNewScreen} / {@code setCanceled}），绝不调 {@code setScreen}；</li>
     *   <li>其它任何地方关屏都走本方法——死亡态先挂起，等 {@code onClientTick} 里死亡态复位后再关。</li>
     * </ol>
     */
    public static void safeCloseScreen(Minecraft mc) {
        safeCloseScreen(mc, false);
    }

    private static void safeCloseScreen(Minecraft mc, boolean force) {
        if (mc == null || Boolean.TRUE.equals(IN_SET_SCREEN.get())) {
            // 已经在一次 setScreen 的嵌套调用里：直接放弃，防止爆栈。
            return;
        }
        Player player = mc.player;
        if (!force && player != null && player.isDeadOrDying()) {
            PENDING_CLOSE.request();
            return;
        }
        IN_SET_SCREEN.set(Boolean.TRUE);
        try {
            mc.setScreen(null);
        } finally {
            IN_SET_SCREEN.set(Boolean.FALSE);
        }
    }

    /**
     * 消费挂起的关屏请求：死亡态复位后立刻关；长期不复位则在
     * {@link ScreenClosePolicy#MAX_DEFER_TICKS} tick 后强制关一次。
     */
    private static void processPendingCloseScreen(Minecraft mc) {
        if (!PENDING_CLOSE.isPending() || mc.player == null) {
            return;
        }
        if (!PENDING_CLOSE.tick(mc.player.isDeadOrDying())) {
            return;
        }
        // 强制关屏即使此刻仍在死亡态也是安全的：Opening handler 会把 newScreen 改回 null。
        safeCloseScreen(mc, true);
    }

    // ===================== 启动拦截：机制面板 =====================

    /** 本进程是否已经弹过启动面板（只弹一次，避免从世界退回标题界面时又被拦）。 */
    private static boolean startupPanelShown = false;

    /**
     * 启动期拦截：标题界面出现的第一时间，用机制面板把它顶掉，玩家点「启动游戏」才继续。
     *
     * <p>为什么是这个时机：MC 的窗口与 Screen 系统在进入标题界面前就已就绪，这是「能画出界面」
     * 的最早点；再早（mod 构造期 / premain）根本没有窗口，不可能显示 GUI。
     * 面板自身在启动模式下不允许 Esc 关闭，关屏时重新打开 {@code TitleScreen}，所以主菜单不会被跳过。
     *
     * <p>可用 {@code -Dstar_sword.mech.startupPanel=false} 关掉这个拦截。
     */
    @SubscribeEvent
    public static void onClientTickStartupGate(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            // 标题界面/主菜单：仍然写一次心跳，让独立编辑器知道「游戏已启动、模组已加载」，
            // 从而把它和「根本没启动/模组没装」区分开。进世界后由 onClientTick 继续写。
            MechanicStateWriter.tick(mc);
        }
        if (startupPanelShown || !startupPanelEnabled()) {
            return;
        }
        if (mc.screen instanceof TitleScreen) {
            startupPanelShown = true;
            mc.setScreen(new MechanicPanelScreen(true));
        }
    }

    private static boolean startupPanelEnabled() {
        String raw = System.getProperty("star_sword.mech.startupPanel", "true");
        return !("0".equals(raw) || "false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw));
    }

    /** 把启动面板里调好的预设下发一次；服务端会按持剑/op/限频复核，失败就保持服务端原值。 */
    private static void flushMechanicPresets(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || !ClientMechanicPresets.hasPending()) {
            return;
        }
        // 服务端要求「手持星辉剑」才接受写入：没拿剑就先攒着，每 tick 重试，拿到剑的那一刻应用。
        if (!ModConstants.isHoldingSword(mc.player)) {
            return;
        }
        for (var entry : ClientMechanicPresets.takePending().entrySet()) {
            ModMessages.sendMechanicSet(entry.getKey(), entry.getValue());
        }
        ModMessages.sendMechanicSnapshotRequest();
        MechanicStateWriter.notePresetsPushed();
        mc.player.displayClientMessage(
                Component.translatable("gui.star_sword_mod_1785589476.mech.presets_applied"), true);
    }

    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：客户端每 tick 防御兜底入口（保护状态/控制权/界面/旁路血量）。
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 启动面板里调好的预设：首次进服下发给服务端复核（只发一次）。
        flushMechanicPresets(mc);
        // 心跳/状态回写：让独立编辑器知道「游戏在跑、进了世界、预设生没生效」。
        MechanicStateWriter.tick(mc);

        // 标记本地玩家 UUID：方向 B 需要一个“谁是本地玩家”的纯 common 静态记录。
        ProtectedState.setClientLocalPlayer(mc.player.getUUID());

        // 方法层心跳：事件侧每 tick 走一步，内部按窗口与 getHealth 注入计数比对。
        MethodLayerState.tick(true);
        // 实体完整性的另一半：服务端好、客户端坏是"立正/空血"最常见的组合。
        EntityIntegrityProbe.tickClient(mc.player);

        // 先消费挂起的关屏请求（死亡态复位后再关屏），再做其它界面/控制权恢复。
        processPendingCloseScreen(mc);

        // 诊断用：每 40 tick 成对打印「恢复前 / 恢复后」的完整控制权快照。
        // 之前的单点打印在恢复动作之前，只能看到“对手刚写完”的值，无法区分
        // “我们没修” 与 “修完之后又被写回”，也无法判断 WASD 是死在输入层还是
        // 死在 isImmobile()（原版 aiStep 清 xxa/zza 处）。成对采样把这两件事分开。
        boolean diagSample = mc.player.tickCount % 40 == 0;
        if (diagSample) {
            ModLog.defense("clientStateBEGIN " + controlState(mc), mc.player);
        }

        handleProtectionStateClient(mc);
        // 客户端同样要中和：外部通道的值会同步到本地实体，本地判定读的就是它。
        ForeignChannelGuard.neutralize(mc.player);
        forceClientControlRecovery(mc);
        forceClientScreenRecovery(mc);
        // 防缴械的本地预判回填保留；两个开关的快捷键（Shift+Z / Shift+C）已取消，
        // 现在只能从机制面板切换。
        handleAntiDisarmClientRestore(mc);
        // 消费服务端同步过来的旁路真实血量（SyncRealHealthPacket），强制刷新本地血量。
        PlayerDefense.applyPendingClientRealHealth(mc.player);

        // 状态锁（默认作用域=仅客户端）：把本地玩家的血量/最大生命/护甲/食物钉回原版满值。
        // 放在最后：前面几个恢复动作可能刚把值改回权威血量，这里做最终收口。
        cn.autoforged.star_sword_mod_1785589476.hijack.authority.VanillaStateLock
                .applyOn(mc.player, true);

        // 状态锁拒绝写入的窗口汇总（热路径只记数，日志在这里落）。
        PlayerDefense.flushRejectedWrites(mc.player);

        // 与上方 clientStateBEGIN 成对：这一行之后本 tick 的恢复动作已全部做完。
        // 若 END 是 20.0/0 而 NEXT 的 BEGIN 又是 0/1，说明对手写在两次采样之间；
        // 若 END 本身就是 0/1，说明我们自己的恢复没生效。
        if (diagSample) {
            ModLog.defense("clientStateEND   " + controlState(mc), mc.player);
        }
    }

    /**
     * 控制权快照：一次性回答“到底哪个环节被卡住”。
     *
     * <p>字段选择都是有针对性的：
     * <ul>
     *   <li>{@code health}/{@code raw}：分别是被 agent 接管后的 {@code getHealth()} 与同步数据项的原始值。
     *       两者不一致时，HUD（读 getHealth）与我们的旁路（读数据项）看到的是两份世界；</li>
     *   <li>{@code xxa}/{@code zza}/{@code yya}：原版 {@code LivingEntity.aiStep} 在
     *       {@code isImmobile()} 时会把 {@code xxa/zza} 清零，这是 WASD 失效的唯一原版入口。
     *       若玩家按着 WASD 而这两个值为 0，且 {@code health<=0}／{@code deathTime>0}，就是死亡态门控；
     *       若二者为 0 但血量正常，则是输入层（KeyboardInput 冲量）被清；</li>
     *   <li>{@code attackAnim}/{@code swingTime}：原版 {@code Player.serverAiStep} 才调
     *       {@code updateSwingTime()}，而它只从 {@code aiStep} 的非 immobile 分支进入。
     *       动画不走，说明这一支也被跳过了；</li>
     *   <li>{@code hurtTime}/{@code deathTime}/{@code deadOrDying}：确认死亡字段与死亡判定的真实读数。</li>
     * </ul>
     */
    private static String controlState(Minecraft mc) {
        Screen s = mc.screen;
        LocalPlayer p = mc.player;
        if (p == null) {
            return "player=null";
        }
        float raw;
        try {
            raw = PlayerDefense.rawSyncedHealth(p);
        } catch (Throwable t) {
            raw = Float.NaN;
        }
        return "screen=" + (s == null ? "null" : s.getClass().getName())
                + " input=" + (p.input == null ? "null" : p.input.getClass().getSimpleName())
                + " health=" + p.getHealth()
                + " raw=" + raw
                + " maxHealth=" + p.getMaxHealth()
                + " xxa=" + p.xxa + " zza=" + p.zza + " yya=" + p.yya
                + " attackAnim=" + p.attackAnim + " swingTime=" + p.swingTime
                + " hurtTime=" + p.hurtTime + " deathTime=" + p.deathTime
                + " deadOrDying=" + p.isDeadOrDying()
                + " spectator=" + p.isSpectator()
                + " onGround=" + p.onGround()
                + " sprinting=" + p.isSprinting();
    }

    /**
     * HUD 绘制血量那一刻的读数：回答“日志说血量 20，为什么心是空的”。
     *
     * <p>原版血量条由 {@code minecraft:player_health} 这个 overlay 画，它在
     * {@code RenderGuiOverlayEvent.Pre} 之前已经从 {@code player.getHealth()}/
     * {@code getMaxHealth()} 取过值。这里在同一个时间点再取一次，就能判断
     * HUD 的血量来源是否与我们接管的那条 {@code getHealth()} 一致。
     */
    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        if (!event.getOverlay().id().equals(PLAYER_HEALTH_OVERLAY)) {
            return;
        }
        // 每 20t 一次，避免逐帧刷屏；只关心受保护玩家。
        if (p.tickCount % 20 != 0 || !SwordGuard.isProtected(p)) {
            return;
        }
        float raw;
        try {
            raw = PlayerDefense.rawSyncedHealth(p);
        } catch (Throwable t) {
            raw = Float.NaN;
        }
        ModLog.health("hudHealthRead[层=原版血量条绘制] 取一次 HUD 读到的血量", p,
                "health=" + p.getHealth() + " raw=" + raw + " maxHealth=" + p.getMaxHealth()
                        + " absorption=" + p.getAbsorptionAmount()
                        + " deathTime=" + p.deathTime + " deadOrDying=" + p.isDeadOrDying());
    }

    /** {@code minecraft:player_health} 这个 overlay 的 id（原版血量条）。 */
    private static final ResourceLocation PLAYER_HEALTH_OVERLAY =
            new ResourceLocation("minecraft", "player_health");


    /**
     * 客户端每 tick 处理服务端广播的保护状态（SyncProtectedStatePacket）：
     * <ul>
     *   <li>方向 B 清理：服务端判定“不受保护”后，清掉本地玩家在状态未知期被临时保护
     *       所覆盖的死亡态/旁路血量，之后不再干预原版死亡流程；</li>
     *   <li>每 tick 兜底恢复：受保护玩家（含方向 B 未知期）若血量≤0/正在死亡计时/dead=true，
     *       把死亡字段整体恢复，堵住 {@code LivingEntity.tick()} 的 {@code tickDeath()} 路径。</li>
     * </ul>
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：处理服务端广播的保护状态并每 tick 恢复死亡字段。
    private static void handleProtectionStateClient(Minecraft mc) {
        UUID localId = mc.player.getUUID();
        Boolean known = ProtectedState.getClientState(localId);
        if (known != null && !known && !Boolean.FALSE.equals(lastLocalProtectedState)) {
            PlayerDefense.clearClientProtectionArtifacts(mc.player);
            ModLog.defense("ProtectedState[层=客户端方向B清理] 服务端判定不受保护，清掉临时保护残留", mc.player);
        }
        if (known != null) {
            lastLocalProtectedState = known;
        }
        if (SwordGuard.isProtected(mc.player)) {
            PlayerDefense.clientRecoverDeathStateIfNeeded(mc.player);
        }
    }

    /**
     * 客户端控制权强制恢复（本轮加强）：受保护玩家每 tick 强制恢复输入并清除死亡动画字段。
     *
     * <p>与上面的诊断打印（screen / input / spectator / deadOrDying / hurtTime / deathTime）配合：
     * <ul>
     *   <li>{@code LocalPlayer.input} 不是 {@link KeyboardInput}（登录/重生空窗期被清、或被其它
     *       逻辑替换）→ 重建 {@link KeyboardInput}（原版在 {@code ClientPacketListener} 处理登录/
     *       重生时也是这么重置的）；</li>
     *   <li>每 tick 把 {@code hurtTime}/{@code hurtDuration}/{@code deathTime} 置 0，
     *       直到 {@code deadOrDying} 为 false，避免死亡动画卡住移动/姿势。</li>
     * </ul>
     * 只对受保护玩家生效，不影响正常玩家的原版流程。
     */
    // [DEFENSE-MARK] 防御：强制恢复受保护玩家的输入与死亡动画字段。
    private static void forceClientControlRecovery(Minecraft mc) {
        if (mc.player == null || !SwordGuard.isProtected(mc.player)) {
            return;
        }
        // 强制恢复 input：任何非 KeyboardInput 的输入对象都重建。
        if (!(mc.player.input instanceof KeyboardInput)) {
            mc.player.input = new KeyboardInput(mc.options);
            ModLog.defense("forceClientControlRecovery[层=控制权恢复] 强制重建 KeyboardInput", mc.player);
        }
        // 每 tick 清除死亡动画字段（与 PlayerDefense.clientRecoverDeathStateIfNeeded 互补）。
        mc.player.hurtTime = 0;
        mc.player.hurtDuration = 0;
        mc.player.deathTime = 0;
    }

    /**
     * 客户端界面强制恢复（本轮新增）：受保护玩家每 tick 关闭死亡界面与非白名单界面。
     *
     * <p>白名单保留：命令面板、容器界面、聊天、暂停界面；死亡界面与其它界面一律关闭，
     * 防止死亡流程残留的界面卡住控制权。只对受保护玩家生效。
     */
    // [DEFENSE-MARK] 防御：每 tick 关闭死亡界面与非白名单界面。
    private static void forceClientScreenRecovery(Minecraft mc) {
        if (mc.player == null || !SwordGuard.isProtected(mc.player)) {
            return;
        }
        Screen screen = mc.screen;
        if (screen == null) {
            return;
        }
        // 死亡界面直接关闭。
        if (screen instanceof DeathScreen) {
            safeCloseScreen(mc);
            return;
        }
        // 非白名单界面强制关闭。机制面板是本轮新增的界面，必须在这里登记，
        // 否则受保护玩家一打开就会被每 tick 关掉。
        boolean allow = screen instanceof CommandPanelScreen
                || screen instanceof MechanicPanelScreen
                || screen instanceof AbstractContainerScreen
                || screen instanceof ChatScreen
                || screen instanceof PauseScreen;
        if (!allow) {
            safeCloseScreen(mc);
        }
    }

    // Shift+C「移除模式」与 Shift+Z「防缴械」两个快捷键已取消：
    // 这两个开关现在只在机制面板（M）里切换，避免与其它模组的 Shift+字母组合冲突。

    /** 开关开启且第 0 格不是自己的剑时，用本地备份把剑放回第 0 格，并通知服务端补剑。 */
    // [DEFENSE-MARK] 防御（防缴械）：用本地备份把星辉剑放回第 0 格。
    private static void handleAntiDisarmClientRestore(Minecraft mc) {
        if (!AntiDisarmClientState.isEnabled()) return;
        Player player = mc.player;
        Inventory inv = player.getInventory();
        ItemStack slot0 = inv.items.get(0);
        if (SwordGuard.isStarSword(slot0)) {
            UUID owner = SwordGuard.getOwner(slot0);
            if (owner == null || owner.equals(player.getUUID())) {
                return;
            }
        }
        ItemStack backup = AntiDisarmClientState.getBackup();
        if (backup.isEmpty()) {
            notifyMissingSword(mc);
            return;
        }
        for (int i = 1; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (SwordGuard.isStarSword(stack) && player.getUUID().equals(SwordGuard.getOwner(stack))) {
                inv.items.set(i, ItemStack.EMPTY);
            }
        }
        ItemStack offhand = inv.offhand.get(0);
        if (SwordGuard.isStarSword(offhand) && player.getUUID().equals(SwordGuard.getOwner(offhand))) {
            inv.offhand.set(0, ItemStack.EMPTY);
        }
        // 客户端不能改服务端，只做本地预判显示，由同步包/服务端 tick 校正。
        inv.items.set(0, backup.copy());
        notifyMissingSword(mc);
    }

    /** 缺剑通知包（20 tick 节流，避免刷屏）。 */
    private static void notifyMissingSword(Minecraft mc) {
        int tick = mc.player.tickCount;
        if (tick - lastMissingNotifyTick >= 20) {
            lastMissingNotifyTick = tick;
            ModMessages.sendMissingSword();
        }
    }
}
