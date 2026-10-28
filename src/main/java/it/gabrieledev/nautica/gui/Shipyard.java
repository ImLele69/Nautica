package it.gabrieledev.nautica.gui;

import it.gabrieledev.nautica.config.Messages;
import it.gabrieledev.nautica.config.NauticaConfig;
import it.gabrieledev.nautica.sail.ShipController;
import it.gabrieledev.nautica.ship.ModuleType;
import it.gabrieledev.nautica.ship.ShipRecord;
import it.gabrieledev.nautica.ship.ShipState;
import it.gabrieledev.nautica.ship.ShipStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// the shipyard: statistics, installed modules and the cost of the next level, paid from the owner's
// inventory. A chest menu for now; Paper's Dialog API is the planned replacement once it leaves
// experimental status
public final class Shipyard implements InventoryHolder {

    private static final int INFO_SLOT = 4;
    private static final int FIRST_MODULE_SLOT = 10;

    private final ShipRecord record;
    private final Inventory inventory;

    private Shipyard(ShipRecord record, Messages msg) {
        this.record = record;
        this.inventory = Bukkit.createInventory(this, 27, msg.get("shipyard.title", "ship", record.name()));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static void open(Player player, ShipRecord record, ShipController controller, NauticaConfig cfg, Messages msg) {
        if (record.state() != ShipState.DOCKED || controller.busy(record.id())) {
            msg.send(player, "helm.busy");
            return;
        }
        Shipyard menu = new Shipyard(record, msg);
        menu.fill(cfg, msg);
        player.openInventory(menu.inventory);
    }

    private void fill(NauticaConfig cfg, Messages msg) {
        inventory.clear();
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
        ShipStats s = record.stats();
        inventory.setItem(INFO_SLOT, item(Material.OAK_BOAT, record.name(), lines(msg, "shipyard.stats",
                "blocks", s.blocks(),
                "tonnage", Math.round(s.tonnage()),
                "sail", s.sailArea(),
                "ratio", String.format(Locale.ROOT, "%.3f", s.sailRatio()),
                "draft", s.draft(),
                "hold", s.hold(),
                "updated", msg.plain(s.blocks() == 0 ? "shipyard.stats-pending" : "shipyard.stats-updated"))));
        int slot = FIRST_MODULE_SLOT;
        for (ModuleType type : ModuleType.values()) {
            int level = record.module(type);
            List<String> lore = new ArrayList<>();
            lore.add(msg.plain("shipyard.level", "level", level, "max", type.maxLevel()));
            if (level >= type.maxLevel()) {
                lore.add(msg.plain("shipyard.maxed"));
            } else {
                lore.add(msg.plain("shipyard.next-level"));
                for (ItemStack cost : cfg.cost(type, level + 1)) {
                    lore.add("  " + cost.getAmount() + "x " + cost.getType().getKey().getKey().replace('_', ' '));
                }
                lore.add(msg.plain("shipyard.click"));
            }
            inventory.setItem(slot++, item(type.icon(), msg.plain("module." + type.id()), lore));
        }
    }

    private static List<String> lines(Messages msg, String key, Object... pairs) {
        return List.of(msg.plain(key, pairs).split("\n"));
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(l -> (Component) Component.text(l, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    public static final class Clicks implements Listener {

        private final ShipController controller;
        private final NauticaConfig cfg;
        private final Messages msg;

        public Clicks(ShipController controller, NauticaConfig cfg, Messages msg) {
            this.controller = controller;
            this.cfg = cfg;
            this.msg = msg;
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getInventory().getHolder(false) instanceof Shipyard) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder(false) instanceof Shipyard menu)) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != menu.inventory) {
                return;
            }
            int index = event.getRawSlot() - FIRST_MODULE_SLOT;
            if (index < 0 || index >= ModuleType.values().length) {
                return;
            }
            ModuleType type = ModuleType.values()[index];
            ShipRecord r = menu.record;
            if (r.state() != ShipState.DOCKED || controller.busy(r.id())) {
                player.closeInventory();
                return;
            }
            if (!player.hasPermission(type.permission())) {
                msg.send(player, "no-permission");
                return;
            }
            int next = r.module(type) + 1;
            if (next > type.maxLevel()) {
                return;
            }
            List<ItemStack> cost = cfg.cost(type, next);
            for (ItemStack c : cost) {
                if (!player.getInventory().containsAtLeast(c, c.getAmount())) {
                    msg.send(player, "shipyard.missing", "item", c.getType().getKey().getKey().replace('_', ' '),
                            "amount", c.getAmount());
                    return;
                }
            }
            for (ItemStack c : cost) {
                player.getInventory().removeItem(c.clone());
            }
            r.module(type, next);
            controller.save(r);
            player.playSound(player.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1f);
            msg.send(player, "shipyard.installed", "module", msg.plain("module." + type.id()), "level", next);
            menu.fill(cfg, msg);
        }
    }
}
