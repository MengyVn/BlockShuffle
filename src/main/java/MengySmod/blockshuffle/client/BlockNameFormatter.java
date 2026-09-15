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

    /** 这些列表里的 {@code =} 后面是百分比而不是权重 */
    private static final java.util.Set<String> CHANCE_LISTS = java.util.Set.of("blockParticipationChance");

    private BlockNameFormatter() {
    }

    /**
     * @param rawEntry 配置里的原始条目，例如 {@code "minecraft:obsidian"}、{@code "minecraft:stone=3.0"}
     *                 或参与概率 {@code "minecraft:diamond_ore=100%"}
     * @param listKey  该条目所属的配置项名，用于决定后缀显示"权重"还是"参与概率"
     * @return 展示用组件：已知方块显示本地化名称，未知条目显示红色原文
     */
    static Component displayName(Object rawEntry, String listKey) {
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
        if (CHANCE_LISTS.contains(listKey)) {
            return Component.translatable("blockshuffle.configuration.chanceEntry", name, normalizePercent(weightPart));
        }
        return Component.translatable("blockshuffle.configuration.weightedEntry", name, weightPart);
    }

    /** 参与概率列表里的值统一按百分比显示（"100" 与 "100%" 都显示成 "100%"）。 */
    private static String normalizePercent(String raw) {
        try {
            double percent = Double.parseDouble(raw.trim().replace("%", ""));
            return (Math.round(percent * 10.0D) / 10.0D) + "%";
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static Block blockById(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(key);
    }
}
