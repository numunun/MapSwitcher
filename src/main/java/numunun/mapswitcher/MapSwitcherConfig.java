package numunun.mapswitcher;

import net.minecraftforge.common.ForgeConfigSpec;

// config/mapswitcher-common.toml
public final class MapSwitcherConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue RESTORE_ENABLED;
    public static final ForgeConfigSpec.IntValue RESTORE_DELAY_SECONDS;
    public static final ForgeConfigSpec.IntValue RESTORE_BLOCKS_PER_SECOND;
    public static final ForgeConfigSpec.BooleanValue RESTORE_REMOVE_PLACED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("restore");
        RESTORE_ENABLED = b
                .comment("불러온 맵에서 부서진 블록을 시간이 지나면 조금씩 원래대로 되돌림")
                .define("enabled", true);
        RESTORE_DELAY_SECONDS = b
                .comment("블록이 바뀐 뒤 복구를 시작하기까지 기다리는 시간(초)")
                .defineInRange("delaySeconds", 60, 1, 3600);
        RESTORE_BLOCKS_PER_SECOND = b
                .comment("1초에 복구하는 블록 수 (클수록 빨리 차오름)")
                .defineInRange("blocksPerSecond", 20, 1, 2000);
        RESTORE_REMOVE_PLACED = b
                .comment("플레이어가 새로 놓은 블록도 시간이 지나면 없앰")
                .define("removePlacedBlocks", true);
        b.pop();
        SPEC = b.build();
    }

    private MapSwitcherConfig() {}
}