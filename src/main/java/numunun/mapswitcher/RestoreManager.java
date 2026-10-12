package numunun.mapswitcher;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 불러온 맵에서 바뀐 블록의 "원래 모습"을 기억해 두고, 시간이 지나면 조금씩 되돌림
@Mod.EventBusSubscriber(modid = MapSwitcher.MODID)
public final class RestoreManager {

    // 원래 블록과 마지막으로 바뀐 시각
    private record Change(BlockState original, @Nullable CompoundTag blockEntity, long changedAt) {}

    // 바뀐 시각 순서로 유지 (다시 바뀌면 맨 뒤로)
    private static final LinkedHashMap<BlockPos, Change> changes = new LinkedHashMap<>();
    private static double budget = 0;

    private RestoreManager() {}

    public static void clear() {
        changes.clear();
        budget = 0;
    }

    public static int pending() {
        return changes.size();
    }

    // ── 기록 ──
    // 같은 자리가 여러 번 바뀌어도 "처음" 모습을 기억하고, 시각만 최신으로
    private static void record(LevelAccessor la, BlockPos pos, BlockState original) {
        if (!(la instanceof ServerLevel level) || !isTracked(level)) return;
        if (!MapSwitcherConfig.RESTORE_ENABLED.get()) return;
        BlockPos key = pos.immutable();
        long now = level.getGameTime();
        Change old = changes.remove(key);
        if (old != null) {
            changes.put(key, new Change(old.original(), old.blockEntity(), now));
            return;
        }
        CompoundTag nbt = null;
        BlockEntity be = level.getBlockEntity(key);
        if (be != null) nbt = be.saveWithFullMetadata();
        changes.put(key, new Change(original, nbt, now));
    }

    private static boolean isTracked(ServerLevel level) {
        ResourceKey<Level> current = MapManager.currentKey();
        return current != null && level.dimension().equals(current);
    }

    // 플레이어가 부숨
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!e.isCanceled()) record(e.getLevel(), e.getPos(), e.getState());
    }

    // 블록을 놓음 (문, 침대처럼 여러 칸짜리 포함)
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.isCanceled() || !MapSwitcherConfig.RESTORE_REMOVE_PLACED.get()) return;
        if (e instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot s : multi.getReplacedBlockSnapshots()) record(e.getLevel(), s.getPos(), s.getReplacedBlock());
        } else {
            record(e.getLevel(), e.getPos(), e.getBlockSnapshot().getReplacedBlock());
        }
    }

    // 폭발 (수류탄, TNT 등): 날아가기 직전 모습을 기억
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosion(ExplosionEvent.Detonate e) {
        Level level = e.getLevel();
        for (BlockPos pos : e.getAffectedBlocks()) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) record(level, pos, state);
        }
    }

    // 물과 용암이 만나서 생긴 블록
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent e) {
        record(e.getLevel(), e.getPos(), e.getOriginalState());
    }

    // 농지 밟기
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTrample(BlockEvent.FarmlandTrampleEvent e) {
        if (!e.isCanceled()) record(e.getLevel(), e.getPos(), e.getState());
    }

    // ── 복구 ──
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || changes.isEmpty()) return;
        try {
            tick(e.getServer());
        } catch (Exception ex) {
            MapSwitcher.LOG.error("[MapSwitcher] 맵 복구 중 오류", ex);
        }
    }

    private static void tick(MinecraftServer server) {
        ResourceKey<Level> current = MapManager.currentKey();
        ServerLevel level = current != null ? server.getLevel(current) : null;
        if (level == null || !MapSwitcherConfig.RESTORE_ENABLED.get()) return;

        budget += MapSwitcherConfig.RESTORE_BLOCKS_PER_SECOND.get() / 20.0;
        int count = (int) budget;
        if (count <= 0) return;
        budget -= count;

        // 복구할 때가 된 것들을 앞에서부터 꺼냄 (바뀐 시각 순서라 아직 안 된 게 나오면 멈춤)
        long now = level.getGameTime();
        long delay = MapSwitcherConfig.RESTORE_DELAY_SECONDS.get() * 20L;
        List<Map.Entry<BlockPos, Change>> due = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, Change>> it = changes.entrySet().iterator();
        while (it.hasNext() && due.size() < count) {
            Map.Entry<BlockPos, Change> en = it.next();
            if (now - en.getValue().changedAt() < delay) break;
            due.add(en);
        }
        if (due.isEmpty()) return;

        // 아래쪽부터 채워야 횃불, 문처럼 붙어 있는 블록이 떨어지지 않음
        due.sort(Comparator.comparingInt(en -> en.getKey().getY()));
        List<Map.Entry<BlockPos, Change>> retry = new ArrayList<>();
        for (Map.Entry<BlockPos, Change> en : due) {
            BlockPos pos = en.getKey();
            Change c = en.getValue();
            changes.remove(pos);
            if (!level.isLoaded(pos)) {
                retry.add(en);
                continue;
            }
            // 사람이나 몹이 서 있는 자리에 블록을 채우면 끼이니까 나중에 다시
            if (!c.original().isAir() && !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty()) {
                retry.add(en);
                continue;
            }
            level.setBlock(pos, c.original(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (c.blockEntity() != null) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) be.load(c.blockEntity());
            }
        }
        // 1초 뒤 다시 시도
        for (Map.Entry<BlockPos, Change> en : retry) {
            Change c = en.getValue();
            changes.put(en.getKey(), new Change(c.original(), c.blockEntity(), now - delay + 20));
        }
    }
}