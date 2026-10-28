package it.gabrieledev.nautica.sail;

import it.gabrieledev.nautica.Keys;
import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.EntityEntry;
import it.gabrieledev.nautica.blueprint.FixtureEntry;
import it.gabrieledev.nautica.blueprint.TileCodec;
import it.gabrieledev.nautica.config.Messages;
import it.gabrieledev.nautica.config.NauticaConfig;
import it.gabrieledev.nautica.fixture.FixtureModel;
import it.gabrieledev.nautica.fixture.FixtureType;
import it.gabrieledev.nautica.fixture.Fixtures;
import it.gabrieledev.nautica.math.Pose;
import it.gabrieledev.nautica.math.Rot;
import it.gabrieledev.nautica.ship.ModuleType;
import it.gabrieledev.nautica.ship.ShipRecord;
import it.gabrieledev.nautica.ship.ShipStats;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.joml.Quaternionf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

// a ship at sea. Movement is kinematic: a current speed that chases a target speed, a heading that turns
// only with way on, and a collision test before every move. The feel (wind, waves, roll, sound) is
// layered on top and never decides where the ship can go
public final class Voyage {

    public static final int REVERSE = -1;
    public static final int STOP = 0;
    public static final int FULL_AHEAD = 3;

    public record Rider(FixtureEntry fixture, ArmorStand stand, Entity passenger, double lx, double ly, double lz, float yaw) {
    }

    private record Mover(Entity entity, double lx, double ly, double lz, float yaw) {
    }

    private final Plugin plugin;
    private final NauticaConfig cfg;
    private final Messages msg;
    private final WindSystem windSystem;
    private final ShipRecord record;
    private final Blueprint bp;
    private final World world;
    private final ShipStats stats;
    private final Random random = new Random();

    private double x, y, z;
    private float heading;
    private double speed;
    private int gear;
    private boolean anchorDown;
    private Float alignTarget;
    private boolean left, right, lastForward, lastBackward, lastJump, lastSprint;
    private boolean sneakPressed;
    private float roll;
    private float turnRate;

    private Player pilot;
    private final List<Rider> riders = new ArrayList<>();
    private final List<Mover> movers = new ArrayList<>();
    private final ShipRenderer renderer;
    private final CollisionModel collision;
    private final ChunkKeeper chunks;
    private BossBar windBar;

    private long ticks;
    private float renderedHeading;
    private float renderedRoll;
    private long lastChunkUpdate = -100;
    private int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;
    private long nextCreak;
    private float lastWindHeading;
    private boolean groundedWarned;
    private boolean steamOutWarned;
    private long lastBlockedMessage = -100;
    private final double bowLocal, sternLocal;
    private boolean ended;

    Voyage(Plugin plugin, NauticaConfig cfg, Messages msg, WindSystem wind, ShipRecord record, Blueprint bp,
           World world, Pose start) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.msg = msg;
        this.windSystem = wind;
        this.record = record;
        this.bp = bp;
        this.world = world;
        this.stats = record.stats();
        this.x = start.x();
        this.y = start.y();
        this.z = start.z();
        this.heading = start.heading();
        this.renderedHeading = heading;
        this.renderer = new ShipRenderer(cfg);
        this.collision = new CollisionModel(bp, cfg.blocks);
        this.chunks = new ChunkKeeper(plugin, world);
        this.lastWindHeading = wind.get(world).toHeading();

        double fx = Rot.forwardX(bp.heading()), fz = Rot.forwardZ(bp.heading());
        double max = -1e9, min = 1e9;
        for (int i = 0; i < bp.size(); i++) {
            double d = bp.dx(i) * fx + bp.dz(i) * fz;
            max = Math.max(max, d);
            min = Math.min(min, d);
        }
        bowLocal = max + 0.6;
        sternLocal = min - 0.6;
    }

    public ShipRecord record() {
        return record;
    }

    public Blueprint blueprint() {
        return bp;
    }

    public World world() {
        return world;
    }

    public Player pilot() {
        return pilot;
    }

    public Pose pose() {
        return new Pose(x, y, z, heading);
    }

    public double speed() {
        return speed;
    }

    public List<Rider> riders() {
        return riders;
    }

    public int displayCount() {
        return renderer.size();
    }

    public boolean isPilot(Player p) {
        return pilot != null && pilot.getUniqueId().equals(p.getUniqueId());
    }

    public boolean ownsStand(Entity vehicle) {
        for (Rider r : riders) {
            if (r.stand().equals(vehicle)) {
                return true;
            }
        }
        return false;
    }

    public boolean sneakPressed() {
        return sneakPressed;
    }

    // spawns the rendering and seats everyone. seated maps fixture id to whoever was already
    // sitting there when the ship was assembled
    void start(Player pilot, Map<UUID, Entity> seated) {
        this.pilot = pilot;
        Location at = rootLocation();
        renderer.spawn(world, at, bp, rotation(), record.id());
        collision.place(x, y, z, heading);

        for (FixtureEntry f : bp.fixtures()) {
            Entity passenger = f.type() == FixtureType.HELM ? pilot : seated.get(f.id());
            if (passenger == null || !passenger.isValid()) {
                continue;
            }
            float[] o = FixtureModel.riderOffset(f.type());
            double rad = Math.toRadians(f.yaw());
            double lx = f.dx() + Rot.rotX(o[0], o[2], Math.cos(rad), Math.sin(rad));
            double lz = f.dz() + Rot.rotZ(o[0], o[2], Math.cos(rad), Math.sin(rad));
            double ly = f.dy() + o[1] + cfg.seatYOffset;
            Location spot = worldPoint(lx, ly, lz);
            spot.setYaw(Rot.wrap(f.yaw() + heading - bp.heading()));
            ArmorStand stand = Fixtures.spawnRiderStand(spot, f.id());
            stand.addPassenger(passenger);
            riders.add(new Rider(f, stand, passenger, lx, ly, lz, f.yaw()));
        }

        for (EntityEntry e : bp.entities()) {
            if (e.hanging()) {
                continue;
            }
            Location spot = worldPoint(e.dx(), e.dy(), e.dz());
            Entity clone = spawnClone(e, spot);
            if (clone != null) {
                movers.add(new Mover(clone, e.dx(), e.dy(), e.dz(), e.yaw()));
            }
        }

        if (cfg.hud && cfg.windBossbar && cfg.wind) {
            windBar = BossBar.bossBar(msg.get("hud.wind-bar", "dir", "-", "strength", 0), 0f,
                    BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
            pilot.showBossBar(windBar);
        }
        updateChunks(true);
    }

    private Entity spawnClone(EntityEntry e, Location spot) {
        try {
            spot.setYaw(Rot.wrap(e.yaw() + heading - bp.heading()));
            Entity clone = Bukkit.getEntityFactory()
                    .createEntitySnapshot(new String(e.data(), StandardCharsets.UTF_8)).createEntity(spot);
            clone.setPersistent(false);
            clone.getPersistentDataContainer().set(Keys.RENDER, PersistentDataType.STRING, record.id().toString());
            if (clone instanceof ArmorStand stand) {
                stand.setMarker(true);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.setDisabledSlots(EquipmentSlot.values());
            }
            return clone;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Couldn't create the visual copy of an onboard entity: " + ex.getMessage());
            return null;
        }
    }

    public void input(Input in) {
        sneakPressed = in.isSneak();
        if (in.isForward() && !lastForward) {
            if (anchorDown) {
                notify("sail.anchor-is-down");
            } else {
                gear = Math.min(FULL_AHEAD, gear + 1);
            }
        }
        if (in.isBackward() && !lastBackward) {
            gear = Math.max(REVERSE, gear - 1);
        }
        if (in.isSprint() && !lastSprint) {
            alignTarget = Rot.snapCardinal(heading);
        }
        if (in.isJump() && !lastJump) {
            if (record.module(ModuleType.ANCHOR) > 0) {
                anchorDown = !anchorDown;
                if (anchorDown) {
                    gear = STOP;
                }
                sound(Sound.BLOCK_CHAIN_PLACE, 1f, anchorDown ? 0.6f : 0.9f);
                notify(anchorDown ? "sail.anchor-down" : "sail.anchor-up");
            } else {
                sound(Sound.BLOCK_BELL_USE, 1.2f, 1f);
            }
        }
        left = in.isLeft();
        right = in.isRight();
        if (left || right) {
            alignTarget = null;
        }
        lastForward = in.isForward();
        lastBackward = in.isBackward();
        lastJump = in.isJump();
        lastSprint = in.isSprint();
    }

    // called once per server tick by the controller
    void tick() {
        ticks++;
        Wind wind = windSystem.get(world);
        boolean storm = world.isThundering();
        boolean heart = record.module(ModuleType.HEART) > 0;
        double tonnageRatio = cfg.referenceTonnage / Math.max(1, stats.tonnage());

        double vmax = maxSpeed(wind, storm && !heart);
        double target = switch (gear) {
            case REVERSE -> -vmax * cfg.reverseFraction;
            case STOP -> 0;
            default -> vmax * cfg.gears[gear - 1];
        };
        if (anchorDown) {
            target = 0;
        }
        double accel = cfg.acceleration * clamp(tonnageRatio, 0.25, 2.0);
        double decel = cfg.deceleration * clamp(tonnageRatio, 0.25, 2.0) * (anchorDown ? 6 : 1);
        if (speed < target) {
            speed = Math.min(target, speed + (speed < 0 ? decel : accel));
        } else if (speed > target) {
            speed = Math.max(target, speed - (speed > 0 ? decel : accel));
        }

        float turn = turnInput();
        double helm = record.module(ModuleType.HELM) > 0 ? cfg.helmMultiplier[Math.min(record.module(ModuleType.HELM), cfg.helmMultiplier.length) - 1] : 1.0;
        double way = clamp(Math.abs(speed) / (0.35 * cfg.maxSpeedCap), 0, 1);
        double rate = cfg.turnRateMax * helm * clamp(Math.sqrt(tonnageRatio), 0.35, 1.25) * way;
        if (record.module(ModuleType.HELM) >= 3) {
            rate = Math.max(rate, cfg.standstillTurn);
        }
        if (storm && !heart) {
            rate *= 0.8;
        }
        turnRate = (float) (turn * rate);
        if (speed < 0) {
            turnRate = -turnRate;
        }

        if (ticks % 4 == 0 && speed > 0.02) {
            float look = (float) Math.max(1.5, speed * 8);
            CollisionModel.Result ahead = collision.test(world, x + Rot.forwardX(heading) * look, y,
                    z + Rot.forwardZ(heading) * look, heading);
            if (ahead == CollisionModel.Result.GROUNDED) {
                speed = Math.max(0, speed - decel * 4 * 4);
                if (!groundedWarned) {
                    notify("sail.shoaling");
                    groundedWarned = true;
                }
            } else {
                groundedWarned = false;
            }
        }

        move();

        if (ticks % cfg.updateTicks == 0) {
            render();
        }
        updateChunks(false);
        feel(wind, storm, heart);
        if (record.module(ModuleType.STEAM) > 0 && gear != STOP) {
            burnFuel();
        }
    }

    private float turnInput() {
        if (left != right) {
            return right ? 1f : -1f;
        }
        if (alignTarget != null) {
            float d = Rot.delta(heading, alignTarget);
            if (Math.abs(d) < 0.3f) {
                heading = alignTarget;
                alignTarget = null;
                return 0f;
            }
            return Math.signum(d) * Math.min(1f, Math.abs(d) / 3f);
        }
        double[] wp = record.waypoint();
        if (wp != null && record.module(ModuleType.COMPASS) > 0 && gear > STOP) {
            float bearing = Rot.headingTo(wp[0] - x, wp[1] - z);
            float d = Rot.delta(heading, bearing);
            if (Math.hypot(wp[0] - x, wp[1] - z) < 8) {
                record.waypoint(null);
                notify("sail.waypoint-reached");
                return 0f;
            }
            return Math.abs(d) < 0.5f ? 0f : Math.signum(d) * Math.min(1f, Math.abs(d) / 10f);
        }
        return 0f;
    }

    private double maxSpeed(Wind wind, boolean stormPenalty) {
        int sails = record.module(ModuleType.SAILS);
        double sailMult = sails > 0 ? cfg.sailMultiplier[Math.min(sails, cfg.sailMultiplier.length) - 1] : 1.0;
        double sailFactor = clamp(stats.sailRatio() * sailMult / cfg.sailRatioForFullSpeed, 0, 1);
        double hull = cfg.bareHullFactor + (1 - cfg.bareHullFactor) * sailFactor;
        double windFactor = cfg.wind ? (0.35 + 0.65 * wind.strength()) * wind.pointOfSail(heading) : 0.85;
        double v = cfg.maxSpeedCap * hull * windFactor;
        if (record.module(ModuleType.OARS) > 0) {
            v = Math.max(v, cfg.oarsMinSpeed);
        }
        if (record.module(ModuleType.STEAM) > 0 && record.fuelTicks() > 0) {
            double steam = cfg.steamSpeed * (cfg.wind ? 0.85 + 0.15 * wind.pointOfSail(heading) : 1.0);
            v = Math.max(v, steam);
        }
        if (stormPenalty) {
            v *= 0.75;
        }
        return Math.min(v, cfg.maxSpeedCap);
    }

    private void move() {
        float newHeading = Rot.wrap(heading + turnRate);
        double nx = x + Rot.forwardX(newHeading) * speed;
        double nz = z + Rot.forwardZ(newHeading) * speed;
        if (Math.abs(speed) < 1e-4 && Math.abs(turnRate) < 1e-4) {
            return;
        }
        CollisionModel.Result r = collision.test(world, nx, y, nz, newHeading);
        if (r == CollisionModel.Result.CLEAR) {
            collision.commit();
            x = nx;
            z = nz;
            heading = newHeading;
            return;
        }
        if (turnRate != 0) {
            double mx = x + Rot.forwardX(heading) * speed, mz = z + Rot.forwardZ(heading) * speed;
            if (collision.test(world, mx, y, mz, heading) == CollisionModel.Result.CLEAR) {
                collision.commit();
                x = mx;
                z = mz;
                return;
            }
            if (collision.test(world, x, y, z, newHeading) == CollisionModel.Result.CLEAR) {
                collision.commit();
                heading = newHeading;
                speed *= 0.5;
                return;
            }
        }
        switch (r) {
            case UNLOADED -> speed *= 0.5;
            case GROUNDED -> {
                speed = 0;
                gear = STOP;
                notify("sail.grounded");
                if (cfg.particles) {
                    world.spawnParticle(Particle.BLOCK, worldPoint(bowLocal * Rot.forwardX(bp.heading()), bp.minY(),
                            bowLocal * Rot.forwardZ(bp.heading())), 30, 1, 0.3, 1, Material.SAND.createBlockData());
                }
            }
            default -> {
                speed = 0;
                gear = STOP;
                if (ticks - lastBlockedMessage > 40) {
                    notify("sail.blocked");
                    sound(Sound.BLOCK_WOOD_BREAK, 1f, 0.6f);
                    lastBlockedMessage = ticks;
                }
            }
        }
    }

    private void render() {
        Location at = rootLocation();
        renderer.move(at);
        double bob = bobOffset();
        float delta = heading - bp.heading();
        double rad = Math.toRadians(delta);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        for (Rider r : riders) {
            if (!r.stand().isValid()) {
                continue;
            }
            Location l = new Location(world, x + Rot.rotX(r.lx(), r.lz(), cos, sin), y + r.ly() + bob,
                    z + Rot.rotZ(r.lx(), r.lz(), cos, sin), Rot.wrap(r.yaw() + delta), 0f);
            r.stand().teleport(l, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        for (Mover m : movers) {
            if (m.entity().isValid()) {
                m.entity().teleport(new Location(world, x + Rot.rotX(m.lx(), m.lz(), cos, sin), y + m.ly() + bob,
                        z + Rot.rotZ(m.lx(), m.lz(), cos, sin), Rot.wrap(m.yaw() + delta), 0f));
            }
        }

        float targetRoll = 0f;
        if (cfg.rollOnTurn) {
            targetRoll = (float) (-turnRate / Math.max(0.1, cfg.turnRateMax) * cfg.rollMaxDegrees
                    * clamp(Math.abs(speed) / (0.5 * cfg.maxSpeedCap), 0, 1));
        }
        roll += (targetRoll - roll) * 0.15f;
        if (ticks % cfg.rotationUpdateTicks == 0
                && (Math.abs(Rot.delta(renderedHeading, heading)) > 0.05f || Math.abs(roll - renderedRoll) > 0.05f)) {
            renderer.rotate(rotation(), cfg.rotationUpdateTicks);
            renderedHeading = heading;
            renderedRoll = roll;
        }
    }

    private Quaternionf rotation() {
        Quaternionf q = Rot.yaw(heading - bp.heading());
        if (roll != 0f) {
            q.mul(new Quaternionf().rotateAxis((float) Math.toRadians(roll),
                    (float) Rot.forwardX(bp.heading()), 0f, (float) Rot.forwardZ(bp.heading())));
        }
        return q;
    }

    private Location rootLocation() {
        return new Location(world, x, y + bobOffset(), z, 0f, 0f);
    }

    private double bobOffset() {
        if (!cfg.bobbing) {
            return 0;
        }
        double amp = cfg.bobAmplitude * clamp(Math.sqrt(cfg.referenceTonnage / Math.max(1, stats.tonnage())), 0.3, 1.5);
        if (world.isThundering() && record.module(ModuleType.HEART) == 0) {
            amp *= cfg.stormMultiplier;
        }
        return amp * Math.sin(2 * Math.PI * ticks / cfg.bobPeriodTicks);
    }

    Location worldPoint(double lx, double ly, double lz) {
        double rad = Math.toRadians(heading - bp.heading());
        double cos = Math.cos(rad), sin = Math.sin(rad);
        return new Location(world, x + Rot.rotX(lx, lz, cos, sin), y + ly, z + Rot.rotZ(lx, lz, cos, sin));
    }

    private void updateChunks(boolean force) {
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (force || cx != lastChunkX || cz != lastChunkZ || ticks - lastChunkUpdate > 40) {
            chunks.update(x, z, bp.radius() + 16 + Math.abs(speed) * 40);
            lastChunkX = cx;
            lastChunkZ = cz;
            lastChunkUpdate = ticks;
        }
    }

    private void feel(Wind wind, boolean storm, boolean heart) {
        double abs = Math.abs(speed);
        double fx = Rot.forwardX(bp.heading()), fz = Rot.forwardZ(bp.heading());
        int wl = bp.waterline();
        if (cfg.particles && ticks % 2 == 0 && abs > 0.05) {
            Location bow = worldPoint(fx * bowLocal, wl + 0.9, fz * bowLocal);
            Location stern = worldPoint(fx * sternLocal, wl + 0.6, fz * sternLocal);
            int n = (int) Math.ceil(abs * 20);
            world.spawnParticle(Particle.SPLASH, bow, n, 0.8, 0.1, 0.8, 0.1);
            world.spawnParticle(Particle.BUBBLE, stern.clone().add(0, -0.8, 0), n, 0.6, 0.2, 0.6, 0.02);
            world.spawnParticle(Particle.CLOUD, stern, Math.max(1, n / 4), 0.5, 0.05, 0.5, 0.01);
            if (heart) {
                world.spawnParticle(Particle.GLOW, stern, Math.max(1, n / 2), 0.6, 0.1, 0.6, 0.01);
            }
        }
        if (cfg.sounds) {
            if (ticks >= nextCreak) {
                sound(Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 0.25f, 0.45f + random.nextFloat() * 0.15f);
                nextCreak = ticks + (storm ? 40 : 100) + random.nextInt(storm ? 80 : 200);
            }
            if (abs > 0.25 && ticks % 40 == 0) {
                sound(Sound.ENTITY_GENERIC_SPLASH, 0.3f, 0.8f);
            }
            if (cfg.wind && Math.abs(Rot.delta(lastWindHeading, wind.toHeading())) > 12f) {
                sound(Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.6f, 0.6f);
                lastWindHeading = wind.toHeading();
            }
        }
        if (cfg.hud && pilot != null && ticks % 10 == 0) {
            double knots = abs * 20 * 1.94;
            notify("hud.line",
                    "heading", Math.round(heading),
                    "compass", msg.plain("compass." + Rot.compass(heading)),
                    "knots", String.format(Locale.ROOT, "%.1f", knots),
                    "gear", msg.get(anchorDown ? "gear.anchor" : "gear." + gear));
            if (windBar != null) {
                float rel = Math.abs(Rot.delta(heading, wind.toHeading()));
                String dir = rel <= 35 ? "wind.running" : rel <= 80 ? "wind.broad" : rel <= 110 ? "wind.beam"
                        : rel <= 150 ? "wind.close" : "wind.head";
                windBar.name(msg.get("hud.wind-bar", "dir", msg.get(dir), "strength", Math.round(wind.strength() * 100)));
                windBar.progress((float) clamp(wind.strength(), 0, 1));
            }
        }
    }

    private void burnFuel() {
        int remaining = record.fuelTicks();
        if (remaining > 0) {
            record.fuelTicks(remaining - 1);
            return;
        }
        for (Map.Entry<Integer, Map<String, Object>> e : bp.tiles().entrySet()) {
            String data = bp.blockData(e.getKey());
            if (!(data.startsWith("minecraft:furnace") || data.startsWith("minecraft:blast_furnace")
                    || data.startsWith("minecraft:smoker")) || !TileCodec.holdsItems(e.getValue())) {
                continue;
            }
            ItemStack[] items = TileCodec.decodeItems((String) e.getValue().get("items"));
            if (items.length < 2 || items[1] == null || items[1].isEmpty()) {
                continue;
            }
            Material m = items[1].getType();
            int units = m == Material.COAL || m == Material.CHARCOAL ? 1 : m == Material.COAL_BLOCK ? 9 : 0;
            if (units == 0) {
                continue;
            }
            items[1].setAmount(items[1].getAmount() - 1);
            Map<String, Object> copy = new LinkedHashMap<>(e.getValue());
            copy.put("items", TileCodec.encodeItems(items));
            bp.replaceTile(e.getKey(), copy);
            record.fuelTicks(units * cfg.steamSecondsPerFuel * 20);
            steamOutWarned = false;
            return;
        }
        if (!steamOutWarned) {
            notify("sail.no-fuel");
            steamOutWarned = true;
        }
    }

    private void notify(String key, Object... pairs) {
        if (pilot != null && pilot.isOnline()) {
            msg.actionBar(pilot, key, pairs);
        }
    }

    private void sound(Sound sound, float volume, float pitch) {
        if (cfg.sounds) {
            world.playSound(new Location(world, x, y + 1, z), sound, SoundCategory.NEUTRAL, volume, pitch);
        }
    }

    // puts the pilot back on the helm after a refused mooring
    void remountPilot() {
        for (Rider r : riders) {
            if (r.fixture().type() == FixtureType.HELM && pilot != null && pilot.isOnline() && r.stand().isValid()) {
                r.stand().addPassenger(pilot);
                gear = STOP;
                return;
            }
        }
    }

    void dropRider(Entity passenger) {
        riders.removeIf(r -> {
            if (r.passenger().equals(passenger)) {
                r.stand().remove();
                return true;
            }
            return false;
        });
    }

    // removes everything this voyage spawned. Riders are ejected; the caller decides where they go
    void end() {
        if (ended) {
            return;
        }
        ended = true;
        renderer.remove();
        for (Rider r : riders) {
            r.stand().eject();
            r.stand().remove();
        }
        for (Mover m : movers) {
            m.entity().remove();
        }
        movers.clear();
        chunks.releaseAll();
        if (windBar != null && pilot != null) {
            pilot.hideBossBar(windBar);
        }
    }

    void releasePilotReference() {
        pilot = null;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
