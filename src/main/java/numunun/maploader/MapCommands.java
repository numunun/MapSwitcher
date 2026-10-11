package numunun.maploader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

// /map list | load | reload | join | leave | unload | current
public final class MapCommands {
    private static final SuggestionProvider<CommandSourceStack> MAP_NAMES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(MapManager.listMaps(ctx.getSource().getServer()), builder);

    private MapCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("map")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("list").executes(MapCommands::list))
                .then(Commands.literal("current").executes(MapCommands::current))
                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(MAP_NAMES)
                                .executes(ctx -> load(ctx, MapManager.Move.ALL))
                                .then(Commands.literal("all").executes(ctx -> load(ctx, MapManager.Move.ALL)))
                                .then(Commands.literal("self").executes(ctx -> load(ctx, MapManager.Move.SELF)))
                                .then(Commands.literal("none").executes(ctx -> load(ctx, MapManager.Move.NONE)))))
                .then(Commands.literal("reload")
                        .executes(ctx -> reload(ctx, MapManager.Move.ALL))
                        .then(Commands.literal("all").executes(ctx -> reload(ctx, MapManager.Move.ALL)))
                        .then(Commands.literal("self").executes(ctx -> reload(ctx, MapManager.Move.SELF)))
                        .then(Commands.literal("none").executes(ctx -> reload(ctx, MapManager.Move.NONE))))
                .then(Commands.literal("join")
                        .executes(ctx -> join(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> join(ctx, EntityArgument.getPlayers(ctx, "targets")))))
                .then(Commands.literal("leave")
                        .executes(ctx -> leave(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> leave(ctx, EntityArgument.getPlayers(ctx, "targets")))))
                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> save(ctx, false))
                                .then(Commands.literal("force").executes(ctx -> save(ctx, true)))))
                .then(Commands.literal("unload").executes(MapCommands::unload)));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        List<String> maps = MapManager.listMaps(server);
        if (maps.isEmpty()) return ok(ctx, "maps 폴더에 맵이 없습니다.");
        String current = MapManager.currentName();
        StringBuilder sb = new StringBuilder("맵 목록:");
        for (String m : maps) {
            sb.append("\n  ").append(m);
            String display = MapManager.displayName(server, m);
            if (!display.equals(m)) sb.append(" (").append(display).append(")");
            if (m.equals(current)) sb.append("  ← 열려 있음");
        }
        return ok(ctx, sb.toString());
    }

    private static int current(CommandContext<CommandSourceStack> ctx) {
        String current = MapManager.currentName();
        if (MapManager.isBusy()) return ok(ctx, "맵을 불러오는 중입니다.");
        return ok(ctx, current == null ? "열려 있는 맵이 없습니다."
                : "지금 맵: " + MapManager.displayName(ctx.getSource().getServer(), current) + " (" + current + ")");
    }

    private static int load(CommandContext<CommandSourceStack> ctx, MapManager.Move move) {
        String name = StringArgumentType.getString(ctx, "name");
        String err = MapManager.load(ctx.getSource().getServer(), name, move, ctx.getSource().getPlayer());
        return err == null ? 1 : fail(ctx, err);
    }

    private static int reload(CommandContext<CommandSourceStack> ctx, MapManager.Move move) {
        String current = MapManager.currentName();
        if (current == null) return fail(ctx, "열려 있는 맵이 없습니다.");
        String err = MapManager.load(ctx.getSource().getServer(), current, move, ctx.getSource().getPlayer());
        return err == null ? 1 : fail(ctx, err);
    }

    private static int join(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer p : targets) {
            String err = MapManager.join(p);
            if (err != null) return fail(ctx, err);
        }
        return ok(ctx, targets.size() + "명을 맵으로 이동했습니다.");
    }

    private static int leave(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer p : targets) MapManager.sendToOverworld(p);
        return ok(ctx, targets.size() + "명을 원래 월드로 보냈습니다.");
    }

    private static int save(CommandContext<CommandSourceStack> ctx, boolean force) {
        String name = StringArgumentType.getString(ctx, "name");
        String err = MapManager.save(ctx.getSource().getServer(), name, force);
        return err == null ? 1 : fail(ctx, err);
    }

    private static int unload(CommandContext<CommandSourceStack> ctx) {
        String err = MapManager.unload(ctx.getSource().getServer());
        return err == null ? 1 : fail(ctx, err);
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg).withStyle(ChatFormatting.RED));
        return 0;
    }
}