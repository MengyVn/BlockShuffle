package MengySmod.blockshuffle.shuffle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 以玩家所在区块为中心、按距离由近到远遍历区域内所有方块格子。
 *
 * <p>性能要点（都依赖已核实的原版 API）：
 * <ul>
 *   <li>只遍历服务端<b>已加载</b>的区块（{@link ServerChunkCache#getChunkNow}），未加载的直接跳过；</li>
 *   <li>只遍历到 {@link LevelChunk#getHighestFilledSectionIndex()}（最高的非空 section，注意它返回的是<b>下标</b>），
 *       世界高度 384 格里有大量空气段被整体跳过；</li>
 *   <li>整段只有空气的 section 用 {@link LevelChunkSection#hasOnlyAir()}（O(1)）跳过；</li>
 *   <li>section 内按 y→z→x 顺序访问，与调色板位存储的索引顺序一致，缓存友好。</li>
 * </ul>
 */
final class RegionCursor {

    private final ServerLevel level;
    private final List<Long> chunkOrder;
    private int orderIndex;

    private LevelChunk chunk;
    /** 最高的非空 section 下标（含），-1 表示没有 */ 
    private int topSection = -1;
    private int originX;
    private int originZ;
    private int minSectionY;
    private int section;
    private int y;
    private int z;
    private int x;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    RegionCursor(ServerLevel level, BlockPos center, int radiusChunks) {
        this.level = level;
        this.chunkOrder = buildOrder(center.getX() >> 4, center.getZ() >> 4, radiusChunks);
    }

    /** 区域内的区块总数（含中心），仅用于统计与日志。 */
    int chunkCount() {
        return chunkOrder.size();
    }

    /** 区域内的区块遍历顺序（由近到远），采样选型阶段复用同一份顺序。 */
    static List<Long> chunkOrder(BlockPos center, int radiusChunks) {
        return buildOrder(center.getX() >> 4, center.getZ() >> 4, radiusChunks);
    }

    private static List<Long> buildOrder(int centerChunkX, int centerChunkZ, int radius) {
        List<int[]> offsets = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                offsets.add(new int[] { dx, dz });
            }
        }
        // 由近到远：玩家附近先发生变化，视觉上也更像"以玩家为中心扩散"
        offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));

        List<Long> result = new ArrayList<>(offsets.size());
        for (int[] offset : offsets) {
            result.add(ChunkPos.asLong(centerChunkX + offset[0], centerChunkZ + offset[1]));
        }
        return result;
    }

    /**
     * 前进到下一个格子。
     *
     * @return false 表示区域已遍历完
     */
    boolean advance() {
        while (true) {
            if (chunk == null) {
                if (!loadNextChunk()) {
                    return false;
                }
                section = -1;
            }
            if (section < 0) {
                section = 0;
                y = 0;
                z = 0;
                x = 0;
            } else {
                x++;
                if (x >= 16) {
                    x = 0;
                    z++;
                    if (z >= 16) {
                        z = 0;
                        y++;
                        if (y >= 16) {
                            y = 0;
                            section++;
                        }
                    }
                }
            }
            // 跳过整段空气（空气既不参与互换，也不参与扫描统计）
            while (section <= topSection && chunk.getSection(section).hasOnlyAir()) {
                section++;
                y = 0;
                z = 0;
                x = 0;
            }
            if (section > topSection) {
                chunk = null;
                continue;
            }
            return true;
        }
    }

    private boolean loadNextChunk() {
        while (orderIndex < chunkOrder.size()) {
            long packed = chunkOrder.get(orderIndex++);
            int chunkX = ChunkPos.getX(packed);
            int chunkZ = ChunkPos.getZ(packed);
            LevelChunk loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (loaded == null) {
                continue;
            }
            int top = loaded.getHighestFilledSectionIndex();
            if (top < 0) {
                continue;
            }
            chunk = loaded;
            topSection = top;
            originX = chunkX << 4;
            originZ = chunkZ << 4;
            minSectionY = loaded.getMinSection();
            return true;
        }
        return false;
    }

    /** 当前格子的方块状态。 */
    BlockState state() {
        return chunk.getSection(section).getBlockState(x, y, z);
    }

    /** 当前格子的世界坐标（复用的可变对象，仅在覆盖调用 {@code Level.setBlock} 前有效）。 */
    BlockPos pos() {
        return cursor.set(originX | x, (minSectionY + section) * 16 + y, originZ | z);
    }

    /** 已加载并参与遍历的区块序号，用于日志。 */
    int visitedChunks() {
        return orderIndex;
    }
}
