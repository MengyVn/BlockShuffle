package MengySmod.blockshuffle.shuffle;

import MengySmod.blockshuffle.config.ShuffleConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 互换时在游戏内提示换掉的是哪两种方块，形如「泥土 ⇄ 橡木」。
 *
 * <p>关键点：发送的是<b>可翻译组件</b>（{@code Component}）而不是拼好的字符串，
 * 方块名用 {@code Block#getName()}（即 {@code block.minecraft.xxx} 翻译键），
 * 因此每个客户端都会用自己的语言渲染——中文客户端看到「泥土」，英文客户端看到「Dirt」，
 * 服务端不需要知道任何语言文件。
 */
public final class SwapNotifier {

    private SwapNotifier() {
    }

    /**
     * 在选定互换对、开始替换之前广播提示。
     *
     * @param level        发生互换的维度
     * @param center       互换区域中心
     * @param playerId     触发者（控制台触发时为空 UUID，查不到玩家）
     * @param blockA       被换掉的方块类型之一
     * @param blockB       被换掉的方块类型之二
     */
    public static void announcePair(ServerLevel level, BlockPos center, UUID playerId, Block blockA, Block blockB) {
        ShuffleConfig.Values values = ShuffleConfig.get();
        ShuffleConfig.MessageMode mode = values.messageMode();
        if (mode == ShuffleConfig.MessageMode.OFF) {
            return;
        }

        // 必须带 fallback：没装本模组的客户端没有这个翻译键，
        // 没有 fallback 时它只会显示 "blockshuffle.message.swapped" 这样的原始键名。
        // 方块名本身是原版翻译键（block.minecraft.xxx），任何客户端都能解析，
        // 所以未装模组的玩家也能看到「泥土 ⇄ 橡木」这样的正常文本。
        Component message = Component.translatableWithFallback("blockshuffle.message.swapped", "%s ⇄ %s",
                blockA.getName().withStyle(ChatFormatting.AQUA),
                blockB.getName().withStyle(ChatFormatting.AQUA));

        for (ServerPlayer player : targets(level, center, playerId, values)) {
            send(player, mode, message);
        }
    }

    private static List<ServerPlayer> targets(ServerLevel level, BlockPos center, UUID playerId, ShuffleConfig.Values values) {
        List<ServerPlayer> targets = new ArrayList<>();
        switch (values.messageScope()) {
            case TRIGGERER -> {
                // 只通知触发者（下面统一补上）
            }
            case REGION -> {
                // 与互换区域一致的正方形范围（半径按区块换算成方块）
                double limit = values.radiusChunks() * 16.0D;
                double centerX = center.getX() + 0.5D;
                double centerZ = center.getZ() + 0.5D;
                for (ServerPlayer player : level.players()) {
                    if (Math.abs(player.getX() - centerX) <= limit && Math.abs(player.getZ() - centerZ) <= limit) {
                        targets.add(player);
                    }
                }
            }
            case DIMENSION -> targets.addAll(level.players());
            case ALL -> {
                for (ServerLevel other : level.getServer().getAllLevels()) {
                    targets.addAll(other.players());
                }
            }
        }

        // 触发者始终能看到提示，即使他刚刚换了维度或不在上面的范围里
        ServerPlayer triggerer = level.getServer().getPlayerList().getPlayer(playerId);
        if (triggerer != null && !targets.contains(triggerer)) {
            targets.add(triggerer);
        }
        return targets;
    }

    private static void send(ServerPlayer player, ShuffleConfig.MessageMode mode, Component message) {
        switch (mode) {
            case CHAT -> player.sendSystemMessage(message);
            case ACTION_BAR -> player.sendSystemMessage(message, true);
            case TITLE -> {
                player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
                player.connection.send(new ClientboundSetTitleTextPacket(message));
            }
            case OFF -> {
                // 不会走到这里
            }
        }
    }
}
