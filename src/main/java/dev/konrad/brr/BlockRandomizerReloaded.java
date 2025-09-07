package dev.konrad.brr;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.data.Waterlogged;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class BlockRandomizerReloaded extends JavaPlugin {

    private final Set<String> enabledWorldNames = new HashSet<>();
    private final Set<Material> exposureNeighbors = EnumSet.noneOf(Material.class);
    private final Set<Material> replacementWhitelist = EnumSet.noneOf(Material.class);
    private List<Material> replacementList = new ArrayList<>();
    private final Set<Material> protectedSourceBlocks = EnumSet.noneOf(Material.class);

    private boolean triggerOnChunkLoad = true;
    private int periodicSeconds = 0;
    private int minY = 60;
    private int maxY = 320;
    private double tickBudgetMs = 2.0;
    private boolean logChangedBlocks = false;
    private int underDepth = 3; // number of blocks below the surface to also replace
    private int underMinY = 57; // minimum Y for under-replacements

    private final Random rng = new Random();

    private final Map<String, BukkitTask> activeChunkTasks = new HashMap<>();
    private BukkitTask periodicTask;
    private BukkitTask paletteTask;

    // Stats
    private long statBlocksChanged = 0L;
    private long statChunksQueued = 0L;
    private long statTasksCompleted = 0L;
    private int paletteEpoch = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigAndRebuildWhitelist();

        // Listener
        Bukkit.getPluginManager().registerEvents(new ChunkRandomizeListener(this), this);

        // Optional periodic timer
        scheduleOrCancelPeriodic();
        getLogger().info("BlockRandomizerReloaded enabled. Worlds=" + enabledWorldNames + ", whitelist size=" + replacementWhitelist.size());
    }

    @Override
    public void onDisable() {
        // Cancel all tasks
        for (BukkitTask task : activeChunkTasks.values()) {
            task.cancel();
        }
        activeChunkTasks.clear();
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    public void reloadConfigAndRebuildWhitelist() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        enabledWorldNames.clear();
        enabledWorldNames.addAll(cfg.getStringList("enabled-worlds"));

        minY = cfg.getInt("min-y", 60);
        maxY = cfg.getInt("max-y", 320);

        ConfigurationSection trig = cfg.getConfigurationSection("trigger");
        if (trig != null) {
            triggerOnChunkLoad = trig.getBoolean("on-chunk-load", true);
            periodicSeconds = trig.getInt("periodic-seconds", 0);
        } else {
            triggerOnChunkLoad = true;
            periodicSeconds = 0;
        }

        exposureNeighbors.clear();
        for (String s : cfg.getStringList("exposure-neighbors")) {
            Material m = safeMaterial(s);
            if (m != null) exposureNeighbors.add(m);
        }
        if (exposureNeighbors.isEmpty()) {
            Collections.addAll(exposureNeighbors, Material.AIR, Material.WATER, Material.LAVA);
        }

        tickBudgetMs = cfg.getDouble("tick-budget-ms", 2.0);
        logChangedBlocks = cfg.getConfigurationSection("log") != null && cfg.getConfigurationSection("log").getBoolean("changed-blocks", false);

        ConfigurationSection underSec = cfg.getConfigurationSection("under");
        if (underSec != null) {
            underDepth = Math.max(0, underSec.getInt("depth", 3));
            underMinY = underSec.getInt("min-y", 57);
        } else {
            underDepth = 3;
            underMinY = 57;
        }

        // Protected source blocks (never modify original if matches)
        protectedSourceBlocks.clear();
        buildProtectedSourceBlocks();

        // Build whitelist by exclusions
        replacementWhitelist.clear();
        buildWhitelistFromConfig();
        replacementList = new ArrayList<>(replacementWhitelist);

        // Reset stats? keep across reloads
        scheduleOrCancelPeriodic();
    }

    private void scheduleOrCancelPeriodic() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
        if (periodicSeconds > 0) {
            long periodTicks = Math.max(1, periodicSeconds * 20L);
            periodicTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (World w : Bukkit.getWorlds()) {
                    if (!isWorldEnabled(w)) continue;
                    for (Chunk c : w.getLoadedChunks()) {
                        queueChunk(c);
                    }
                }
            }, periodTicks, periodTicks);
        }
        schedulePaletteRotation();
    }

    private void schedulePaletteRotation() {
        if (paletteTask != null) {
            paletteTask.cancel();
            paletteTask = null;
        }
        boolean paletteEnabled = getConfig().getConfigurationSection("palette") == null || getConfig().getConfigurationSection("palette").getBoolean("enabled", true);
        int rotateSec = getConfig().getConfigurationSection("palette") != null ? getConfig().getConfigurationSection("palette").getInt("rotation-seconds", 60) : 60;
        if (paletteEnabled && rotateSec > 0) {
            long ticks = Math.max(20L, rotateSec * 20L);
            paletteTask = Bukkit.getScheduler().runTaskTimer(this, () -> rotatePalette(), ticks, ticks);
        }
    }

    private final Map<Material, Material> paletteMap = new EnumMap<>(Material.class);

    private void rotatePalette() {
        paletteMap.clear();
        paletteEpoch++;
        getLogger().info("BRR palette rotated. Epoch=" + paletteEpoch + ". Re-queueing loaded chunks...");
        // Re-queue all loaded chunks to apply new palette mapping
        for (World w : Bukkit.getWorlds()) {
            if (!isWorldEnabled(w)) continue;
            for (Chunk c : w.getLoadedChunks()) {
                queueChunk(c);
            }
        }
    }

    private Material getPaletteReplacement(Material source) {
        Material rep = paletteMap.get(source);
        if (rep != null) return rep;
        // Choose a deterministic-ish random per source per epoch
        // But for simplicity, just pick a random allowed material not equal to source
        Material picked = pickReplacementNotSource(source);
        paletteMap.put(source, picked);
        return picked;
    }

    private Material pickReplacementNotSource(Material source) {
        Material pick = pickRandomMaterial(rng);
        // try a few times to avoid identical replacement
        for (int i = 0; i < 8 && pick == source; i++) {
            pick = pickRandomMaterial(rng);
        }
        if (pick == Material.WATER || pick == Material.LAVA) return Material.STONE;
        return pick;
    }

    private void buildProtectedSourceBlocks() {
        // Containers and block entities to never modify
        // Chests
        addIfPresent(protectedSourceBlocks, "CHEST");
        addIfPresent(protectedSourceBlocks, "TRAPPED_CHEST");
        addIfPresent(protectedSourceBlocks, "ENDER_CHEST");
        addIfPresent(protectedSourceBlocks, "BARREL");
        // Furnaces
        addIfPresent(protectedSourceBlocks, "FURNACE");
        addIfPresent(protectedSourceBlocks, "BLAST_FURNACE");
        addIfPresent(protectedSourceBlocks, "SMOKER");
        // Hopper/dispensers/droppers
        addIfPresent(protectedSourceBlocks, "HOPPER");
        addIfPresent(protectedSourceBlocks, "DROPPER");
        addIfPresent(protectedSourceBlocks, "DISPENSER");
        // Shulker boxes
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.equals("SHULKER_BOX") || n.endsWith("_SHULKER_BOX")) {
                protectedSourceBlocks.add(m);
            }
        }
        // Misc block entities and interactables
        addIfPresent(protectedSourceBlocks, "JUKEBOX");
        addIfPresent(protectedSourceBlocks, "NOTE_BLOCK");
        addIfPresent(protectedSourceBlocks, "BEACON");
        addIfPresent(protectedSourceBlocks, "CONDUIT");
        addIfPresent(protectedSourceBlocks, "ENCHANTING_TABLE");
        addIfPresent(protectedSourceBlocks, "BREWING_STAND");
        addIfPresent(protectedSourceBlocks, "GRINDSTONE");
        addIfPresent(protectedSourceBlocks, "STONECUTTER");
        addIfPresent(protectedSourceBlocks, "LOOM");
        addIfPresent(protectedSourceBlocks, "CARTOGRAPHY_TABLE");
        addIfPresent(protectedSourceBlocks, "SMITHING_TABLE");
        addIfPresent(protectedSourceBlocks, "COMPOSTER");
        addIfPresent(protectedSourceBlocks, "BEE_NEST");
        addIfPresent(protectedSourceBlocks, "BEEHIVE");
        // Heads/skulls
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.endsWith("_HEAD") || n.endsWith("_WALL_HEAD") || n.endsWith("_SKULL") || n.endsWith("_WALL_SKULL") || n.equals("PLAYER_HEAD") || n.equals("PLAYER_WALL_HEAD")) {
                protectedSourceBlocks.add(m);
            }
        }
        // Spawner
        addIfPresent(protectedSourceBlocks, "SPAWNER");
    }

    private void buildWhitelistFromConfig() {
        FileConfiguration cfg = getConfig();

        Set<String> excludedCategories = new HashSet<>(cfg.getStringList("exclusions.categories"));
        Set<String> excludedMaterials = new HashSet<>(cfg.getStringList("exclusions.materials"));
        Set<String> whitelistOverrides = new HashSet<>(cfg.getStringList("whitelist-overrides"));

        // Build base allowlist by filtering all block materials with conservative rules
        for (Material m : Material.values()) {
            if (!m.isBlock()) continue;
            if (m.isAir()) continue;
            // Avoid liquids: Material does not expose isLiquid() reliably across API versions
            if (m == Material.WATER || m == Material.LAVA) continue;
            if (hasGravity(m)) continue;
            if (!m.isSolid()) continue; // restrict to solid blocks (full-cube or translucent solids)
            if (isWaterloggable(m)) continue; // avoid waterloggable blocks entirely
            String name = m.name();

            if (matchesNonFullOrThin(name)) continue;
            if (matchesPlantOrFoliage(name)) continue;
            if (matchesRedstoneOrUpdateable(name)) continue;
            if (matchesPortalOrSpecial(name)) continue;
            if (protectedSourceBlocks.contains(m)) continue; // never place containers/entities

            if (excludedMaterials.contains(name)) continue;

            // Category switches
            if (excludedCategories.contains("LIQUIDS") && (name.contains("WATER") || name.contains("LAVA"))) continue;
            if (excludedCategories.contains("GRAVITY") && hasGravity(m)) continue;
            if (excludedCategories.contains("NON_FULL_BLOCKS") && !m.isSolid()) continue;
            if (excludedCategories.contains("PLANTS") && matchesPlantOrFoliage(name)) continue;
            if (excludedCategories.contains("REDSTONE") && matchesRedstoneOrUpdateable(name)) continue;
            if (excludedCategories.contains("CONTAINERS") && isContainerName(name)) continue;
            if (excludedCategories.contains("PORTALS") && matchesPortalOrSpecial(name)) continue;
            if (excludedCategories.contains("SCULK_SENSORS") && name.contains("SCULK_SENSOR")) continue;
            if (excludedCategories.contains("SCULK_SHRIEKER") && name.contains("SCULK_SHRIEKER")) continue;

            // Specific blacklist additions
            if (name.equals("DRAGON_EGG") || name.equals("BUDDING_AMETHYST") || name.equals("REDSTONE_BLOCK") || name.equals("SNOW") || name.equals("BELL") || name.equals("END_ROD") || name.equals("LIGHTNING_ROD") || name.equals("IRON_BARS") || name.equals("JUKEBOX") || name.equals("NOTE_BLOCK") || name.equals("DRIPSTONE_BLOCK") || name.equals("LECTERN") || name.equals("CRAFTING_TABLE") || name.equals("FARMLAND") || name.equals("STRUCTURE_BLOCK") || name.equals("STRUCTURE_VOID") || name.equals("JIGSAW") || name.equals("BARRIER") || name.equals("LIGHT") || name.equals("ICE") || name.equals("FROSTED_ICE")) {
                continue;
            }

            replacementWhitelist.add(m);
        }

        // Explicit whitelist overrides
        for (String s : whitelistOverrides) {
            Material m = safeMaterial(s);
            if (m == null || !m.isBlock()) continue;
            if (m == Material.WATER || m == Material.LAVA) continue; // never allow liquids via overrides
            if (m.name().equals("ICE") || m.name().equals("FROSTED_ICE")) continue; // avoid melting to water
            if (isWaterloggable(m)) continue;
            replacementWhitelist.add(m);
        }
    }

    private boolean isWaterloggable(Material m) {
        try {
            return m.createBlockData() instanceof Waterlogged;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasGravity(Material m) {
        try {
            return m.hasGravity();
        } catch (NoSuchMethodError e) {
            // fallback: heuristic by name
            String n = m.name();
            return n.endsWith("SAND") || n.endsWith("GRAVEL") || n.contains("CONCRETE_POWDER") || n.contains("ANVIL");
        }
    }

    private boolean matchesNonFullOrThin(String n) {
        return n.contains("SLAB") || n.contains("STAIRS") || n.contains("WALL") || n.contains("FENCE_GATE") || (n.endsWith("_FENCE") || n.equals("FENCE")) || n.contains("PANE") || n.equals("IRON_BARS") || n.equals("CHAIN") || n.endsWith("_BANNER") || n.endsWith("_BED") || n.endsWith("_CARPET") || n.equals("SNOW") || n.endsWith("_TRAPDOOR") || n.endsWith("_DOOR") || n.endsWith("_BUTTON") || n.equals("LEVER") || n.endsWith("PRESSURE_PLATE") || n.endsWith("_SIGN") || n.endsWith("_WALL_SIGN") || n.endsWith("TORCH") || n.equals("LANTERN") || n.endsWith("_LANTERN") || n.contains("CANDLE") || n.endsWith("_ROD") || n.equals("LADDER") || n.equals("VINE") || n.equals("SCAFFOLDING") || n.equals("CAMPFIRE") || n.equals("SOUL_CAMPFIRE") || n.equals("SEA_PICKLE") || n.equals("FLOWER_POT") || n.equals("CAKE") || n.contains("CAULDRON") || n.equals("POINTED_DRIPSTONE") || n.equals("AMETHYST_CLUSTER") || n.endsWith("_AMETHYST_BUD");
    }

    private boolean matchesPlantOrFoliage(String n) {
        return n.endsWith("_FLOWER") || n.endsWith("_FLOWERS") || n.endsWith("_SAPLING") || n.endsWith("_MUSHROOM") || n.contains("TALL_") || n.equals("GRASS") || n.equals("FERN") || n.equals("LARGE_FERN") || n.equals("SWEET_BERRY_BUSH") || n.contains("LEAVES") || n.contains("SEAGRASS") || n.contains("KELP") || n.contains("VINES") || n.contains("CORAL") || n.contains("CORAL_FAN") || n.contains("CORAL_BLOCK") || n.contains("AZALEA") || n.contains("HANGING_ROOTS") || n.contains("MANGROVE_PROPAGULE") || n.contains("BAMBOO") || n.equals("CACTUS") || n.contains("TURTLE_EGG");
    }

    private boolean matchesRedstoneOrUpdateable(String n) {
        return n.equals("REDSTONE_BLOCK") || n.equals("REDSTONE_WIRE") || n.equals("REPEATER") || n.equals("COMPARATOR") || n.contains("OBSERVER") || n.contains("RAIL") || n.equals("DAYLIGHT_DETECTOR") || n.contains("PISTON") || n.equals("SLIME_BLOCK") || n.equals("HONEY_BLOCK") || n.equals("TARGET") || n.contains("SCULK_SENSOR") || n.contains("SCULK_SHRIEKER") || n.contains("SCULK_CATALYST") || n.equals("SCULK") || n.equals("SCULK_VEIN") || n.equals("SCULK_BLOCK") || n.equals("TNT") || n.contains("COMMAND_BLOCK") || n.equals("LECTERN") || n.equals("CRAFTING_TABLE") || n.equals("FARMLAND") || n.equals("STRUCTURE_BLOCK") || n.equals("STRUCTURE_VOID") || n.equals("JIGSAW") || n.equals("BARRIER") || n.equals("LIGHT");
    }

    private boolean matchesPortalOrSpecial(String n) {
        return n.contains("_PORTAL") || n.contains("END_GATEWAY") || n.contains("PORTAL_FRAME") || n.equals("BUDDING_AMETHYST") || n.equals("DRAGON_EGG");
    }

    private boolean isContainerName(String n) {
        return n.equals("CHEST") || n.equals("TRAPPED_CHEST") || n.equals("ENDER_CHEST") || n.equals("BARREL") || n.equals("FURNACE") || n.equals("BLAST_FURNACE") || n.equals("SMOKER") || n.equals("HOPPER") || n.equals("DROPPER") || n.equals("DISPENSER") || n.endsWith("_SHULKER_BOX") || n.equals("SHULKER_BOX");
    }

    private void addIfPresent(Set<Material> set, String name) {
        Material m = safeMaterial(name);
        if (m != null) set.add(m);
    }

    private Material safeMaterial(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Helpers used by listeners
    public boolean isWorldEnabled(World w) {
        return enabledWorldNames.contains(w.getName());
    }

    public boolean isAllowedReplacement(Material m) {
        return replacementWhitelist.contains(m);
    }

    public Material pickRandomMaterial(Random r) {
        if (replacementList.isEmpty()) return Material.STONE;
        // Hard guard: never return WATER or LAVA even if somehow present
        for (int attempts = 0; attempts < 10; attempts++) {
            int idx = r.nextInt(replacementList.size());
            Material pick = replacementList.get(idx);
            if (pick != Material.WATER && pick != Material.LAVA) {
                return pick;
            }
        }
        return Material.STONE;
    }

    public boolean isExposedToAirOrLiquid(Chunk chunk, int x, int y, int z) {
        // Only check neighbors within this chunk to avoid cross-chunk reads
        // Always check UP and DOWN if in world bounds
        if (withinWorldY(chunk.getWorld(), y + 1)) {
            Material t = chunk.getBlock(x, y + 1, z).getType();
            if (exposureNeighbors.contains(t)) return true;
        }
        if (withinWorldY(chunk.getWorld(), y - 1)) {
            Material t = chunk.getBlock(x, y - 1, z).getType();
            if (exposureNeighbors.contains(t)) return true;
        }
        // Lateral neighbors (only if within 0..15 bounds)
        if (x + 1 <= 15) {
            Material t = chunk.getBlock(x + 1, y, z).getType();
            if (exposureNeighbors.contains(t)) return true;
        }
        if (x - 1 >= 0) {
            Material t = chunk.getBlock(x - 1, y, z).getType();
            if (exposureNeighbors.contains(t)) return true;
        }
        if (z + 1 <= 15) {
            Material t = chunk.getBlock(x, y, z + 1).getType();
            if (exposureNeighbors.contains(t)) return true;
        }
        if (z - 1 >= 0) {
            Material t = chunk.getBlock(x, y, z - 1).getType();
            if (exposureNeighbors.contains(t)) return true;
        }
        return false;
    }

    private boolean withinWorldY(World w, int y) {
        return y >= w.getMinHeight() && y < w.getMaxHeight();
    }

    public boolean isBlockEntityOrProtected(Block b) {
        Material t = b.getType();
        if (protectedSourceBlocks.contains(t)) return true;
        // Do not replace flowers or grass (tall and small variants)
        if (isFlowerOrGrass(t)) return true;
        // Config toggles: preserve natural chests/spawners
        if (t == Material.SPAWNER && getConfig().getConfigurationSection("preserve-natural").getBoolean("spawners", true)) return true;
        if ((t == Material.CHEST || t == Material.TRAPPED_CHEST || t == Material.ENDER_CHEST) && getConfig().getConfigurationSection("preserve-natural").getBoolean("chests", true)) return true;
        return false;
    }

    private static final EnumSet<Material> FLOWERS = EnumSet.of(
            // Small flowers (1.19.2)
            Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
            Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP,
            Material.PINK_TULIP, Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY,
            Material.WITHER_ROSE,
            // Tall flowers
            Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY
    );

    private boolean isFlowerOrGrass(Material m) {
        if (FLOWERS.contains(m)) return true;
        String n = m.name();
        if (n.equals("GRASS") || n.equals("TALL_GRASS") || n.equals("FERN") || n.equals("LARGE_FERN")) return true;
        if (n.equals("SNOW")) return true; // snow layers: target the block underneath
        return false;
    }

    public boolean shouldTriggerOnChunkLoad() {
        return triggerOnChunkLoad;
    }

    // Task scheduling per chunk
    public void queueChunk(Chunk chunk) {
        World world = chunk.getWorld();
        if (!isWorldEnabled(world)) return;

        int worldMin = world.getMinHeight();
        int worldMax = world.getMaxHeight() - 1;
        int yFrom = Math.max(minY, worldMin);
        int yTo = Math.min(maxY, worldMax);
        if (yFrom > yTo) return;

        String key = chunkKey(chunk);
        if (activeChunkTasks.containsKey(key)) return; // already processing

        // Build queue of positions
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = yFrom; y <= yTo; y++) {
                    queue.add(new int[]{x, y, z});
                }
            }
        }

        statChunksQueued++;
        long nanosBudget = (long) (tickBudgetMs * 1_000_000.0);
        final int maxOpsPerTick = 200;
        final Set<Long> touched = new HashSet<>();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long start = System.nanoTime();
            int ops = 0;
            while (!queue.isEmpty()) {
                int[] p = queue.pollFirst();
                int lx = p[0], y = p[1], lz = p[2];
                Block b = chunk.getBlock(lx, y, lz);
                Material src = b.getType();
                // Never replace AIR or liquids as source
                if (src.isAir() || src == Material.WATER || src == Material.LAVA) {
                    // skip
                } else {
                    boolean isPlantTop = isFlowerOrGrass(src);
                    if (isPlantTop) {
                        int anchorY = y - 1;
                        if (withinWorldY(chunk.getWorld(), anchorY)) {
                            applyChainAt(chunk, lx, anchorY, lz, touched);
                        }
                    } else if (isExposedToAirOrLiquid(chunk, lx, y, lz)) {
                        applyChainAt(chunk, lx, y, lz, touched);
                    }
                }

                ops++;
                if (ops >= maxOpsPerTick) break;
                if ((System.nanoTime() - start) >= nanosBudget) break;
            }
            if (queue.isEmpty()) {
                BukkitTask t = activeChunkTasks.remove(key);
                if (t != null) t.cancel();
                statTasksCompleted++;
            }
        }, 1L, 1L);

        activeChunkTasks.put(key, task);
    }

    private String chunkKey(Chunk c) {
        return c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ();
    }

    private void applyChainAt(Chunk chunk, int x, int y, int z, Set<Long> touched) {
        World w = chunk.getWorld();
        int minFloor = Math.max(w.getMinHeight(), underMinY);
        int maxCeil = w.getMaxHeight() - 1;
        // base
        processAt(chunk, x, y, z, touched);
        // extend 6 directions up to underDepth
        for (int d = 1; d <= underDepth; d++) {
            if (y + d <= maxCeil) processAt(chunk, x, y + d, z, touched);
            if (y - d >= minFloor) processAt(chunk, x, y - d, z, touched);
            if (x + d <= 15) processAt(chunk, x + d, y, z, touched);
            if (x - d >= 0) processAt(chunk, x - d, y, z, touched);
            if (z + d <= 15) processAt(chunk, x, y, z + d, touched);
            if (z - d >= 0) processAt(chunk, x, y, z - d, touched);
        }
    }

    private void processAt(Chunk chunk, int x, int y, int z, Set<Long> touched) {
        World w = chunk.getWorld();
        if (!withinWorldY(w, y)) return;
        Material current = chunk.getBlock(x, y, z).getType();
        long key = (((long) y) << 8) | ((long) (x & 0xF) << 4) | (long) (z & 0xF);
        if (touched.contains(key)) return;
        if (current.isAir() || current == Material.WATER || current == Material.LAVA) return;
        Block target = chunk.getBlock(x, y, z);
        if (isBlockEntityOrProtected(target)) return;
        Material pick = getPaletteReplacement(current);
        if (isAllowedReplacement(pick) && pick != current) {
            target.setType(pick, false);
            statBlocksChanged++;
            touched.add(key);
            if (logChangedBlocks) {
                getLogger().info("Changed block at " + target.getLocation() + " -> " + pick);
            }
        }
    }

    // Simple inline command handler to avoid extra boilerplate file
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("brr")) return false;
        if (args.length == 0) {
            sender.sendMessage("/brr reload | /brr stats | /brr here | /brr rotate");
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            if (!sender.hasPermission("brr.admin")) {
                sender.sendMessage("You don't have permission.");
                return true;
            }
            reloadConfigAndRebuildWhitelist();
            sender.sendMessage("BRR: config reloaded. Whitelist size=" + replacementWhitelist.size());
            return true;
        } else if (sub.equals("stats")) {
            sender.sendMessage("BRR stats: changed=" + statBlocksChanged + ", chunksQueued=" + statChunksQueued + ", tasksCompleted=" + statTasksCompleted + ", whitelist=" + replacementWhitelist.size());
            return true;
        } else if (sub.equals("here")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("/brr here can only be run by a player.");
                return true;
            }
            if (!sender.hasPermission("brr.admin")) {
                sender.sendMessage("You don't have permission.");
                return true;
            }
            Player p = (Player) sender;
            queueChunk(p.getLocation().getChunk());
            sender.sendMessage("BRR: queued current chunk for randomization.");
            return true;
        } else if (sub.equals("rotate")) {
            if (!sender.hasPermission("brr.admin")) {
                sender.sendMessage("You don't have permission.");
                return true;
            }
            rotatePalette();
            sender.sendMessage("BRR: palette rotated. Epoch=" + paletteEpoch);
            return true;
        }
        return false;
    }
}
