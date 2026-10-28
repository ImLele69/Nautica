package it.gabrieledev.nautica.math;

import org.bukkit.configuration.ConfigurationSection;

// where a ship stands. A docked pose has (x, z) on the centre of the pivot block and a cardinal heading;
// a sailing pose can be anywhere
public record Pose(double x, double y, double z, float heading) {

    public static Pose docked(BlockPos pivot, float heading) {
        return new Pose(pivot.x() + 0.5, pivot.y(), pivot.z() + 0.5, Rot.snapCardinal(heading));
    }

    public BlockPos pivotBlock() {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public Pose snapped() {
        return docked(pivotBlock(), heading);
    }

    public void write(ConfigurationSection section) {
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("heading", (double) heading);
    }

    public static Pose read(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        return new Pose(section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("heading"));
    }
}
