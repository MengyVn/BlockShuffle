package MengySmod.blockshuffle.client;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * 把配置里的方块条目解析成"能看懂的"展示文本。
 *
 * <p>配置里存的是 id（{@code "minecraft:obsidian"}），但玩家想看的是名字（黑曜石）。
 * 这里用 {@link Block#getName()} 拿到<b>可翻译</b>的方块名——它由客户端按自己的语言渲染，
 * 所以语言文件里不需要为每个方块单独写条目，模组也不必内置方块名列表。
 */
final class BlockNameFormatter {

    private BlockNameFormatter() {
    }

    /**
     * @param rawEntry 配置里的原始条目，例如 {@code "minecraft:obsidian"} 或权重形式 {@code "minecraft:stone=3.0"}
     * @return 展示用组件：已知方块显示本地化名称，未知条目显示红色原文
     */
    static Component displayName(Object rawEntry) {
        if (!(rawEntry instanceof String text)) {
            return Component.literal(String.valueOf(rawEntry));
        }

        String idPart = text;
        String weightPart = null;
        int equals = text.indexOf('=');
        if (equals >= 0) {
            idPart = text.substring(0, equals);
            weightPart = text.substring(equals + 1).trim();
        }

        Block block = blockById(idPart.trim());
        if (block == null) {
            return Component.translatable("blockshuffle.configuration.unknownBlock", text)
                    .withStyle(ChatFormatting.RED);
        }

        Component name = block.getName().withStyle(ChatFormatting.AQUA);
        if (weightPart == null || weightPart.isEmpty()) {
            return name;
        }
        return Component.translatable("blockshuffle.configuration.weightedEntry", name, weightPart);
    }

    private static Block blockById(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(key);
    }
}
