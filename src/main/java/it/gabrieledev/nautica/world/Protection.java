package it.gabrieledev.nautica.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// "Whoever cannot break a block by hand cannot carry it away on a ship." Checks go through synthetic
// break and place events, which claim plugins already listen to; a Hook covers the rest.
// The synthetic events are subclasses, so a plugin that needs to can tell them apart from real ones
public final class Protection {

    public interface Hook {
        boolean canBreak(Player player, Block block);

        boolean canPlace(Player player, Block block);
    }

    public static final class CheckBreakEvent extends BlockBreakEvent {
        public CheckBreakEvent(Block block, Player player) {
            super(block, player);
            setDropItems(false);
            setExpToDrop(0);
        }
    }

    public static final class CheckPlaceEvent extends BlockPlaceEvent {
        public CheckPlaceEvent(Block block, Block against, ItemStack item, Player player) {
            super(block, block.getState(), against, item, player, true, EquipmentSlot.HAND);
        }
    }

    private final List<Hook> hooks = new CopyOnWriteArrayList<>();

    public void register(Hook hook) {
        hooks.add(hook);
    }

    public void unregister(Hook hook) {
        hooks.remove(hook);
    }

    public boolean canBreak(Player player, Block block) {
        CheckBreakEvent event = new CheckBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        for (Hook h : hooks) {
            if (!h.canBreak(player, block)) {
                return false;
            }
        }
        return true;
    }

    public boolean canPlace(Player player, Block block, Material as) {
        Material itemType = as.isItem() ? as : Material.STONE;
        CheckPlaceEvent event = new CheckPlaceEvent(block, block.getRelative(BlockFace.DOWN), new ItemStack(itemType), player);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            return false;
        }
        for (Hook h : hooks) {
            if (!h.canPlace(player, block)) {
                return false;
            }
        }
        return true;
    }
}
