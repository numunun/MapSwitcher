package numunun.maploader;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

// 서버 전용 맵 불러오기 모드: 서버 폴더의 maps/ 안에 있는 맵을 차원으로 열고 플레이어를 이동
@Mod(MapLoader.MODID)
public final class MapLoader {
    public static final String MODID = "maploader";
    public static final Logger LOG = LogUtils.getLogger();

    public MapLoader() {}

    @Mod.EventBusSubscriber(modid = MODID)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent e) {
            MapCommands.register(e.getDispatcher());
        }

        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            try {
                MapManager.tick(e.getServer());
            } catch (Exception ex) {
                LOG.error("[MapLoader] 맵 처리 중 오류", ex);
            }
        }

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent e) {
            MapManager.onServerStarted(e.getServer());
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent e) {
            MapManager.onServerStopped();
        }
    }
}