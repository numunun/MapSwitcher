package numunun.maploader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import commoble.infiniverse.api.InfiniverseAPI;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

// 맵 불러오기/닫기의 실제 처리
public final class MapManager {
    public enum Move { ALL, SELF, NONE }

    private static final String[] COPY_DIRS = {"region", "entities", "poi"};
    private static final String VOID_GENERATOR = "{\"type\":\"minecraft:flat\",\"settings\":"
            + "{\"biome\":\"minecraft:the_void\",\"layers\":[],\"lakes\":false,\"features\":false}}";

    // 다음 틱 이후에 이어서 처리할 불러오기 작업
    private static final class Job {
        final String name;
        final ResourceKey<Level> key;
        final List<UUID> movers;
        int waitTicks = 2;      // 이전 차원의 등록 해제가 끝날 때까지 기다림
        boolean copying = false;

        Job(String name, List<UUID> movers) {
            this.name = name;
            this.key = keyOf(name);
            this.movers = movers;
        }
    }

    private static volatile boolean saving = false;  // 저장 중

    record Spawn(double x, double y, double z, float yaw) {}

    @Nullable private static ResourceKey<Level> current;  // 지금 열려 있는 맵
    @Nullable private static Job job;                      // 진행 중인 불러오기

    private MapManager() {}

    // ── 조회 ──
    static Path mapsDir(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("maps");
    }

    static ResourceKey<Level> keyOf(String name) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(MapLoader.MODID, name));
    }

    // 맵 이름 = 차원 이름이라 영어 소문자, 숫자, _ - . 만 가능
    static boolean isValidName(String name) {
        return !name.isEmpty() && name.chars().allMatch(c ->
                (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.');
    }

    public static List<String> listMaps(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        Path dir = mapsDir(server);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> Files.isDirectory(p.resolve("region")))
                    .map(p -> p.getFileName().toString())
                    .filter(MapManager::isValidName)
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            MapLoader.LOG.error("[MapLoader] maps 폴더 읽기 실패", e);
        }
        return out;
    }

    @Nullable
    public static String currentName() {
        return current != null ? current.location().getPath() : null;
    }

    public static boolean isBusy() {
        return job != null;
    }

    // ── 불러오기 ──
    // 문제가 있으면 메시지, 시작했으면 null
    @Nullable
    public static String load(MinecraftServer server, String name, Move move, @Nullable ServerPlayer caller) {
        if (saving) return "맵을 저장하는 중입니다. 잠시 후 다시 시도하세요.";
        if (job != null) return "다른 맵을 불러오는 중입니다. 잠시 후 다시 시도하세요.";
        if (!isValidName(name)) return "맵 폴더 이름은 영어 소문자, 숫자, _ 만 쓸 수 있습니다.";
        Path template = mapsDir(server).resolve(name);
        if (!Files.isDirectory(template.resolve("region"))) {
            return "maps/" + name + " 폴더가 없거나, 안에 region 폴더가 없습니다.";
        }

        List<UUID> movers = new ArrayList<>();
        if (move == Move.ALL) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) movers.add(p.getUUID());
        } else if (move == Move.SELF && caller != null) {
            movers.add(caller.getUUID());
        }

        // 지금 열린 맵과, 같은 이름으로 남아 있는 차원은 먼저 닫음 (부서진 맵을 버리고 원본에서 새로 복사)
        if (current != null) closeLevel(server, current);
        ResourceKey<Level> key = keyOf(name);
        if (server.getLevel(key) != null) closeLevel(server, key);
        current = null;

        job = new Job(name, movers);
        broadcast(server, Component.literal("[맵] " + displayName(server, name) + " 불러오는 중...").withStyle(ChatFormatting.GRAY));
        return null;
    }

    // ── 닫기 ──
    @Nullable
    public static String unload(MinecraftServer server) {
        if (saving) return "맵을 저장하는 중입니다. 잠시 후 다시 시도하세요.";
        if (job != null) return "맵을 불러오는 중에는 닫을 수 없습니다.";
        if (current == null) return "열려 있는 맵이 없습니다.";
        closeLevel(server, current);
        current = null;
        broadcast(server, Component.literal("[맵] 맵을 닫았습니다.").withStyle(ChatFormatting.GRAY));
        return null;
    }

    // ── 저장: 지금 열린 맵의 상태를 maps/<이름> 으로 저장 ──
    @Nullable
    public static String save(MinecraftServer server, String newName, boolean force) {
        if (job != null) return "맵을 불러오는 중에는 저장할 수 없습니다.";
        if (saving) return "이미 저장 중입니다.";
        if (current == null) return "열려 있는 맵이 없습니다.";
        if (!isValidName(newName)) return "맵 이름은 영어 소문자, 숫자, _ 만 쓸 수 있습니다.";
        ServerLevel level = server.getLevel(current);
        if (level == null) return "맵 차원을 찾을 수 없습니다.";

        Path target = mapsDir(server).resolve(newName);
        if (Files.exists(target) && !force) {
            return "maps/" + newName + " 이(가) 이미 있습니다. 덮어쓰려면: /map save " + newName + " force";
        }

        level.save(null, true, false);  // 메모리에 있던 변경 사항을 파일로 내보내기

        String sourceName = current.location().getPath();
        Path source = DimensionType.getStorageFolder(current, server.getWorldPath(LevelResource.ROOT));
        Spawn spawn = readSpawn(mapsDir(server).resolve(sourceName));
        String display = newName.equals(sourceName) ? displayName(server, sourceName) : newName;
        Path temp = mapsDir(server).resolve(".tmp").resolve(newName);

        saving = true;
        broadcast(server, Component.literal("[맵] " + newName + " 저장 중...").withStyle(ChatFormatting.GRAY));
        CompletableFuture.runAsync(() -> {
            try {
                // 임시 폴더에 먼저 복사하고, 다 끝나면 바꿔치기 (도중에 실패해도 기존 맵은 안전)
                deleteRecursively(temp);
                copyMap(source, temp);
                writeInfo(temp, display, spawn);
                deleteRecursively(target);
                Files.createDirectories(target.getParent());
                Files.move(temp, target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.backgroundExecutor()).whenComplete((v, err) -> server.execute(() -> {
            saving = false;
            if (err != null) {
                MapLoader.LOG.error("[MapLoader] 맵 저장 실패: " + newName, err);
                broadcast(server, Component.literal("[맵] 저장 실패: " + newName + " (서버 로그 확인)").withStyle(ChatFormatting.RED));
            } else {
                MapLoader.LOG.info("[MapLoader] 맵 저장: {} → maps/{}", sourceName, newName);
                broadcast(server, Component.literal("[맵] 저장 완료: maps/" + newName).withStyle(ChatFormatting.GRAY));
            }
        }));
        return null;
    }

    private static void writeInfo(Path dir, String name, Spawn s) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        JsonArray a = new JsonArray();
        a.add(s.x());
        a.add(s.y());
        a.add(s.z());
        o.add("spawn", a);
        o.addProperty("yaw", s.yaw());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("map.json"), o.toString(), StandardCharsets.UTF_8);
    }

    // 안에 있는 사람을 원래 월드로 보내고 차원 등록 해제 (틱이 끝날 때 실제로 제거됨)
    private static void closeLevel(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel level = server.getLevel(key);
        if (level != null) {
            for (ServerPlayer p : List.copyOf(level.players())) sendToOverworld(p);
        }
        InfiniverseAPI.get().markDimensionForUnregistration(server, key);
    }

    // ── 이동 ──
    @Nullable
    public static String join(ServerPlayer p) {
        if (job != null) return "맵을 불러오는 중입니다.";
        if (current == null) return "열려 있는 맵이 없습니다.";
        ServerLevel level = p.server.getLevel(current);
        if (level == null) return "맵 차원을 찾을 수 없습니다.";
        teleport(p, level, readSpawn(mapsDir(p.server).resolve(current.location().getPath())));
        return null;
    }

    public static void sendToOverworld(ServerPlayer p) {
        ServerLevel overworld = p.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        p.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, p.getYRot(), 0f);
    }

    private static void teleport(ServerPlayer p, ServerLevel level, Spawn s) {
        p.teleportTo(level, s.x(), s.y(), s.z(), s.yaw(), 0f);
    }

    // ── 매 틱: 불러오기 진행 ──
    static void tick(MinecraftServer server) {
        Job j = job;
        if (j == null || j.copying) return;
        if (j.waitTicks > 0) {
            j.waitTicks--;
            return;
        }
        if (server.getLevel(j.key) != null) return;  // 아직 이전 차원이 안 닫힘

        // 파일 복사는 서버가 멈추지 않게 백그라운드에서
        j.copying = true;
        Path template = mapsDir(server).resolve(j.name);
        Path dest = DimensionType.getStorageFolder(j.key, server.getWorldPath(LevelResource.ROOT));
        CompletableFuture.runAsync(() -> {
            try {
                deleteRecursively(dest);
                copyMap(template, dest);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.backgroundExecutor()).whenComplete((v, err) -> server.execute(() -> finish(server, j, err)));
    }

    private static void finish(MinecraftServer server, Job j, @Nullable Throwable err) {
        job = null;
        if (err != null) {
            MapLoader.LOG.error("[MapLoader] 맵 복사 실패: " + j.name, err);
            broadcast(server, Component.literal("[맵] 불러오기 실패: " + j.name + " (서버 로그 확인)").withStyle(ChatFormatting.RED));
            return;
        }
        ServerLevel level;
        try {
            level = InfiniverseAPI.get().getOrCreateLevel(server, j.key, () -> createStem(server));
        } catch (Exception e) {
            MapLoader.LOG.error("[MapLoader] 맵 차원 만들기 실패: " + j.name, e);
            broadcast(server, Component.literal("[맵] 불러오기 실패: " + j.name + " (서버 로그 확인)").withStyle(ChatFormatting.RED));
            return;
        }
        current = j.key;

        Spawn spawn = readSpawn(mapsDir(server).resolve(j.name));
        for (UUID id : j.movers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) teleport(p, level, spawn);
        }
        broadcast(server, Component.literal("[맵] " + displayName(server, j.name) + " 불러오기 완료").withStyle(ChatFormatting.GRAY));
        MapLoader.LOG.info("[MapLoader] 맵 불러옴: {} ({}명 이동)", j.name, j.movers.size());
    }

    // 맵 밖은 아무것도 생성되지 않는 빈 공간
    private static LevelStem createStem(MinecraftServer server) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        JsonElement json = JsonParser.parseString(VOID_GENERATOR);
        ChunkGenerator generator = ChunkGenerator.CODEC.parse(ops, json)
                .getOrThrow(false, msg -> MapLoader.LOG.error("[MapLoader] 빈 공간 생성기 오류: {}", msg));
        return new LevelStem(server.overworld().dimensionTypeRegistration(), generator);
    }

    // ── 맵 정보 ──
    // 스폰 위치: map.json의 spawn → 싱글 월드의 level.dat 스폰 → (0, 100, 0)
    static Spawn readSpawn(Path template) {
        JsonObject info = readInfo(template);
        if (info != null && info.has("spawn")) {
            JsonArray a = info.getAsJsonArray("spawn");
            float yaw = info.has("yaw") ? info.get("yaw").getAsFloat() : 0f;
            return new Spawn(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), yaw);
        }
        Path dat = template.resolve("level.dat");
        if (Files.isRegularFile(dat)) {
            try {
                CompoundTag data = NbtIo.readCompressed(dat.toFile()).getCompound("Data");
                return new Spawn(data.getInt("SpawnX") + 0.5, data.getInt("SpawnY"), data.getInt("SpawnZ") + 0.5,
                        data.getFloat("SpawnAngle"));
            } catch (IOException e) {
                MapLoader.LOG.warn("[MapLoader] level.dat 읽기 실패: {}", dat);
            }
        }
        return new Spawn(0.5, 100, 0.5, 0f);
    }

    // 보여줄 이름: map.json의 name, 없으면 폴더 이름
    static String displayName(MinecraftServer server, String name) {
        JsonObject info = readInfo(mapsDir(server).resolve(name));
        return info != null && info.has("name") ? info.get("name").getAsString() : name;
    }

    @Nullable
    private static JsonObject readInfo(Path template) {
        Path json = template.resolve("map.json");
        if (!Files.isRegularFile(json)) return null;
        try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            MapLoader.LOG.warn("[MapLoader] map.json 읽기 실패: {}", json);
            return null;
        }
    }

    // ── 파일 ──
    private static void copyMap(Path template, Path dest) throws IOException {
        for (String dir : COPY_DIRS) {
            Path from = template.resolve(dir);
            if (!Files.isDirectory(from)) continue;
            Path to = dest.resolve(dir);
            try (Stream<Path> walk = Files.walk(from)) {
                for (Path src : (Iterable<Path>) walk::iterator) {
                    Path target = to.resolve(from.relativize(src).toString());
                    if (Files.isDirectory(src)) Files.createDirectories(target);
                    else Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    // ── 서버 시작/종료 ──
    // 지난번에 열려 있던 맵 차원은 Infiniverse가 서버 시작 때 다시 열어줌
    static void onServerStarted(MinecraftServer server) {
        current = null;
        job = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().getNamespace().equals(MapLoader.MODID)) {
                current = level.dimension();
                MapLoader.LOG.info("[MapLoader] 지난번 맵이 열려 있음: {}", current.location().getPath());
                break;
            }
        }
    }

    static void onServerStopped() {
        current = null;
        job = null;
    }

    private static void broadcast(MinecraftServer server, Component msg) {
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }
}