package it.gabrieledev.nautica.ship;

import it.gabrieledev.nautica.blueprint.Blueprint;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// numbers derived from what was built: no physics, two divisions and a table of weights
public record ShipStats(int blocks, double tonnage, int sailArea, double sailRatio, int draft, int hold) {

    public static final ShipStats EMPTY = new ShipStats(0, 0, 0, 0, 0, 0);

    public static ShipStats of(Blueprint bp, ToDoubleFunction<Material> weight, Predicate<Material> sail) {
        double tonnage = 0;
        int sailArea = 0;
        int hold = 0;
        Material[] byPalette = new Material[bp.palette().size()];
        for (int p = 0; p < byPalette.length; p++) {
            byPalette[p] = materialOf(bp.palette().get(p));
        }
        for (int i = 0; i < bp.size(); i++) {
            Material m = byPalette[bp.paletteIndex(i)];
            if (m == null) {
                tonnage += 1;
                continue;
            }
            tonnage += weight.applyAsDouble(m);
            if (sail.test(m) && bp.dy(i) >= bp.waterline() + 2) {
                sailArea++;
            }
            if (isHold(m)) {
                hold++;
            }
        }
        tonnage = Math.max(1, tonnage);
        int draft = Math.max(0, bp.waterline() - bp.minY() + 1);
        return new ShipStats(bp.size(), tonnage, sailArea, sailArea / tonnage, draft, hold);
    }

    static Material materialOf(String blockData) {
        int bracket = blockData.indexOf('[');
        String name = bracket < 0 ? blockData : blockData.substring(0, bracket);
        return Material.matchMaterial(name);
    }

    private static boolean isHold(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT);
        return (n.endsWith("chest") && m != Material.ENDER_CHEST) || n.equals("barrel") || n.endsWith("shulker_box");
    }

    public void write(ConfigurationSection s) {
        s.set("blocks", blocks);
        s.set("tonnage", tonnage);
        s.set("sail-area", sailArea);
        s.set("sail-ratio", sailRatio);
        s.set("draft", draft);
        s.set("hold", hold);
    }

    public static ShipStats read(ConfigurationSection s) {
        if (s == null) {
            return EMPTY;
        }
        return new ShipStats(s.getInt("blocks"), s.getDouble("tonnage"), s.getInt("sail-area"),
                s.getDouble("sail-ratio"), s.getInt("draft"), s.getInt("hold"));
    }
}
