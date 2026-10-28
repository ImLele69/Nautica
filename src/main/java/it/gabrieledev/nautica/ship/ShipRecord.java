package it.gabrieledev.nautica.ship;

import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.math.Pose;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// the header of a ship: who owns it and where it is. Small enough to rewrite atomically at every state
// change and at every checkpoint while sailing; the blueprint lives in its own file.
//
// confirmed is false from the moment the world changes because of this ship until a save of that
// world proves the change reached disk. Recovery only trusts confirmed records
public final class ShipRecord {

    private final UUID id;
    private String name;
    private UUID owner;
    private final Set<UUID> crew = new LinkedHashSet<>();
    private String world;
    private ShipState state = ShipState.DOCKED;
    private Pose docked;
    private Pose departure;
    private Pose position;
    private Pose dockingTarget;
    private boolean confirmed = true;
    private boolean hasBlueprint;
    private final Map<ModuleType, Integer> modules = new EnumMap<>(ModuleType.class);
    private ShipStats stats = ShipStats.EMPTY;
    private final Map<UUID, BlockPos> beds = new HashMap<>();
    private double[] waypoint;
    private int fuelTicks;

    public ShipRecord(UUID id, UUID owner, String name) {
        this.id = id;
        this.owner = owner;
        this.name = name;
    }

    public UUID id() {
        return id;
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public UUID owner() {
        return owner;
    }

    public void owner(UUID owner) {
        this.owner = owner;
        crew.remove(owner);
    }

    public Set<UUID> crew() {
        return crew;
    }

    public boolean canPilot(UUID player) {
        return player.equals(owner) || crew.contains(player);
    }

    public String world() {
        return world;
    }

    public void world(String world) {
        this.world = world;
    }

    public ShipState state() {
        return state;
    }

    public void state(ShipState next) {
        if (next != state && !state.canMoveTo(next)) {
            throw new IllegalStateException("ship " + shortId() + ": " + state + " -> " + next);
        }
        this.state = next;
    }

    // recovery and admin tools bypass the transition table on purpose
    public void forceState(ShipState next) {
        this.state = next;
    }

    public Pose docked() {
        return docked;
    }

    public void docked(Pose docked) {
        this.docked = docked;
    }

    public Pose departure() {
        return departure;
    }

    public void departure(Pose departure) {
        this.departure = departure;
    }

    public Pose position() {
        return position;
    }

    public void position(Pose position) {
        this.position = position;
    }

    public Pose dockingTarget() {
        return dockingTarget;
    }

    public void dockingTarget(Pose dockingTarget) {
        this.dockingTarget = dockingTarget;
    }

    public boolean confirmed() {
        return confirmed;
    }

    public void confirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    public boolean hasBlueprint() {
        return hasBlueprint;
    }

    public void hasBlueprint(boolean hasBlueprint) {
        this.hasBlueprint = hasBlueprint;
    }

    public int module(ModuleType type) {
        return modules.getOrDefault(type, 0);
    }

    public void module(ModuleType type, int level) {
        if (level <= 0) {
            modules.remove(type);
        } else {
            modules.put(type, Math.min(level, type.maxLevel()));
        }
    }

    public Map<ModuleType, Integer> modules() {
        return modules;
    }

    public ShipStats stats() {
        return stats;
    }

    public void stats(ShipStats stats) {
        this.stats = stats;
    }

    // players whose respawn bed is on this ship, with the bed's cell in blueprint coordinates
    public Map<UUID, BlockPos> beds() {
        return beds;
    }

    public double[] waypoint() {
        return waypoint;
    }

    public void waypoint(double[] waypoint) {
        this.waypoint = waypoint;
    }

    public int fuelTicks() {
        return fuelTicks;
    }

    public void fuelTicks(int fuelTicks) {
        this.fuelTicks = fuelTicks;
    }

    public String write() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("id", id.toString());
        y.set("name", name);
        y.set("owner", owner == null ? null : owner.toString());
        y.set("crew", crew.stream().map(UUID::toString).toList());
        y.set("world", world);
        y.set("state", state.name());
        writePose(y, "docked", docked);
        writePose(y, "departure", departure);
        writePose(y, "position", position);
        writePose(y, "docking-target", dockingTarget);
        y.set("confirmed", confirmed);
        y.set("has-blueprint", hasBlueprint);
        ConfigurationSection mods = y.createSection("modules");
        modules.forEach((k, v) -> mods.set(k.id(), v));
        stats.write(y.createSection("stats"));
        ConfigurationSection bedSection = y.createSection("beds");
        beds.forEach((player, pos) -> bedSection.set(player.toString(), pos.toString()));
        if (waypoint != null) {
            y.set("waypoint", List.of(waypoint[0], waypoint[1]));
        }
        y.set("fuel-ticks", fuelTicks);
        return y.saveToString();
    }

    public static ShipRecord read(YamlConfiguration y) {
        UUID id = UUID.fromString(y.getString("id", ""));
        String owner = y.getString("owner");
        ShipRecord r = new ShipRecord(id, owner == null ? null : UUID.fromString(owner), y.getString("name", "Nave"));
        for (String c : y.getStringList("crew")) {
            r.crew.add(UUID.fromString(c));
        }
        r.world = y.getString("world");
        r.state = ShipState.valueOf(y.getString("state", "DOCKED"));
        r.docked = Pose.read(y.getConfigurationSection("docked"));
        r.departure = Pose.read(y.getConfigurationSection("departure"));
        r.position = Pose.read(y.getConfigurationSection("position"));
        r.dockingTarget = Pose.read(y.getConfigurationSection("docking-target"));
        r.confirmed = y.getBoolean("confirmed", true);
        r.hasBlueprint = y.getBoolean("has-blueprint", false);
        ConfigurationSection mods = y.getConfigurationSection("modules");
        if (mods != null) {
            for (String k : mods.getKeys(false)) {
                ModuleType t = ModuleType.byId(k);
                if (t != null) {
                    r.module(t, mods.getInt(k));
                }
            }
        }
        r.stats = ShipStats.read(y.getConfigurationSection("stats"));
        ConfigurationSection bedSection = y.getConfigurationSection("beds");
        if (bedSection != null) {
            for (String k : bedSection.getKeys(false)) {
                r.beds.put(UUID.fromString(k), BlockPos.parse(bedSection.getString(k, "0,0,0")));
            }
        }
        List<Double> wp = y.getDoubleList("waypoint");
        if (wp.size() == 2) {
            r.waypoint = new double[]{wp.get(0), wp.get(1)};
        }
        r.fuelTicks = y.getInt("fuel-ticks");
        return r;
    }

    private static void writePose(YamlConfiguration y, String key, Pose pose) {
        if (pose != null) {
            pose.write(y.createSection(key));
        }
    }
}
