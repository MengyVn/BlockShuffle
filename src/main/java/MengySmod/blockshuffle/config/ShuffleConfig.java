package MengySmod.blockshuffle.config;

import MengySmod.blockshuffle.Blockshuffle;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BlockShuffle 的全部配置项。
 *
 * <p>配置在加载与热重载时被解析成一个不可变的 {@link Values} 快照，
 * 运行期只读取快照，避免运行中的任务读到"半更新"的配置。
 *
 * <p>翻译键（供 NeoForge 配置界面显示）格式为 {@code blockshuffle.configuration.<配置名>}
 * 与 {@code blockshuffle.configuration.<配置名>.tooltip}。
 */
// bus 无需显式指定：FML 会按事件是否实现 IModBusEvent 自动把方法注册到对应的总线
@EventBusSubscriber(modid = Blockshuffle.MODID)
public final class ShuffleConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ------------------------------------------------------------------ 玩法

    private static final ModConfigSpec.IntValue RADIUS_CHUNKS = BUILDER
            .comment("影响半径，单位为区块（以玩家所在区块为中心的正方形区域）。",
                    "默认 6，上限 32。",
                    "注意：真正能改到的范围取决于服务端已经加载的区块；未加载的区块不会被修改。",
                    "半径越大，扫描与替换耗时越长（32 区块约为 4 亿格，会明显卡顿）。")
            .defineInRange("radiusChunks", 6, 1, 32);

    private static final ModConfigSpec.DoubleValue COOLDOWN_SECONDS = BUILDER
            .comment("两次互换之间的最小间隔（秒）。",
                    "持续伤害（着火、溺水、中毒、饥饿）每 10~20 tick 触发一次，冷却用于防止刷屏与卡顿。",
                    "0 表示不限制。")
            .defineInRange("cooldownSeconds", 5.0D, 0.0D, 600.0D);

    private static final ModConfigSpec.IntValue BLOCKS_PER_TICK = BUILDER
            .comment("每 tick 最多替换的方块数量（分帧预算）。",
                    "数值越大，互换完成得越快，但单 tick 卡顿越明显。",
                    "扫描阶段的预算为该值的 50 倍。")
            .defineInRange("blocksPerTick", 6000, 100, 200000);

    private static final ModConfigSpec.IntValue MAX_SWAP_BLOCKS = BUILDER
            .comment("单次互换的方块数量安全阀。",
                    "抽取到的一对类型若总格数超过该值，会重新抽取（最多 typePickRetries 次），",
                    "仍然超限则跳过本次互换并在聊天栏提示，避免把服务器卡死。",
                    "0 表示不限制（不推荐）。")
            .defineInRange("maxSwapBlocks", 500000, 0, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue TYPE_PICK_RETRIES = BUILDER
            .comment("安全阀触发的最大重抽次数。")
            .defineInRange("typePickRetries", 8, 1, 64);

    // ------------------------------------------------------------- 方块筛选

    private static final ModConfigSpec.BooleanValue FLUIDS_PARTICIPATE = BUILDER
            .comment("水、岩浆是否参与互换。",
                    "参与时互换会抑制邻居更新，但岩浆源仍可能在之后流动并引发水石反应。")
            .define("fluidsParticipate", true);

    private static final ModConfigSpec.BooleanValue KEEP_PLANTS_ATTACHED = BUILDER
            .comment("保持植物/下落方块不因本次互换而脱落。",
                    "开启时互换使用 UPDATE_KNOWN_SHAPE，不触发邻居与形状更新：",
                    "花、草、海带、沙子等会保持原样（可能悬空），同时大幅减少掉落物。",
                    "关闭则按原版规则级联更新（掉落物多、开销大，不建议）。")
            .define("keepPlantsAttached", true);

    private static final ModConfigSpec.BooleanValue HANDLE_FALLING_BLOCKS = BUILDER
            .comment("是否在观察窗口内限制下落方块实体（FallingBlockEntity）的数量。",
                    "石头变成沙子后可能大面积下落，同样会导致卡顿。")
            .define("handleFallingBlocks", true);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_BLACKLIST = BUILDER
            .comment("黑名单方块，永不参与互换（候选与目标都会被排除）。",
                    "默认排除基岩、传送门、屏障、命令方块、结构方块、刷怪笼等会破坏存档或玩法平衡的方块。",
                    "方块实体（箱子、告示牌、熔炉等）与空气始终被排除，无需在此列出。")
            .defineListAllowEmpty("blockBlacklist", List.of(
                    "minecraft:bedrock",
                    "minecraft:barrier",
                    "minecraft:light",
                    "minecraft:structure_block",
                    "minecraft:structure_void",
                    "minecraft:jigsaw",
                    "minecraft:command_block",
                    "minecraft:chain_command_block",
                    "minecraft:repeating_command_block",
                    "minecraft:end_portal",
                    "minecraft:end_portal_frame",
                    "minecraft:end_gateway",
                    "minecraft:nether_portal",
                    "minecraft:spawner",
                    "minecraft:trial_spawner",
                    "minecraft:vault",
                    "minecraft:moving_piston",
                    "minecraft:piston_head"), () -> "minecraft:bedrock", ShuffleConfig::validateBlockId);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS = BUILDER
            .comment("生效的维度列表，例如 [\"minecraft:overworld\", \"minecraft:the_nether\"]。",
                    "填写 \"*\" 或留空表示所有维度都生效。")
            .defineListAllowEmpty("dimensions", List.of("*"), () -> "minecraft:overworld", ShuffleConfig::validateDimension);

    // ----------------------------------------------------------------- 权重

    private static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_WEIGHTS = BUILDER
            .comment("手动指定方块被抽中的权重，格式 \"minecraft:stone=3.0\"（省略 =1.0）。",
                    "未列出的方块权重为 1.0；权重 0 表示永不被抽中。",
                    "权重只影响\"是否被抽到\"，不影响互换范围：抽中后该类型在半径内出现的所有位置都会被换掉。")
            .defineListAllowEmpty("blockWeights", List.of(), () -> "minecraft:stone=1.0", ShuffleConfig::validateWeightEntry);

    // --------------------------------------------------------------- 提示信息

    private static final ModConfigSpec.EnumValue<MessageMode> SWAP_MESSAGE_MODE = BUILDER
            .comment("互换时在游戏内提示方块名称的方式。",
                    "CHAT = 聊天栏，ACTION_BAR = 物品栏上方，TITLE = 屏幕中央大字，OFF = 不提示。",
                    "提示内容形如「泥土 ⇄ 橡木」，方块名按各客户端自己的语言显示。")
            .defineEnum("swapMessageMode", MessageMode.CHAT);

    private static final ModConfigSpec.EnumValue<MessageScope> SWAP_MESSAGE_SCOPE = BUILDER
            .comment("谁会看到这条提示。",
                    "TRIGGERER = 只有受伤的玩家（触发者始终能看到，不受此项影响），",
                    "REGION = 受影响范围内的玩家，DIMENSION = 该维度所有玩家，ALL = 全服所有玩家。")
            .defineEnum("swapMessageScope", MessageScope.REGION);

    // ------------------------------------------------------------- 掉落物上限

    private static final ModConfigSpec.IntValue DROP_ENTITY_LIMIT = BUILDER
            .comment("互换后观察窗口内，区域内允许存在的掉落物实体上限。",
                    "上限 = 区域内总量：窗口开始时已存在的掉落物计入配额，超出后新生成的掉落物会被取消。",
                    "0 表示不限制。")
            .defineInRange("dropEntityLimit", 100, 0, 10000);

    private static final ModConfigSpec.DoubleValue DROP_WATCH_SECONDS = BUILDER
            .comment("掉落物观察窗口时长（秒）。",
                    "树叶腐烂、沙子下落、植物脱落等掉落大多发生在互换之后，",
                    "因此限制只在窗口内生效，窗口结束后恢复正常。")
            .defineInRange("dropWatchSeconds", 15.0D, 0.0D, 120.0D);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ------------------------------------------------------------------ 枚举

    /** 互换提示的显示方式。实现 {@link TranslatableEnum} 让配置界面显示中文选项而非 CHAT/TITLE。 */
    public enum MessageMode implements TranslatableEnum {
        OFF,
        CHAT,
        ACTION_BAR,
        TITLE;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("blockshuffle.configuration.swapMessageMode." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** 互换提示的可见范围。 */
    public enum MessageScope implements TranslatableEnum {
        TRIGGERER,
        REGION,
        DIMENSION,
        ALL;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("blockshuffle.configuration.swapMessageScope." + name().toLowerCase(Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 运行期使用的不可变配置快照。
     */
    public record Values(int radiusChunks,
                         double cooldownSeconds,
                         int blocksPerTick,
                         int maxSwapBlocks,
                         int typePickRetries,
                         boolean fluidsParticipate,
                         boolean keepPlantsAttached,
                         boolean handleFallingBlocks,
                         Set<ResourceLocation> dimensions,
                         boolean allDimensions,
                         Object2DoubleMap<Block> blockWeights,
                         Set<Block> blockBlacklist,
                         int dropEntityLimit,
                         double dropWatchSeconds,
                         MessageMode messageMode,
                         MessageScope messageScope) {

        /** 该方块被抽中的权重，未配置为 1.0。 */
        public double weightOf(Block block) {
            return blockWeights.getOrDefault(block, 1.0D);
        }
    }

    private static volatile Values values = defaults();

    private ShuffleConfig() {
    }

    public static Values get() {
        return values;
    }

    private static Values defaults() {
        return new Values(6, 5.0D, 6000, 500000, 8, true, true, true,
                Set.of(), true, new Object2DoubleOpenHashMap<>(), Set.of(), 100, 15.0D,
                MessageMode.CHAT, MessageScope.REGION);
    }

    // ------------------------------------------------------------------ 解析

    @SubscribeEvent
    static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            reload();
        }
    }

    @SubscribeEvent
    static void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            reload();
        }
    }

    private static void reload() {
        Object2DoubleMap<Block> weights = new Object2DoubleOpenHashMap<>();
        for (String entry : BLOCK_WEIGHTS.get()) {
            String[] parts = entry.split("=", 2);
            Block block = blockById(parts[0].trim());
            if (block == null) {
                Blockshuffle.LOGGER.warn("[BlockShuffle] 未知方块权重条目: {}", entry);
                continue;
            }
            double weight = 1.0D;
            if (parts.length == 2) {
                try {
                    weight = Double.parseDouble(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    Blockshuffle.LOGGER.warn("[BlockShuffle] 无法解析权重: {}", entry);
                    continue;
                }
            }
            weights.put(block, Math.max(0.0D, weight));
        }

        Set<Block> blacklist = new LinkedHashSet<>();
        for (String entry : BLOCK_BLACKLIST.get()) {
            Block block = blockById(entry.trim());
            if (block != null) {
                blacklist.add(block);
            } else {
                Blockshuffle.LOGGER.warn("[BlockShuffle] 黑名单中的未知方块: {}", entry);
            }
        }

        Set<ResourceLocation> dimensions = new LinkedHashSet<>();
        boolean all = DIMENSIONS.get().isEmpty();
        for (String entry : DIMENSIONS.get()) {
            String trimmed = entry.trim();
            if (trimmed.equals("*")) {
                all = true;
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(trimmed);
            if (id != null) {
                dimensions.add(id);
            } else {
                Blockshuffle.LOGGER.warn("[BlockShuffle] 无法解析维度 id: {}", entry);
            }
        }

        values = new Values(
                RADIUS_CHUNKS.get(),
                COOLDOWN_SECONDS.get(),
                BLOCKS_PER_TICK.get(),
                MAX_SWAP_BLOCKS.get(),
                TYPE_PICK_RETRIES.get(),
                FLUIDS_PARTICIPATE.get(),
                KEEP_PLANTS_ATTACHED.get(),
                HANDLE_FALLING_BLOCKS.get(),
                Set.copyOf(dimensions),
                all,
                weights,
                Set.copyOf(blacklist),
                DROP_ENTITY_LIMIT.get(),
                DROP_WATCH_SECONDS.get(),
                SWAP_MESSAGE_MODE.get(),
                SWAP_MESSAGE_SCOPE.get());

        Blockshuffle.LOGGER.info("[BlockShuffle] 配置已加载：半径 {} 区块，冷却 {} 秒，每 tick {} 格，安全阀 {}，提示 {} / {}",
                values.radiusChunks(), values.cooldownSeconds(), values.blocksPerTick(),
                values.maxSwapBlocks() == 0 ? "关闭" : String.valueOf(values.maxSwapBlocks()),
                values.messageMode(), values.messageScope());
    }

    private static Block blockById(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(key);
    }

    // ---------------------------------------------------------------- 校验器

    private static boolean validateBlockId(Object obj) {
        return obj instanceof String id && ResourceLocation.tryParse(id.trim()) != null
                && BuiltInRegistries.BLOCK.containsKey(ResourceLocation.tryParse(id.trim()));
    }

    private static boolean validateDimension(Object obj) {
        if (!(obj instanceof String id)) {
            return false;
        }
        String trimmed = id.trim();
        return trimmed.equals("*") || ResourceLocation.tryParse(trimmed) != null;
    }

    private static boolean validateWeightEntry(Object obj) {
        if (!(obj instanceof String entry)) {
            return false;
        }
        String[] parts = entry.split("=", 2);
        if (ResourceLocation.tryParse(parts[0].trim()) == null) {
            return false;
        }
        if (parts.length == 2) {
            try {
                return Double.parseDouble(parts[1].trim()) >= 0.0D;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
