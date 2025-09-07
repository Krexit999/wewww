package dev.konrad.brr;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class GhostItemListener implements Listener {
    private final BlockRandomizerReloaded plugin;
    public GhostItemListener(BlockRandomizerReloaded plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof org.bukkit.entity.Player)) return;
        ItemStack current = e.getCurrentItem();
        if (current == null) return;
        if (plugin.isGhostItem(current)) {
            e.setCancelled(true);
            int slot = e.getSlot();
            // remove the item from that slot
            e.getWhoClicked().getInventory().setItem(slot, null);
            // optional: small sound/particle could be added later
        }
    }
}

