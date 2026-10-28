package it.gabrieledev.nautica.math;

import org.bukkit.block.BlockFace;
import org.bukkit.block.structure.StructureRotation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RotTest {

    @Test
    void forwardFollowsMinecraftYaw() {
        assertEquals(0, Rot.forwardX(0), 1e-9);
        assertEquals(1, Rot.forwardZ(0), 1e-9);
        assertEquals(-1, Rot.forwardX(90), 1e-9);
        assertEquals(-1, Rot.forwardZ(180), 1e-9);
        assertEquals(1, Rot.forwardX(270), 1e-9);
    }

    @Test
    void quarterTurnMatchesClockwiseStructureRotation() {
        assertEquals(-3, Rot.rotX(2, 3, 1));
        assertEquals(2, Rot.rotZ(2, 3, 1));
        assertEquals(StructureRotation.CLOCKWISE_90, Rot.structureRotation(1));
        assertEquals(BlockFace.WEST, Rot.rotate(BlockFace.SOUTH, 1));
        assertEquals(BlockFace.EAST, Rot.rotate(BlockFace.NORTH, 1));
        for (int k = 0; k < 4; k++) {
            int x = 5, z = -2;
            for (int i = 0; i < k; i++) {
                int nx = -z;
                z = x;
                x = nx;
            }
            assertEquals(x, Rot.rotX(5, -2, k));
            assertEquals(z, Rot.rotZ(5, -2, k));
        }
    }

    @Test
    void continuousRotationAgreesWithQuarterTurns() {
        for (int k = 0; k < 4; k++) {
            double rad = Math.toRadians(90 * k);
            assertEquals(Rot.rotX(4, 7, k), Rot.rotX(4.0, 7.0, Math.cos(rad), Math.sin(rad)), 1e-9);
            assertEquals(Rot.rotZ(4, 7, k), Rot.rotZ(4.0, 7.0, Math.cos(rad), Math.sin(rad)), 1e-9);
        }
    }

    @Test
    void quaternionTurnsForwardVectorToNewHeading() {
        for (float h : new float[]{0, 45, 90, 180, 270, 333}) {
            Quaternionf q = Rot.yaw(h);
            Vector3f f = q.transform(new Vector3f(0, 0, 1));
            assertEquals(Rot.forwardX(h), f.x, 1e-5);
            assertEquals(Rot.forwardZ(h), f.z, 1e-5);
        }
    }

    @Test
    void quaternionAgreesWithOffsetRotation() {
        float d = 37f;
        Vector3f v = Rot.yaw(d).transform(new Vector3f(3, 0, -5));
        double rad = Math.toRadians(d);
        assertEquals(Rot.rotX(3, -5, Math.cos(rad), Math.sin(rad)), v.x, 1e-5);
        assertEquals(Rot.rotZ(3, -5, Math.cos(rad), Math.sin(rad)), v.z, 1e-5);
    }

    @Test
    void headingHelpers() {
        assertEquals(90f, Rot.snapCardinal(100f));
        assertEquals(0f, Rot.snapCardinal(350f));
        assertEquals(-20f, Rot.delta(10f, 350f));
        assertEquals(1, Rot.quarterTurns(0f, 90f));
        assertEquals(3, Rot.quarterTurns(0f, 270f));
        assertEquals(2, Rot.quarterTurns(270f, 90f));
        assertEquals(0f, Rot.headingTo(0, 1), 1e-4);
        assertEquals(90f, Rot.headingTo(-1, 0), 1e-4);
        assertEquals(270f, Rot.headingTo(1, 0), 1e-4);
        assertEquals("N", Rot.compass(180f));
        assertEquals("W", Rot.compass(95f));
        assertEquals("SE", Rot.compass(310f));
        assertEquals(180f, Rot.headingOf(BlockFace.NORTH));
        assertEquals(BlockFace.EAST, Rot.faceOf(268f));
    }
}
