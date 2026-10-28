package it.gabrieledev.nautica.world;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.EntityEntry;
import it.gabrieledev.nautica.blueprint.FixtureEntry;
import it.gabrieledev.nautica.blueprint.TileCodec;
import it.gabrieledev.nautica.config.NauticaConfig;
import it.gabrieledev.nautica.fixture.FixtureType;
import it.gabrieledev.nautica.fixture.Fixtures;
import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.ship.ShipRegistry;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.util.BoundingBox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// reads a ship out of the world without changing it. The flood fill is iterative, stops the moment a
// limit is crossed and never loads a chunk: a ship that reaches into an unloaded chunk is refused
public final class Assembler {

    public static final class Failure extends Exception {
        private final String key;
        private final Object[] args;

        Failure(String key, Object... args) {
            super(key);
            this.key = key;
            this.args = args;
        }

        public String key() {
            return key;
        }

        public Object[] args() {
            return args;
        }
    }

    public record Options(Player actor, boolean checkClaims, boolean ignoreSize) {
    }

    private final NauticaConfig cfg;
    private final ShipRegistry registry;
    private final Protection protection;

    public Assembler(NauticaConfig cfg, ShipRegistry registry, Protection protection) {
        this.cfg = cfg;
        this.registry = registry;
        this.protection = protection;
    }

    public Capture capture(World world, BlockPos helmCell, float helmYaw, UUID shipId, Options opt) throws Failure {
        Block start = helmCell.offset(0, -1, 0).block(world);
        if (!cfg.blocks.allowed(start.getType())) {
            throw new Failure("assemble.helm-not-on-ship");
        }

        List<BlockPos> cells = flood(world, BlockPos.of(start), shipId, opt.ignoreSize());
        Set<Long> occupied = new HashSet<>(cells.size() * 2);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            occupied.add(p.key());
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }

        int waterY = waterline(world, cells, occupied);

        if (opt.actor() != null && opt.checkClaims()) {
            for (BlockPos p : cells) {
                if (!protection.canBreak(opt.actor(), p.block(world))) {
                    throw new Failure("assemble.claim", "x", p.x(), "y", p.y(), "z", p.z());
                }
            }
        }

        for (BlockPos p : cells) {
            closeViewers(p.block(world));
        }

        List<BlockPos> dry = dryInterior(world, occupied, minX, minY, minZ, maxX, maxZ, waterY);

        Blueprint.Builder b = new Blueprint.Builder().heading(helmYaw).waterline(waterY - helmCell.y());
        List<BlockPos> partners = new ArrayList<>();
        for (BlockPos p : cells) {
            Block block = p.block(world);
            BlockData data = block.getBlockData();
            if (data instanceof Chest chest && chest.getType() != Chest.Type.SINGLE) {
                BlockPos partner = chestPartner(p, chest);
                if (!occupied.contains(partner.key())) {
                    chest.setType(Chest.Type.SINGLE);
                    partners.add(partner);
                }
            }
            int i = b.add(p.x() - helmCell.x(), p.y() - helmCell.y(), p.z() - helmCell.z(), data.getAsString());
            BlockState st = block.getState();
            b.tile(i, TileCodec.capture(st));
        }
        for (BlockPos d : dry) {
            b.dry(d.x() - helmCell.x(), d.y() - helmCell.y(), d.z() - helmCell.z());
        }

        BoundingBox box = new BoundingBox(minX - 1, minY - 1, minZ - 1, maxX + 2, maxY + 3, maxZ + 2);
        List<Fixtures.Found> fixtures = new ArrayList<>();
        boolean helmFound = false;
        for (Fixtures.Found f : Fixtures.inBox(world, box)) {
            boolean onShip = occupied.contains(f.cell().offset(0, -1, 0).key());
            boolean isOurHelm = f.cell().equals(helmCell) && f.type() == FixtureType.HELM;
            if (!onShip || occupied.contains(f.cell().key())) {
                continue;
            }
            if (f.type() == FixtureType.HELM && !isOurHelm) {
                throw new Failure("assemble.two-helms", "x", f.cell().x(), "y", f.cell().y(), "z", f.cell().z());
            }
            helmFound |= isOurHelm;
            fixtures.add(f);
            b.fixture(new FixtureEntry(f.type(), f.cell().x() - helmCell.x(), f.cell().y() - helmCell.y(),
                    f.cell().z() - helmCell.z(), f.yaw(), f.id()));
        }
        if (!helmFound) {
            throw new Failure("assemble.helm-not-on-ship");
        }

        List<Entity> entities = new ArrayList<>();
        double px = helmCell.x() + 0.5, pz = helmCell.z() + 0.5;
        for (Entity e : world.getNearbyEntities(box)) {
            EntityEntry entry = entityEntry(e, occupied, px, helmCell.y(), pz);
            if (entry != null) {
                entities.add(e);
                b.entity(entry);
            }
        }

        return new Capture(world, helmCell, waterY, b.build(), cells, dry, entities, fixtures, partners);
    }

    // true when the world still holds exactly what c copied. Called after the snapshot write
    // returns: anything that moved in between (a hopper, a player, a piston) aborts the departure
    public boolean unchanged(Capture c) {
        Blueprint bp = c.blueprint();
        for (int i = 0; i < bp.size(); i++) {
            Block block = c.cells().get(i).block(c.world());
            BlockData data = block.getBlockData();
            String expected = bp.blockData(i);
            if (!data.getAsString().equals(expected)) {
                if (!(data instanceof Chest chest) || chest.getType() == Chest.Type.SINGLE) {
                    return false;
                }
                chest.setType(Chest.Type.SINGLE);
                if (!chest.getAsString().equals(expected)) {
                    return false;
                }
            }
            if (!Objects.equals(TileCodec.capture(block.getState()), bp.tile(i))) {
                return false;
            }
        }
        for (Entity e : c.entities()) {
            if (!e.isValid()) {
                return false;
            }
        }
        for (Fixtures.Found f : c.fixtures()) {
            if (!f.interaction().isValid()) {
                return false;
            }
        }
        return true;
    }

    private List<BlockPos> flood(World world, BlockPos start, UUID shipId, boolean ignoreSize) throws Failure {
        List<BlockPos> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.key());
        int minX = start.x(), maxX = start.x(), minY = start.y(), maxY = start.y(), minZ = start.z(), maxZ = start.z();
        int minWorldY = world.getMinHeight(), maxWorldY = world.getMaxHeight() - 1;
        String worldName = world.getName();

        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            out.add(p);
            if (!ignoreSize && out.size() > cfg.maxBlocks) {
                throw new Failure("assemble.too-many", "max", cfg.maxBlocks);
            }
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
            if (!ignoreSize) {
                int sx = maxX - minX + 1, sz = maxZ - minZ + 1, sy = maxY - minY + 1;
                if (Math.max(sx, sz) > cfg.maxLength || Math.min(sx, sz) > cfg.maxWidth || sy > cfg.maxHeight) {
                    throw new Failure("assemble.too-large", "length", cfg.maxLength, "width", cfg.maxWidth,
                            "height", cfg.maxHeight);
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int x = p.x() + dx, y = p.y() + dy, z = p.z() + dz;
                        if (y < minWorldY || y > maxWorldY || !seen.add(BlockPos.pack(x, y, z))) {
                            continue;
                        }
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                            throw new Failure("assemble.unloaded", "x", x, "z", z);
                        }
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (!cfg.blocks.allowed(m)) {
                            continue;
                        }
                        UUID other = registry.shipAt(worldName, x, y, z);
                        if (other != null && !other.equals(shipId)) {
                            continue;
                        }
                        queue.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    // the most common height of the water surface beside the hull, found by climbing each water column
    // that touches the ship sideways. Water under the keel counts only when nothing touches the sides,
    // as with a raft: it always has the hull above it, so it would otherwise outvote the real surface
    private int waterline(World world, List<BlockPos> cells, Set<Long> occupied) throws Failure {
        Map<Integer, Integer> sides = new HashMap<>();
        Map<Integer, Integer> below = new HashMap<>();
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        int top = world.getMaxHeight() - 1;
        for (BlockPos p : cells) {
            for (int[] d : around) {
                int x = p.x() + d[0], z = p.z() + d[2];
                if (occupied.contains(BlockPos.pack(x, p.y(), z)) || !world.isChunkLoaded(x >> 4, z >> 4)
                        || !isWater(world.getBlockAt(x, p.y(), z))) {
                    continue;
                }
                int y = p.y();
                while (y < top && !occupied.contains(BlockPos.pack(x, y + 1, z)) && isWater(world.getBlockAt(x, y + 1, z))) {
                    y++;
                }
                sides.merge(y, 1, Integer::sum);
            }
            int y = p.y() - 1;
            if (!occupied.contains(BlockPos.pack(p.x(), y, p.z())) && isWater(world.getBlockAt(p.x(), y, p.z()))) {
                below.merge(y, 1, Integer::sum);
            }
        }
        Map<Integer, Integer> votes = sides.isEmpty() ? below : sides;
        if (votes.isEmpty()) {
            throw new Failure("assemble.not-in-water");
        }
        return votes.entrySet().stream().max(Map.Entry.<Integer, Integer>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey())).orElseThrow().getKey();
    }

    static boolean isWater(Block block) {
        Material m = block.getType();
        return m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS;
    }

    // cells inside the hull at or below the waterline that water from outside cannot reach without going
    // above the waterline. They are pumped dry at every mooring
    private List<BlockPos> dryInterior(World world, Set<Long> occupied, int minX, int minY, int minZ,
                                       int maxX, int maxZ, int waterY) {
        List<BlockPos> out = new ArrayList<>();
        if (waterY < minY) {
            return out;
        }
        int x0 = minX - 1, y0 = minY - 1, z0 = minZ - 1;
        int sx = maxX - minX + 3, sy = waterY - y0 + 1, sz = maxZ - minZ + 3;
        boolean[] outside = new boolean[sx * sy * sz];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    boolean border = x == 0 || z == 0 || y == 0 || x == sx - 1 || z == sz - 1;
                    if (border && !occupied.contains(BlockPos.pack(x0 + x, y0 + y, z0 + z))) {
                        outside[(x * sy + y) * sz + z] = true;
                        queue.add(new int[]{x, y, z});
                    }
                }
            }
        }
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] f : faces) {
                int x = c[0] + f[0], y = c[1] + f[1], z = c[2] + f[2];
                if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                    continue;
                }
                int idx = (x * sy + y) * sz + z;
                if (outside[idx] || occupied.contains(BlockPos.pack(x0 + x, y0 + y, z0 + z))) {
                    continue;
                }
                outside[idx] = true;
                queue.add(new int[]{x, y, z});
            }
        }
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    int wx = x0 + x, wy = y0 + y, wz = z0 + z;
                    if (!outside[(x * sy + y) * sz + z] && !occupied.contains(BlockPos.pack(wx, wy, wz))) {
                        Material m = world.getBlockAt(wx, wy, wz).getType();
                        if (m.isAir() || isWater(world.getBlockAt(wx, wy, wz))) {
                            out.add(new BlockPos(wx, wy, wz));
                        }
                    }
                }
            }
        }
        return out;
    }

    private EntityEntry entityEntry(Entity e, Set<Long> occupied, double px, int py, double pz) {
        if (Fixtures.isFixturePart(e) || Fixtures.isRiderStand(e) || e instanceof Player) {
            return null;
        }
        if (e instanceof ItemFrame frame) {
            Block attached = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
            if (!occupied.contains(BlockPos.of(attached).key())) {
                return null;
            }
            byte[] item = frame.getItem().isEmpty() ? null : frame.getItem().serializeAsBytes();
            Block cell = frame.getLocation().getBlock();
            return new EntityEntry(e instanceof GlowItemFrame ? EntityEntry.GLOW_ITEM_FRAME : EntityEntry.ITEM_FRAME,
                    cell.getX() + 0.5 - px, cell.getY() - py, cell.getZ() + 0.5 - pz, 0f, frame.getFacing(),
                    snbt(e), item);
        }
        if (e instanceof ArmorStand stand) {
            Block feet = stand.getLocation().getBlock();
            boolean standsOnShip = occupied.contains(BlockPos.of(feet.getRelative(BlockFace.DOWN)).key())
                    || occupied.contains(BlockPos.of(feet).key());
            if (!standsOnShip || !stand.getPassengers().isEmpty() || stand.createSnapshot() == null) {
                return null;
            }
            return new EntityEntry(EntityEntry.ARMOR_STAND, stand.getX() - px, stand.getY() - py, stand.getZ() - pz,
                    stand.getYaw(), null, snbt(e), null);
        }
        return null;
    }

    private static byte[] snbt(Entity e) {
        EntitySnapshot snapshot = e.createSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("entity can't be serialized: " + e.getType());
        }
        return snapshot.getAsString().getBytes(StandardCharsets.UTF_8);
    }

    private static BlockPos chestPartner(BlockPos p, Chest chest) {
        BlockFace facing = chest.getFacing();
        BlockFace side = chest.getType() == Chest.Type.LEFT ? rightOf(facing) : rightOf(facing).getOppositeFace();
        return p.offset(side.getModX(), 0, side.getModZ());
    }

    // the face on a chest's right as seen by someone looking at its front
    private static BlockFace rightOf(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private static void closeViewers(Block block) {
        if (block.getState() instanceof InventoryHolder holder) {
            for (HumanEntity viewer : new ArrayList<>(holder.getInventory().getViewers())) {
                viewer.closeInventory();
            }
        }
    }
}
