package it.gabrieledev.nautica.sail;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.config.BlockRules;
import it.gabrieledev.nautica.math.BlockPos;
import org.bukkit.World;

// tests the hull shell against the world at the real heading, not at the nearest cardinal one.
// A cell is tested only when the move takes it into a block it was not in already; the test uses the
// centre of each hull block, which is what keeps a ship sailing at 45 degrees out of the shore
final class CollisionModel {

    enum Result {
        CLEAR,
        BLOCKED,
        GROUNDED,
        UNLOADED
    }

    private final int[] dx, dy, dz;
    private final boolean[] keel;
    private final long[] current;
    private final long[] candidate;
    private final BlockRules rules;
    private final float blueprintHeading;

    CollisionModel(Blueprint bp, BlockRules rules) {
        int[] shell = bp.shell();
        dx = new int[shell.length];
        dy = new int[shell.length];
        dz = new int[shell.length];
        keel = new boolean[shell.length];
        for (int i = 0; i < shell.length; i++) {
            dx[i] = bp.dx(shell[i]);
            dy[i] = bp.dy(shell[i]);
            dz[i] = bp.dz(shell[i]);
            keel[i] = dy[i] == bp.minY();
        }
        current = new long[shell.length];
        candidate = new long[shell.length];
        this.rules = rules;
        this.blueprintHeading = bp.heading();
    }

    // records the cells of the starting pose without testing them: the ship is already there
    void place(double px, double py, double pz, float heading) {
        compute(px, py, pz, heading, current);
    }

    Result test(World world, double px, double py, double pz, float heading) {
        compute(px, py, pz, heading, candidate);
        boolean keelOnly = true;
        boolean blocked = false;
        for (int i = 0; i < candidate.length; i++) {
            long key = candidate[i];
            if (key == current[i]) {
                continue;
            }
            int x = BlockPos.unpackX(key), y = BlockPos.unpackY(key), z = BlockPos.unpackZ(key);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return Result.UNLOADED;
            }
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return Result.BLOCKED;
            }
            if (!rules.passable(world.getBlockAt(x, y, z).getType())) {
                blocked = true;
                keelOnly &= keel[i];
            }
        }
        if (blocked) {
            return keelOnly ? Result.GROUNDED : Result.BLOCKED;
        }
        return Result.CLEAR;
    }

    // makes the last tested pose the current one
    void commit() {
        System.arraycopy(candidate, 0, current, 0, current.length);
    }

    private void compute(double px, double py, double pz, float heading, long[] out) {
        double rad = Math.toRadians(heading - blueprintHeading);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        for (int i = 0; i < dx.length; i++) {
            double x = px + dx[i] * cos - dz[i] * sin;
            double z = pz + dx[i] * sin + dz[i] * cos;
            out[i] = BlockPos.pack((int) Math.floor(x), (int) Math.floor(py + dy[i] + 0.5), (int) Math.floor(z));
        }
    }
}
