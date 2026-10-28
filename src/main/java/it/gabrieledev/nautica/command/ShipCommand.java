package it.gabrieledev.nautica.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import it.gabrieledev.nautica.NauticaPlugin;
import it.gabrieledev.nautica.Perms;
import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.fixture.FixtureType;
import it.gabrieledev.nautica.fixture.Fixtures;
import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.math.Rot;
import it.gabrieledev.nautica.prefab.PrefabStore;
import it.gabrieledev.nautica.sail.ShipController;
import it.gabrieledev.nautica.sail.Voyage;
import it.gabrieledev.nautica.ship.ModuleType;
import it.gabrieledev.nautica.ship.ShipRecord;
import it.gabrieledev.nautica.ship.ShipState;
import it.gabrieledev.nautica.ship.ShipStats;
import it.gabrieledev.nautica.world.Assembler;
import it.gabrieledev.nautica.world.WorldOps;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ShipCommand {

    private static final double NEAR = 24;
    private static final Map<String, Float> HEADINGS = Map.of(
            "sud", 0f, "ovest", 90f, "nord", 180f, "est", 270f,
            "south", 0f, "west", 90f, "north", 180f, "east", 270f);

    private final NauticaPlugin plugin;

    public ShipCommand(NauticaPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ship");

        root.then(Commands.literal("info").executes(this::info));

        root.then(Commands.literal("crew")
                .then(Commands.literal("add").then(Commands.argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers()).executes(ctx -> crew(ctx, true))))
                .then(Commands.literal("remove").then(Commands.argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers()).executes(ctx -> crew(ctx, false)))));

        root.then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(this::rename)));

        root.then(Commands.literal("transfer").then(Commands.argument("player", StringArgumentType.word())
                .suggests(onlinePlayers()).executes(this::transfer)));

        root.then(Commands.literal("waypoint")
                .then(Commands.literal("off").executes(ctx -> waypoint(ctx, null)))
                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                .executes(ctx -> waypoint(ctx, new double[]{
                                        DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "z")})))));

        LiteralArgumentBuilder<CommandSourceStack> give = Commands.literal("give")
                .requires(src -> src.getSender().hasPermission(Perms.ADMIN));
        for (FixtureType type : FixtureType.values()) {
            give.then(Commands.literal(type.id())
                    .executes(ctx -> give(ctx, type, null))
                    .then(Commands.argument("target", ArgumentTypes.player())
                            .executes(ctx -> give(ctx, type, ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                                    .resolve(ctx.getSource()).getFirst()))));
        }
        root.then(give);

        root.then(Commands.literal("admin")
                .requires(src -> src.getSender().hasPermission(Perms.ADMIN))
                .then(Commands.literal("list").executes(this::adminList))
                .then(Commands.literal("recover").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(shipIds(true))
                        .executes(ctx -> recover(ctx, false))
                        .then(Commands.literal("force").executes(ctx -> recover(ctx, true)))))
                .then(Commands.literal("forcedock").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(shipIds(false)).executes(this::forceDock)))
                .then(Commands.literal("reload").executes(this::reload)));

        root.then(Commands.literal("prefab")
                .requires(src -> src.getSender().hasPermission(Perms.PREFAB))
                .then(Commands.literal("list").executes(this::prefabList))
                .then(Commands.literal("spawn").then(Commands.argument("name", StringArgumentType.word())
                        .suggests(prefabNames())
                        .executes(ctx -> prefabSpawn(ctx, null, false))
                        .then(Commands.literal("force").executes(ctx -> prefabSpawn(ctx, null, true)))
                        .then(Commands.argument("heading", StringArgumentType.word()).suggests(headings())
                                .executes(ctx -> prefabSpawn(ctx, StringArgumentType.getString(ctx, "heading"), false))
                                .then(Commands.literal("force").executes(ctx ->
                                        prefabSpawn(ctx, StringArgumentType.getString(ctx, "heading"), true))))))
                .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.word())
                        .executes(this::prefabSave))));

        return root.build();
    }

    private SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return (ctx, b) -> {
            Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName()));
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> shipIds(boolean virtualOnly) {
        return (ctx, b) -> {
            for (ShipRecord r : plugin.registry().all()) {
                if (!virtualOnly || r.state() == ShipState.VIRTUAL_PORT) {
                    b.suggest(r.shortId());
                }
            }
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> prefabNames() {
        return (ctx, b) -> {
            plugin.prefabs().names().forEach(b::suggest);
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> headings() {
        return (ctx, b) -> {
            List.of("north", "south", "east", "west").forEach(b::suggest);
            return CompletableFuture.completedFuture(b.build());
        };
    }

    private Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) {
            return p;
        }
        plugin.messages().send(ctx.getSource().getSender(), "players-only");
        return null;
    }

    // the ship being piloted, or the closest docked one the player owns (any, for staff)
    private ShipRecord ownShip(Player p, boolean mustOwn) {
        Voyage v = plugin.controller().piloting(p);
        ShipRecord r = v != null ? v.record() : plugin.registry().nearestDocked(p.getLocation(), NEAR);
        if (r == null) {
            plugin.messages().send(p, "no-ship-near");
            return null;
        }
        if (mustOwn && !p.getUniqueId().equals(r.owner()) && !p.hasPermission(Perms.ADMIN)) {
            plugin.messages().send(p, "not-owner");
            return null;
        }
        return r;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx);
        if (p == null) {
            return 0;
        }
        ShipRecord r = ownShip(p, false);
        if (r == null) {
            return 0;
        }
        ShipStats s = r.stats();
        OfflinePlayer owner = r.owner() == null ? null : Bukkit.getOfflinePlayer(r.owner());
        StringBuilder modules = new StringBuilder();
        for (Map.Entry<ModuleType, Integer> e : r.modules().entrySet()) {
            if (!modules.isEmpty()) {
                modules.append(", ");
            }
            modules.append(plugin.messages().plain("module." + e.getKey().id())).append(' ').append(e.getValue());
        }
        plugin.messages().send(p, "info",
                "ship", r.name(), "id", r.shortId(),
                "owner", owner == null || owner.getName() == null ? "?" : owner.getName(),
                "state", plugin.messages().get("state." + r.state().name().toLowerCase(Locale.ROOT)),
                "crew", r.crew().size(),
                "blocks", s.blocks(), "tonnage", Math.round(s.tonnage()), "sail", s.sailArea(),
                "ratio", String.format(Locale.ROOT, "%.3f", s.sailRatio()), "draft", s.draft(), "hold", s.hold(),
                "modules", modules.isEmpty() ? "-" : modules.toString());
        return Command.SINGLE_SUCCESS;
    }

    private int crew(CommandContext<CommandSourceStack> ctx, boolean add) {
        Player p = player(ctx);
        if (p == null) {
            return 0;
        }
        ShipRecord r = ownShip(p, true);
        if (r == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) {
            plugin.messages().send(p, "unknown-player", "player", name);
            return 0;
        }
        if (add) {
            if (target.getUniqueId().equals(r.owner())) {
                return 0;
            }
            r.crew().add(target.getUniqueId());
        } else {
            r.crew().remove(target.getUniqueId());
        }
        plugin.controller().save(r);
        plugin.messages().send(p, add ? "crew.added" : "crew.removed", "player", name, "ship", r.name());
        return Command.SINGLE_SUCCESS;
    }

    private int rename(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx);
        if (p == null) {
            return 0;
        }
        ShipRecord r = ownShip(p, true);
        if (r == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.isEmpty() || name.length() > 32 || name.chars().anyMatch(Character::isISOControl)) {
            plugin.messages().send(p, "rename.invalid");
            return 0;
        }
        r.name(name);
        plugin.controller().save(r);
        plugin.messages().send(p, "rename.done", "ship", name);
        return Command.SINGLE_SUCCESS;
    }

    private int transfer(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx);
        if (p == null) {
            return 0;
        }
        ShipRecord r = ownShip(p, true);
        if (r == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) {
            plugin.messages().send(p, "unknown-player", "player", name);
            return 0;
        }
        r.owner(target.getUniqueId());
        plugin.controller().save(r);
        plugin.messages().send(p, "transfer.done", "player", name, "ship", r.name());
        return Command.SINGLE_SUCCESS;
    }

    private int waypoint(CommandContext<CommandSourceStack> ctx, double[] wp) {
        Player p = player(ctx);
        if (p == null) {
            return 0;
        }
        ShipRecord r = ownShip(p, false);
        if (r == null) {
            return 0;
        }
        if (!r.canPilot(p.getUniqueId()) && !p.hasPermission(Perms.ADMIN)) {
            plugin.messages().send(p, "helm.not-crew", "ship", r.name());
            return 0;
        }
        if (r.module(ModuleType.COMPASS) == 0) {
            plugin.messages().send(p, "waypoint.no-compass");
            return 0;
        }
        r.waypoint(wp);
        plugin.controller().save(r);
        if (wp == null) {
            plugin.messages().send(p, "waypoint.cleared");
        } else {
            plugin.messages().send(p, "waypoint.set", "x", Math.round(wp[0]), "z", Math.round(wp[1]));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int give(CommandContext<CommandSourceStack> ctx, FixtureType type, Player target) {
        Player to = target != null ? target : player(ctx);
        if (to == null) {
            return 0;
        }
        to.getInventory().addItem(Fixtures.item(type)).values()
                .forEach(rest -> to.getWorld().dropItemNaturally(to.getLocation(), rest));
        plugin.messages().send(ctx.getSource().getSender(), "give.done", "item", Fixtures.displayName(type), "player", to.getName());
        return Command.SINGLE_SUCCESS;
    }

    private int adminList(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        ShipController c = plugin.controller();
        plugin.messages().send(s, "admin.list-header", "ships", plugin.registry().all().size(),
                "sailing", c.voyages().size(), "ms", String.format(Locale.ROOT, "%.2f", c.averageTickMillis()));
        List<ShipRecord> sorted = new ArrayList<>(plugin.registry().all());
        sorted.sort((a, b) -> a.state().compareTo(b.state()));
        for (ShipRecord r : sorted) {
            Voyage v = c.voyage(r.id());
            var pose = v != null ? v.pose() : r.state() == ShipState.DOCKED ? r.docked() : r.position();
            String where = pose == null ? "-" : r.world() + " " + Math.round(pose.x()) + " " + Math.round(pose.y()) + " " + Math.round(pose.z());
            plugin.messages().send(s, "admin.list-line", "id", r.shortId(), "ship", r.name(),
                    "state", plugin.messages().get("state." + r.state().name().toLowerCase(Locale.ROOT)),
                    "where", where,
                    "extra", v == null ? "" : String.format(Locale.ROOT, "%.2f b/t, %d display", v.speed(), v.displayCount()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int recover(CommandContext<CommandSourceStack> ctx, boolean force) {
        Player p = player(ctx);
        if (p == null) {
            return 0;
        }
        ShipRecord r = plugin.registry().byShortId(StringArgumentType.getString(ctx, "id"));
        if (r == null) {
            plugin.messages().send(p, "admin.unknown-ship");
            return 0;
        }
        if (r.state() == ShipState.SAILING || r.state() == ShipState.DOCKED) {
            plugin.messages().send(p, "admin.not-virtual");
            return 0;
        }
        try {
            WorldOps.Area area = plugin.controller().recoverAt(r, p.getWorld(), BlockPos.of(p.getLocation().getBlock()),
                    Rot.snapCardinal(p.getYaw()), force);
            if (!area.ok()) {
                plugin.messages().send(p, area.key(), "x", area.at().x(), "y", area.at().y(), "z", area.at().z());
                return 0;
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Recover of " + r.shortId() + " failed: " + e.getMessage());
            plugin.messages().send(p, "admin.recover-failed");
            return 0;
        }
        plugin.messages().send(p, "admin.recovered", "ship", r.name());
        return Command.SINGLE_SUCCESS;
    }

    private int forceDock(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        ShipRecord r = plugin.registry().byShortId(StringArgumentType.getString(ctx, "id"));
        Voyage v = r == null ? null : plugin.controller().voyage(r.id());
        if (v == null) {
            plugin.messages().send(s, "admin.not-sailing");
            return 0;
        }
        plugin.controller().forceDock(v);
        plugin.messages().send(s, "admin.forcedocked", "ship", r.name(), "state",
                plugin.messages().get("state." + r.state().name().toLowerCase(Locale.ROOT)));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        if (!plugin.controller().voyages().isEmpty()) {
            plugin.messages().send(s, "admin.reload-sailing");
            return 0;
        }
        plugin.reloadAll();
        plugin.messages().send(s, "admin.reloaded", "blocks", plugin.config().blocks.allowedCount());
        return Command.SINGLE_SUCCESS;
    }

    private int prefabList(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        for (String name : plugin.prefabs().names()) {
            try {
                Blueprint bp = plugin.prefabs().load(name);
                ShipStats st = ShipStats.of(bp, plugin.config().blocks::weight, plugin.config().blocks::sail);
                plugin.messages().send(s, "prefab.line", "name", name, "blocks", bp.size(),
                        "tonnage", Math.round(st.tonnage()), "ratio", String.format(Locale.ROOT, "%.3f", st.sailRatio()));
            } catch (IOException e) {
                plugin.messages().send(s, "prefab.broken", "name", name);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int prefabSpawn(CommandContext<CommandSourceStack> ctx, String headingArg, boolean force) {
        Player p = player(ctx);
        if (p == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        Blueprint bp;
        try {
            bp = plugin.prefabs().load(name);
        } catch (IOException e) {
            plugin.messages().send(p, "prefab.broken", "name", name);
            return 0;
        }
        if (bp == null) {
            plugin.messages().send(p, "prefab.unknown", "name", name);
            return 0;
        }
        float heading;
        if (headingArg == null) {
            heading = Rot.snapCardinal(p.getYaw());
        } else {
            Float h = HEADINGS.get(headingArg.toLowerCase(Locale.ROOT));
            if (h == null) {
                plugin.messages().send(p, "prefab.bad-heading");
                return 0;
            }
            heading = h;
        }

        Block target = p.getTargetBlockExact(96, FluidCollisionMode.ALWAYS);
        if (target == null) {
            plugin.messages().send(p, "prefab.no-target");
            return 0;
        }
        int surfaceY;
        if (isWater(target)) {
            Block b = target;
            while (isWater(b.getRelative(BlockFace.UP))) {
                b = b.getRelative(BlockFace.UP);
            }
            surfaceY = b.getY();
        } else if (force) {
            surfaceY = target.getY() + 1 - bp.minY() + bp.waterline();
        } else {
            plugin.messages().send(p, "prefab.not-water");
            return 0;
        }
        int k = Rot.quarterTurns(bp.heading(), heading);
        int cx = Math.round((bp.minX() + bp.maxX()) / 2f), cz = Math.round((bp.minZ() + bp.maxZ()) / 2f);
        BlockPos pivot = new BlockPos(target.getX() - Rot.rotX(cx, cz, k), surfaceY - bp.waterline(),
                target.getZ() - Rot.rotZ(cx, cz, k));
        try {
            ShipRecord r = plugin.controller().spawnPrefab(bp, plugin.messages().plain("prefab.ship-name", "name", name),
                    p, p.getWorld(), pivot, heading, force);
            plugin.messages().send(p, "prefab.spawned", "name", name, "ship", r.name(), "id", r.shortId(),
                    "blocks", bp.size());
        } catch (ShipController.PrefabRefused e) {
            WorldOps.Area a = e.area();
            plugin.messages().send(p, a.key(), "x", a.at().x(), "y", a.at().y(), "z", a.at().z());
            plugin.messages().send(p, "prefab.try-force");
            return 0;
        } catch (IOException e) {
            plugin.getLogger().severe("Spawning prefab " + name + " failed: " + e.getMessage());
            plugin.messages().send(p, "prefab.failed");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int prefabSave(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx);
        if (p == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        if (!PrefabStore.validName(name)) {
            plugin.messages().send(p, "prefab.bad-name");
            return 0;
        }
        ShipRecord r = plugin.registry().nearestDocked(p.getLocation(), NEAR);
        if (r == null) {
            plugin.messages().send(p, "no-ship-near");
            return 0;
        }
        BlockPos helmCell = r.docked().pivotBlock();
        Fixtures.Found helm = Fixtures.at(p.getWorld(), helmCell);
        if (helm == null || helm.type() != FixtureType.HELM) {
            plugin.messages().send(p, "prefab.no-helm");
            return 0;
        }
        try {
            Blueprint bp = plugin.assembler().capture(p.getWorld(), helmCell, helm.yaw(), r.id(),
                    new Assembler.Options(null, false, true)).blueprint();
            plugin.prefabs().save(name, bp);
            plugin.messages().send(p, "prefab.saved", "name", name, "blocks", bp.size());
        } catch (Assembler.Failure f) {
            plugin.messages().send(p, f.key(), f.args());
            return 0;
        } catch (IOException e) {
            plugin.messages().send(p, "prefab.failed");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static boolean isWater(Block b) {
        Material m = b.getType();
        return m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS;
    }
}
