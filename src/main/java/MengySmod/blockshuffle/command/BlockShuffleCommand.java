package MengySmod.blockshuffle.command;

import MengySmod.blockshuffle.Blockshuffle;
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
        event.getDispatcher().register(Commands.literal("blockshuffle")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("trigger").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    ServerPlayer player = source.getPlayer();
                    if (player != null) {
                        ShuffleManager.forceTrigger(player);
                    } else {
                        // 控制台/Rcon 执行：以执行位置为中心（便于在无玩家时验证功能）
                        ShuffleManager.forceTrigger(source.getLevel(), BlockPos.containing(source.getPosition()));
                    }
                    // 带 fallback：未装模组的执行者（如未装模组的 OP 客户端）也能看到正常文本
                    source.sendSuccess(() -> Component.translatableWithFallback(
                            "blockshuffle.command.triggered", "[BlockShuffle] Force-triggered one swap."), false);
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
