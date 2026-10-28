package it.gabrieledev.nautica.prefab;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.BlueprintIO;
import it.gabrieledev.nautica.fixture.FixtureType;
import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.ship.ShipStats;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinPrefabsTest {

    private static double weight(Material m) {
        String n = m.name();
        if (n.endsWith("WOOL")) {
            return 0.3;
        }
        if (n.contains("STONE") || n.contains("COBBLE")) {
            return 3.0;
        }
        return 1.0;
    }

    @Test
    void builtinsRespectDefaultLimits() {
        for (Map.Entry<String, Blueprint> e : BuiltinPrefabs.all(false).entrySet()) {
            Blueprint bp = e.getValue();
            int sx = bp.maxX() - bp.minX() + 1, sz = bp.maxZ() - bp.minZ() + 1, sy = bp.maxY() - bp.minY() + 1;
            ShipStats st = ShipStats.of(bp, BuiltinPrefabsTest::weight, m -> m.name().endsWith("WOOL") || m.name().endsWith("BANNER"));
            System.out.printf("%-8s %5d blocks  %2dx%2dx%2d  tonnage %.0f  sail %d  ratio %.3f  draft %d  dry %d%n",
                    e.getKey(), bp.size(), Math.min(sx, sz), Math.max(sx, sz), sy, st.tonnage(), st.sailArea(),
                    st.sailRatio(), st.draft(), bp.dryCount());
            assertTrue(bp.size() <= 1500, e.getKey() + " over 1500 blocks");
            assertTrue(Math.max(sx, sz) <= 64 && Math.min(sx, sz) <= 24 && sy <= 32, e.getKey() + " too big");
        }
    }

    @Test
    void helmSitsOnDeckAtOrigin() {
        for (Map.Entry<String, Blueprint> e : BuiltinPrefabs.all(false).entrySet()) {
            Blueprint bp = e.getValue();
            assertNotNull(bp.helm(), e.getKey());
            assertEquals(0, bp.helm().dx());
            assertEquals(0, bp.helm().dy());
            assertEquals(0, bp.helm().dz());
            Set<Long> cells = new HashSet<>();
            for (int i = 0; i < bp.size(); i++) {
                cells.add(BlockPos.pack(bp.dx(i), bp.dy(i), bp.dz(i)));
            }
            assertTrue(cells.contains(BlockPos.pack(0, -1, 0)), e.getKey() + ": no deck under the helm");
            for (var f : bp.fixtures()) {
                assertFalse(cells.contains(BlockPos.pack(f.dx(), f.dy(), f.dz())), e.getKey() + ": " + f.type() + " inside a block");
                assertTrue(cells.contains(BlockPos.pack(f.dx(), f.dy() - 1, f.dz())), e.getKey() + ": " + f.type() + " floating");
            }
            assertEquals(1, bp.fixtures().stream().filter(f -> f.type() == FixtureType.HELM).count());
        }
    }

    @Test
    void builtinsFloat() {
        for (Map.Entry<String, Blueprint> e : BuiltinPrefabs.all(false).entrySet()) {
            Blueprint bp = e.getValue();
            assertTrue(bp.waterline() >= bp.minY(), e.getKey() + ": waterline below the keel");
            assertTrue(bp.waterline() < 0, e.getKey() + ": deck has to be above water");
        }
    }

    @Test
    void blueprintTextRoundTrip() throws Exception {
        Blueprint bp = BuiltinPrefabs.all(false).get("schooner");
        Blueprint back = BlueprintIO.read(BlueprintIO.write(bp));
        assertEquals(bp.size(), back.size());
        assertEquals(bp.dryCount(), back.dryCount());
        assertEquals(bp.waterline(), back.waterline());
        assertEquals(bp.heading(), back.heading());
        assertEquals(bp.fixtures(), back.fixtures());
        assertEquals(bp.tiles().keySet(), back.tiles().keySet());
        for (int i = 0; i < bp.size(); i++) {
            assertEquals(bp.blockData(i), back.blockData(i));
            assertEquals(bp.dx(i), back.dx(i));
            assertEquals(bp.dy(i), back.dy(i));
            assertEquals(bp.dz(i), back.dz(i));
        }
        assertEquals(bp.tiles(), back.tiles());
    }
}
