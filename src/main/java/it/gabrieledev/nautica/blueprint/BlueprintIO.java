package it.gabrieledev.nautica.blueprint;

import it.gabrieledev.nautica.fixture.FixtureType;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// text format shared by snapshots and prefabs. Cells are packed in one line ("dx,dy,dz,p;...") so a
// 1,500-block ship stays a readable file of a few hundred lines, most of them tile data
public final class BlueprintIO {

    private BlueprintIO() {
    }

    public static String write(Blueprint bp) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", Blueprint.FORMAT);
        yaml.set("heading", (double) bp.heading());
        yaml.set("waterline", bp.waterline());
        yaml.set("palette", bp.palette());

        StringBuilder sb = new StringBuilder(bp.size() * 12);
        int[] c = bp.rawCells();
        for (int i = 0; i < c.length; i += 4) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(c[i]).append(',').append(c[i + 1]).append(',').append(c[i + 2]).append(',').append(c[i + 3]);
        }
        yaml.set("cells", sb.toString());

        sb.setLength(0);
        int[] d = bp.rawDry();
        for (int i = 0; i < d.length; i += 3) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(d[i]).append(',').append(d[i + 1]).append(',').append(d[i + 2]);
        }
        yaml.set("dry", sb.toString());

        ConfigurationSection tiles = yaml.createSection("tiles");
        bp.tiles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> tiles.set(String.valueOf(e.getKey()), e.getValue()));

        List<Map<String, Object>> entities = new ArrayList<>();
        for (EntityEntry e : bp.entities()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.kind());
            m.put("x", e.dx());
            m.put("y", e.dy());
            m.put("z", e.dz());
            m.put("yaw", (double) e.yaw());
            if (e.facing() != null) {
                m.put("facing", e.facing().name());
            }
            m.put("data", Base64.getEncoder().encodeToString(e.data()));
            if (e.renderItem() != null) {
                m.put("item", Base64.getEncoder().encodeToString(e.renderItem()));
            }
            entities.add(m);
        }
        yaml.set("entities", entities);

        List<Map<String, Object>> fixtures = new ArrayList<>();
        for (FixtureEntry f : bp.fixtures()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", f.type().id());
            m.put("x", f.dx());
            m.put("y", f.dy());
            m.put("z", f.dz());
            m.put("yaw", (double) f.yaw());
            m.put("id", f.id().toString());
            fixtures.add(m);
        }
        yaml.set("fixtures", fixtures);
        return yaml.saveToString();
    }

    public static Blueprint read(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        int format = yaml.getInt("format", -1);
        if (format != Blueprint.FORMAT) {
            throw new InvalidConfigurationException("unsupported blueprint format " + format);
        }
        List<String> palette = yaml.getStringList("palette");

        String cellText = yaml.getString("cells", "");
        String[] parts = cellText.isEmpty() ? new String[0] : cellText.split(";");
        int[] cells = new int[parts.length * 4];
        for (int i = 0; i < parts.length; i++) {
            String[] n = parts[i].split(",");
            for (int k = 0; k < 4; k++) {
                cells[i * 4 + k] = Integer.parseInt(n[k].trim());
            }
            if (cells[i * 4 + 3] < 0 || cells[i * 4 + 3] >= palette.size()) {
                throw new InvalidConfigurationException("cell " + i + " points outside the palette");
            }
        }

        String dryText = yaml.getString("dry", "");
        String[] dryParts = dryText.isEmpty() ? new String[0] : dryText.split(";");
        int[] dry = new int[dryParts.length * 3];
        for (int i = 0; i < dryParts.length; i++) {
            String[] n = dryParts[i].split(",");
            for (int k = 0; k < 3; k++) {
                dry[i * 3 + k] = Integer.parseInt(n[k].trim());
            }
        }

        Map<Integer, Map<String, Object>> tiles = new HashMap<>();
        ConfigurationSection ts = yaml.getConfigurationSection("tiles");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                ConfigurationSection t = ts.getConfigurationSection(key);
                if (t != null) {
                    tiles.put(Integer.parseInt(key), toPlainMap(t));
                }
            }
        }

        List<EntityEntry> entities = new ArrayList<>();
        for (Map<?, ?> m : yaml.getMapList("entities")) {
            String facing = (String) m.get("facing");
            String item = (String) m.get("item");
            entities.add(new EntityEntry(
                    (String) m.get("kind"),
                    num(m.get("x")), num(m.get("y")), num(m.get("z")),
                    (float) num(m.get("yaw")),
                    facing == null ? null : BlockFace.valueOf(facing),
                    Base64.getDecoder().decode((String) m.get("data")),
                    item == null ? null : Base64.getDecoder().decode(item)));
        }

        List<FixtureEntry> fixtures = new ArrayList<>();
        for (Map<?, ?> m : yaml.getMapList("fixtures")) {
            FixtureType type = FixtureType.byId(String.valueOf(m.get("type")));
            if (type == null) {
                continue;
            }
            Object id = m.get("id");
            fixtures.add(new FixtureEntry(type,
                    (int) num(m.get("x")), (int) num(m.get("y")), (int) num(m.get("z")),
                    (float) num(m.get("yaw")),
                    id == null ? UUID.randomUUID() : UUID.fromString(id.toString())));
        }

        return new Blueprint(palette, cells, tiles, entities, fixtures, dry,
                yaml.getInt("waterline"), (float) yaml.getDouble("heading"));
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static Map<String, Object> toPlainMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object v = section.get(key);
            out.put(key, v instanceof ConfigurationSection s ? toPlainMap(s) : v);
        }
        return out;
    }
}
