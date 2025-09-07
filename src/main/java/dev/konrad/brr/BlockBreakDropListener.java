package dev.konrad.brr;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class BlockBreakDropListener implements Listener {
    private final BlockRandomizerReloaded plugin;

    public BlockBreakDropListener(BlockRandomizerReloaded plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isWorldEnabled(event.getBlock().getWorld())) return;
        Player p = event.getPlayer();
        if (p == null) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE) return; // avoid creative spam; adjust if desired

        Material source = event.getBlock().getType();
        // Use drop palette: allows categories disallowed for world placement (still no water/lava)
        Material replacement = plugin.getDropPaletteReplacement(source);
        if (replacement == null) return;
        if (replacement == Material.WATER || replacement == Material.LAVA) return; // paranoia guard

        // Replace default drops with a single stack of the mapped block
        event.setDropItems(false);
        ItemStack stack = new ItemStack(replacement, 1);
        Map<Integer, ItemStack> notStored = p.getInventory().addItem(stack);
        if (!notStored.isEmpty()) {
            notStored.values().forEach(item -> event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item));
        }
    }
}
