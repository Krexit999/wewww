package dev.konrad.brr;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public class NetherLavaWaterListener implements Listener {
    private final BlockRandomizerReloaded plugin;
    public NetherLavaWaterListener(BlockRandomizerReloaded plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (e.isAsynchronous()) return;
        Chunk c = e.getChunk();
        World w = c.getWorld();
        if (w.getEnvironment() != Environment.NETHER) return;
        if (!plugin.isWorldEnabled(w)) return;
        // Config toggle
        boolean enabled = plugin.getConfig().getConfigurationSection("trolling") == null ||
                plugin.getConfig().getConfigurationSection("trolling").getBoolean("nether-water.enabled", true);
        if (!enabled) return;
        Bukkit.getScheduler().runTask(plugin, () -> replaceLavaInChunk(c));
    }

    private void replaceLavaInChunk(Chunk chunk) {
        World w = chunk.getWorld();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Material t = chunk.getBlock(x, y, z).getType();
                    if (t == Material.LAVA) {
                        // set still water; may evaporate in vanilla but we honor the request
                        chunk.getBlock(x, y, z).setType(Material.WATER, false);
                    }
                }
            }
        }
    }
}

