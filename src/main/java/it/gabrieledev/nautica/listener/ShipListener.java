package it.gabrieledev.nautica.listener;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import it.gabrieledev.nautica.Keys;
import it.gabrieledev.nautica.Perms;
import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.config.Messages;
import it.gabrieledev.nautica.config.NauticaConfig;
import it.gabrieledev.nautica.fixture.FixtureType;
import it.gabrieledev.nautica.fixture.Fixtures;
import it.gabrieledev.nautica.gui.Shipyard;
import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.math.Pose;
import it.gabrieledev.nautica.math.Rot;
import it.gabrieledev.nautica.sail.ShipController;
import it.gabrieledev.nautica.sail.Voyage;
import it.gabrieledev.nautica.ship.ShipRecord;
import it.gabrieledev.nautica.ship.ShipRegistry;
import it.gabrieledev.nautica.ship.ShipState;
import it.gabrieledev.nautica.world.Protection;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

public final class ShipListener implements Listener {

    private final Plugin plugin;
    private final NauticaConfig cfg;
    private final Messages msg;
    private final ShipRegistry registry;
    private final ShipController controller;
    private final Protection protection;

    public ShipListener(Plugin plugin, NauticaConfig cfg, Messages msg, ShipRegistry registry,
                        ShipController controller, Protection protection) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.msg = msg;
        this.registry = registry;
        this.controller = controller;
        this.protection = protection;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        FixtureType type = Fixtures.itemType(item);
        if (type == null) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getBlockFace() != BlockFace.UP) {
            msg.send(player, "fixture.place-on-top");
            return;
        }
        Block cell = clicked.getRelative(BlockFace.UP);
        BlockPos pos = BlockPos.of(cell);
        if (!cell.getType().isAir() || Fixtures.at(cell.getWorld(), pos) != null) {
            msg.send(player, "fixture.occupied");
            return;
        }
        if (!player.hasPermission(Perms.BYPASS_CLAIM) && !protection.canPlace(player, cell, item.getType())) {
            msg.send(player, "fixture.no-build");
            return;
        }
        float facing = Rot.snapCardinal(player.getYaw());
        float yaw = type == FixtureType.HELM ? facing : Rot.wrap(facing + 180f);
        UUID ship = null;
        if (type == FixtureType.HELM) {
            ShipRecord r = registry.get(Fixtures.itemShip(item));
            boolean reuse = r != null && r.state() == ShipState.DOCKED
                    && (player.getUniqueId().equals(r.owner()) || player.hasPermission(Perms.ADMIN))
                    && !controller.busy(r.id());
            if (!reuse) {
                r = controller.newShip(player, msg.plain("ship.default-name", "player", player.getName()));
            }
            r.world(cell.getWorld().getName());
            r.docked(Pose.docked(pos, yaw));
            r.position(r.docked());
            controller.save(r);
            ship = r.id();
            msg.send(player, reuse ? "fixture.helm-restored" : "fixture.helm-placed", "ship", r.name());
        }
        Fixtures.spawn(cell.getWorld(), pos, type, yaw, UUID.randomUUID(), ship);
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.subtract(1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction) || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Fixtures.Found f = Fixtures.read(event.getRightClicked());
        if (f == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        switch (f.type()) {
            case HELM -> {
                ShipRecord r = registry.get(f.ship());
                if (player.isSneaking()) {
                    if (r == null) {
                        msg.send(player, "helm.orphan");
                    } else if (!player.getUniqueId().equals(r.owner()) && !player.hasPermission(Perms.ADMIN)) {
                        msg.send(player, "not-owner");
                    } else {
                        Shipyard.open(player, r, controller, cfg, msg);
                    }
                    return;
                }
                if (r == null && player.hasPermission(Perms.ADMIN)) {
                    ShipRecord adopted = controller.newShip(player, msg.plain("ship.default-name", "player", player.getName()));
                    adopted.world(f.interaction().getWorld().getName());
                    adopted.docked(Pose.docked(f.cell(), f.yaw()));
                    controller.save(adopted);
                    Fixtures.bind(f.interaction().getWorld(), f.cell(), f.id(), adopted.id());
                    msg.send(player, "helm.adopted", "ship", adopted.name());
                    return;
                }
                controller.depart(player, f);
            }
            case SEAT -> sit(player, f);
            case PAD -> msg.send(player, "fixture.pad-info");
            case PERCH -> msg.send(player, "fixture.perch-info");
        }
    }

    private void sit(Player player, Fixtures.Found f) {
        if (player.isInsideVehicle()) {
            return;
        }
        ShipRecord r = registry.get(f.ship());
        if (cfg.passengersCrewOnly && r != null && !r.canPilot(player.getUniqueId()) && !player.hasPermission(Perms.ADMIN)) {
            msg.send(player, "seat.crew-only");
            return;
        }
        World world = f.interaction().getWorld();
        for (Entity e : world.getNearbyEntities(Fixtures.cellBase(world, f.cell()).add(0, 0.5, 0), 0.8, 1.5, 0.8)) {
            if (Fixtures.isRiderStand(e) && !e.getPassengers().isEmpty()) {
                msg.send(player, "seat.taken");
                return;
            }
        }
        Location spot = Fixtures.riderSpot(Fixtures.cellBase(world, f.cell()), f.type(), f.yaw(), cfg.seatYOffset);
        ArmorStand stand = Fixtures.spawnRiderStand(spot, f.id());
        stand.addPassenger(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Interaction interaction)) {
            return;
        }
        Fixtures.Found f = Fixtures.read(interaction);
        if (f == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        World world = interaction.getWorld();
        if (f.type() == FixtureType.HELM) {
            ShipRecord r = registry.get(f.ship());
            if (r != null) {
                if (!player.getUniqueId().equals(r.owner()) && !player.hasPermission(Perms.ADMIN)) {
                    msg.send(player, "not-owner");
                    return;
                }
                if (r.state() != ShipState.DOCKED || controller.busy(r.id())) {
                    msg.send(player, "helm.busy");
                    return;
                }
            }
            Fixtures.remove(world, f.cell(), f.id());
            ItemStack drop = r == null ? Fixtures.item(FixtureType.HELM) : Fixtures.helmFor(r.id(), r.name());
            give(player, drop);
            msg.send(player, "fixture.helm-picked");
            return;
        }
        Block below = f.cell().offset(0, -1, 0).block(world);
        if (!player.hasPermission(Perms.BYPASS_CLAIM) && !protection.canBreak(player, below)) {
            msg.send(player, "fixture.no-build");
            return;
        }
        for (Entity e : world.getNearbyEntities(Fixtures.cellBase(world, f.cell()).add(0, 0.5, 0), 0.8, 1.5, 0.8)) {
            if (Fixtures.isRiderStand(e)) {
                e.eject();
                e.remove();
            }
        }
        Fixtures.remove(world, f.cell(), f.id());
        give(player, Fixtures.item(f.type()));
    }

    private static void give(Player player, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE && player.getInventory().containsAtLeast(item, 1)) {
            return;
        }
        player.getInventory().addItem(item).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        Voyage v = controller.piloting(event.getPlayer());
        if (v != null) {
            v.input(event.getInput());
        }
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (controller.internal()) {
            return;
        }
        Entity vehicle = event.getDismounted();
        Entity rider = event.getEntity();
        Voyage v = controller.byStand(vehicle);
        if (v != null) {
            if (rider instanceof Player p && v.isPilot(p)) {
                if (p.isOnline() && !p.isDead() && v.sneakPressed()) {
                    controller.requestDock(v);
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (controller.voyage(v.record().id()) == v) {
                            controller.pilotLeft(v, p);
                        }
                    });
                }
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> controller.riderLeft(v, rider));
            }
            return;
        }
        if (Fixtures.isRiderStand(vehicle)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (vehicle.getPassengers().isEmpty()) {
                    vehicle.remove();
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Voyage v = controller.piloting(p);
        if (v != null) {
            controller.pilotLeft(v, p);
            return;
        }
        Entity vehicle = p.getVehicle();
        if (vehicle != null && Fixtures.isRiderStand(vehicle) && controller.byStand(vehicle) == null) {
            vehicle.eject();
            vehicle.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Voyage v = controller.piloting(event.getPlayer());
        if (v != null) {
            controller.pilotLeft(v, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (isOurs(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isOurs(event.getEntity()) || Fixtures.isFixturePart(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private static boolean isOurs(Entity e) {
        return Fixtures.isRiderStand(e) || e.getPersistentDataContainer().has(Keys.RENDER, PersistentDataType.STRING);
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        controller.onWorldSaved(event.getWorld());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        for (ShipRecord r : registry.all()) {
            r.beds().remove(player);
        }
        Location loc = event.getLocation();
        if (event.getCause() != PlayerSetSpawnEvent.Cause.BED || loc == null) {
            return;
        }
        UUID shipId = registry.shipAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        ShipRecord r = registry.get(shipId);
        if (r == null || r.docked() == null) {
            return;
        }
        Blueprint bp = controller.blueprintFor(r);
        if (bp == null) {
            return;
        }
        BlockPos pivot = r.docked().pivotBlock();
        int back = Math.floorMod(-Rot.quarterTurns(bp.heading(), r.docked().heading()), 4);
        int dx = loc.getBlockX() - pivot.x(), dz = loc.getBlockZ() - pivot.z();
        r.beds().put(player, new BlockPos(Rot.rotX(dx, dz, back), loc.getBlockY() - pivot.y(), Rot.rotZ(dx, dz, back)));
        controller.save(r);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        for (ShipRecord r : registry.all()) {
            BlockPos bed = controller.currentBed(r, p.getUniqueId());
            World w = r.world() == null ? null : Bukkit.getWorld(r.world());
            if (bed != null && w != null) {
                p.setRespawnLocation(new Location(w, bed.x(), bed.y(), bed.z()), true);
                return;
            }
        }
    }
}
