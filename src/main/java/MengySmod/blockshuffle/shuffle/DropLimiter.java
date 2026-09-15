package MengySmod.blockshuffle.shuffle;

import MengySmod.blockshuffle.Blockshuffle;
import MengySmod.blockshuffle.config.ShuffleConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 掉落物上限（互换后的观察窗口）。
 *
 * <p>互换本身不会掉落任何东西（替换走的是 setBlock，不经过破坏流程），
 * 但互换之后会由随机刻/方块刻引发连锁反应：植物失去支撑而脱落、树叶腐烂掉苹果木棍、沙砾落定等。
 * 这些都不在互换的瞬间发生，所以限制做成"观察窗口"。
 *
 * <p>核心原则：<b>只限制互换引发的掉落物，绝不误伤玩家自己的行为</b>。为此做了三件事：
 *
 * <ol>
 *   <li><b>玩家行为识别</b>：{@link BlockEvent.BreakEvent}（玩家破坏方块）、
 *       {@link LivingDropsEvent}（生物死亡掉落；该事件在掉落物进入世界之前触发）、
 *       以及 {@link ItemEntity#getOwner()}（玩家 Q 键丢出的物品）都会把位置记入短期记忆；
 *       命中记忆的掉落物<b>永不拦截，也不占用配额</b>。</li>
 *   <li><b>不再拦截下落方块实体</b>：{@code FallingBlockEntity.fall()} 会先把方块从世界里移除
 *       （把该位置设成空气/流体）再生成实体来搬运它，因此取消这个实体的生成 = 方块凭空消失，
 *       比丢掉落物更严重。"石头变沙子后大面积下落"这个风险由互换时的 {@code UPDATE_KNOWN_SHAPE} 抑制
 *       （见配置项 keepPlantsAttached），不在这里处理。</li>
 *   <li><b>配额按"区域内掉落物总量"计算</b>：窗口开始时已存在的掉落物计入配额。</li>
 * </ol>
 *
 * <p>已知边界：玩家砍树时，原木的掉落物在原木位置（受保护），
 * 但随后由树叶腐烂产生的掉落物不在记录位置上，仍可能被配额限制——
 * 这正是本功能要压制的"海量掉落"场景。
 */
@EventBusSubscriber(modid = Blockshuffle.MODID)
public final class DropLimiter {

    /** 玩家行为的位置记忆保留多少个 tick（破坏方块与掉落物生成发生在同一 tick，10 tick 足够宽松） */
    private static final int ACTION_MEMORY_TICKS = 10;
    /** 位置记忆的条目上限，防止长时间开启的窗口把队列撑大 */
    private static final int MAX_ACTIONS = 256;
    /** 掉落物与记录位置的距离在 2 格以内即视为同一次行为产生 */
    private static final double ACTION_RADIUS_SQ = 4.0D;

    private record Action(Level level, BlockPos pos, long tick) {
    }

    private static final Deque<Action> RECENT_ACTIONS = new ArrayDeque<>();

    /** 由 {@link LivingDropsEvent} 精确标记的"生物死亡掉落物"，永不限制 */
    private static final Set<ItemEntity> PROTECTED_DROPS = Collections.newSetFromMap(new WeakHashMap<>());

    private static ServerLevel level;
    private static AABB region;
    private static long expireTick;
    private static int itemBudget;
    private static int blockedItems;

    private DropLimiter() {
    }

    /** 互换成功后开启观察窗口。 */
    public static void startWindow(ServerLevel serverLevel, BlockPos center, int radiusChunks, ShuffleConfig.Values values) {
        clear();
        if (values.dropEntityLimit() <= 0 || values.dropWatchSeconds() <= 0.0D) {
            return;
        }
        AABB box = new AABB(center).inflate(radiusChunks * 16.0D);
        int existing = serverLevel.getEntitiesOfClass(ItemEntity.class, box).size();

        level = serverLevel;
        region = box;
        itemBudget = Math.max(0, values.dropEntityLimit() - existing);
        blockedItems = 0;
        expireTick = serverLevel.getGameTime() + (long) (values.dropWatchSeconds() * 20.0D);

        Blockshuffle.LOGGER.info("[BlockShuffle] 掉落物观察窗口开启：已有 {} 个掉落物，配额 {} 个，时长 {} 秒"
                        + "（玩家自己破坏方块产生的掉落物不受限制）",
                existing, itemBudget, values.dropWatchSeconds());
    }

    /** 由 {@link ShuffleManager} 在每个服务端 tick 调用，用于清理过期记忆与关闭窗口。 */
    public static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        pruneActions(level.getGameTime());
        if (level.getServer() != server || level.getGameTime() > expireTick) {
            if (blockedItems > 0) {
                Blockshuffle.LOGGER.info("[BlockShuffle] 观察窗口结束：共限制互换引发的掉落物 {} 个", blockedItems);
            }
            clear();
        }
    }

    /** 玩家破坏方块：记下位置，之后在该位置产生的掉落物一律放行。 */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            remember(serverLevel, event.getPos());
        }
    }

    /** 生物死亡掉落：事件里就是"即将进入世界"的那批掉落物，直接按实体精确保护（比按位置猜测更准）。 */
    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getDrops().isEmpty()) {
            return;
        }
        for (ItemEntity drop : event.getDrops()) {
            PROTECTED_DROPS.add(drop);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (level == null || event.getLevel() != level || event.loadedFromDisk()) {
            return;
        }
        // 只处理掉落物；下落方块实体不做任何拦截（拦截会让方块凭空消失）
        if (!(event.getEntity() instanceof ItemEntity item)) {
            return;
        }
        if (!region.contains(item.position())) {
            return;
        }
        if (PROTECTED_DROPS.contains(item) || isPlayerCaused(item)) {
            return;
        }
        if (itemBudget <= 0) {
            blockedItems++;
            event.setCanceled(true);
        } else {
            itemBudget--;
        }
    }

    // ------------------------------------------------------------------ 归属判断

    private static void remember(ServerLevel serverLevel, BlockPos pos) {
        RECENT_ACTIONS.addLast(new Action(serverLevel, pos.immutable(), serverLevel.getGameTime()));
        while (RECENT_ACTIONS.size() > MAX_ACTIONS) {
            RECENT_ACTIONS.removeFirst();
        }
    }

    /**
     * 这个掉落物是不是玩家自己弄出来的。
     *
     * @return true 表示永不限制（也不占用配额）
     */
    private static boolean isPlayerCaused(ItemEntity item) {
        if (item.getOwner() != null) {
            // 玩家 Q 键丢出 / 投掷出来的物品
            return true;
        }
        Level itemLevel = item.level();
        long now = itemLevel.getGameTime();
        for (Action action : RECENT_ACTIONS) {
            if (action.level() != itemLevel) {
                continue;
            }
            if (now - action.tick() > ACTION_MEMORY_TICKS) {
                continue;
            }
            // 掉落物生成在方块中心附近，因此按"到方块中心"的距离判断
            if (action.pos().distToCenterSqr(item.getX(), item.getY(), item.getZ()) <= ACTION_RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }

    private static void pruneActions(long now) {
        while (!RECENT_ACTIONS.isEmpty() && now - RECENT_ACTIONS.peekFirst().tick() > ACTION_MEMORY_TICKS) {
            RECENT_ACTIONS.removeFirst();
        }
    }

    private static void clear() {
        level = null;
        region = null;
        expireTick = 0L;
        itemBudget = 0;
        RECENT_ACTIONS.clear();
        PROTECTED_DROPS.clear();
    }
}
