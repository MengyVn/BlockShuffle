package MengySmod.blockshuffle.shuffle;

import MengySmod.blockshuffle.Blockshuffle;
import MengySmod.blockshuffle.config.ShuffleConfig;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * 一次互换任务的完整生命周期：先扫描统计区域内"存在哪些方块类型"，
 * 再按权重抽两个类型，最后分帧把所有出现位置替换掉。
 *
 * <p>整个过程被切成多个 tick 执行（{@link #tick()}），不会长时间阻塞服务端主线程。
 *
 * <p>关键正确性约定：
 * <ul>
 *   <li>每个格子只访问一次、只做一次决策（A→B 或 B→A），
 *       因此不会出现"先换成 B 又被换回 A"的自我抵消；</li>
 *   <li>候选与目标都排除空气、方块实体与黑名单方块；</li>
 *   <li>默认使用 UPDATE_KNOWN_SHAPE 抑制邻居/形状级联更新，
 *       这样植物不会脱落、沙子不会立刻下落、流体不会大面积流动；</li>
 *   <li>触发互换的玩家自身碰撞箱占据的格子会被跳过，避免把玩家埋进方块。</li>
 * </ul>
 */
public final class ShuffleJob {

    private enum Phase {
        SCAN,
        SWAP,
        DONE
    }

    private final ShuffleConfig.Values values;
    private final ServerLevel level;
    private final BlockPos center;
    private final UUID playerId;
    private final RandomSource random = RandomSource.create();

    private final Object2IntOpenHashMap<Block> tally = new Object2IntOpenHashMap<>();
    private RegionCursor cursor;
    private Phase phase = Phase.SCAN;

    private Block blockA;
    private Block blockB;
    private int countA;
    private int countB;
    private long swapped;
    private long visitedChunks;
    private String message;

    public ShuffleJob(ServerLevel level, BlockPos center, UUID playerId) {
        this.values = ShuffleConfig.get();
        this.level = level;
        this.center = center.immutable();
        this.playerId = playerId;
        this.cursor = new RegionCursor(level, this.center, values.radiusChunks());
    }

    /**
     * 推进任务。
     *
     * @return true 表示任务已结束（成功或放弃），应从队列中移除
     */
    public boolean tick() {
        if (phase == Phase.SCAN) {
            scanTick();
            if (phase == Phase.SCAN) {
                return false;
            }
            if (phase == Phase.DONE) {
                return true;
            }
            // 扫描完成后立刻开始替换，同一 tick 内衔接
        }
        if (phase == Phase.SWAP) {
            swapTick();
        }
        return phase == Phase.DONE;
    }

    // ------------------------------------------------------------------ 扫描

    private void scanTick() {
        int cellBudget = Math.max(50_000, values.blocksPerTick() * 50);
        int visited = 0;
        while (visited < cellBudget) {
            if (!cursor.advance()) {
                finishScan();
                return;
            }
            visited++;
            BlockState state = cursor.state();
            if (SwapHelper.isCandidate(state, values)) {
                tally.addTo(state.getBlock(), 1);
            }
        }
    }

    private void finishScan() {
        visitedChunks = cursor.visitedChunks();
        if (tally.size() < 2) {
            abort("区域内可互换的方块种类不足 2 种（空气、方块实体与黑名单方块不参与）");
            return;
        }
        if (!pickPair()) {
            return;
        }
        Blockshuffle.LOGGER.info("[BlockShuffle] 选定互换对：{} <-> {}（{} 格 + {} 格，{}/{} 区块已加载）",
                blockA, blockB, countA, countB, visitedChunks, cursor.chunkCount());
        // 选定即提示，玩家在方块开始变化前就知道换的是哪两种方块
        SwapNotifier.announcePair(level, center, playerId, blockA, blockB);
        cursor = new RegionCursor(level, center, values.radiusChunks());
        phase = Phase.SWAP;
    }

    /** 按权重抽取两个不同的方块类型，并应用"单次最大格数"安全阀。 */
    private boolean pickPair() {
        double totalWeight = 0.0D;
        for (Object2IntMap.Entry<Block> entry : tally.object2IntEntrySet()) {
            totalWeight += values.weightOf(entry.getKey());
        }
        if (totalWeight <= 0.0D) {
            abort("所有候选方块的权重都是 0，无法抽取");
            return false;
        }

        int attempts = Math.max(1, values.typePickRetries());
        int limit = values.maxSwapBlocks();
        Block bestA = null;
        Block bestB = null;
        long bestTotal = Long.MAX_VALUE;

        for (int attempt = 0; attempt < attempts; attempt++) {
            Block a = pickWeighted(totalWeight, null);
            if (a == null) {
                break;
            }
            Block b = pickWeighted(totalWeight - values.weightOf(a), a);
            if (b == null) {
                break;
            }
            long total = (long) tally.getInt(a) + tally.getInt(b);
            if (limit <= 0 || total <= limit) {
                blockA = a;
                blockB = b;
                countA = tally.getInt(a);
                countB = tally.getInt(b);
                return true;
            }
            if (total < bestTotal) {
                bestTotal = total;
                bestA = a;
                bestB = b;
            }
        }

        if (bestA != null) {
            abort("抽到的方块组合数量过大（" + bestTotal + " 格 > 安全阀 " + limit + "），已跳过本次互换");
        } else {
            abort("无法抽出两个不同的方块类型");
        }
        return false;
    }

    private Block pickWeighted(double totalWeight, Block exclude) {
        if (totalWeight <= 0.0D) {
            return null;
        }
        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0.0D;
        for (Object2IntMap.Entry<Block> entry : tally.object2IntEntrySet()) {
            Block block = entry.getKey();
            if (block == exclude) {
                continue;
            }
            cumulative += values.weightOf(block);
            if (roll < cumulative) {
                return block;
            }
        }
        // 浮点误差兜底：返回最后一个权重为正的合法方块
        Block fallback = null;
        for (Object2IntMap.Entry<Block> entry : tally.object2IntEntrySet()) {
            if (entry.getKey() != exclude && values.weightOf(entry.getKey()) > 0.0D) {
                fallback = entry.getKey();
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------ 替换

    private void swapTick() {
        int writeBudget = values.blocksPerTick();
        int visitBudget = Math.max(20_000, writeBudget * 20);
        int writes = 0;
        int visited = 0;
        AABB playerBox = protectedPlayerBox();

        while (writes < writeBudget && visited < visitBudget) {
            if (!cursor.advance()) {
                phase = Phase.DONE;
                return;
            }
            visited++;
            BlockState state = cursor.state();
            Block current = state.getBlock();
            Block target;
            if (current == blockA) {
                target = blockB;
            } else if (current == blockB) {
                target = blockA;
            } else {
                continue;
            }
            if (applySwap(state, target, playerBox)) {
                writes++;
                swapped++;
            }
        }
    }

    private boolean applySwap(BlockState state, Block target, AABB playerBox) {
        BlockState mapped = SwapHelper.mapState(state, target.defaultBlockState());
        if (mapped.getBlock() != target || mapped.hasBlockEntity()) {
            // 目标方块带方块实体的情况理论上已被候选筛选排除，这里再兜一层
            return false;
        }
        BlockPos pos = cursor.pos();
        if (playerBox != null
                && playerBox.intersects(pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D)) {
            return false;
        }
        // UPDATE_CLIENTS: 客户端同步（原版会按 section 合并成批量包）
        // UPDATE_KNOWN_SHAPE: 不触发邻居/形状级联更新，植物不掉落、沙子不下落、流体不流动
        int flags = Block.UPDATE_CLIENTS | (values.keepPlantsAttached()
                ? Block.UPDATE_KNOWN_SHAPE
                : Block.UPDATE_NEIGHBORS);
        return level.setBlock(pos, mapped, flags);
    }

    /** 触发互换的玩家碰撞箱（若玩家仍在同一维度），避免把玩家换成实心方块造成窒息。 */
    private AABB protectedPlayerBox() {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null || player.level() != level) {
            return null;
        }
        return player.getBoundingBox();
    }

    // ------------------------------------------------------------------ 状态

    private void abort(String reason) {
        this.message = reason;
        this.phase = Phase.DONE;
        Blockshuffle.LOGGER.info("[BlockShuffle] 本次未互换：{}", reason);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) {
            // 带 fallback：未装模组的客户端也能看到提示，而不是原始翻译键名
            player.displayClientMessage(Component.translatableWithFallback(
                    "blockshuffle.message.skipped", "[BlockShuffle] %s", reason), true);
        }
    }

    public ServerLevel level() {
        return level;
    }

    public BlockPos center() {
        return center;
    }

    public boolean swappedAnything() {
        return swapped > 0;
    }

    public long swappedCount() {
        return swapped;
    }

    public String summary() {
        if (message != null) {
            return "跳过：" + message;
        }
        if (blockA == null) {
            return "无结果";
        }
        return blockA.getName().getString() + " <-> " + blockB.getName().getString()
                + "，实际替换 " + swapped + " 格（候选 " + countA + " + " + countB + " 格，"
                + visitedChunks + "/" + cursor.chunkCount() + " 区块已加载）";
    }
}
