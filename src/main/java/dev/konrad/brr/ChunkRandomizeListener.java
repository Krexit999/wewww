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
        if (!event.isNewChunk()) return;
        World world = event.getWorld();
        if (!plugin.isWorldAllowed(world.getName())) return;

        Chunk chunk = event.getChunk();
        Map<Material, Material> map = plugin.getMapping();
        Set<Material> excluded = plugin.getExcluded();

        new BukkitRunnable() {
            final List<Block> toProcess = new ArrayList<>();
            {
                int cx = chunk.getX() << 4;
                int cz = chunk.getZ() << 4;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = world.getMaxHeight() - 1; y > world.getMinHeight(); y--) {
                            Block b = world.getBlockAt(cx+x, y, cz+z);
                            Material src = b.getType();
                            if (src.isAir() || excluded.contains(src)) continue;

                            // Check if exposed to air, water, or lava
                            boolean exposed = false;
                            for (int[] dir : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                                Block n = world.getBlockAt(cx+x+dir[0], y+dir[1], cz+z+dir[2]);
                                Material nt = n.getType();
                                if (nt == Material.AIR || nt == Material.CAVE_AIR || nt == Material.VOID_AIR ||
                                    nt == Material.WATER || nt == Material.LAVA) { 
                                    exposed = true; break; 
                                }
                            }
                            if (exposed) {
                                toProcess.add(b);
                            }
                        }
                    }
                }
            }

            int index = 0;

            @Override public void run() {
                if (!chunk.isLoaded() || index >= toProcess.size()) { cancel(); return; }
                int max = plugin.getMaxBlocksPerTick();
                for (int i = 0; i < max && index < toProcess.size(); i++, index++) {
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
}
