package it.gabrieledev.nautica.sail;

import it.gabrieledev.nautica.Keys;
import it.gabrieledev.nautica.Perms;
import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.FixtureEntry;
import it.gabrieledev.nautica.config.Messages;
import it.gabrieledev.nautica.config.NauticaConfig;
import it.gabrieledev.nautica.fixture.Fixtures;
import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.math.Pose;
import it.gabrieledev.nautica.math.Rot;
import it.gabrieledev.nautica.ship.ShipRecord;
import it.gabrieledev.nautica.ship.ShipRegistry;
import it.gabrieledev.nautica.ship.ShipState;
import it.gabrieledev.nautica.ship.ShipStats;
import it.gabrieledev.nautica.store.SnapshotStore;
import it.gabrieledev.nautica.world.Assembler;
import it.gabrieledev.nautica.world.Capture;
import it.gabrieledev.nautica.world.WorldOps;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

// every transition that touches the world goes through here, and every one of them reaches the disk
// before the world changes: the snapshot before blocks are removed, the DOCKING header before blocks are
// placed. The order is the anti-dupe contract
public final class ShipController {

    private final Plugin plugin;
    private final NauticaConfig cfg;
    private final Messages msg;
    private final ShipRegistry registry;
    private final SnapshotStore store;
    private final Assembler assembler;
    private final WorldOps ops;
    private final WindSystem wind = new WindSystem();
    private final Logger log;

    private final Map<UUID, Voyage> voyages = new LinkedHashMap<>();
    private final Map<UUID, Voyage> byPilot = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Set<UUID> pendingDock = new HashSet<>();
    private int internal;
    private BukkitTask task;
    private long ticks;
    private double avgTickMillis;

    public ShipController(Plugin plugin, NauticaConfig cfg, Messages msg, ShipRegistry registry, SnapshotStore store,
                          Assembler assembler, WorldOps ops) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.msg = msg;
        this.registry = registry;
        this.store = store;
        this.assembler = assembler;
        this.ops = ops;
        this.log = plugin.getLogger();
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // moors every ship at sea. Runs in onDisable, before the worlds are saved
    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Voyage v : List.copyOf(voyages.values())) {
            forceDock(v);
        }
    }

    public boolean internal() {
        return internal > 0;
    }

    public Collection<Voyage> voyages() {
        return voyages.values();
    }

    public Voyage voyage(UUID shipId) {
        return voyages.get(shipId);
    }

    public Voyage piloting(Player p) {
        return byPilot.get(p.getUniqueId());
    }

    public Voyage byStand(Entity vehicle) {
        for (Voyage v : voyages.values()) {
            if (v.ownsStand(vehicle)) {
                return v;
            }
        }
        return null;
    }

    public WindSystem wind() {
        return wind;
    }

    public double averageTickMillis() {
        return avgTickMillis;
    }

    public boolean busy(UUID shipId) {
        return busy.contains(shipId);
    }

    private void tick() {
        long t0 = System.nanoTime();
        ticks++;
        if (ticks % 20 == 0) {
            Set<World> worlds = new HashSet<>();
            for (Voyage v : voyages.values()) {
                worlds.add(v.world());
            }
            wind.tickSecond(worlds);
        }
        for (UUID id : List.copyOf(pendingDock)) {
            pendingDock.remove(id);
            Voyage v = voyages.get(id);
            if (v != null && !dock(v, true)) {
                v.remountPilot();
            }
        }
        long checkpoint = cfg.checkpointSeconds * 20L;
        for (Voyage v : List.copyOf(voyages.values())) {
            try {
                v.tick();
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "Error ticking ship " + v.record().shortId() + ", force docking it", e);
                forceDock(v);
                continue;
            }
            if (ticks % checkpoint == 0) {
                checkpoint(v);
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        avgTickMillis = avgTickMillis * 0.95 + ms * 0.05;
    }

    private void checkpoint(Voyage v) {
        ShipRecord r = v.record();
        r.position(v.pose());
        store.writeRecord(r).exceptionally(ex -> {
            log.log(Level.WARNING, "Checkpoint for ship " + r.shortId() + " not written", ex);
            return null;
        });
        if (v.blueprint().isDirty()) {
            store.writeBlueprint(r.id(), v.blueprint());
        }
    }

    // pilot sat at the helm: copy, write, verify, then and only then take the ship out of the world
    public void depart(Player player, Fixtures.Found helm) {
        ShipRecord r = registry.get(helm.ship());
        if (r == null) {
            msg.send(player, "helm.orphan");
            return;
        }
        if (!player.hasPermission(Perms.USE)) {
            msg.send(player, "no-permission");
            return;
        }
        if (!r.canPilot(player.getUniqueId()) && !player.hasPermission(Perms.ADMIN)) {
            msg.send(player, "helm.not-crew", "ship", r.name());
            return;
        }
        if (r.state() != ShipState.DOCKED || busy.contains(r.id()) || byPilot.containsKey(player.getUniqueId())) {
            msg.send(player, "helm.busy");
            return;
        }
        World world = helm.interaction().getWorld();
        busy.add(r.id());
        Capture c;
        try {
            boolean claims = cfg.requireClaimPermission && !player.hasPermission(Perms.BYPASS_CLAIM);
            c = assembler.capture(world, helm.cell(), helm.yaw(), r.id(),
                    new Assembler.Options(player, claims, player.hasPermission(Perms.BYPASS_SIZE)));
        } catch (Assembler.Failure f) {
            busy.remove(r.id());
            msg.send(player, f.key(), f.args());
            return;
        }

        Pose departure = Pose.docked(helm.cell(), helm.yaw());
        r.state(ShipState.ASSEMBLING);
        r.world(world.getName());
        r.departure(departure);
        r.position(departure);
        r.dockingTarget(null);
        r.stats(ShipStats.of(c.blueprint(), cfg.blocks::weight, cfg.blocks::sail));
        r.confirmed(false);
        r.hasBlueprint(true);
        msg.actionBar(player, "assemble.started", "blocks", c.blueprint().size());

        store.writeDeparture(r, c.blueprint()).whenComplete((ok, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> finishDeparture(player, r, c, ex)));
    }

    private void finishDeparture(Player player, ShipRecord r, Capture c, Throwable ex) {
        try {
            if (ex != null) {
                log.log(Level.SEVERE, "Snapshot for ship " + r.shortId() + " not written, the ship stays docked", ex);
                abortDeparture(r, player, "assemble.disk-error");
                return;
            }
            if (!player.isOnline() || player.isDead() || !player.getWorld().equals(c.world())) {
                abortDeparture(r, null, null);
                return;
            }
            if (!assembler.unchanged(c)) {
                abortDeparture(r, player, "assemble.changed");
                return;
            }

            Map<UUID, Entity> seated = collectRiders(c);
            internal++;
            try {
                if (player.isInsideVehicle()) {
                    player.leaveVehicle();
                }
                ops.remove(c);
            } finally {
                internal--;
            }
            registry.unindex(r.id());

            Voyage v = new Voyage(plugin, cfg, msg, wind, r, c.blueprint(), c.world(), r.departure());
            r.state(ShipState.SAILING);
            voyages.put(r.id(), v);
            byPilot.put(player.getUniqueId(), v);
            internal++;
            try {
                v.start(player, seated);
            } finally {
                internal--;
            }
            store.writeRecord(r);
            msg.send(player, "sail.departed", "ship", r.name());
        } finally {
            busy.remove(r.id());
        }
    }

    private void abortDeparture(ShipRecord r, Player player, String key) {
        r.state(ShipState.DOCKED);
        r.confirmed(true);
        store.writeRecord(r);
        if (player != null && key != null) {
            msg.send(player, key);
        }
    }

    // who rides along: players on seats, animals on pads, the nearest free parrot for each perch
    private Map<UUID, Entity> collectRiders(Capture c) {
        Map<UUID, Entity> seated = new HashMap<>();
        Set<Entity> taken = new HashSet<>();
        internal++;
        try {
            for (Fixtures.Found f : c.fixtures()) {
                Location base = Fixtures.cellBase(c.world(), f.cell());
                switch (f.type()) {
                    case SEAT -> {
                        for (Entity e : c.world().getNearbyEntities(base.clone().add(0, 0.5, 0), 1, 1.5, 1)) {
                            if (Fixtures.isRiderStand(e) && f.id().toString().equals(
                                    e.getPersistentDataContainer().get(Keys.SEAT, PersistentDataType.STRING))) {
                                if (!e.getPassengers().isEmpty()) {
                                    Entity rider = e.getPassengers().getFirst();
                                    seated.put(f.id(), rider);
                                    taken.add(rider);
                                }
                                e.eject();
                                e.remove();
                            }
                        }
                    }
                    case PAD -> {
                        BoundingBox cell = new BoundingBox(f.cell().x(), f.cell().y(), f.cell().z(),
                                f.cell().x() + 1, f.cell().y() + 1.5, f.cell().z() + 1);
                        for (Entity e : c.world().getNearbyEntities(cell)) {
                            if (e instanceof LivingEntity && !(e instanceof Player) && !(e instanceof ArmorStand)
                                    && !e.isInsideVehicle() && taken.add(e)) {
                                seated.put(f.id(), e);
                                break;
                            }
                        }
                    }
                    case PERCH -> {
                        Parrot best = null;
                        double bestSq = 16;
                        for (Entity e : c.world().getNearbyEntities(base, 4, 3, 4, en -> en instanceof Parrot)) {
                            double sq = e.getLocation().distanceSquared(base);
                            if (sq < bestSq && !e.isInsideVehicle() && !taken.contains(e)) {
                                best = (Parrot) e;
                                bestSq = sq;
                            }
                        }
                        if (best != null) {
                            taken.add(best);
                            seated.put(f.id(), best);
                        }
                    }
                    default -> {
                    }
                }
            }
        } finally {
            internal--;
        }
        return seated;
    }

    public void requestDock(Voyage v) {
        pendingDock.add(v.record().id());
    }

    // moors at the nearest grid pose. A voluntary mooring only tries that pose and reports why it failed;
    // a forced one walks the fallback chain and never leaves the ship in memory only
    public boolean dock(Voyage v, boolean voluntary) {
        Blueprint bp = v.blueprint();
        Pose target = v.pose().snapped();
        Player actor = v.pilot() != null && v.pilot().isOnline() ? v.pilot() : null;
        boolean claims = cfg.requireClaimPermission && actor != null && !actor.hasPermission(Perms.BYPASS_CLAIM);
        WorldOps.Area area = ops.checkArea(v.world(), bp, target.pivotBlock(), k(bp, target), actor, claims);
        if (area.ok()) {
            commitDock(v, target);
            return true;
        }
        if (voluntary) {
            if (actor != null) {
                msg.send(actor, area.key(), "x", area.at().x(), "y", area.at().y(), "z", area.at().z());
            }
            return false;
        }
        BlockPos p = target.pivotBlock();
        for (int ring = 1; ring <= 3; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    Pose alt = Pose.docked(p.offset(dx, 0, dz), target.heading());
                    if (ops.checkArea(v.world(), bp, alt.pivotBlock(), k(bp, alt), actor, claims).ok()) {
                        commitDock(v, alt);
                        return true;
                    }
                }
            }
        }
        Pose departure = v.record().departure();
        if (departure != null) {
            World w = Bukkit.getWorld(v.record().world());
            if (w != null && w.equals(v.world())) {
                loadChunks(w, bp, departure);
                if (ops.checkArea(w, bp, departure.pivotBlock(), k(bp, departure), null, false).ok()) {
                    commitDock(v, departure);
                    return true;
                }
            }
        }
        toVirtualPort(v);
        return false;
    }

    public void forceDock(Voyage v) {
        dock(v, false);
    }

    // the pilot is gone (quit, death, teleport): they do not come back on deck, the ship moors without them
    public void pilotLeft(Voyage v, Player p) {
        internal++;
        try {
            v.dropRider(p);
        } finally {
            internal--;
        }
        forceDock(v);
    }

    public void riderLeft(Voyage v, Entity rider) {
        internal++;
        try {
            v.dropRider(rider);
        } finally {
            internal--;
        }
    }

    private void commitDock(Voyage v, Pose target) {
        ShipRecord r = v.record();
        Blueprint bp = v.blueprint();
        int k = k(bp, target);
        BlockPos pivot = target.pivotBlock();

        r.position(v.pose());
        r.dockingTarget(target);
        r.state(ShipState.DOCKING);
        r.confirmed(false);
        try {
            if (bp.isDirty()) {
                store.writeBlueprintNow(r.id(), bp);
            }
            store.writeRecordNow(r);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Docking header not written for " + r.shortId()
                    + ", docking anyway: recovery will find the ship by its position", e);
        }

        List<Voyage.Rider> riders = new ArrayList<>(v.riders());
        Player pilot = v.pilot();
        internal++;
        try {
            v.end();
            ops.place(v.world(), bp, pivot, k, r.id(), null);
            for (Voyage.Rider rider : riders) {
                FixtureEntry f = rider.fixture();
                Entity e = rider.passenger();
                if (e == null || !e.isValid()) {
                    continue;
                }
                Location spot = Fixtures.cellBase(v.world(), WorldOps.target(pivot, f.dx(), f.dy(), f.dz(), k)).add(0, 0.05, 0);
                spot.setYaw(e.getYaw());
                spot.setPitch(e.getPitch());
                e.teleport(spot);
            }
        } finally {
            internal--;
        }

        r.docked(target);
        r.position(target);
        r.dockingTarget(null);
        r.state(ShipState.DOCKED);
        store.writeRecord(r);
        store.archive(r.id(), cfg.archiveKeep);
        registry.index(r, bp);
        voyages.remove(r.id());
        if (pilot != null) {
            byPilot.remove(pilot.getUniqueId());
            v.releasePilotReference();
            if (pilot.isOnline()) {
                msg.send(pilot, "dock.done", "ship", r.name());
            }
        }
        refreshBeds(r, bp, pivot, k);
    }

    private void toVirtualPort(Voyage v) {
        ShipRecord r = v.record();
        r.position(v.pose());
        r.state(ShipState.VIRTUAL_PORT);
        try {
            if (v.blueprint().isDirty()) {
                store.writeBlueprintNow(r.id(), v.blueprint());
            }
            store.writeRecordNow(r);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Header for ship " + r.shortId() + " not written", e);
        }
        Player pilot = v.pilot();
        internal++;
        try {
            v.end();
        } finally {
            internal--;
        }
        voyages.remove(r.id());
        if (pilot != null) {
            byPilot.remove(pilot.getUniqueId());
            v.releasePilotReference();
        }
        log.warning("Ship " + r.shortId() + " (" + r.name() + ") went to the virtual port, no free spot to dock it. "
                + "Place it back with /ship admin recover " + r.shortId());
        Player owner = r.owner() == null ? null : Bukkit.getPlayer(r.owner());
        if (owner != null) {
            msg.send(owner, "dock.virtual-port", "ship", r.name());
        }
    }

    // admin placement of a ship that is not in the world: at pivot, bow toward heading
    public WorldOps.Area recoverAt(ShipRecord r, World world, BlockPos pivot, float heading, boolean force) throws IOException {
        Blueprint bp = store.readBlueprint(r.id());
        if (bp == null) {
            return new WorldOps.Area("admin.no-blueprint", pivot);
        }
        Pose target = Pose.docked(pivot, heading);
        int k = k(bp, target);
        loadChunks(world, bp, target);
        if (!force) {
            WorldOps.Area area = ops.checkArea(world, bp, pivot, k, null, false);
            if (!area.ok()) {
                return area;
            }
        }
        r.world(world.getName());
        r.dockingTarget(target);
        r.forceState(ShipState.DOCKING);
        r.confirmed(false);
        store.writeRecordNow(r);
        ops.place(world, bp, pivot, k, r.id(), null);
        settle(r, bp, target);
        return WorldOps.Area.OK;
    }

    // places a prefab as a new docked ship owned by owner. Fixture ids are fresh at every spawn
    public ShipRecord spawnPrefab(Blueprint bp, String name, Player owner, World world, BlockPos pivot, float heading,
                                  boolean force) throws IOException, PrefabRefused {
        Pose target = Pose.docked(pivot, heading);
        int k = k(bp, target);
        loadChunks(world, bp, target);
        if (!force) {
            boolean claims = cfg.requireClaimPermission && !owner.hasPermission(Perms.BYPASS_CLAIM);
            WorldOps.Area area = ops.checkArea(world, bp, pivot, k, owner, claims);
            if (!area.ok()) {
                throw new PrefabRefused(area);
            }
        }
        Map<UUID, UUID> ids = new HashMap<>();
        Blueprint.Builder copy = new Blueprint.Builder().heading(bp.heading()).waterline(bp.waterline());
        for (int i = 0; i < bp.size(); i++) {
            int idx = copy.add(bp.dx(i), bp.dy(i), bp.dz(i), bp.blockData(i));
            copy.tile(idx, bp.tile(i));
        }
        for (int i = 0; i < bp.dryCount(); i++) {
            copy.dry(bp.dryX(i), bp.dryY(i), bp.dryZ(i));
        }
        bp.entities().forEach(copy::entity);
        for (FixtureEntry f : bp.fixtures()) {
            UUID fresh = UUID.randomUUID();
            ids.put(f.id(), fresh);
            copy.fixture(f.withId(fresh));
        }
        Blueprint own = copy.build();

        ShipRecord r = new ShipRecord(UUID.randomUUID(), owner.getUniqueId(), name);
        r.world(world.getName());
        r.stats(ShipStats.of(own, cfg.blocks::weight, cfg.blocks::sail));
        r.hasBlueprint(true);
        r.dockingTarget(target);
        r.forceState(ShipState.DOCKING);
        r.confirmed(false);
        store.writeBlueprintNow(r.id(), own);
        store.writeRecordNow(r);
        registry.add(r);
        ops.place(world, own, pivot, k, r.id(), null);
        settle(r, own, target);
        return r;
    }

    public static final class PrefabRefused extends Exception {
        private final WorldOps.Area area;

        PrefabRefused(WorldOps.Area area) {
            super(area.key());
            this.area = area;
        }

        public WorldOps.Area area() {
            return area;
        }
    }

    private void settle(ShipRecord r, Blueprint bp, Pose at) {
        r.docked(at);
        r.position(at);
        r.dockingTarget(null);
        r.forceState(ShipState.DOCKED);
        store.writeRecord(r);
        registry.index(r, bp);
    }

    // startup: every ship whose record is not a confirmed mooring is looked for in the world first, at
    // each pose it may have reached, and placed only if it is found nowhere. Looking before placing is
    // what turns a crash into a rollback instead of a duplicate
    public void recoverAll() {
        for (ShipRecord r : store.loadRecords()) {
            registry.add(r);
        }
        for (ShipRecord r : List.copyOf(registry.all())) {
            try {
                recover(r);
            } catch (IOException | RuntimeException e) {
                log.log(Level.SEVERE, "Recovery of ship " + r.shortId() + " failed, leaving it as it is on disk", e);
            }
        }
    }

    private void recover(ShipRecord r) throws IOException {
        if (r.state() == ShipState.VIRTUAL_PORT) {
            log.warning("Ship " + r.shortId() + " (" + r.name() + ") is in the virtual port: /ship admin recover " + r.shortId());
            return;
        }
        World world = r.world() == null ? null : Bukkit.getWorld(r.world());
        Blueprint bp = r.hasBlueprint() ? store.readBlueprint(r.id()) : null;
        if (r.state() == ShipState.DOCKED && r.confirmed()) {
            if (bp != null) {
                registry.index(r, bp);
            }
            return;
        }
        if (world == null || bp == null) {
            if (r.state() != ShipState.DOCKED) {
                log.severe("Nave " + r.shortId() + " in stato " + r.state() + " senza mondo o senza snapshot: lasciata in porto virtuale");
                r.forceState(ShipState.VIRTUAL_PORT);
                store.writeRecord(r);
            }
            return;
        }

        List<Pose> candidates = new ArrayList<>();
        addCandidate(candidates, r.state() == ShipState.DOCKED ? r.docked() : null);
        addCandidate(candidates, r.dockingTarget());
        addCandidate(candidates, r.position() == null ? null : r.position().snapped());
        addCandidate(candidates, r.departure());

        for (Pose c : candidates) {
            loadChunks(world, bp, c);
            double ratio = ops.matchRatio(world, bp, c.pivotBlock(), k(bp, c));
            if (ratio >= cfg.matchThreshold) {
                ensureFixtures(world, bp, c, r);
                settle(r, bp, c);
                r.confirmed(true);
                store.writeRecord(r);
                log.info("Ship " + r.shortId() + " found docked (" + Math.round(ratio * 100) + "% of blocks match)");
                return;
            }
        }
        List<Pose> placeable = new ArrayList<>();
        addCandidate(placeable, r.dockingTarget());
        addCandidate(placeable, r.position() == null ? null : r.position().snapped());
        addCandidate(placeable, r.departure());
        addCandidate(placeable, r.docked());
        for (Pose c : placeable) {
            if (ops.checkArea(world, bp, c.pivotBlock(), k(bp, c), null, false).ok()) {
                r.dockingTarget(c);
                r.forceState(ShipState.DOCKING);
                store.writeRecordNow(r);
                ops.place(world, bp, c.pivotBlock(), k(bp, c), r.id(), null);
                settle(r, bp, c);
                log.warning("Nave " + r.shortId() + " ripristinata dallo snapshot a " + c.pivotBlock());
                return;
            }
        }
        r.forceState(ShipState.VIRTUAL_PORT);
        store.writeRecord(r);
        log.warning("Ship " + r.shortId() + " can't be placed back, moved to the virtual port: /ship admin recover " + r.shortId());
    }

    private static void addCandidate(List<Pose> list, Pose p) {
        if (p == null) {
            return;
        }
        for (Pose q : list) {
            if (q.pivotBlock().equals(p.pivotBlock()) && Rot.snapCardinal(q.heading()) == Rot.snapCardinal(p.heading())) {
                return;
            }
        }
        list.add(p);
    }

    private void ensureFixtures(World world, Blueprint bp, Pose at, ShipRecord r) {
        int k = k(bp, at);
        for (FixtureEntry f : bp.fixtures()) {
            BlockPos cell = WorldOps.target(at.pivotBlock(), f.dx(), f.dy(), f.dz(), k);
            world.getChunkAt(cell.x() >> 4, cell.z() >> 4).getEntities();
            if (Fixtures.at(world, cell) == null) {
                Fixtures.spawn(world, cell, f.type(), Rot.wrap(f.yaw() + 90f * k), f.id(), r.id());
                log.info("Nave " + r.shortId() + ": " + f.type().id() + " mancante ricreato");
            }
        }
    }

    // a world save makes every pending mooring in that world durable
    public void onWorldSaved(World world) {
        for (ShipRecord r : registry.all()) {
            if (!r.confirmed() && r.state() == ShipState.DOCKED && world.getName().equals(r.world())) {
                r.confirmed(true);
                store.writeRecord(r);
            }
        }
    }

    private void refreshBeds(ShipRecord r, Blueprint bp, BlockPos pivot, int k) {
        for (Map.Entry<UUID, BlockPos> e : r.beds().entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                BlockPos b = e.getValue();
                BlockPos t = WorldOps.target(pivot, b.x(), b.y(), b.z(), k);
                p.setRespawnLocation(new Location(Bukkit.getWorld(r.world()), t.x(), t.y(), t.z()), true);
            }
        }
    }

    public BlockPos currentBed(ShipRecord r, UUID player) {
        BlockPos b = r.beds().get(player);
        if (b == null || r.state() != ShipState.DOCKED || r.docked() == null) {
            return null;
        }
        Blueprint bp = blueprintFor(r);
        if (bp == null) {
            return null;
        }
        return WorldOps.target(r.docked().pivotBlock(), b.x(), b.y(), b.z(), k(bp, r.docked()));
    }

    public Blueprint blueprintFor(ShipRecord r) {
        Voyage v = voyages.get(r.id());
        if (v != null) {
            return v.blueprint();
        }
        try {
            return store.readBlueprint(r.id());
        } catch (IOException e) {
            log.log(Level.WARNING, "Unreadable snapshot for " + r.shortId(), e);
            return null;
        }
    }

    private void loadChunks(World world, Blueprint bp, Pose at) {
        int r = (int) Math.ceil(bp.radius()) + 1;
        BlockPos p = at.pivotBlock();
        for (int cx = (p.x() - r) >> 4; cx <= (p.x() + r) >> 4; cx++) {
            for (int cz = (p.z() - r) >> 4; cz <= (p.z() + r) >> 4; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
    }

    private static int k(Blueprint bp, Pose target) {
        return Rot.quarterTurns(bp.heading(), target.heading());
    }

    public ShipRecord newShip(Player owner, String name) {
        ShipRecord r = new ShipRecord(UUID.randomUUID(), owner.getUniqueId(), name);
        registry.add(r);
        return r;
    }

    public void save(ShipRecord r) {
        store.writeRecord(r);
    }

    public void retire(ShipRecord r) {
        registry.remove(r.id());
        store.retire(r.id());
    }
}
