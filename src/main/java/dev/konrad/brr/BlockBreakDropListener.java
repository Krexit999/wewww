package dev.konrad.brr;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BlockBreakDropListener implements Listener {
    private final BlockRandomizerReloaded plugin;
    private List<EntityType> cachedSpawnableMobs;
    private long lastMobCacheEpoch = -1;

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

        // 5% random mob spawn (configurable)
        maybeSpawnRandomMob(event);
    }

    private void maybeSpawnRandomMob(BlockBreakEvent event) {
        World world = event.getBlock().getWorld();
        if (!plugin.isWorldEnabled(world)) return;

        double chance = 0.05;
        boolean enabled = true;
        boolean excludeBosses = false; // allow bosses by default
        try {
            if (plugin.getConfig().getConfigurationSection("trolling") != null) {
                if (plugin.getConfig().getConfigurationSection("trolling").getConfigurationSection("block-break-mob") != null) {
                    org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig().getConfigurationSection("trolling").getConfigurationSection("block-break-mob");
                    enabled = sec.getBoolean("enabled", true);
                    chance = sec.getDouble("chance", 0.05);
                    excludeBosses = sec.getBoolean("exclude-bosses", false);
                }
            }
        } catch (Exception ignored) {}
        if (!enabled) return;

        Random r = new Random();
        if (r.nextDouble() >= chance) return;

        // Build cache per palette epoch (heuristic: reuse plugin's paletteEpoch via method? not exposed, so rebuild on empty)
        if (cachedSpawnableMobs == null || cachedSpawnableMobs.isEmpty()) {
            cachedSpawnableMobs = buildSpawnableMobList(excludeBosses);
        }
        if (cachedSpawnableMobs.isEmpty()) return;

        EntityType type = cachedSpawnableMobs.get(r.nextInt(cachedSpawnableMobs.size()));
        Location loc = event.getBlock().getLocation().add(0.5, 0.0, 0.5);
        try {
            world.spawnEntity(loc, type);
        } catch (Throwable t) {
            // ignore if cannot spawn for some reason
        }
    }

    private List<EntityType> buildSpawnableMobList(boolean excludeBosses) {
        List<EntityType> list = new ArrayList<>();
        for (EntityType et : EntityType.values()) {
            try {
                if (!et.isAlive()) continue;
                if (!et.isSpawnable()) continue;
                if (et == EntityType.PLAYER) continue;
                if (et == EntityType.ARMOR_STAND) continue;
                if (excludeBosses && (et == EntityType.ENDER_DRAGON || et == EntityType.WITHER)) continue;
                list.add(et);
            } catch (Throwable ignored) {
            }
        }
        return list;
    }
}
