package it.gabrieledev.nautica.world;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.EntityEntry;
import it.gabrieledev.nautica.blueprint.FixtureEntry;
import it.gabrieledev.nautica.blueprint.TileCodec;
import it.gabrieledev.nautica.config.NauticaConfig;
import it.gabrieledev.nautica.fixture.Fixtures;
import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.math.Rot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

// puts a blueprint into the world and takes a captured ship out of it. A placement always targets a
// cardinal heading: k is the number of quarter turns from the blueprint's own heading
public final class WorldOps {

    public record Area(String key, BlockPos at) {
        public static final Area OK = new Area(null, null);

        public boolean ok() {
            return key == null;
        }
    }

    private final NauticaConfig cfg;
    private final Protection protection;
    private final Logger log;

    public WorldOps(NauticaConfig cfg, Protection protection, Logger log) {
        this.cfg = cfg;
        this.protection = protection;
        this.log = log;
    }

    public static BlockPos target(BlockPos pivot, int dx, int dy, int dz, int k) {
        return new BlockPos(pivot.x() + Rot.rotX(dx, dz, k), pivot.y() + dy, pivot.z() + Rot.rotZ(dx, dz, k));
    }

    // checks that the ship fits at the pose: loaded, inside the world, every target cell free
    public Area checkArea(World world, Blueprint bp, BlockPos pivot, int k, Player actor, boolean claims) {
        int minY = world.getMinHeight(), maxY = world.getMaxHeight() - 1;
        List<BlockPos> targets = new ArrayList<>(bp.size() + bp.dryCount() + bp.fixtures().size());
        for (int i = 0; i < bp.size(); i++) {
            targets.add(target(pivot, bp.dx(i), bp.dy(i), bp.dz(i), k));
        }
        int blockTargets = targets.size();
        for (int i = 0; i < bp.dryCount(); i++) {
            targets.add(target(pivot, bp.dryX(i), bp.dryY(i), bp.dryZ(i), k));
        }
        for (FixtureEntry f : bp.fixtures()) {
            targets.add(target(pivot, f.dx(), f.dy(), f.dz(), k));
        }
        for (BlockPos t : targets) {
            if (t.y() < minY || t.y() > maxY) {
                return new Area("dock.out-of-world", t);
            }
            if (!world.isChunkLoaded(t.x() >> 4, t.z() >> 4)) {
                return new Area("dock.unloaded", t);
            }
            Material m = t.block(world).getType();
            if (!cfg.blocks.passable(m)) {
                return new Area("dock.occupied", t);
            }
        }
        for (FixtureEntry f : bp.fixtures()) {
            BlockPos t = target(pivot, f.dx(), f.dy(), f.dz(), k);
            if (Fixtures.at(world, t) != null) {
                return new Area("dock.occupied", t);
            }
        }
        if (actor != null && claims) {
            for (int i = 0; i < blockTargets; i++) {
                BlockPos t = targets.get(i);
                Material as = Bukkit.createBlockData(bp.blockData(i)).getMaterial();
                if (!protection.canPlace(actor, t.block(world), as)) {
                    return new Area("dock.claim", t);
                }
            }
        }
        return Area.OK;
    }

    // share of the blueprint's blocks that the world already holds at this pose, by material
    public double matchRatio(World world, Blueprint bp, BlockPos pivot, int k) {
        if (bp.size() == 0) {
            return 0;
        }
        Material[] byPalette = new Material[bp.palette().size()];
        for (int p = 0; p < byPalette.length; p++) {
            byPalette[p] = Bukkit.createBlockData(bp.palette().get(p)).getMaterial();
        }
        int hit = 0;
        for (int i = 0; i < bp.size(); i++) {
            BlockPos t = target(pivot, bp.dx(i), bp.dy(i), bp.dz(i), k);
            if (t.block(world).getType() == byPalette[bp.paletteIndex(i)]) {
                hit++;
            }
        }
        return hit / (double) bp.size();
    }

    public List<BlockData> rotatedPalette(Blueprint bp, int k) {
        StructureRotation rotation = Rot.structureRotation(k);
        List<BlockData> out = new ArrayList<>(bp.palette().size());
        for (String s : bp.palette()) {
            BlockData data = Bukkit.createBlockData(s);
            if (rotation != StructureRotation.NONE) {
                data.rotate(rotation);
            }
            out.add(data);
        }
        return out;
    }

    // writes blocks, block entities, entities and fixtures. Fixture ids from fixtureIds replace the
    // blueprint's when present (prefabs get fresh ids at every spawn)
    public void place(World world, Blueprint bp, BlockPos pivot, int k, UUID shipId, Map<UUID, UUID> fixtureIds) {
        List<BlockData> palette = rotatedPalette(bp, k);
        for (int i = 0; i < bp.size(); i++) {
            target(pivot, bp.dx(i), bp.dy(i), bp.dz(i), k).block(world).setBlockData(palette.get(bp.paletteIndex(i)), false);
        }
        for (int i = 0; i < bp.dryCount(); i++) {
            Block b = target(pivot, bp.dryX(i), bp.dryY(i), bp.dryZ(i), k).block(world);
            if (Assembler.isWater(b) || b.getType().isAir()) {
                b.setType(Material.AIR, false);
            }
        }
        for (Map.Entry<Integer, Map<String, Object>> e : bp.tiles().entrySet()) {
            int i = e.getKey();
            try {
                TileCodec.apply(target(pivot, bp.dx(i), bp.dy(i), bp.dz(i), k).block(world), e.getValue());
            } catch (RuntimeException ex) {
                log.log(Level.SEVERE, "Block contents not restored (ship " + shipId + ", cell " + i
                        + "): resta nello snapshot in archive/", ex);
            }
        }
        double px = pivot.x() + 0.5, pz = pivot.z() + 0.5;
        for (EntityEntry en : bp.entities()) {
            spawnEntity(world, en, px, pivot.y(), pz, k, true);
        }
        for (FixtureEntry f : bp.fixtures()) {
            UUID id = fixtureIds == null ? f.id() : fixtureIds.getOrDefault(f.id(), f.id());
            Fixtures.spawn(world, target(pivot, f.dx(), f.dy(), f.dz(), k), f.type(), Rot.wrap(f.yaw() + 90f * k), id, shipId);
        }
    }

    public Entity spawnEntity(World world, EntityEntry en, double px, double py, double pz, int k, boolean persistent) {
        try {
            EntitySnapshot snapshot = Bukkit.getEntityFactory().createEntitySnapshot(new String(en.data(), StandardCharsets.UTF_8));
            double cos = Math.cos(Math.toRadians(90.0 * k)), sin = Math.sin(Math.toRadians(90.0 * k));
            double x = px + Rot.rotX(en.dx(), en.dz(), cos, sin);
            double z = pz + Rot.rotZ(en.dx(), en.dz(), cos, sin);
            Location at = new Location(world, x, py + en.dy(), z, Rot.wrap(en.yaw() + 90f * k), 0f);
            Entity entity = snapshot.createEntity(at);
            entity.setPersistent(persistent);
            if (entity instanceof Hanging hanging && en.facing() != null) {
                hanging.setFacingDirection(Rot.rotate(en.facing(), k), true);
            }
            return entity;
        } catch (RuntimeException ex) {
            log.log(Level.SEVERE, "Onboard entity not restored (" + en.kind() + ")", ex);
            return null;
        }
    }

    // takes the captured ship out of the world. Block entities are emptied first, so no removal path can
    // drop items that the snapshot already holds; cells at or under the waterline become water sources
    public void remove(Capture c) {
        World world = c.world();
        for (Entity e : c.entities()) {
            e.remove();
        }
        for (Fixtures.Found f : c.fixtures()) {
            Fixtures.remove(world, f.cell(), f.id());
        }
        Blueprint bp = c.blueprint();
        for (int i : bp.tiles().keySet()) {
            TileCodec.clear(c.cells().get(i).block(world));
        }
        BlockData water = Bukkit.createBlockData(Material.WATER);
        BlockData air = Bukkit.createBlockData(Material.AIR);
        for (BlockPos p : c.cells()) {
            p.block(world).setBlockData(p.y() <= c.waterY() ? water : air, false);
        }
        for (BlockPos p : c.dry()) {
            if (p.y() <= c.waterY()) {
                p.block(world).setBlockData(water, false);
            }
        }
        for (BlockPos p : c.partners()) {
            Block b = p.block(world);
            if (b.getBlockData() instanceof Chest chest) {
                chest.setType(Chest.Type.SINGLE);
                b.setBlockData(chest, false);
            }
        }
    }
}
