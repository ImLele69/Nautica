package it.gabrieledev.nautica.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

// block lists from config, resolved once against the server's registries. Entries are materials or
// #tag names; whatever this server does not know is skipped with a warning, so the same file
// works on later versions
public final class BlockRules {

    private final Set<Material> allowed;
    private final Set<Material> passable;
    private final Set<Material> sail;
    private final Map<Material, Double> weights;

    private BlockRules(Set<Material> allowed, Set<Material> passable, Set<Material> sail, Map<Material, Double> weights) {
        this.allowed = allowed;
        this.passable = passable;
        this.sail = sail;
        this.weights = weights;
    }

    public static BlockRules load(ConfigurationSection section, Logger log) {
        Set<Material> allowed = resolve(section.getStringList("allowed"), "blocks.allowed", log);
        Set<Material> passable = resolve(section.getStringList("passable"), "blocks.passable", log);
        Set<Material> sail = resolve(section.getStringList("sail"), "blocks.sail", log);

        Map<Material, Double> weights = new EnumMap<>(Material.class);
        ConfigurationSection ws = section.getConfigurationSection("weights");
        if (ws != null) {
            for (String key : ws.getKeys(false)) {
                if (key.startsWith("#")) {
                    for (Material m : resolve(List.of(key), "blocks.weights", log)) {
                        weights.putIfAbsent(m, ws.getDouble(key));
                    }
                }
            }
            for (String key : ws.getKeys(false)) {
                if (!key.startsWith("#")) {
                    for (Material m : resolve(List.of(key), "blocks.weights", log)) {
                        weights.put(m, ws.getDouble(key));
                    }
                }
            }
        }
        return new BlockRules(allowed, passable, sail, weights);
    }

    private static Set<Material> resolve(List<String> entries, String where, Logger log) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String raw : entries) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(entry.substring(1));
                Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) {
                    log.warning(where + ": tag sconosciuto " + raw + ", saltato");
                    continue;
                }
                out.addAll(tag.getValues());
            } else {
                Material m = Material.matchMaterial(entry);
                if (m == null || !m.isBlock()) {
                    log.warning(where + ": blocco sconosciuto " + raw + ", saltato");
                    continue;
                }
                out.add(m);
            }
        }
        return out;
    }

    public boolean allowed(Material m) {
        return allowed.contains(m);
    }

    public boolean passable(Material m) {
        return passable.contains(m);
    }

    public boolean sail(Material m) {
        return sail.contains(m);
    }

    public double weight(Material m) {
        return weights.getOrDefault(m, 1.0);
    }

    public int allowedCount() {
        return allowed.size();
    }
}
