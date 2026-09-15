package MengySmod.blockshuffle.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Set;

/**
 * 在 NeoForge 自带配置界面的基础上，让<b>方块列表</b>显示本地化方块名。
 *
 * <p>效果：打开「方块黑名单 / 方块权重」时，每一行的左侧显示「黑曜石」「石头 ×3.0」这样的名字，
 * 右侧仍是可直接编辑的 id 文本框（{@code minecraft:obsidian}）。名字由客户端语言决定，
 * 中文客户端显示中文名，英文客户端显示英文名。
 *
 * <p>实现方式是 NeoForge 官方留出的扩展点：
 * <ul>
 *   <li>{@code ConfigurationScreen} 的构造器接受一个"分节界面工厂"，用它替换默认的分节界面；</li>
 *   <li>覆写 {@code createList} 只对<b>方块列表</b>换用自定义列表界面，其余配置项走原版逻辑，
 *       保证配置界面的行为与外观和其他模组完全一致；</li>
 *   <li>覆写 {@code createListLabel} 把默认的「List element #n」换成方块名。</li>
 * </ul>
 */
public final class LocalizedBlockSectionScreen extends ConfigurationScreen.ConfigurationSectionScreen {

    /** 需要显示方块名的列表配置项（与 ShuffleConfig 中的键名一致）。 */
    private static final Set<String> BLOCK_LISTS = Set.of(
            "blockBlacklist", "blockWeights", "blockParticipationChance");

    /**
     * NeoForge 把这两个翻译键声明为 private 常量，这里按字面量复刻，保证按钮外观与其它模组一致：
     * section = "%s..."、sectiontext = "Edit"。
     */
    private static final String SECTION_KEY = "neoforge.configuration.uitext.section";
    private static final String SECTION_TEXT_KEY = "neoforge.configuration.uitext.sectiontext";

    public LocalizedBlockSectionScreen(Screen parent, ModConfig.Type type, ModConfig modConfig, Component title) {
        super(parent, type, modConfig, title);
    }

    @Override
    protected <T> Element createList(String key, ModConfigSpec.ListValueSpec spec, ModConfigSpec.ConfigValue<List<T>> list) {
        if (!BLOCK_LISTS.contains(key)) {
            return super.createList(key, spec, list);
        }

        Component buttonText = Component.translatable(SECTION_KEY,
                Component.translatableWithFallback(getTranslationKey(key) + ".button", SECTION_TEXT_KEY));

        return new Element(
                Component.translatable(SECTION_KEY, getTranslationComponent(key)),
                getTooltipComponent(key, null),
                Button.builder(buttonText, button -> {
                    ConfigurationScreen.ConfigurationSectionScreen target = sectionCache.computeIfAbsent(key,
                            k -> new BlockListScreen<>(Context.list(context, this), key,
                                    Component.empty()
                                            .append(getTitle())
                                            .append(ConfigurationScreen.CRUMB_SEPARATOR)
                                            .append(getTranslationComponent(key)),
                                    spec, list));
                    minecraft.setScreen(target);
                }).tooltip(Tooltip.create(getTooltipComponent(key, null))).build(),
                false);
    }

    /**
     * 方块列表界面：只改左侧标签的显示内容，其余（上下移动、删除、新增、撤销、编辑框）全部沿用 NeoForge 默认实现。
     *
     * <p>注意：编辑框里修改 id 后，左侧标签要等该行重建时才刷新（例如离开再进入本界面、
     * 新增/删除/移动行时）；新增行会立即显示新方块的名称。
     */
    public static final class BlockListScreen<T> extends ConfigurationScreen.ConfigurationListScreen<T> {

        BlockListScreen(Context context, String key, Component title,
                        ModConfigSpec.ListValueSpec spec, ModConfigSpec.ConfigValue<List<T>> valueList) {
            super(context, key, title, spec, valueList);
        }

        @Override
        protected AbstractWidget createListLabel(int idx) {
            Object entry = idx >= 0 && idx < cfgList.size() ? cfgList.get(idx) : null;
            return new ListLabelWidget(0, 0, Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT,
                    BlockNameFormatter.displayName(entry, key), idx);
        }
    }
}
