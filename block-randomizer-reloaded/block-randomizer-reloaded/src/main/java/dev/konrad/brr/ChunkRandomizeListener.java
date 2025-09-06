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
    int minY = world.getMinHeight();
    int maxY = world.getMaxHeight() - 1;
    final int yStep = plugin.getYStepPerTick();

    new BukkitRunnable() {
      int yCursor = minY;

      @Override public void run() {
        if (!chunk.isLoaded()) { cancel(); return; }
        int cx = chunk.getX() << 4;
        int cz = chunk.getZ() << 4;

        int yStart = yCursor;
        int yEnd = Math.min(yCursor + yStep - 1, maxY);

        for (int y = yStart; y <= yEnd; y++) {
          for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
              Block b = world.getBlockAt(cx + x, y, cz + z);
              Material src = b.getType();
              if (!src.isBlock()) continue;
              if (!plugin.getConfig().getBoolean("includeAir", false) && src.isAir()) continue;
              if (excluded.contains(src)) continue;

              Material dst = map.get(src);
              if (dst != null && dst != src) {
                b.setType(dst, false);
              }
            }
          }
        }

        yCursor = yEnd + 1;
        if (yCursor > maxY) { cancel(); }
      }
    }.runTaskTimer(plugin, 1L, 1L);
  }
}
