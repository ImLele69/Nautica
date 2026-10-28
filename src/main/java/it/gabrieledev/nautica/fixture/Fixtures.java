package it.gabrieledev.nautica.fixture;

import it.gabrieledev.nautica.Keys;
import it.gabrieledev.nautica.config.Messages;
import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.math.Rot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// fixtures standing in the world while a ship is docked: one Interaction for clicks plus the display parts
// of the model, all tagged with the same fixture id. Removal always goes by that exact id, never by
// proximity, so other plugins' displays are never touched
public final class Fixtures {

    public record Found(FixtureType type, UUID id, UUID ship, float yaw, BlockPos cell, Interaction interaction) {
    }

    // item names come from the language file. static because items get built from
    // places that have no plugin at hand (recipes, the shipyard, helm pickup)
    private static Messages messages;

    private Fixtures() {
    }

    public static void useMessages(Messages m) {
        messages = m;
    }

    public static ItemStack item(FixtureType type) {
        ItemStack item = new ItemStack(switch (type) {
            case HELM -> Material.SPRUCE_TRAPDOOR;
            case SEAT -> Material.SPRUCE_SLAB;
            case PAD -> Material.HAY_BLOCK;
            case PERCH -> Material.SPRUCE_FENCE;
        });
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(displayName(type), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(lore(type), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(Keys.ITEM, PersistentDataType.STRING, type.id());
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    // a helm picked up from a ship keeps the ship's id, so placing it again brings back name, crew and modules
    public static ItemStack helmFor(UUID shipId, String shipName) {
        ItemStack item = item(FixtureType.HELM);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.ITEM_SHIP, PersistentDataType.STRING, shipId.toString());
        meta.lore(List.of(
                Component.text(lore(FixtureType.HELM), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(messages.plain("item.helm-ship", "ship", shipName), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    public static FixtureType itemType(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(Keys.ITEM, PersistentDataType.STRING);
        return id == null ? null : FixtureType.byId(id);
    }

    public static UUID itemShip(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(Keys.ITEM_SHIP, PersistentDataType.STRING);
        return id == null ? null : UUID.fromString(id);
    }

    public static String displayName(FixtureType type) {
        return messages.plain("item." + type.id() + ".name");
    }

    private static String lore(FixtureType type) {
        return messages.plain("item." + type.id() + ".lore");
    }

    public static Location cellBase(World world, BlockPos cell) {
        return new Location(world, cell.x() + 0.5, cell.y(), cell.z() + 0.5, 0f, 0f);
    }

    public static void spawn(World world, BlockPos cell, FixtureType type, float yaw, UUID fixtureId, UUID ship) {
        Location base = cellBase(world, cell);
        FixtureModel.Shape shape = FixtureModel.of(type);
        Interaction interaction = world.spawn(base, Interaction.class, it -> {
            it.setInteractionWidth(shape.width());
            it.setInteractionHeight(shape.height());
            it.setResponsive(true);
            it.setPersistent(true);
            tag(it.getPersistentDataContainer(), type, fixtureId, ship, yaw);
        });
        Matrix4f turn = new Matrix4f().rotate(Rot.yaw(yaw));
        for (FixtureModel.Part part : shape.parts()) {
            world.spawn(base, BlockDisplay.class, d -> {
                d.setBlock(Bukkit.createBlockData(part.blockData()));
                d.setTransformationMatrix(new Matrix4f(turn).mul(part.local()));
                d.setPersistent(true);
                tag(d.getPersistentDataContainer(), type, fixtureId, ship, yaw);
            });
        }
        interaction.setRotation(0f, 0f);
    }

    private static void tag(PersistentDataContainer pdc, FixtureType type, UUID fixtureId, UUID ship, float yaw) {
        pdc.set(Keys.FIXTURE_ID, PersistentDataType.STRING, fixtureId.toString());
        pdc.set(Keys.FIXTURE_TYPE, PersistentDataType.STRING, type.id());
        pdc.set(Keys.FIXTURE_YAW, PersistentDataType.FLOAT, yaw);
        if (ship != null) {
            pdc.set(Keys.SHIP, PersistentDataType.STRING, ship.toString());
        } else {
            pdc.remove(Keys.SHIP);
        }
    }

    public static Found read(Entity e) {
        if (!(e instanceof Interaction interaction)) {
            return null;
        }
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        String id = pdc.get(Keys.FIXTURE_ID, PersistentDataType.STRING);
        FixtureType type = FixtureType.byId(pdc.getOrDefault(Keys.FIXTURE_TYPE, PersistentDataType.STRING, ""));
        if (id == null || type == null) {
            return null;
        }
        String ship = pdc.get(Keys.SHIP, PersistentDataType.STRING);
        Location l = e.getLocation();
        return new Found(type, UUID.fromString(id), ship == null ? null : UUID.fromString(ship),
                pdc.getOrDefault(Keys.FIXTURE_YAW, PersistentDataType.FLOAT, 0f),
                new BlockPos(l.getBlockX(), (int) Math.floor(l.getY() + 1e-3), l.getBlockZ()), interaction);
    }

    public static boolean isFixturePart(Entity e) {
        return e.getPersistentDataContainer().has(Keys.FIXTURE_ID, PersistentDataType.STRING);
    }

    public static List<Entity> parts(World world, BlockPos cell, UUID fixtureId) {
        List<Entity> out = new ArrayList<>();
        String id = fixtureId.toString();
        for (Entity e : world.getNearbyEntities(cellBase(world, cell).add(0, 0.5, 0), 1.5, 2.0, 1.5)) {
            if (id.equals(e.getPersistentDataContainer().get(Keys.FIXTURE_ID, PersistentDataType.STRING))) {
                out.add(e);
            }
        }
        return out;
    }

    public static void remove(World world, BlockPos cell, UUID fixtureId) {
        for (Entity e : parts(world, cell, fixtureId)) {
            e.remove();
        }
    }

    public static void bind(World world, BlockPos cell, UUID fixtureId, UUID ship) {
        for (Entity e : parts(world, cell, fixtureId)) {
            if (ship == null) {
                e.getPersistentDataContainer().remove(Keys.SHIP);
            } else {
                e.getPersistentDataContainer().set(Keys.SHIP, PersistentDataType.STRING, ship.toString());
            }
        }
    }

    public static List<Found> inBox(World world, BoundingBox box) {
        List<Found> out = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(box, en -> en instanceof Interaction)) {
            Found f = read(e);
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

    public static Found at(World world, BlockPos cell) {
        for (Entity e : world.getNearbyEntities(cellBase(world, cell).add(0, 0.5, 0), 0.6, 1.0, 0.6,
                en -> en instanceof Interaction)) {
            Found f = read(e);
            if (f != null && f.cell().equals(cell)) {
                return f;
            }
        }
        return null;
    }

    // an invisible stand to sit on. Never persistent: a crash must not leave seats floating at sea
    public static ArmorStand spawnRiderStand(Location at, UUID fixtureId) {
        return at.getWorld().spawn(at, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setSmall(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setBasePlate(false);
            s.setCanTick(false);
            s.setCanMove(false);
            s.setDisabledSlots(org.bukkit.inventory.EquipmentSlot.values());
            s.setPersistent(false);
            s.getPersistentDataContainer().set(Keys.SEAT, PersistentDataType.STRING, fixtureId.toString());
        });
    }

    public static boolean isRiderStand(Entity e) {
        return e instanceof ArmorStand && e.getPersistentDataContainer().has(Keys.SEAT, PersistentDataType.STRING);
    }

    // rider stand position for a fixture whose cell base is at base
    public static Location riderSpot(Location base, FixtureType type, float yaw, double seatYOffset) {
        float[] o = FixtureModel.riderOffset(type);
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        Location l = base.clone().add(Rot.rotX(o[0], o[2], cos, sin), o[1] + seatYOffset, Rot.rotZ(o[0], o[2], cos, sin));
        l.setYaw(yaw);
        return l;
    }
}
