package MengySmod.blockshuffle.shuffle;

import MengySmod.blockshuffle.config.ShuffleConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;

/**
 * 候选筛选与方块状态迁移。
 *
 * <p>互换是"类型级"的：泥土整体变成橡树原木，橡树原木整体变成泥土。
 * 但每种方块都有自己的属性（朝向、轴向、含水、半砖位置……），
 * 所以替换时要尽量把两边同名的属性值搬过去，否则楼梯朝向、原木朝向会全部重置。
 */
public final class SwapHelper {

    private SwapHelper() {
    }

    /**
     * 该方块状态是否可以作为互换的候选/目标。
     *
     * <p>排除：空气、方块实体（箱子/告示牌/熔炉等）、黑名单方块，
     * 以及配置关闭时的流体（水/岩浆）。
     */
    public static boolean isCandidate(BlockState state, ShuffleConfig.Values values) {
        if (state.isAir()) {
            return false;
        }
        if (state.hasBlockEntity()) {
            return false;
        }
        if (values.blockBlacklist().contains(state.getBlock())) {
            return false;
        }
        if (!values.fluidsParticipate() && state.getBlock() instanceof LiquidBlock) {
            return false;
        }
        return true;
    }

    /**
     * 这对组合是否允许互换。
     *
     * <p>配置项 {@code fluidsOnlyWithSolidBlocks} 打开时，流体只能与"实心方块"互换：
     * 流体不与流体配对，也不与火把、花草、作物这类徒手瞬间破坏的方块配对
     * （它们无法在流体中存留，换过去往往立刻掉落或消失）。
     */
    public static boolean isPairAllowed(Block a, Block b, ShuffleConfig.Values values) {
        if (!values.fluidsOnlyWithSolidBlocks()) {
            return true;
        }
        boolean aFluid = isFluidBlock(a);
        boolean bFluid = isFluidBlock(b);
        if (aFluid && bFluid) {
            return false;
        }
        if (aFluid) {
            return isSolidBlock(b);
        }
        if (bFluid) {
            return isSolidBlock(a);
        }
        return true;
    }

    /** 是否是流体方块（水/岩浆的源头与流动形态都是 {@link LiquidBlock}）。 */
    public static boolean isFluidBlock(Block block) {
        return block instanceof LiquidBlock;
    }

    /**
     * 「可被直接破坏掉落的物品」：硬度为 0、徒手瞬间破坏的方块（火把、花、草、海带、作物、红石线等）。
     *
     * <p>{@code getDestroySpeed} 在 1.21 里只返回方块自身的硬度字段，不会使用传入的 level/pos
     * （已核实原版没有任何方块重写它），因此这里传 null 是安全的。
     */
    public static boolean isInstantlyBreakable(BlockState state) {
        return state.getDestroySpeed(null, null) <= 0.0F;
    }

    private static boolean isSolidBlock(Block block) {
        return !isInstantlyBreakable(block.defaultBlockState());
    }

    /**
     * 把 {@code from} 的属性尽可能迁移到目标方块的默认状态上。
     *
     * @param from      被替换位置的原始状态
     * @param toDefault 目标方块的默认状态
     * @return 迁移后的目标状态
     */
    public static BlockState mapState(BlockState from, BlockState toDefault) {
        BlockState result = toDefault;
        Collection<Property<?>> properties = from.getProperties();
        if (properties.isEmpty()) {
            return result;
        }
        for (Property<?> property : properties) {
            result = copyProperty(result, property, from);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState copyProperty(BlockState target, Property<?> property, BlockState from) {
        Property<T> source = (Property<T>) property;
        Property<T> destination = (Property<T>) findProperty(target, property.getName());
        if (destination == null) {
            return target;
        }
        T value = from.getValue(source);
        // 属性同名但取值范围可能不同（例如 age 0~7 与 0~25），取不到就保持默认值
        if (!destination.getPossibleValues().contains(value)) {
            return target;
        }
        return target.setValue(destination, value);
    }

    private static Property<?> findProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }
}
