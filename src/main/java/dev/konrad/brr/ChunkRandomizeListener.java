package dev.konrad.brr;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public class ChunkRandomizeListener implements Listener {

    private final BlockRandomizerReloaded plugin;

    public ChunkRandomizeListener(BlockRandomizerReloaded plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk chunk = e.getChunk();
        World world = chunk.getWorld();
        if (!plugin.isWorldEnabled(world)) return;
        if (!plugin.shouldTriggerOnChunkLoad()) return;

        // Primary trigger
        if (e.isAsynchronous()) return; // safety: only on main thread

        // Always process on load (as per config; currently always true)
        plugin.queueChunk(chunk);
    }
}
