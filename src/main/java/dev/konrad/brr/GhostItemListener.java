package dev.konrad.brr;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class GhostItemListener implements Listener {
    private final BlockRandomizerReloaded plugin;
    public GhostItemListener(BlockRandomizerReloaded plugin) { this.plugin = plugin; }

    // Disallow moving/placing into slots; remove on click
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        // Current slot item
        ItemStack current = e.getCurrentItem();
        if (current != null && plugin.isGhostItem(current)) {
            e.setCancelled(true);
            Inventory inv = e.getClickedInventory();
            if (inv != null) inv.setItem(e.getSlot(), null);
            else ((Player)e.getWhoClicked()).getInventory().setItem(e.getSlot(), null);
            return;
        }
        // Cursor item
        ItemStack cursor = e.getCursor();
        if (cursor != null && plugin.isGhostItem(cursor)) {
            e.setCancelled(true);
            e.setCursor(null);
            return;
        }
        // Hotbar swap number key
        int hb = e.getHotbarButton();
        if (hb >= 0) {
            ItemStack hot = ((Player)e.getWhoClicked()).getInventory().getItem(hb);
            if (hot != null && plugin.isGhostItem(hot)) {
                e.setCancelled(true);
                ((Player)e.getWhoClicked()).getInventory().setItem(hb, null);
            }
        }
    }

    // Prevent placing ghost blocks
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        ItemStack inHand = e.getItemInHand();
        if (inHand != null && plugin.isGhostItem(inHand)) {
            e.setCancelled(true);
            clearHand(e.getPlayer(), e.getHand());
        }
    }

    // Prevent dropping (Q) — remove item instead
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack dropped = e.getItemDrop().getItemStack();
        if (dropped != null && plugin.isGhostItem(dropped)) {
            e.setCancelled(true);
            e.getItemDrop().remove();
            // Also clear from player's hand if still present
            Player p = e.getPlayer();
            ItemStack main = p.getInventory().getItemInMainHand();
            if (main != null && plugin.isGhostItem(main)) p.getInventory().setItemInMainHand(null);
            ItemStack off = p.getInventory().getItemInOffHand();
            if (off != null && plugin.isGhostItem(off)) p.getInventory().setItemInOffHand(null);
        }
    }

    // Prevent right-click use/equip/consume
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        if (it != null && plugin.isGhostItem(it)) {
            e.setCancelled(true);
            clearHand(e.getPlayer(), e.getHand());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (it != null && plugin.isGhostItem(it)) {
            e.setCancelled(true);
            clearHand(e.getPlayer(), EquipmentSlot.HAND); // consume uses main hand
        }
    }

    // Prevent swapping ghost into offhand
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if ((e.getMainHandItem() != null && plugin.isGhostItem(e.getMainHandItem())) ||
            (e.getOffHandItem() != null && plugin.isGhostItem(e.getOffHandItem()))) {
            e.setCancelled(true);
            if (e.getMainHandItem() != null && plugin.isGhostItem(e.getMainHandItem())) {
                e.getPlayer().getInventory().setItemInMainHand(null);
            }
            if (e.getOffHandItem() != null && plugin.isGhostItem(e.getOffHandItem())) {
                e.getPlayer().getInventory().setItemInOffHand(null);
            }
        }
    }

    private void clearHand(Player p, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            ItemStack off = p.getInventory().getItemInOffHand();
            if (off != null && plugin.isGhostItem(off)) p.getInventory().setItemInOffHand(null);
        } else {
            ItemStack main = p.getInventory().getItemInMainHand();
            if (main != null && plugin.isGhostItem(main)) p.getInventory().setItemInMainHand(null);
        }
    }
}
