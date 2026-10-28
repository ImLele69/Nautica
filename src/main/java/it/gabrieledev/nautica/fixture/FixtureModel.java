package it.gabrieledev.nautica.fixture;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

// vanilla-only models, built from scaled block displays. Coordinates are relative to the bottom centre
// of the fixture's cell, facing south (+z is the bow for the helm)
public final class FixtureModel {

    public record Part(String blockData, Matrix4f local) {
    }

    public record Shape(List<Part> parts, float width, float height) {
    }

    private static final Shape HELM;
    private static final Shape SEAT;
    private static final Shape PAD;
    private static final Shape PERCH;

    static {
        List<Part> helm = new ArrayList<>();
        helm.add(box("minecraft:spruce_log[axis=y]", -0.09f, 0f, 0.22f, 0.18f, 0.92f, 0.18f));
        float hx = 0f, hy = 1.02f, hz = 0.2f;
        helm.add(new Part("minecraft:spruce_planks",
                new Matrix4f().translate(hx, hy, hz).translate(-0.1f, -0.1f, -0.08f).scale(0.2f, 0.2f, 0.16f)));
        for (int i = 0; i < 4; i++) {
            float a = (float) Math.toRadians(i * 45);
            helm.add(new Part("minecraft:dark_oak_planks",
                    new Matrix4f().translate(hx, hy, hz).rotateZ(a).translate(-0.52f, -0.035f, -0.035f).scale(1.04f, 0.07f, 0.07f)));
        }
        for (int i = 0; i < 8; i++) {
            float a = (float) Math.toRadians(i * 45 + 22.5);
            helm.add(new Part("minecraft:dark_oak_planks",
                    new Matrix4f().translate(hx, hy, hz).rotateZ(a).translate(-0.16f, 0.36f, -0.045f).scale(0.32f, 0.07f, 0.09f)));
        }
        HELM = new Shape(helm, 0.9f, 1.45f);

        SEAT = new Shape(List.of(
                box("minecraft:spruce_slab[type=bottom]", -0.42f, 0f, -0.42f, 0.84f, 1f, 0.84f),
                box("minecraft:red_carpet", -0.4f, 0.5f, -0.4f, 0.8f, 1f, 0.8f)), 0.9f, 0.6f);

        PAD = new Shape(List.of(
                box("minecraft:hay_block[axis=y]", -0.46f, 0f, -0.46f, 0.92f, 0.12f, 0.92f)), 1f, 0.3f);

        PERCH = new Shape(List.of(
                box("minecraft:spruce_fence", -0.3f, 0f, -0.3f, 0.6f, 0.9f, 0.6f),
                box("minecraft:stripped_spruce_log[axis=x]", -0.32f, 0.9f, -0.05f, 0.64f, 0.1f, 0.1f)), 0.6f, 1.1f);
    }

    private FixtureModel() {
    }

    public static Shape of(FixtureType type) {
        return switch (type) {
            case HELM -> HELM;
            case SEAT -> SEAT;
            case PAD -> PAD;
            case PERCH -> PERCH;
        };
    }

    // where a rider sits, relative to the bottom centre of the cell, before the fixture's yaw
    public static float[] riderOffset(FixtureType type) {
        return switch (type) {
            case HELM -> new float[]{0f, 0f, -0.2f};
            case PERCH -> new float[]{0f, 0.55f, 0f};
            default -> new float[]{0f, 0f, 0f};
        };
    }

    private static Part box(String data, float x, float y, float z, float sx, float sy, float sz) {
        return new Part(data, new Matrix4f().translate(x, y, z).scale(sx, sy, sz));
    }
}
