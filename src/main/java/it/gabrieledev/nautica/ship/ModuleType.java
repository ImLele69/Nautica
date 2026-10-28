package it.gabrieledev.nautica.ship;

import org.bukkit.Material;

import java.util.Locale;

public enum ModuleType {
    SAILS(3, Material.WHITE_WOOL),
    HELM(3, Material.SPRUCE_TRAPDOOR),
    OARS(1, Material.OAK_FENCE),
    STEAM(1, Material.BLAST_FURNACE),
    ANCHOR(1, Material.CHAIN),
    COMPASS(1, Material.COMPASS),
    HEART(1, Material.HEART_OF_THE_SEA);

    private final int maxLevel;
    private final Material icon;

    ModuleType(int maxLevel, Material icon) {
        this.maxLevel = maxLevel;
        this.icon = icon;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int maxLevel() {
        return maxLevel;
    }

    public Material icon() {
        return icon;
    }

    public String permission() {
        return "nautica.module." + id();
    }

    public static ModuleType byId(String id) {
        for (ModuleType t : values()) {
            if (t.id().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }
}
