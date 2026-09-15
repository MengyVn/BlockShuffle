package MengySmod.blockshuffle.shuffle;

import MengySmod.blockshuffle.Blockshuffle;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 玩家进入世界时在聊天栏输出欢迎语，介绍本模组与作者信息。
 *
 * <p>和互换提示一样使用 {@code translatableWithFallback}：
 * 没装本模组的客户端没有对应的翻译键，会退回 fallback 文本，
 * 因此即使朋友不装模组也能看到正常内容，而不是原始的翻译键名。
 */
@EventBusSubscriber(modid = Blockshuffle.MODID)
public final class WelcomeMessage {

    private WelcomeMessage() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        for (Component line : welcomeLines()) {
            player.sendSystemMessage(line);
        }
    }

    /** 欢迎语的三行内容（单独抽出来也便于自检与复用）。 */
    public static java.util.List<Component> welcomeLines() {
        return java.util.List.of(
                Component.translatableWithFallback(
                                "blockshuffle.message.welcome.title",
                                "【BlockShuffle】玩家每受伤一次，附近两种方块会随机整体互换！")
                        .withStyle(ChatFormatting.GOLD),
                Component.translatableWithFallback(
                        "blockshuffle.message.welcome.help",
                        "受伤后屏幕/聊天栏会提示换掉了什么；/blockshuffle status 查看设置，/blockshuffle trigger 手动触发测试。"),
                Component.translatableWithFallback(
                                "blockshuffle.message.welcome.author",
                                "作者：Mengy    联系：2274473985@qq.com")
                        .withStyle(ChatFormatting.GRAY));
    }
}
