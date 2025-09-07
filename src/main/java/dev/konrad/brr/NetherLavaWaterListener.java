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
        // Budgeted processing to avoid lag spikes
        Bukkit.getScheduler().runTask(plugin, () -> replaceLavaInChunkBudgeted(c));
    }

    private void replaceLavaInChunkBudgeted(Chunk chunk) {
        World w = chunk.getWorld();
        final int minY = w.getMinHeight();
        final int maxY = w.getMaxHeight() - 1;
        final int[] state = new int[]{0, minY, 0}; // x, y, z
        final int opsPerTick = 2048; // tuneable budget
        final org.bukkit.scheduler.BukkitTask[] handle = new org.bukkit.scheduler.BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int ops = 0;
            while (ops < opsPerTick) {
                int x = state[0], y = state[1], z = state[2];
                Material t = chunk.getBlock(x, y, z).getType();
                if (t == Material.LAVA) {
                    chunk.getBlock(x, y, z).setType(Material.WATER, false);
                }
                // advance
                state[1]++;
                if (state[1] > maxY) { state[1] = minY; state[2]++; }
                if (state[2] > 15) { state[2] = 0; state[0]++; }
                if (state[0] > 15) { handle[0].cancel(); return; }
                ops++;
            }
        }, 1L, 1L);
    }
}
