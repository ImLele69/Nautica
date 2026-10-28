package it.gabrieledev.nautica.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockPosTest {

    @Test
    void packRoundTripsNegativeCoordinates() {
        int[][] cases = {{0, 0, 0}, {-1, -64, -1}, {30_000_000, 319, -30_000_000}, {-12, 5, 7}};
        for (int[] c : cases) {
            long k = BlockPos.pack(c[0], c[1], c[2]);
            assertEquals(c[0], BlockPos.unpackX(k));
            assertEquals(c[1], BlockPos.unpackY(k));
            assertEquals(c[2], BlockPos.unpackZ(k));
        }
    }

    @Test
    void poseSnapsToPivotCentreAndCardinal() {
        Pose p = new Pose(10.9, 62, -3.2, 97f).snapped();
        assertEquals(10.5, p.x());
        assertEquals(-3.5, p.z());
        assertEquals(90f, p.heading());
        assertEquals(new BlockPos(10, 62, -4), p.pivotBlock());
    }
}
