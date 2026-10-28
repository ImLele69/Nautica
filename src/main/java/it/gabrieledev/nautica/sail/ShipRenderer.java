package it.gabrieledev.nautica.sail;

import it.gabrieledev.nautica.Keys;
import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.EntityEntry;
import it.gabrieledev.nautica.blueprint.FixtureEntry;
import it.gabrieledev.nautica.config.NauticaConfig;
import it.gabrieledev.nautica.fixture.FixtureModel;
import it.gabrieledev.nautica.math.Rot;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// the ship while it sails: one invisible root entity and every display riding it as a passenger, so that
// moving the ship is one teleport. Each display keeps its transform in the blueprint frame; a turn
// rewrites the transforms with the ship's rotation in front.
//
// Blocks the client draws with a block entity renderer (chests, beds, banners, heads, signs, pots) are
// invisible in a block display, so they become item displays or, for signs, a plate with a text display.
// Their orientation offsets live in config because they can only be checked by eye
final class ShipRenderer {

    private final NauticaConfig cfg;
    private final List<Display> displays = new ArrayList<>();
    private final List<Matrix4f> locals = new ArrayList<>();
    private ItemDisplay root;

    ShipRenderer(NauticaConfig cfg) {
        this.cfg = cfg;
    }

    ItemDisplay root() {
        return root;
    }

    int size() {
        return displays.size();
    }

    void spawn(World world, Location at, Blueprint bp, Quaternionf rotation, UUID shipId) {
        root = world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(cfg.teleportDuration);
            tag(d, shipId);
        });
        for (int i = 0; i < bp.size(); i++) {
            BlockData data = Bukkit.createBlockData(bp.blockData(i));
            addBlock(world, at, bp, i, data, rotation, shipId);
        }
        for (FixtureEntry f : bp.fixtures()) {
            Matrix4f base = new Matrix4f().translate(f.dx(), f.dy(), f.dz()).rotate(Rot.yaw(f.yaw()));
            for (FixtureModel.Part part : FixtureModel.of(f.type()).parts()) {
                Matrix4f local = new Matrix4f(base).mul(part.local());
                add(world, at, BlockDisplay.class, d -> d.setBlock(Bukkit.createBlockData(part.blockData())), local, rotation, shipId, 0);
            }
        }
        for (EntityEntry e : bp.entities()) {
            if (e.hanging() && e.renderItem() != null) {
                ItemStack item = ItemStack.deserializeBytes(e.renderItem());
                Matrix4f local = new Matrix4f().translate((float) e.dx(), (float) e.dy() + 0.5f, (float) e.dz());
                faceTowards(local, e.facing());
                local.translate(0, 0, -0.44f).scale(0.5f);
                add(world, at, ItemDisplay.class, d -> {
                    d.setItemStack(item);
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                }, local, rotation, shipId, 0);
            }
        }
    }

    private void addBlock(World world, Location at, Blueprint bp, int i, BlockData data, Quaternionf rotation, UUID shipId) {
        Material m = data.getMaterial();
        float x = bp.dx(i), y = bp.dy(i), z = bp.dz(i);
        int light = data.getLightEmission();
        Map<String, Object> tile = bp.tile(i);

        if (Tag.ALL_SIGNS.isTagged(m)) {
            addSign(world, at, data, tile, x, y, z, rotation, shipId);
            return;
        }
        String kind = itemKind(m);
        if (kind != null) {
            if (data instanceof Bed bed && bed.getPart() == Bed.Part.HEAD) {
                return;
            }
            ItemStack item = renderItem(m, tile);
            Matrix4f local = new Matrix4f().translate(x, y + 0.5f, z)
                    .rotate(Rot.yaw(facingYaw(data) + cfg.itemYaw(kind)));
            if (kind.equals("head") && data instanceof Directional) {
                local.translate(0, 0, -0.25f);
            }
            add(world, at, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            }, local, rotation, shipId, light);
            return;
        }
        Matrix4f local = new Matrix4f().translate(x - 0.5f, y, z - 0.5f);
        add(world, at, BlockDisplay.class, d -> d.setBlock(data), local, rotation, shipId, light);
    }

    private void addSign(World world, Location at, BlockData data, Map<String, Object> tile, float x, float y, float z,
                         Quaternionf rotation, UUID shipId) {
        BlockData plank = Bukkit.createBlockData(planksFor(data.getMaterial()));
        Matrix4f frame = new Matrix4f().translate(x, y, z).rotate(Rot.yaw(facingYaw(data)));
        float textZ;
        float textY;
        if (data instanceof WallSign) {
            add(world, at, BlockDisplay.class, d -> d.setBlock(plank),
                    new Matrix4f(frame).translate(-0.5f, 0.25f, -0.5f).scale(1f, 0.5f, 0.125f), rotation, shipId, 0);
            textZ = -0.37f;
            textY = 0.5f;
        } else if (data instanceof HangingSign || data instanceof WallHangingSign) {
            add(world, at, BlockDisplay.class, d -> d.setBlock(plank),
                    new Matrix4f(frame).translate(-0.44f, 0f, -0.0625f).scale(0.88f, 0.62f, 0.125f), rotation, shipId, 0);
            textZ = 0.07f;
            textY = 0.31f;
        } else {
            add(world, at, BlockDisplay.class, d -> d.setBlock(plank),
                    new Matrix4f(frame).translate(-0.0625f, 0f, -0.0625f).scale(0.125f, 0.55f, 0.125f), rotation, shipId, 0);
            add(world, at, BlockDisplay.class, d -> d.setBlock(plank),
                    new Matrix4f(frame).translate(-0.5f, 0.55f, -0.0625f).scale(1f, 0.5f, 0.125f), rotation, shipId, 0);
            textZ = 0.07f;
            textY = 0.8f;
        }
        Component text = signText(tile);
        if (text != null) {
            add(world, at, TextDisplay.class, d -> {
                d.text(text);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(false);
            }, new Matrix4f(frame).translate(0, textY - 0.12f, textZ).scale(0.32f), rotation, shipId, 0);
        }
    }

    private <T extends Display> void add(World world, Location at, Class<T> type, java.util.function.Consumer<T> setup,
                                         Matrix4f local, Quaternionf rotation, UUID shipId, int light) {
        T display = world.spawn(at, type, d -> {
            setup.accept(d);
            d.setPersistent(false);
            d.setViewRange(cfg.viewRange);
            d.setShadowRadius(0);
            d.setTransformationMatrix(new Matrix4f().rotate(rotation).mul(local));
            if (light > 0) {
                d.setBrightness(new Display.Brightness(light, 15));
            }
            tag(d, shipId);
        });
        root.addPassenger(display);
        displays.add(display);
        locals.add(local);
    }

    void move(Location at) {
        root.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    void rotate(Quaternionf rotation, int ticks) {
        for (int i = 0; i < displays.size(); i++) {
            Display d = displays.get(i);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformationMatrix(new Matrix4f().rotate(rotation).mul(locals.get(i)));
        }
    }

    void remove() {
        for (Display d : displays) {
            d.remove();
        }
        displays.clear();
        locals.clear();
        if (root != null) {
            root.remove();
            root = null;
        }
    }

    static boolean isRender(Entity e) {
        return e.getPersistentDataContainer().has(Keys.RENDER, PersistentDataType.STRING);
    }

    private static void tag(Entity e, UUID shipId) {
        e.getPersistentDataContainer().set(Keys.RENDER, PersistentDataType.STRING, shipId.toString());
    }

    private static String itemKind(Material m) {
        String n = m.name();
        if (m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.ENDER_CHEST || n.endsWith("SHULKER_BOX")) {
            return "chest";
        }
        if (Tag.BEDS.isTagged(m)) {
            return "bed";
        }
        if (Tag.BANNERS.isTagged(m)) {
            return "banner";
        }
        if (n.endsWith("_HEAD") || n.endsWith("_SKULL")) {
            return "head";
        }
        if (m == Material.DECORATED_POT) {
            return "pot";
        }
        return null;
    }

    private static ItemStack renderItem(Material block, Map<String, Object> tile) {
        Material itemType = block.isItem() ? block : Bukkit.createBlockData(block).getPlacementMaterial();
        if (!itemType.isItem() || itemType == Material.AIR) {
            itemType = Material.matchMaterial(block.name().replace("_WALL_", "_"));
        }
        ItemStack item = new ItemStack(itemType == null ? Material.BARRIER : itemType);
        if (tile != null && item.getItemMeta() instanceof BannerMeta meta && tile.get("patterns") instanceof List<?> list) {
            var registry = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.BANNER_PATTERN);
            for (Object o : list) {
                String[] p = String.valueOf(o).split(" ");
                var key = p.length == 2 ? org.bukkit.NamespacedKey.fromString(p[0]) : null;
                var type = key == null ? null : registry.get(key);
                if (type != null) {
                    meta.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.valueOf(p[1]), type));
                }
            }
            item.setItemMeta(meta);
        }
        if (tile != null && item.getItemMeta() instanceof SkullMeta meta && tile.get("profile") instanceof Map<?, ?> pm) {
            Object id = pm.get("id");
            Object name = pm.get("name");
            var profile = Bukkit.createProfile(id == null ? null : UUID.fromString(id.toString()),
                    name == null ? null : name.toString());
            if (pm.get("properties") instanceof List<?> props) {
                for (Object o : props) {
                    if (o instanceof Map<?, ?> pp) {
                        profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty(
                                String.valueOf(pp.get("name")), String.valueOf(pp.get("value")),
                                pp.get("signature") == null ? null : String.valueOf(pp.get("signature"))));
                    }
                }
            }
            meta.setPlayerProfile(profile);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component signText(Map<String, Object> tile) {
        if (tile == null || !(tile.get("front") instanceof Map<?, ?> front) || !(front.get("lines") instanceof List<?> lines)) {
            return null;
        }
        Component out = Component.empty();
        boolean any = false;
        for (int i = 0; i < lines.size(); i++) {
            Component line = GsonComponentSerializer.gson().deserialize(String.valueOf(lines.get(i)));
            any |= !net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).isBlank();
            out = i == 0 ? line : out.append(Component.newline()).append(line);
        }
        return any ? out : null;
    }

    private static Material planksFor(Material sign) {
        String n = sign.name().toLowerCase(Locale.ROOT)
                .replace("_wall_hanging_sign", "").replace("_hanging_sign", "")
                .replace("_wall_sign", "").replace("_sign", "");
        Material m = Material.matchMaterial(n + "_planks");
        if (m == null) {
            m = Material.matchMaterial(n + "_block");
        }
        return m == null || !m.isBlock() ? Material.OAK_PLANKS : m;
    }

    // heading the front of the block faces, in the blueprint frame
    static float facingYaw(BlockData data) {
        BlockFace face = null;
        if (data instanceof Directional d) {
            face = d.getFacing();
        } else if (data instanceof Rotatable r) {
            face = r.getRotation();
        }
        if (face == null || face == BlockFace.UP || face == BlockFace.DOWN || face == BlockFace.SELF) {
            return 0f;
        }
        return Rot.headingTo(face.getModX() == 0 && face.getModZ() == 0 ? 0 : face.getDirection().getX(),
                face.getDirection().getZ());
    }

    private static void faceTowards(Matrix4f local, BlockFace facing) {
        if (facing == BlockFace.UP) {
            local.rotateX((float) Math.toRadians(-90));
        } else if (facing == BlockFace.DOWN) {
            local.rotateX((float) Math.toRadians(90));
        } else if (facing != null) {
            local.rotate(Rot.yaw(Rot.headingOf(facing)));
        }
    }
}
