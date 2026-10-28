package it.gabrieledev.nautica.ship;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipStateTest {

    @Test
    void sailingNeverGoesStraightBackToDocked() {
        assertFalse(ShipState.SAILING.canMoveTo(ShipState.DOCKED));
        assertTrue(ShipState.SAILING.canMoveTo(ShipState.DOCKING));
        assertTrue(ShipState.DOCKING.canMoveTo(ShipState.DOCKED));
    }

    @Test
    void dockedOnlyLeavesThroughAssembly() {
        assertTrue(ShipState.DOCKED.canMoveTo(ShipState.ASSEMBLING));
        assertFalse(ShipState.DOCKED.canMoveTo(ShipState.SAILING));
        assertTrue(ShipState.ASSEMBLING.canMoveTo(ShipState.DOCKED));
    }

    @Test
    void recordRejectsIllegalTransition() {
        ShipRecord r = new ShipRecord(java.util.UUID.randomUUID(), null, "x");
        assertThrows(IllegalStateException.class, () -> r.state(ShipState.SAILING));
    }
}
