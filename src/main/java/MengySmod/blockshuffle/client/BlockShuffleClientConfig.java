package MengySmod.blockshuffle.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 客户端专用：把 NeoForge 内置的配置界面挂到模组列表的 "Config" 按钮上。
 *
 * <p>这个类只会在物理客户端被加载（见 {@code Blockshuffle} 构造器里的 Dist 判断），
 * 因此其中引用的客户端类不会在专用服务器上导致崩溃。
 */
public final class BlockShuffleClientConfig {
    private BlockShuffleClientConfig() {
    }

    public static void register(ModContainer container) {
        // 第三个参数是 NeoForge 留出的"分节界面工厂"扩展点：用它把方块列表换成显示中文名的版本
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new ConfigurationScreen(mod, parent,
                        (screen, type, config, title) -> new LocalizedBlockSectionScreen(screen, type, config, title)));
        MengySmod.blockshuffle.Blockshuffle.LOGGER.info("BlockShuffle 配置界面已注册（NeoForge ConfigurationScreen + 方块名本地化）");
    }
}
