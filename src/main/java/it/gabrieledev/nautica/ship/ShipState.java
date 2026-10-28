package it.gabrieledev.nautica.ship;

// lifecycle of a ship. ASSEMBLING reaches disk once, with the snapshot, before any block is removed:
// recovery treats it like SAILING and finds the blocks still at the departure point
public enum ShipState {
    DOCKED,
    ASSEMBLING,
    SAILING,
    DOCKING,
    VIRTUAL_PORT;

    public boolean canMoveTo(ShipState next) {
        return switch (this) {
            case DOCKED -> next == ASSEMBLING;
            case ASSEMBLING -> next == SAILING || next == DOCKED;
            case SAILING -> next == DOCKING || next == VIRTUAL_PORT;
            case DOCKING -> next == DOCKED || next == SAILING || next == VIRTUAL_PORT;
            case VIRTUAL_PORT -> next == DOCKING || next == DOCKED;
        };
    }
}
