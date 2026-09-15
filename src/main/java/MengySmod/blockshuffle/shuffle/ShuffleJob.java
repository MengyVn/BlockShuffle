package MengySmod.blockshuffle.shuffle;

import MengySmod.blockshuffle.Blockshuffle;
import MengySmod.blockshuffle.config.ShuffleConfig;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
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
        /** 选型：同步完成（采样/全量统计 + 抽取 + 立刻提示），不跨 tick */
        SELECT,
        SWAP,
        DONE
    }

    /** 选型阶段的随机采样上限：区域总格数不超过它就全量统计，超过就采样估算 */
    private static final int SAMPLE_CELLS = 40_000;

    private final ShuffleConfig.Values values;
    private final ServerLevel level;
    private final BlockPos center;
    private final UUID playerId;
    private final RandomSource random = RandomSource.create();

    private final Object2IntOpenHashMap<Block> tally = new Object2IntOpenHashMap<>();
    private RegionCursor cursor;
    private Phase phase = Phase.SELECT;

    private Block blockA;
    private Block blockB;
    private int countA;
    private int countB;
    private long swapped;
    private long visitedChunks;
    private boolean sampled;
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
        if (phase == Phase.SELECT) {
            if (!selectPair()) {
                phase = Phase.DONE;
                return true;
            }
            phase = Phase.SWAP;
        }
        if (phase == Phase.SWAP) {
            swapTick();
        }
        return phase == Phase.DONE;
    }

    // ------------------------------------------------------------------ 选型

    /**
     * 选出要互换的两种方块，并<b>立刻</b>发出提示。
     *
     * <p>这一步是同步完成的（不跨 tick），因此玩家受伤后同一 tick 就能看到"换了什么"，
     * 不必等整个区域扫描完。做法：
     * <ul>
     *   <li>区域较小（总格数 ≤ {@link #SAMPLE_CELLS}）时做全量统计，结果精确；</li>
     *   <li>区域很大时改用<b>随机采样</b>统计，再把采样命中数按比例换算成整区域的估算格数。
     *       采样是均匀覆盖整个区域的，所以常见方块一定会被抽到，只有占比低于约 0.05% 的
     *       极稀有方块可能漏掉（代价换来的是"受伤即刻出提示"）。</li>
     * </ul>
     */
    private boolean selectPair() {
        List<LevelChunk> loaded = new ArrayList<>();
        long totalCells = 0L;
        for (long packed : RegionCursor.chunkOrder(center, values.radiusChunks())) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed));
            if (chunk == null) {
                continue;
            }
            int top = chunk.getHighestFilledSectionIndex();
            if (top < 0) {
                continue;
            }
            loaded.add(chunk);
            totalCells += (long) (top + 1) * 4096L;
        }
        visitedChunks = loaded.size();
        if (loaded.isEmpty()) {
            abort("附近没有已加载的区块");
            return false;
        }

        if (totalCells <= SAMPLE_CELLS) {
            countFullRegion();
        } else {
            sampleRegion(loaded, totalCells);
            if (tally.size() < 2) {
                // 采样没抽到足够种类：退回全量统计（慢，但保证"区域内已有的方块"都能被选中）
                tally.clear();
                countFullRegion();
            }
        }

        if (tally.size() < 2) {
            abort("区域内可互换的方块种类不足 2 种（空气、方块实体与黑名单方块不参与）");
            return false;
        }
        if (!pickPair()) {
            return false;
        }

        Blockshuffle.LOGGER.info("[BlockShuffle] 选定互换对：{} <-> {}（{}{} 格 + {}{} 格，{}/{} 区块已加载）",
                blockA, blockB, sampled ? "约 " : "", countA, sampled ? "约 " : "", countB,
                visitedChunks, RegionCursor.chunkOrder(center, values.radiusChunks()).size());
        // 立刻提示：此时互换还没开始执行，但玩家马上就知道换的是哪两种方块
        SwapNotifier.announcePair(level, center, playerId, blockA, blockB);
        cursor = new RegionCursor(level, center, values.radiusChunks());
        return true;
    }

    /** 全量统计（精确）。 */
    private void countFullRegion() {
        RegionCursor full = new RegionCursor(level, center, values.radiusChunks());
        while (full.advance()) {
            BlockState state = full.state();
            if (SwapHelper.isCandidate(state, values)) {
                tally.addTo(state.getBlock(), 1);
            }
        }
    }

    /** 随机采样统计，并把命中数换算成整区域的估算格数。 */
    private void sampleRegion(List<LevelChunk> loaded, long totalCells) {
        long samples = Math.min(SAMPLE_CELLS, totalCells);
        for (long i = 0; i < samples; i++) {
            LevelChunk chunk = loaded.get(random.nextInt(loaded.size()));
            int top = chunk.getHighestFilledSectionIndex();
            if (top < 0) {
                continue;
            }
            int sectionIndex = random.nextInt(top + 1);
            LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section.hasOnlyAir()) {
                continue;
            }
            BlockState state = section.getBlockState(random.nextInt(16), random.nextInt(16), random.nextInt(16));
            if (SwapHelper.isCandidate(state, values)) {
                tally.addTo(state.getBlock(), 1);
            }
        }

        double scale = (double) totalCells / (double) Math.max(1L, samples);
        List<Object2IntMap.Entry<Block>> sampled = new ArrayList<>(tally.object2IntEntrySet());
        tally.clear();
        for (Object2IntMap.Entry<Block> entry : sampled) {
            tally.put(entry.getKey(), Math.max(1, (int) Math.round(entry.getIntValue() * scale)));
        }
        this.sampled = true;
    }

    /**
     * 抽取要互换的两种方块，并应用"单次最大格数"安全阀。
     *
     * <p>抽取顺序：
     * <ol>
     *   <li>先按 {@code blockParticipationChance} 逐个掷骰，得到"本次必然参与"的方块
     *       （只有真的出现在区域内的才会命中）；</li>
     *   <li>命中 ≥2 个：从命中者中随机取两个；</li>
     *   <li>命中 1 个：它作为一方，另一方按 {@code blockWeights} 随机抽取；</li>
     *   <li>命中 0 个：完全按权重随机抽取两个（默认行为）。</li>
     * </ol>
     */
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

        // 第一轮：带"必然参与"的方块
        List<Block> forced = rollForcedBlocks();
        if (!forced.isEmpty()) {
            Candidate forcedResult = new Candidate();
            if (attemptPick(forced, totalWeight, limit, attempts, forcedResult)) {
                return true;
            }
            // 指定的方块用不了（多半是它本身就超过安全阀）：退化为随机互换，
            // 否则玩家会看到"配置了 100% 却永远不互换"，以为模组坏了
            Blockshuffle.LOGGER.warn("[BlockShuffle] 指定的必然参与方块无法满足安全阀（最小组合 {} 格 > {}），本次改为随机互换",
                    forcedResult.hasValue() ? forcedResult.total : -1L, limit);
            notifyForcedSkipped(forced, forcedResult, limit);
        }

        // 第二轮：常规随机抽取
        Candidate random = new Candidate();
        if (attemptPick(List.of(), totalWeight, limit, attempts, random)) {
            return true;
        }
        if (random.hasValue()) {
            abort("抽到的方块组合数量过大（" + random.total + " 格 > 安全阀 " + limit + "），已跳过本次互换");
        } else if (random.fluidRejected) {
            abort("流体只能与实心方块互换（fluidsOnlyWithSolidBlocks），当前区域没有合适的组合");
        } else {
            abort("无法抽出两个不同的方块类型");
        }
        return false;
    }

    /** 一轮抽取尝试；成功时写入 {@link #blockA}/{@link #blockB} 并返回 true。 */
    private boolean attemptPick(List<Block> forced, double totalWeight, int limit, int attempts, Candidate out) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            Block a;
            Block b;
            if (forced.size() >= 2) {
                // 命中多个"必然参与"的方块：从中随机取两个
                a = forced.get(random.nextInt(forced.size()));
                b = forced.get(random.nextInt(forced.size()));
                if (b == a) {
                    continue;
                }
            } else if (forced.size() == 1) {
                // 命中一个：它必然参与，另一方随机抽取
                a = forced.get(0);
                b = pickWeighted(totalWeight - values.weightOf(a), a);
            } else {
                a = pickWeighted(totalWeight, null);
                b = a == null ? null : pickWeighted(totalWeight - values.weightOf(a), a);
            }
            if (a == null || b == null) {
                break;
            }
            if (!SwapHelper.isPairAllowed(a, b, values)) {
                out.fluidRejected = true;
                continue;
            }
            long total = (long) tally.getInt(a) + tally.getInt(b);
            if (limit <= 0 || total <= limit) {
                blockA = a;
                blockB = b;
                countA = tally.getInt(a);
                countB = tally.getInt(b);
                return true;
            }
            out.remember(a, b, total);
        }
        return false;
    }

    /** 指定方块被安全阀挡下时的提示（同时也告诉玩家本次仍然换了别的）。 */
    private void notifyForcedSkipped(List<Block> forced, Candidate result, int limit) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) {
            return;
        }
        String names = forced.stream()
                .map(block -> block.getName().getString())
                .reduce((x, y) -> x + "、" + y)
                .orElse("?");
        player.displayClientMessage(Component.translatableWithFallback(
                "blockshuffle.message.forcedSkipped",
                "[BlockShuffle] %s 的组合超出安全阀（%s 格），本次改为随机互换",
                names, String.valueOf(result.hasValue() ? result.total : limit)), true);
    }

    /** 一轮尝试里"最小但超限"的组合，仅用于给出有用的日志与提示。 */
    private static final class Candidate {
        private Block a;
        private Block b;
        private long total;
        private boolean fluidRejected;

        void remember(Block a, Block b, long total) {
            if (!hasValue() || total < this.total) {
                this.a = a;
                this.b = b;
                this.total = total;
            }
        }

        boolean hasValue() {
            return a != null;
        }
    }

    /**
     * 按配置的"必然参与概率"掷骰，返回本次必须参与互换的方块。
     *
     * <p>只有真正出现在区域内的方块（即进入了候选统计 {@link #tally}）才会命中，
     * 这样"不存在的方块不在计算之内"这条规则依然成立。
     */
    private List<Block> rollForcedBlocks() {
        Object2DoubleMap<Block> chances = values.blockParticipationChance();
        if (chances.isEmpty()) {
            return List.of();
        }
        List<Block> hits = new ArrayList<>(2);
        for (Object2DoubleMap.Entry<Block> entry : chances.object2DoubleEntrySet()) {
            Block block = entry.getKey();
            if (!tally.containsKey(block)) {
                continue;
            }
            double chance = entry.getDoubleValue();
            if (chance >= 1.0D || random.nextDouble() < chance) {
                hits.add(block);
            }
        }
        return hits;
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
        String prefix = sampled ? "（估算）" : "";
        return blockA.getName().getString() + " <-> " + blockB.getName().getString()
                + "，实际替换 " + swapped + " 格（候选" + prefix + " " + countA + " + " + countB + " 格，"
                + visitedChunks + " 个区块已加载）";
    }
}
