package it.gabrieledev.nautica.blueprint;

import org.bukkit.block.BlockFace;

// an entity carried by the ship. Offsets are relative to the pivot, like block cells.
// renderItem is what a frame shows while sailing; null for armor stands
public record EntityEntry(String kind, double dx, double dy, double dz, float yaw, BlockFace facing,
                          byte[] data, byte[] renderItem) {

    public static final String ITEM_FRAME = "item_frame";
    public static final String GLOW_ITEM_FRAME = "glow_item_frame";
    public static final String ARMOR_STAND = "armor_stand";

    public boolean hanging() {
        return kind.equals(ITEM_FRAME) || kind.equals(GLOW_ITEM_FRAME);
    }
}
