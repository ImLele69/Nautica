package it.gabrieledev.nautica.blueprint;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.block.TileStateInventoryHolder;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Nameable;
import org.bukkit.Registry;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Campfire;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Furnace;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// block entity data as plain maps, one handler per vanilla type. Anything not listed here is moved as its
// block data only: a block entity this codec does not know loses its contents, so the allowed-blocks list
// should not contain one
public final class TileCodec {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private TileCodec() {
    }

    public static Map<String, Object> capture(BlockState st) {
        if (!(st instanceof TileState)) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (st instanceof Nameable n && n.customName() != null) {
            m.put("name", GSON.serialize(n.customName()));
        }
        if (st instanceof TileStateInventoryHolder h) {
            ItemStack[] items = h.getSnapshotInventory().getContents();
            if (hasItems(items)) {
                m.put("items", encodeItems(items));
            }
        }
        if (st instanceof Furnace f) {
            m.put("burn", (int) f.getBurnTime());
            m.put("cook", (int) f.getCookTime());
            m.put("cook-total", f.getCookTimeTotal());
        }
        if (st instanceof BrewingStand b) {
            m.put("brew", b.getBrewingTime());
            m.put("fuel", b.getFuelLevel());
        }
        if (st instanceof Lectern l) {
            m.put("page", l.getPage());
        }
        if (st instanceof Campfire c) {
            ItemStack[] items = new ItemStack[c.getSize()];
            List<Integer> cook = new ArrayList<>();
            List<Integer> total = new ArrayList<>();
            for (int i = 0; i < c.getSize(); i++) {
                items[i] = c.getItem(i);
                cook.add(c.getCookTime(i));
                total.add(c.getCookTimeTotal(i));
            }
            if (hasItems(items)) {
                m.put("items", encodeItems(items));
                m.put("cook", cook);
                m.put("cook-total", total);
            }
        }
        if (st instanceof Sign s) {
            for (Side side : Side.values()) {
                SignSide ss = s.getSide(side);
                Map<String, Object> sm = new LinkedHashMap<>();
                List<String> lines = new ArrayList<>();
                for (Component c : ss.lines()) {
                    lines.add(GSON.serialize(c));
                }
                sm.put("lines", lines);
                if (ss.getColor() != null) {
                    sm.put("color", ss.getColor().name());
                }
                sm.put("glowing", ss.isGlowingText());
                m.put(side.name().toLowerCase(Locale.ROOT), sm);
            }
            m.put("waxed", s.isWaxed());
        }
        if (st instanceof Banner b) {
            List<String> patterns = new ArrayList<>();
            for (Pattern p : b.getPatterns()) {
                NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN).getKey(p.getPattern());
                if (key != null) {
                    patterns.add(key.asString() + " " + p.getColor().name());
                }
            }
            if (!patterns.isEmpty()) {
                m.put("patterns", patterns);
            }
        }
        if (st instanceof Skull s) {
            PlayerProfile profile = s.getPlayerProfile();
            if (profile != null) {
                Map<String, Object> pm = new LinkedHashMap<>();
                if (profile.getId() != null) {
                    pm.put("id", profile.getId().toString());
                }
                if (profile.getName() != null) {
                    pm.put("name", profile.getName());
                }
                List<Map<String, Object>> props = new ArrayList<>();
                for (ProfileProperty p : profile.getProperties()) {
                    Map<String, Object> pp = new LinkedHashMap<>();
                    pp.put("name", p.getName());
                    pp.put("value", p.getValue());
                    if (p.getSignature() != null) {
                        pp.put("signature", p.getSignature());
                    }
                    props.add(pp);
                }
                pm.put("properties", props);
                m.put("profile", pm);
            }
            if (s.getNoteBlockSound() != null) {
                m.put("note-sound", s.getNoteBlockSound().asString());
            }
        }
        if (st instanceof DecoratedPot p) {
            Map<String, Object> sherds = new LinkedHashMap<>();
            p.getSherds().forEach((side, mat) -> sherds.put(side.name(), mat.getKey().asString()));
            m.put("sherds", sherds);
        }
        return m.isEmpty() ? null : m;
    }

    // writes the data into the block entity already placed at block
    @SuppressWarnings("unchecked")
    public static void apply(Block block, Map<String, Object> m) {
        if (m == null) {
            return;
        }
        BlockState st = block.getState();
        if (!(st instanceof TileState)) {
            return;
        }
        if (st instanceof Nameable n && m.get("name") instanceof String json) {
            n.customName(GSON.deserialize(json));
        }
        if (st instanceof TileStateInventoryHolder h && !(st instanceof Campfire) && m.get("items") instanceof String s) {
            Inventory inv = h.getSnapshotInventory();
            ItemStack[] items = decodeItems(s);
            ItemStack[] fitted = new ItemStack[inv.getSize()];
            System.arraycopy(items, 0, fitted, 0, Math.min(items.length, fitted.length));
            inv.setContents(fitted);
        }
        if (st instanceof Furnace f) {
            f.setBurnTime((short) intOf(m.get("burn")));
            f.setCookTime((short) intOf(m.get("cook")));
            f.setCookTimeTotal(intOf(m.get("cook-total")));
        }
        if (st instanceof BrewingStand b) {
            b.setBrewingTime(intOf(m.get("brew")));
            b.setFuelLevel(intOf(m.get("fuel")));
        }
        if (st instanceof Campfire c && m.get("items") instanceof String s) {
            ItemStack[] items = decodeItems(s);
            List<?> cook = m.get("cook") instanceof List<?> l ? l : List.of();
            List<?> total = m.get("cook-total") instanceof List<?> l ? l : List.of();
            for (int i = 0; i < Math.min(items.length, c.getSize()); i++) {
                c.setItem(i, items[i].isEmpty() ? null : items[i]);
                if (i < cook.size()) {
                    c.setCookTime(i, intOf(cook.get(i)));
                }
                if (i < total.size()) {
                    c.setCookTimeTotal(i, intOf(total.get(i)));
                }
            }
        }
        if (st instanceof Sign s) {
            for (Side side : Side.values()) {
                if (!(m.get(side.name().toLowerCase(Locale.ROOT)) instanceof Map<?, ?> sm)) {
                    continue;
                }
                SignSide ss = s.getSide(side);
                if (sm.get("lines") instanceof List<?> lines) {
                    for (int i = 0; i < Math.min(4, lines.size()); i++) {
                        ss.line(i, GSON.deserialize(String.valueOf(lines.get(i))));
                    }
                }
                if (sm.get("color") instanceof String color) {
                    ss.setColor(DyeColor.valueOf(color));
                }
                ss.setGlowingText(Boolean.TRUE.equals(sm.get("glowing")));
            }
            s.setWaxed(Boolean.TRUE.equals(m.get("waxed")));
        }
        if (st instanceof Banner b && m.get("patterns") instanceof List<?> list) {
            Registry<PatternType> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN);
            List<Pattern> patterns = new ArrayList<>();
            for (Object o : list) {
                String[] p = String.valueOf(o).split(" ");
                NamespacedKey key = p.length == 2 ? NamespacedKey.fromString(p[0]) : null;
                PatternType type = key == null ? null : registry.get(key);
                if (type != null) {
                    patterns.add(new Pattern(DyeColor.valueOf(p[1]), type));
                }
            }
            b.setPatterns(patterns);
        }
        if (st instanceof Skull s) {
            if (m.get("profile") instanceof Map<?, ?> pm) {
                Object id = pm.get("id");
                Object name = pm.get("name");
                PlayerProfile profile = Bukkit.createProfile(
                        id == null ? null : UUID.fromString(id.toString()),
                        name == null ? null : name.toString());
                if (pm.get("properties") instanceof List<?> props) {
                    for (Object o : props) {
                        Map<String, Object> pp = (Map<String, Object>) o;
                        profile.setProperty(new ProfileProperty(String.valueOf(pp.get("name")),
                                String.valueOf(pp.get("value")),
                                pp.get("signature") == null ? null : String.valueOf(pp.get("signature"))));
                    }
                }
                s.setPlayerProfile(profile);
            }
            if (m.get("note-sound") instanceof String sound) {
                s.setNoteBlockSound(NamespacedKey.fromString(sound));
            }
        }
        if (st instanceof DecoratedPot p && m.get("sherds") instanceof Map<?, ?> sherds) {
            sherds.forEach((side, mat) -> {
                NamespacedKey key = NamespacedKey.fromString(String.valueOf(mat));
                Material material = key == null ? null : Registry.MATERIAL.get(key);
                if (material != null) {
                    p.setSherd(DecoratedPot.Side.valueOf(String.valueOf(side)), material);
                }
            });
        }
        st.update(true, false);
    }

    // empties the block entity in place, so that removing the block afterwards cannot drop what the
    // snapshot already holds
    public static void clear(Block block) {
        BlockState st = block.getState();
        boolean changed = false;
        if (st instanceof TileStateInventoryHolder h && !(st instanceof Campfire)) {
            h.getSnapshotInventory().clear();
            changed = true;
        }
        if (st instanceof Campfire c) {
            for (int i = 0; i < c.getSize(); i++) {
                c.setItem(i, null);
            }
            changed = true;
        }
        if (changed) {
            st.update(true, false);
        }
    }

    public static boolean holdsItems(Map<String, Object> tile) {
        return tile != null && tile.containsKey("items");
    }

    public static String encodeItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? ItemStack.empty() : items[i];
        }
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(copy));
    }

    public static ItemStack[] decodeItems(String base64) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(base64));
    }

    private static boolean hasItems(ItemStack[] items) {
        for (ItemStack i : items) {
            if (i != null && !i.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
