package MengySmod.blockshuffle.command;

import MengySmod.blockshuffle.Blockshuffle;
import MengySmod.blockshuffle.config.ShuffleConfig;
import MengySmod.blockshuffle.shuffle.ShuffleManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 调试与管理指令。
 *
 * <ul>
 *   <li>{@code /blockshuffle trigger} —— 立刻以执行者为中心强制互换一次（忽略冷却，便于测试）</li>
 *   <li>{@code /blockshuffle status} —— 输出当前配置与上一次运行结果</li>
 * </ul>
 */
@EventBusSubscriber(modid = Blockshuffle.MODID)
public final class BlockShuffleCommand {

    private BlockShuffleCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 权限模型：/blockshuffle status 人人可看（只读）；
        // on / off / trigger 会改变服务端行为或写入服务端配置文件，需要 OP（权限等级 2）。
        event.getDispatcher().register(Commands.literal("blockshuffle")
                .then(Commands.literal("trigger")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    ServerPlayer player = source.getPlayer();
                    boolean accepted;
                    if (player != null) {
                        accepted = ShuffleManager.forceTrigger(player);
                    } else {
                        // 控制台/Rcon 执行：以执行位置为中心（便于在无玩家时验证功能）
                        accepted = ShuffleManager.forceTrigger(source.getLevel(), BlockPos.containing(source.getPosition()));
                    }
                    // 带 fallback：未装模组的执行者（如未装模组的 OP 客户端）也能看到正常文本
                    if (accepted) {
                        source.sendSuccess(() -> Component.translatableWithFallback(
                                "blockshuffle.command.triggered", "[BlockShuffle] Force-triggered one swap."), false);
                    } else {
                        source.sendFailure(Component.translatableWithFallback(
                                "blockshuffle.message.disabled",
                                "[BlockShuffle] 模组当前已关闭，可用 /blockshuffle on 开启"));
                    }
                    return accepted ? 1 : 0;
                }))
                .then(Commands.literal("on")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                    ShuffleConfig.setEnabled(true);
                    context.getSource().sendSuccess(() -> Component.translatableWithFallback(
                            "blockshuffle.command.enabled", "[BlockShuffle] 已开启"), false);
                    return 1;
                }))
                .then(Commands.literal("off")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                    ShuffleConfig.setEnabled(false);
                    context.getSource().sendSuccess(() -> Component.translatableWithFallback(
                            "blockshuffle.command.disabled", "[BlockShuffle] 已关闭"), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    String text = ShuffleManager.statusText();
                    for (String line : text.split("\n")) {
                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                })));
    }
}
