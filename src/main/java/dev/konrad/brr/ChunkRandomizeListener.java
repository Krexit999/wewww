package dev.konrad.brr;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ChunkRandomizeListener implements Listener {
    private final BlockRandomizerReloaded plugin;
    private final Set<Material> excluded;

    public ChunkRandomizeListener(BlockRandomizerReloaded plugin) {
        this.plugin = plugin;
        this.excluded = plugin.getConfig().getStringList("excludeMaterials").stream()
            .map(s -> {
                try { return Material.valueOf(s); } catch (IllegalArgumentException e) { return null; }
            })
            .filter(m -> m != null)
            .collect(Collectors.toSet());
    }

@EventHandler
public void onChunkLoad(ChunkLoadEvent event) {
    if (!event.isNewChunk()) return;
    World world = event.getWorld();
    if (!plugin.isWorldAllowed(world.getName())) return;

    Chunk chunk = event.getChunk();
    Map<Material, Material> map = plugin.getMapping();

    // Schedule task once per minute (1200 ticks)
    new BukkitRunnable() {
        @Override public void run() {
            if (!chunk.isLoaded()) { cancel(); return; }

            int cx = chunk.getX() << 4;
            int cz = chunk.getZ() << 4;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = cx + x;
                    int worldZ = cz + z;
                    Block top = world.getHighestBlockAt(worldX, worldZ);

                    for (int depth = 0; depth < 5; depth++) {
                        Block b = top.getRelative(0, -depth, 0);
                        if (b.getY() < world.getMinHeight()) break;

                        Material src = b.getType();
                        if (!src.isBlock()) continue;
                        if (!plugin.getConfig().getBoolean("includeAir", false) && src.isAir()) continue;
                        if (excluded.contains(src)) continue;
                        if (!src.isSolid()) continue;

                        Material dst = map.get(src);
                        if (dst != null && dst != src && dst.isSolid()) {
                            b.setType(dst, false);
                        }
                    }
                }
            }
        }
    }.runTaskTimer(plugin, 0L, 1200L); // every 60s
}

