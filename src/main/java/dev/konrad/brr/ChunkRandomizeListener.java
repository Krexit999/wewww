// ChunkRandomizeListener.java
package dev.konrad.brr;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class ChunkRandomizeListener implements Listener {
    private final BlockRandomizerReloaded plugin;

    public ChunkRandomizeListener(BlockRandomizerReloaded plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Only work on chunks that are actually loaded/rendered
        if (!event.getChunk().isLoaded()) return;

        World world = event.getWorld();
        if (!plugin.isWorldAllowed(world.getName())) return;

        final Chunk chunk = event.getChunk();
        final Map<Material, Material> map = plugin.getMapping();
        final Set<Material> excluded = plugin.getExcluded();
        final int minY = Math.max(plugin.getMinY(), world.getMinHeight());
        final int maxY = Math.min(plugin.getMaxY(), world.getMaxHeight());
        final boolean liquids = plugin.isConsiderLiquidsAsSurface();
        final int perTick = plugin.getMaxBlocksPerTick();
        final int perChunkCap = plugin.getMaxBlocksPerChunkCap();

        new BukkitRunnable() {
            final List<Block> toProcess = new ArrayList<>();
            {
                int cx = chunk.getX() << 4;
                int cz = chunk.getZ() << 4;

                // Scan only Y in [minY..maxY], top-down
                outer:
                for (int y = maxY; y >= minY; y--) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            Block b = world.getBlockAt(cx + x, y, cz + z);
                            Material src = b.getType();
                            if (!src.isBlock() || src.isAir() || excluded.contains(src) || !src.isSolid())
                                continue;

                            // Surface exposure check (6-neighborhood)
                            boolean exposed = false;
                            Block n;
                            n = world.getBlockAt(cx + x + 1, y, cz + z);
                            exposed |= isSurfaceNeighbor(n.getType(), liquids);
                            if (!exposed) { n = world.getBlockAt(cx + x - 1, y, cz + z); exposed |= isSurfaceNeighbor(n.getType(), liquids); }
                            if (!exposed) { n = world.getBlockAt(cx + x, y + 1, cz + z); exposed |= isSurfaceNeighbor(n.getType(), liquids); }
                            if (!exposed) { n = world.getBlockAt(cx + x, y - 1, cz + z); exposed |= isSurfaceNeighbor(n.getType(), liquids); }
                            if (!exposed) { n = world.getBlockAt(cx + x, y, cz + z + 1); exposed |= isSurfaceNeighbor(n.getType(), liquids); }
                            if (!exposed) { n = world.getBlockAt(cx + x, y, cz + z - 1); exposed |= isSurfaceNeighbor(n.getType(), liquids); }

                            if (exposed) {
                                toProcess.add(b);
                                if (perChunkCap > 0 && toProcess.size() >= perChunkCap) break outer;
                            }
                        }
                    }
                }
            }

            int index = 0;

            @Override public void run() {
                if (!chunk.isLoaded() || index >= toProcess.size()) { cancel(); return; }
                for (int i = 0; i < perTick && index < toProcess.size(); i++, index++) {
                    Block b = toProcess.get(index);
                    Material src = b.getType();
                    Material dst = map.get(src);
                    if (dst != null && dst.isSolid()) {
                        b.setType(dst, false);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static boolean isSurfaceNeighbor(Material m, boolean liquids) {
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return true;
        if (liquids && (m == Material.WATER || m == Material.LAVA)) return true;
        return false;
    }
}
