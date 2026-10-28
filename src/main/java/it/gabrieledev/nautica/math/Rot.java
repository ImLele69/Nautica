package it.gabrieledev.nautica.math;

import org.bukkit.block.BlockFace;
import org.bukkit.block.structure.StructureRotation;
import org.joml.Quaternionf;

// headings follow Minecraft yaw: 0 = south (+z), 90 = west, 180 = north, 270 = east.
// Turning a ship by +d degrees maps an offset (x, z) to (x cos d - z sin d, x sin d + z cos d),
// so a quarter turn takes (x, z) to (-z, x), same as StructureRotation.CLOCKWISE_90
public final class Rot {

    private Rot() {
    }

    public static float wrap(float degrees) {
        float d = degrees % 360f;
        return d < 0 ? d + 360f : d;
    }

    // signed difference to - from, in (-180, 180]
    public static float delta(float from, float to) {
        float d = wrap(to - from);
        return d > 180f ? d - 360f : d;
    }

    public static float snapCardinal(float heading) {
        return wrap(Math.round(wrap(heading) / 90f) * 90f);
    }

    public static int quarterTurns(float fromHeading, float toHeading) {
        return Math.floorMod(Math.round(delta(fromHeading, toHeading) / 90f), 4);
    }

    public static double forwardX(float heading) {
        return -Math.sin(Math.toRadians(heading));
    }

    public static double forwardZ(float heading) {
        return Math.cos(Math.toRadians(heading));
    }

    public static int rotX(int x, int z, int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 0 -> x;
            case 1 -> -z;
            case 2 -> -x;
            default -> z;
        };
    }

    public static int rotZ(int x, int z, int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 0 -> z;
            case 1 -> x;
            case 2 -> -z;
            default -> -x;
        };
    }

    public static double rotX(double x, double z, double cos, double sin) {
        return x * cos - z * sin;
    }

    public static double rotZ(double x, double z, double cos, double sin) {
        return x * sin + z * cos;
    }

    public static StructureRotation structureRotation(int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 0 -> StructureRotation.NONE;
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            default -> StructureRotation.COUNTERCLOCKWISE_90;
        };
    }

    public static BlockFace rotate(BlockFace face, int quarterTurns) {
        BlockFace f = face;
        for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) {
            f = switch (f) {
                case NORTH -> BlockFace.EAST;
                case EAST -> BlockFace.SOUTH;
                case SOUTH -> BlockFace.WEST;
                case WEST -> BlockFace.NORTH;
                default -> f;
            };
        }
        return f;
    }

    public static float headingOf(BlockFace face) {
        return switch (face) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
    }

    public static BlockFace faceOf(float heading) {
        return switch (Math.round(wrap(heading) / 90f) % 4) {
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.EAST;
            default -> BlockFace.SOUTH;
        };
    }

    // rotation that turns the canonical frame by degrees of heading (yaw)
    public static Quaternionf yaw(float degrees) {
        return new Quaternionf().rotateY((float) -Math.toRadians(degrees));
    }

    public static String compass(float heading) {
        String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        return names[Math.round(wrap(heading) / 45f) % 8];
    }

    // heading of the vector (dx, dz), the inverse of forwardX/forwardZ
    public static float headingTo(double dx, double dz) {
        return wrap((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }
}
