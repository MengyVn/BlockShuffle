package MengySmod.blockshuffle.shuffle;

import MengySmod.blockshuffle.Blockshuffle;
import MengySmod.blockshuffle.config.ShuffleConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 互换请求的入口与调度器。
 *
 * <p>职责：
 * <ul>
 *   <li>监听玩家受伤事件（含冷却与创造/旁观模式过滤）；</li>
 *   <li>维护一个串行队列：同一时间只允许一个互换任务运行，多人同时受伤时排队而不是并行；</li>
 *   <li>在服务端 tick 中推进当前任务，并在任务真正替换了方块后开启掉落物观察窗口。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Blockshuffle.MODID)
public final class ShuffleManager {

    private static final int MAX_QUEUE = 16;

    /** 控制台触发的请求没有玩家，用这个空 UUID 占位（查不到玩家，因此不会保护任何人） */
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private static final Deque<Request> QUEUE = new ArrayDeque<>();
    private static final Map<UUID, Long> LAST_TRIGGER_NANOS = new HashMap<>();

    private static ShuffleJob active;
    private static String lastSummary = "尚未触发过互换";
    private static long totalShuffles;

    private ShuffleManager() {
    }

    private record Request(ServerLevel level, BlockPos center, @Nullable UUID playerId) {
    }

    // ------------------------------------------------------------- 事件入口

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 只处理真正扣了血的伤害；被完全格挡/免疫的伤害不触发
        if (event.getNewDamage() <= 0.0F) {
            return;
        }
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        if (!ShuffleConfig.get().enabled()) {
            return;
        }
        request(player, false);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        DropLimiter.tick(server);

        // 总开关关闭：不再接受新任务，并中止进行中的互换（已经换掉的部分无法回滚）
        if (!ShuffleConfig.get().enabled()) {
            if (active != null) {
                Blockshuffle.LOGGER.info("[BlockShuffle] 总开关已关闭，中止进行中的互换");
                active = null;
            }
            if (!QUEUE.isEmpty()) {
                QUEUE.clear();
            }
            return;
        }

        if (active == null) {
            Request request = pollValidRequest(server);
            if (request != null) {
                UUID playerId = request.playerId() != null ? request.playerId() : NIL_UUID;
                active = new ShuffleJob(request.level(), request.center(), playerId);
            }
        }
        if (active == null) {
            return;
        }
        if (!active.tick()) {
            return;
        }

        if (active.swappedAnything()) {
            ShuffleConfig.Values values = ShuffleConfig.get();
            DropLimiter.startWindow(active.level(), active.center(), values.radiusChunks(), values);
            totalShuffles++;
        }
        lastSummary = active.summary();
        active = null;
    }

    // ------------------------------------------------------------- 请求管理

    /** 玩家受伤触发（受冷却限制）。 */
    private static void request(ServerPlayer player, boolean ignoreCooldown) {
        enqueue(player.serverLevel(), player.blockPosition().immutable(), player.getUUID(), ignoreCooldown);
    }

    /**
     * 指令手动触发（玩家执行），忽略冷却，便于测试。
     *
     * @return true 表示请求已被接受；false 表示模组总开关处于关闭状态
     */
    public static boolean forceTrigger(ServerPlayer player) {
        if (!ShuffleConfig.get().enabled()) {
            player.displayClientMessage(Component.translatableWithFallback(
                    "blockshuffle.message.disabled",
                    "[BlockShuffle] 模组当前已关闭，可用 /blockshuffle on 开启"), true);
            return false;
        }
        request(player, true);
        return true;
    }

    /** 指令手动触发（控制台执行），忽略冷却与玩家校验。 */
    public static boolean forceTrigger(ServerLevel level, BlockPos center) {
        if (!ShuffleConfig.get().enabled()) {
            Blockshuffle.LOGGER.info("[BlockShuffle] 模组已关闭，忽略手动触发");
            return false;
        }
        enqueue(level, center.immutable(), null, true);
        return true;
    }

    /** 一键开关（供指令调用），会写入配置文件并立即生效。 */
    public static boolean toggleEnabled() {
        boolean next = !ShuffleConfig.get().enabled();
        ShuffleConfig.setEnabled(next);
        return next;
    }

    private static void enqueue(ServerLevel level, BlockPos center, @Nullable UUID playerId, boolean ignoreCooldown) {
        ShuffleConfig.Values values = ShuffleConfig.get();

        if (!dimensionEnabled(level, values)) {
            Blockshuffle.LOGGER.info("[BlockShuffle] 维度 {} 未启用，忽略本次互换请求",
                    level.dimension().location());
            return;
        }
        if (playerId != null) {
            long now = System.nanoTime();
            long cooldownNanos = (long) (values.cooldownSeconds() * 1_000_000_000L);
            Long last = LAST_TRIGGER_NANOS.get(playerId);
            if (!ignoreCooldown && last != null && now - last < cooldownNanos) {
                return;
            }
            LAST_TRIGGER_NANOS.put(playerId, now);
        }

        if (QUEUE.size() >= MAX_QUEUE) {
            QUEUE.poll();
        }
        QUEUE.add(new Request(level, center, playerId));
    }

    private static Request pollValidRequest(MinecraftServer server) {
        Request request;
        while ((request = QUEUE.poll()) != null) {
            // 玩家在排队期间离线或换维度时丢弃该请求；控制台请求（playerId == null）始终有效
            if (request.playerId() != null && server.getPlayerList().getPlayer(request.playerId()) == null) {
                continue;
            }
            return request;
        }
        return null;
    }

    private static boolean dimensionEnabled(ServerLevel level, ShuffleConfig.Values values) {
        if (values.allDimensions()) {
            return true;
        }
        ResourceLocation id = level.dimension().location();
        return values.dimensions().contains(id);
    }

    // ------------------------------------------------------------------ 状态

    public static String statusText() {
        ShuffleConfig.Values values = ShuffleConfig.get();
        StringBuilder builder = new StringBuilder();
        builder.append("BlockShuffle 状态\n");
        builder.append("总开关: ").append(values.enabled() ? "开启" : "已关闭").append('\n');
        builder.append("半径: ").append(values.radiusChunks()).append(" 区块（正方形区域）\n");
        builder.append("冷却: ").append(values.cooldownSeconds()).append(" 秒\n");
        builder.append("每 tick 预算: ").append(values.blocksPerTick()).append(" 格\n");
        builder.append("安全阀: ")
                .append(values.maxSwapBlocks() == 0 ? "关闭" : values.maxSwapBlocks() + " 格").append('\n');
        builder.append("流体参与: ").append(values.fluidsParticipate() ? "是" : "否").append('\n');
        builder.append("抑制级联更新: ").append(values.keepPlantsAttached() ? "是" : "否").append('\n');
        builder.append("掉落物上限: ")
                .append(values.dropEntityLimit() == 0
                        ? "不限制"
                        : values.dropEntityLimit() + " 个 / " + values.dropWatchSeconds() + " 秒窗口")
                .append('\n');
        builder.append("必然参与: ");
        if (values.blockParticipationChance().isEmpty()) {
            builder.append("未配置（完全随机）");
        } else {
            boolean first = true;
            for (it.unimi.dsi.fastutil.objects.Object2DoubleMap.Entry<net.minecraft.world.level.block.Block> entry
                    : values.blockParticipationChance().object2DoubleEntrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(entry.getKey().getName().getString())
                        .append(' ')
                        .append(Math.round(entry.getDoubleValue() * 1000.0D) / 10.0D)
                        .append('%');
                first = false;
            }
        }
        builder.append('\n');
        builder.append("互换提示: ").append(values.messageMode().name())
                .append(" / ").append(values.messageScope().name()).append('\n');
        builder.append("累计互换次数: ").append(totalShuffles).append('\n');
        builder.append("当前任务: ").append(active != null ? "进行中" : "空闲").append('\n');
        builder.append("上次结果: ").append(lastSummary).append('\n');
        builder.append("说明: 生效的是本服务端（房主）的配置；其他玩家改自己的配置只影响他们自己开的世界。");
        return builder.toString();
    }

    public static long totalShuffles() {
        return totalShuffles;
    }
}
