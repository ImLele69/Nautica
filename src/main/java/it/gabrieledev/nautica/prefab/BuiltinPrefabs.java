package it.gabrieledev.nautica.prefab;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.FixtureEntry;
import it.gabrieledev.nautica.blueprint.TileCodec;
import it.gabrieledev.nautica.fixture.FixtureType;
import it.gabrieledev.nautica.math.BlockPos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// the ships that come with the plugin, generated rather than stored so they stay inside the default
// limits whatever the config says. Built bow to the south (+z), keel at y = 0, then shifted so the helm
// cell is the origin.
//
// each one exercises something: the sloop is the smallest thing that sails, the schooner is all sail,
// the carrack is heavy and carries a furnace with coal for the steam engine, the stress ship sits near
// the 1,500-block limit for render and tick measurements
public final class BuiltinPrefabs {

    private BuiltinPrefabs() {
    }

    public static Map<String, Blueprint> all() {
        return all(true);
    }

    // withItems=false skips container contents, which need a running server to serialize
    public static Map<String, Blueprint> all(boolean withItems) {
        Map<String, Blueprint> out = new LinkedHashMap<>();
        out.put("sloop", sloop(withItems));
        out.put("schooner", schooner(withItems));
        out.put("carrack", carrack(withItems));
        out.put("stress", stress(withItems));
        return out;
    }

    private static Blueprint sloop(boolean items) {
        Sketch s = new Sketch(6, 3, 1, "spruce_planks", "oak_planks", "dark_oak_planks");
        s.hull();
        s.mast(2, 5, 0, "white_wool");
        s.helm(1);
        s.sign(0, "Sloop");
        return s.build(items ? chestItems() : null, false);
    }

    private static Blueprint schooner(boolean items) {
        Sketch s = new Sketch(18, 5, 2, "spruce_planks", "birch_planks", "dark_oak_planks");
        s.hull();
        s.railing(2);
        s.mast(6, 10, 1, "white_wool");
        s.mast(11, 9, 1, "white_wool");
        s.helm(2);
        s.seat(-1, 4, 270f);
        s.seat(1, 4, 90f);
        s.perch(1, 8);
        s.chest(1, 1);
        s.lantern(3);
        s.sign(0, "Schooner");
        return s.build(items ? chestItems() : null, false);
    }

    private static Blueprint carrack(boolean items) {
        Sketch s = new Sketch(24, 9, 4, "dark_oak_planks", "spruce_planks", "dark_oak_planks");
        s.hull();
        s.railing(3);
        s.castle(0, 3, "stone_bricks");
        s.castle(17, 19, "stone_bricks");
        s.ballast("cobblestone");
        s.mast(8, 9, 0, "red_wool");
        s.mast(13, 9, 0, "white_wool");
        s.helm(5);
        s.seat(-2, 7, 270f);
        s.seat(2, 7, 90f);
        s.pad(-2, 11);
        s.chest(2, 4);
        s.furnace(-2, 4);
        s.bed(2, 9);
        s.lantern(5);
        s.sign(0, "Carrack");
        return s.build(items ? chestItems() : null, items);
    }

    private static Blueprint stress(boolean items) {
        Sketch s = new Sketch(44, 11, 4, "spruce_planks", "oak_planks", "dark_oak_planks");
        s.hull();
        s.railing(3);
        s.mast(12, 13, 1, "white_wool");
        s.mast(21, 13, 1, "white_wool");
        s.mast(29, 9, 0, "white_wool");
        s.helm(4);
        s.seat(-3, 6, 270f);
        s.seat(3, 6, 90f);
        s.chest(2, 3);
        s.lantern(5);
        s.sign(0, "Stress");
        return s.build(items ? chestItems() : null, false);
    }

    private static ItemStack[] chestItems() {
        ItemStack[] items = new ItemStack[27];
        items[0] = new ItemStack(Material.BREAD, 16);
        items[1] = new ItemStack(Material.COMPASS);
        items[2] = new ItemStack(Material.MAP);
        items[13] = new ItemStack(Material.SPYGLASS);
        return items;
    }

    // a hull drawn in voxels: full deck, sides that narrow toward the keel, a bow that closes to a point
    private static final class Sketch {
        final int length, beam, depth;
        final String hullMat, deckMat, keelMat;
        final int waterline;
        final Map<Long, String> blocks = new LinkedHashMap<>();
        final Map<Long, String> tileKind = new LinkedHashMap<>();
        final Map<Long, String> signText = new LinkedHashMap<>();
        final List<long[]> dry = new ArrayList<>();
        final List<Object[]> fixtures = new ArrayList<>();
        int helmZ;

        Sketch(int length, int beam, int depth, String hull, String deck, String keel) {
            this.length = length;
            this.beam = beam;
            this.depth = depth;
            this.hullMat = "minecraft:" + hull;
            this.deckMat = "minecraft:" + deck;
            this.keelMat = "minecraft:" + keel;
            this.waterline = Math.max(1, depth - 2);
        }

        int half(int t) {
            int h = (beam - 1) / 2;
            int bowStart = (int) (length * 0.62);
            if (t >= bowStart) {
                double frac = (t - bowStart) / (double) Math.max(1, length - 1 - bowStart);
                return (int) Math.round(h * Math.cos(frac * Math.PI / 2));
            }
            return t == 0 ? Math.max(1, h - 1) : h;
        }

        int half(int t, int y) {
            if (y >= depth) {
                return half(t);
            }
            return (int) Math.round(half(t) * (0.5 + 0.5 * y / (double) depth));
        }

        boolean inside(int x, int y, int t) {
            return t >= 0 && t < length && y >= 0 && y <= depth && Math.abs(x) <= half(t, y);
        }

        void put(int x, int y, int z, String data) {
            blocks.put(BlockPos.pack(x, y, z), data.startsWith("minecraft:") ? data : "minecraft:" + data);
        }

        void hull() {
            int h = (beam - 1) / 2;
            for (int t = 0; t < length; t++) {
                for (int y = 0; y <= depth; y++) {
                    for (int x = -h; x <= h; x++) {
                        if (!inside(x, y, t)) {
                            continue;
                        }
                        boolean shell = y == depth || !inside(x + 1, y, t) || !inside(x - 1, y, t)
                                || !inside(x, y - 1, t) || !inside(x, y, t + 1) || !inside(x, y, t - 1);
                        if (shell) {
                            put(x, y, t, y == depth ? deckMat : y == 0 ? keelMat : hullMat);
                        } else if (y <= waterline) {
                            dry.add(new long[]{x, y, t});
                        }
                    }
                }
            }
        }

        void railing(int gapFromStern) {
            for (int t = 0; t < length; t++) {
                int h = half(t);
                for (int x = -h; x <= h; x++) {
                    boolean edge = Math.abs(x) == h || t == 0 || t == length - 1;
                    if (!edge || (t == gapFromStern + 3 && Math.abs(x) == h)) {
                        continue;
                    }
                    put(x, depth + 1, t, "oak_fence");
                }
            }
        }

        void castle(int from, int to, String mat) {
            for (int t = from; t <= to; t++) {
                int h = half(t);
                for (int x = -h; x <= h; x++) {
                    if (Math.abs(x) == h || t == from || t == to) {
                        put(x, depth + 1, t, mat);
                    }
                }
            }
        }

        void ballast(String mat) {
            for (long[] d : List.copyOf(dry)) {
                if (d[1] == 1) {
                    put((int) d[0], 1, (int) d[2], mat);
                    dry.remove(d);
                }
            }
        }

        void mast(int t, int height, int overhang, String sail) {
            for (int y = depth + 1; y <= depth + height; y++) {
                put(0, y, t, "spruce_log[axis=y]");
            }
            int w = half(t) + overhang;
            for (int y = depth + 3; y < depth + height; y++) {
                for (int x = -w; x <= w; x++) {
                    put(x, y, t + 1, sail);
                }
            }
            for (int x = -w; x <= w; x++) {
                put(x, depth + height, t + 1, "spruce_log[axis=x]");
            }
            put(0, depth + height + 1, t, "red_banner[rotation=8]");
        }

        void helm(int t) {
            helmZ = t;
            fixtures.add(new Object[]{FixtureType.HELM, 0, depth + 1, t, 0f});
            blocks.remove(BlockPos.pack(0, depth + 1, t));
        }

        void seat(int x, int t, float yaw) {
            fixtures.add(new Object[]{FixtureType.SEAT, x, depth + 1, t, yaw});
        }

        void pad(int x, int t) {
            fixtures.add(new Object[]{FixtureType.PAD, x, depth + 1, t, 0f});
        }

        void perch(int x, int t) {
            fixtures.add(new Object[]{FixtureType.PERCH, x, depth + 1, t, 0f});
        }

        void chest(int x, int t) {
            put(x, depth + 1, t, "chest[facing=south,type=single,waterlogged=false]");
            tileKind.put(BlockPos.pack(x, depth + 1, t), "chest");
        }

        void furnace(int x, int t) {
            put(x, depth + 1, t, "furnace[facing=south,lit=false]");
            tileKind.put(BlockPos.pack(x, depth + 1, t), "furnace");
        }

        void bed(int x, int t) {
            put(x, depth + 1, t, "red_bed[facing=south,occupied=false,part=foot]");
            put(x, depth + 1, t + 1, "red_bed[facing=south,occupied=false,part=head]");
        }

        void lantern(int t) {
            int h = half(t);
            put(h, depth + 2, t, "lantern[hanging=false,waterlogged=false]");
            put(-h, depth + 2, t, "lantern[hanging=false,waterlogged=false]");
        }

        void sign(int x, String text) {
            put(x, depth, -1, "oak_wall_sign[facing=north,waterlogged=false]");
            signText.put(BlockPos.pack(x, depth, -1), text);
        }

        Blueprint build(ItemStack[] chestItems, boolean fuel) {
            connectFences();
            int hy = depth + 1;
            Blueprint.Builder b = new Blueprint.Builder().heading(0f).waterline(waterline - hy);
            for (Map.Entry<Long, String> e : blocks.entrySet()) {
                long k = e.getKey();
                int x = BlockPos.unpackX(k), y = BlockPos.unpackY(k), z = BlockPos.unpackZ(k);
                int i = b.add(x, y - hy, z - helmZ, e.getValue());
                String kind = tileKind.get(k);
                if ("chest".equals(kind) && chestItems != null) {
                    Map<String, Object> tile = new LinkedHashMap<>();
                    tile.put("items", TileCodec.encodeItems(chestItems));
                    b.tile(i, tile);
                } else if ("furnace".equals(kind) && fuel) {
                    Map<String, Object> tile = new LinkedHashMap<>();
                    tile.put("items", TileCodec.encodeItems(new ItemStack[]{null, new ItemStack(Material.COAL, 32), null}));
                    tile.put("burn", 0);
                    tile.put("cook", 0);
                    tile.put("cook-total", 200);
                    b.tile(i, tile);
                }
                String text = signText.get(k);
                if (text != null) {
                    b.tile(i, signTile(text));
                }
            }
            for (long[] d : dry) {
                b.dry((int) d[0], (int) d[1] - hy, (int) d[2] - helmZ);
            }
            for (Object[] f : fixtures) {
                b.fixture(new FixtureEntry((FixtureType) f[0], (int) f[1], (int) f[2] - hy, (int) f[3] - helmZ,
                        (float) f[4], UUID.randomUUID()));
            }
            return b.build();
        }

        private void connectFences() {
            for (Map.Entry<Long, String> e : blocks.entrySet()) {
                if (!e.getValue().equals("minecraft:oak_fence")) {
                    continue;
                }
                long k = e.getKey();
                int x = BlockPos.unpackX(k), y = BlockPos.unpackY(k), z = BlockPos.unpackZ(k);
                e.setValue("minecraft:oak_fence[east=" + fence(x + 1, y, z) + ",north=" + fence(x, y, z - 1)
                        + ",south=" + fence(x, y, z + 1) + ",waterlogged=false,west=" + fence(x - 1, y, z) + "]");
            }
        }

        private boolean fence(int x, int y, int z) {
            String s = blocks.get(BlockPos.pack(x, y, z));
            return s != null && s.startsWith("minecraft:oak_fence");
        }

        private static Map<String, Object> signTile(String text) {
            GsonComponentSerializer gson = GsonComponentSerializer.gson();
            String empty = gson.serialize(Component.empty());
            Map<String, Object> front = new LinkedHashMap<>();
            front.put("lines", List.of(empty, gson.serialize(Component.text(text)), empty, empty));
            front.put("color", "BLACK");
            front.put("glowing", false);
            Map<String, Object> back = new LinkedHashMap<>();
            back.put("lines", List.of(empty, empty, empty, empty));
            back.put("color", "BLACK");
            back.put("glowing", false);
            Map<String, Object> tile = new LinkedHashMap<>();
            tile.put("front", front);
            tile.put("back", back);
            tile.put("waxed", false);
            return tile;
        }
    }
}
