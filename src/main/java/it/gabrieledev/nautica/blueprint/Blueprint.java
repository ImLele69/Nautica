package it.gabrieledev.nautica.blueprint;

import it.gabrieledev.nautica.fixture.FixtureType;
import it.gabrieledev.nautica.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// a ship as data, in the frame it had when it was captured: cell offsets are relative to the pivot block
// (the cell the helm stands in) and heading() is the cardinal heading the bow had then.
//
// Everything is immutable except the tile data, which the steam engine draws fuel from while sailing;
// markDirty() tells the snapshot store to rewrite it
public final class Blueprint {

    public static final int FORMAT = 1;

    private final List<String> palette;
    private final int[] cells;
    private final Map<Integer, Map<String, Object>> tiles;
    private final List<EntityEntry> entities;
    private final List<FixtureEntry> fixtures;
    private final int[] dry;
    private final int waterline;
    private final float heading;

    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private int[] shell;
    private volatile boolean dirty;

    public Blueprint(List<String> palette, int[] cells, Map<Integer, Map<String, Object>> tiles,
                     List<EntityEntry> entities, List<FixtureEntry> fixtures, int[] dry, int waterline, float heading) {
        if (cells.length % 4 != 0 || dry.length % 3 != 0) {
            throw new IllegalArgumentException("malformed cell arrays");
        }
        this.palette = List.copyOf(palette);
        this.cells = cells;
        this.tiles = new HashMap<>(tiles);
        this.entities = List.copyOf(entities);
        this.fixtures = List.copyOf(fixtures);
        this.dry = dry;
        this.waterline = waterline;
        this.heading = heading;

        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i < size(); i++) {
            x0 = Math.min(x0, dx(i));
            y0 = Math.min(y0, dy(i));
            z0 = Math.min(z0, dz(i));
            x1 = Math.max(x1, dx(i));
            y1 = Math.max(y1, dy(i));
            z1 = Math.max(z1, dz(i));
        }
        for (FixtureEntry f : fixtures) {
            y1 = Math.max(y1, f.dy());
        }
        minX = x0;
        minY = y0;
        minZ = z0;
        maxX = x1;
        maxY = y1;
        maxZ = z1;
    }

    public int size() {
        return cells.length / 4;
    }

    public int dx(int i) {
        return cells[i * 4];
    }

    public int dy(int i) {
        return cells[i * 4 + 1];
    }

    public int dz(int i) {
        return cells[i * 4 + 2];
    }

    public int paletteIndex(int i) {
        return cells[i * 4 + 3];
    }

    public String blockData(int i) {
        return palette.get(paletteIndex(i));
    }

    public List<String> palette() {
        return palette;
    }

    int[] rawCells() {
        return cells;
    }

    public Map<String, Object> tile(int i) {
        return tiles.get(i);
    }

    public Map<Integer, Map<String, Object>> tiles() {
        return Collections.unmodifiableMap(tiles);
    }

    public void replaceTile(int i, Map<String, Object> data) {
        tiles.put(i, new LinkedHashMap<>(data));
        dirty = true;
    }

    public List<EntityEntry> entities() {
        return entities;
    }

    public List<FixtureEntry> fixtures() {
        return fixtures;
    }

    public FixtureEntry helm() {
        for (FixtureEntry f : fixtures) {
            if (f.type() == FixtureType.HELM) {
                return f;
            }
        }
        return null;
    }

    public int dryCount() {
        return dry.length / 3;
    }

    public int dryX(int i) {
        return dry[i * 3];
    }

    public int dryY(int i) {
        return dry[i * 3 + 1];
    }

    public int dryZ(int i) {
        return dry[i * 3 + 2];
    }

    int[] rawDry() {
        return dry;
    }

    // offset (dy) of the water surface block the ship floats in
    public int waterline() {
        return waterline;
    }

    public float heading() {
        return heading;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        dirty = true;
    }

    public void clearDirty() {
        dirty = false;
    }

    // horizontal distance from the pivot to the farthest cell, used for chunk tickets
    public double radius() {
        double r = 0;
        for (int i = 0; i < size(); i++) {
            r = Math.max(r, Math.hypot(dx(i), dz(i)));
        }
        return r + 1;
    }

    // indices of cells with at least one face not touching another cell of the ship
    public int[] shell() {
        if (shell == null) {
            Set<Long> occupied = new HashSet<>(size() * 2);
            for (int i = 0; i < size(); i++) {
                occupied.add(BlockPos.pack(dx(i), dy(i), dz(i)));
            }
            List<Integer> out = new ArrayList<>();
            int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int i = 0; i < size(); i++) {
                for (int[] f : faces) {
                    if (!occupied.contains(BlockPos.pack(dx(i) + f[0], dy(i) + f[1], dz(i) + f[2]))) {
                        out.add(i);
                        break;
                    }
                }
            }
            shell = out.stream().mapToInt(Integer::intValue).toArray();
        }
        return shell;
    }

    public static final class Builder {
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> paletteIndex = new HashMap<>();
        private final List<int[]> cells = new ArrayList<>();
        private final Map<Integer, Map<String, Object>> tiles = new HashMap<>();
        private final List<EntityEntry> entities = new ArrayList<>();
        private final List<FixtureEntry> fixtures = new ArrayList<>();
        private final List<int[]> dry = new ArrayList<>();
        private int waterline;
        private float heading;

        public int add(int dx, int dy, int dz, String blockData) {
            int p = paletteIndex.computeIfAbsent(blockData, k -> {
                palette.add(k);
                return palette.size() - 1;
            });
            cells.add(new int[]{dx, dy, dz, p});
            return cells.size() - 1;
        }

        public Builder tile(int cellIndex, Map<String, Object> data) {
            if (data != null && !data.isEmpty()) {
                tiles.put(cellIndex, data);
            }
            return this;
        }

        public Builder entity(EntityEntry entry) {
            entities.add(entry);
            return this;
        }

        public Builder fixture(FixtureEntry entry) {
            fixtures.add(entry);
            return this;
        }

        public Builder dry(int dx, int dy, int dz) {
            dry.add(new int[]{dx, dy, dz});
            return this;
        }

        public Builder waterline(int waterline) {
            this.waterline = waterline;
            return this;
        }

        public Builder heading(float heading) {
            this.heading = heading;
            return this;
        }

        public int cellCount() {
            return cells.size();
        }

        public Blueprint build() {
            int[] c = new int[cells.size() * 4];
            for (int i = 0; i < cells.size(); i++) {
                System.arraycopy(cells.get(i), 0, c, i * 4, 4);
            }
            int[] d = new int[dry.size() * 3];
            for (int i = 0; i < dry.size(); i++) {
                System.arraycopy(dry.get(i), 0, d, i * 3, 3);
            }
            return new Blueprint(palette, c, tiles, entities, fixtures, d, waterline, heading);
        }
    }
}
