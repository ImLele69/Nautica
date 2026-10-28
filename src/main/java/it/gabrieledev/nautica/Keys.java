package it.gabrieledev.nautica;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

public final class Keys {

    public static NamespacedKey ITEM;
    public static NamespacedKey ITEM_SHIP;
    public static NamespacedKey FIXTURE_ID;
    public static NamespacedKey FIXTURE_TYPE;
    public static NamespacedKey FIXTURE_YAW;
    public static NamespacedKey SHIP;
    public static NamespacedKey RENDER;
    public static NamespacedKey SEAT;

    private Keys() {
    }

    static void init(Plugin plugin) {
        ITEM = new NamespacedKey(plugin, "item");
        ITEM_SHIP = new NamespacedKey(plugin, "item_ship");
        FIXTURE_ID = new NamespacedKey(plugin, "fixture_id");
        FIXTURE_TYPE = new NamespacedKey(plugin, "fixture_type");
        FIXTURE_YAW = new NamespacedKey(plugin, "fixture_yaw");
        SHIP = new NamespacedKey(plugin, "ship");
        RENDER = new NamespacedKey(plugin, "render");
        SEAT = new NamespacedKey(plugin, "seat");
    }
}
