package MengySmod.blockshuffle;

import MengySmod.blockshuffle.config.ShuffleConfig;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * BlockShuffle - 玩家每次受伤时，以玩家为中心在指定区块范围内随机抽取两种方块类型并整体互换。
 */
@Mod(Blockshuffle.MODID)
public class Blockshuffle {
    /** The mod id, must match the entry in META-INF/neoforge.mods.toml */
    public static final String MODID = "blockshuffle";

    public static final Logger LOGGER = LogUtils.getLogger();

    public Blockshuffle(IEventBus modEventBus, ModContainer modContainer) {
        // 玩法参数走 COMMON 配置：单人存档与专用服务器都可编辑（config/blockshuffle-common.toml）
        modContainer.registerConfig(ModConfig.Type.COMMON, ShuffleConfig.SPEC);

        // 配置界面（仅客户端）：NeoForge 自带的 ConfigurationScreen，零额外依赖
        if (FMLEnvironment.dist.isClient()) {
            MengySmod.blockshuffle.client.BlockShuffleClientConfig.register(modContainer);
        }

        LOGGER.info("BlockShuffle initialized.");
    }
}
