package MengySmod.blockshuffle.shuffle;

import MengySmod.blockshuffle.Blockshuffle;
import MengySmod.blockshuffle.config.ShuffleConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 掉落物上限。
 *
 * <p>互换本身不会掉落任何东西（替换走的是 setBlock，不经过破坏流程），
 * 但互换之后会由随机刻/方块刻引发连锁反应：树叶腐烂掉苹果木棍、植物失去支撑而脱落、
 * 石头变沙子后大面积下落。这些都不在互换的瞬间发生，所以限制必须做成"观察窗口"：
 *
 * <p>窗口开启时先统计区域内已有的掉落物数量并计入配额，窗口期内新生成的掉落物超出配额就被取消，
 * 窗口到期后自动失效。下落方块（FallingBlockEntity）按同样方式单独计数。
 */
@EventBusSubscriber(modid = Blockshuffle.MODID)
public final class DropLimiter {

    private static ServerLevel level;
    private static AABB region;
    private static long expireTick;
    private static int itemBudget;
    private static int fallingBudget;
    private static boolean capFalling;
    private static int blockedItems;
    private static int blockedFalling;

    private DropLimiter() {
    }

    /** 互换成功后开启观察窗口。 */
    public static void startWindow(ServerLevel serverLevel, BlockPos center, int radiusChunks, ShuffleConfig.Values values) {
        clear();
        if (values.dropEntityLimit() <= 0 || values.dropWatchSeconds() <= 0.0D) {
            return;
        }
        double radiusBlocks = radiusChunks * 16.0D;
        AABB box = new AABB(center).inflate(radiusBlocks);
        int existing = serverLevel.getEntitiesOfClass(ItemEntity.class, box).size();

        level = serverLevel;
        region = box;
        capFalling = values.handleFallingBlocks();
        itemBudget = Math.max(0, values.dropEntityLimit() - existing);
        fallingBudget = values.dropEntityLimit();
        blockedItems = 0;
        blockedFalling = 0;
        expireTick = serverLevel.getGameTime() + (long) (values.dropWatchSeconds() * 20.0D);

        Blockshuffle.LOGGER.info("[BlockShuffle] 掉落物观察窗口开启：已有 {} 个掉落物，配额 {} 个，时长 {} 秒",
                existing, itemBudget, values.dropWatchSeconds());
    }

    /** 由 {@link ShuffleManager} 在每个服务端 tick 调用，用于关闭过期窗口。 */
    public static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        if (level.getServer() != server || level.getGameTime() > expireTick) {
            if (blockedItems > 0 || blockedFalling > 0) {
                Blockshuffle.LOGGER.info("[BlockShuffle] 观察窗口结束：拦截掉落物 {} 个，拦截下落方块 {} 个",
                        blockedItems, blockedFalling);
            }
            clear();
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (level == null || event.getLevel() != level || event.loadedFromDisk()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!region.contains(entity.position())) {
            return;
        }
        if (entity instanceof ItemEntity) {
            if (itemBudget <= 0) {
                blockedItems++;
                event.setCanceled(true);
            } else {
                itemBudget--;
            }
        } else if (capFalling && entity instanceof FallingBlockEntity) {
            if (fallingBudget <= 0) {
                blockedFalling++;
                event.setCanceled(true);
            } else {
                fallingBudget--;
            }
        }
    }

    private static void clear() {
        level = null;
        region = null;
        expireTick = 0L;
        itemBudget = 0;
        fallingBudget = 0;
        capFalling = false;
    }
}
