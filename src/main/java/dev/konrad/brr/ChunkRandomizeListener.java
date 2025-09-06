package dev.konrad.brr;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

public final class ChunkRandomizeListener implements Listener {
    private final BlockRandomizerReloaded plugin;
    private final Random rng = new Random();

    public ChunkRandomizeListener(BlockRandomizerReloaded plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        final Chunk chunk = event.getChunk();
        final World world = chunk.getWorld();

        if (!plugin.isWorldEnabled(world)) return;

        final int scanDelay = Math.max(1, plugin.getConfig().getInt("scanDelayTicks", 10));
        final int cx = chunk.getX();
        final int cz = chunk.getZ();

        // Defer the scan so the chunk is fully “ready” and neighbors may stabilize.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // If chunk dropped meanwhile, skip.
            if (!world.isChunkLoaded(cx, cz)) return;
            processChunkSafely(world.getChunkAt(cx, cz));
        }, scanDelay);
    }

    private void processChunkSafely(Chunk chunk) {
        final World world = chunk.getWorld();

        // Y range (clamped to world build limits)
        final int minY = Math.max(plugin.getConfig().getInt("minY", 60), world.getMinHeight());
        final int maxY = Math.min(plugin.getConfig().getInt("maxY", 320), world.getMaxHeight() - 1);
        if (minY > maxY) return;

        final boolean liquidsCount = plugin.getConfig().getBoolean("considerLiquidsAsSurface", true);
        // Margin=1 means we never look outside the chunk when checking neighbors (prevents sync loads).
        final int edgeMargin = Math.max(0, Math.min(1, plugin.getConfig().getInt("edgeMargin", 1)));

        // SNAPSHOT READ (no chunk loads during the scan)
        final ChunkSnapshot snap = chunk.getChunkSnapshot(false, true, false);

        // Collect absolute positions to change (small, safe queue processed later)
        final Deque<BlockPos> toChange = new ArrayDeque<>();

        for (int x = edgeMargin; x < 16 - edgeMargin; x++) {
            for (int z = edgeMargin; z < 16 - edgeMargin; z++) {
                for (int y = minY; y <= maxY; y++) {
                    final Material m = snap.getBlockType(x, y, z);
                    if (!plugin.isAllowedReplacement(m)) continue;

                    if (isExposedInSnapshot(snap, x, y, z, minY, maxY, liquidsCount)) {
                        final int ax = (chunk.getX() << 4) + x;
                        final int az = (chunk.getZ() << 4) + z;
                        toChange.add(new BlockPos(ax, y, az));
                    }
                }
            }
        }

        if (toChange.isEmpty()) return;

        // WRITE PHASE (rate-limited, main thread)
        final int perTick = Math.max(1, plugin.getConfig().getInt("maxBlocksPerTick", 300));
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!world.isChunkLoaded(chunk.getX(), chunk.getZ())) {
                    cancel();
                    return;
                }
                int budget = perTick;
                while (budget-- > 0 && !toChange.isEmpty()) {
                    BlockPos p = toChange.pollFirst();
                    Block b = world.getBlockAt(p.x, p.y, p.z);

                    // Double-check live world material still allowed & still exposed
                    Material current = b.getType();
                    if (!plugin.isAllowedReplacement(current)) continue;

                    if (!isExposedLive(b, liquidsCount, edgeMargin)) continue;

                    // Replace
                    Material rnd = plugin.pickRandomMaterial(rng);
                    if (rnd != null) {
                        // No physics to avoid chain updates; we already exclude falling/updateable blocks
                        b.setType(rnd, false);
                    }
                }
                if (toChange.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private boolean isExposedInSnapshot(ChunkSnapshot s, int x, int y, int z,
                                        int minY, int maxY, boolean liquidsCount) {
        // Up
        if (y < maxY) {
            Material up = s.getBlockType(x, y + 1, z);
            if (isAirOrLiquid(up, liquidsCount)) return true;
        }
        // Down
        if (y > minY) {
            Material down = s.getBlockType(x, y - 1, z);
            if (isAirOrLiquid(down, liquidsCount)) return true;
        }
        // Sides (bounded so we never read outside this chunk)
        Material m;
        if (x > 0) { m = s.getBlockType(x - 1, y, z); if (isAirOrLiquid(m, liquidsCount)) return true; }
        if (x < 15) { m = s.getBlockType(x + 1, y, z); if (isAirOrLiquid(m, liquidsCount)) return true; }
        if (z > 0) { m = s.getBlockType(x, y, z - 1); if (isAirOrLiquid(m, liquidsCount)) return true; }
        if (z < 15) { m = s.getBlockType(x, y, z + 1); if (isAirOrLiquid(m, liquidsCount)) return true; }

        return false;
    }

    private boolean isExposedLive(Block b, boolean liquidsCount, int edgeMargin) {
        // Only check neighbors inside the same chunk bounds (avoid cross-chunk)
        final Chunk c = b.getChunk();
        final int lx = b.getX() & 15;
        final int lz = b.getZ() & 15;

        // Up
        Block up = b.getRelative(0, 1, 0);
        if (isAirOrLiquid(up.getType(), liquidsCount)) return true;

        // Down
        Block dn = b.getRelative(0, -1, 0);
        if (isAirOrLiquid(dn.getType(), liquidsCount)) return true;

        // Sides (guard edges)
        if (lx > edgeMargin)  { if (isAirOrLiquid(b.getRelative(-1, 0, 0).getType(), liquidsCount)) return true; }
        if (lx < 15 - edgeMargin) { if (isAirOrLiquid(b.getRelative( 1, 0, 0).getType(), liquidsCount)) return true; }
        if (lz > edgeMargin)  { if (isAirOrLiquid(b.getRelative(0, 0, -1).getType(), liquidsCount)) return true; }
        if (lz < 15 - edgeMargin) { if (isAirOrLiquid(b.getRelative(0, 0,  1).getType(), liquidsCount)) return true; }

        return false;
    }

    private boolean isAirOrLiquid(Material m, boolean liquidsCount) {
        if (m.isAir()) return true;
        return liquidsCount && (m == Material.WATER || m == Material.LAVA);
    }

    private static final class BlockPos {
        final int x, y, z;
        BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
