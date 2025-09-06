// BlockRandomizerReloaded.java
package dev.konrad.brr;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

public final class BlockRandomizerReloaded extends JavaPlugin {
    private final Map<Material, Material> mapping = new EnumMap<>(Material.class);
    private final Set<Material> excluded = EnumSet.noneOf(Material.class);

    private boolean includeAir;
    private Set<String> worldFilter;
    private long seed;
    private Random rng;

    // Parameters
    private int maxBlocksPerTick;
    private int maxBlocksPerChunkCap;
    private int minY;
    private int maxY;
    private boolean considerLiquidsAsSurface;
    private boolean oneToOne;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAndBuild();
        if (getCommand("brr") != null) {
            getCommand("brr").setExecutor((sender, cmd, label, args) -> {
                if (!sender.hasPermission("brr.admin")) {
                    sender.sendMessage("§cNo permission.");
                    return true;
                }
                if (args.length == 0) {
                    sender.sendMessage("§eUsage: /brr <reload|dump>");
                    return true;
                }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "reload" -> {
                        reloadConfig();
                        loadAndBuild();
                        sender.sendMessage("§aBlockRandomizerSurface: config & mapping reloaded.");
                    }
                    case "dump" -> {
                        File out = new File(getDataFolder(), "mapping-dump.txt");
                        try (FileWriter fw = new FileWriter(out, false)) {
                            fw.write("# seed=" + seed + "\n");
                            for (Map.Entry<Material, Material> e : mapping.entrySet()) {
                                fw.write(e.getKey().name() + " -> " + e.getValue().name() + "\n");
                            }
                            sender.sendMessage("§aDumped mapping to " + out.getAbsolutePath());
                        } catch (IOException ex) {
                            sender.sendMessage("§cFailed to write mapping: " + ex.getMessage());
                        }
                    }
                    default -> sender.sendMessage("§eUsage: /brr <reload|dump>");
                }
                return true;
            });
        }
        getServer().getPluginManager().registerEvents(new ChunkRandomizeListener(this), this);
    }

    void loadAndBuild() {
        FileConfiguration cfg = getConfig();
        this.includeAir = cfg.getBoolean("includeAir", false);
        this.oneToOne = cfg.getBoolean("oneToOneMapping", false);
        this.worldFilter = new HashSet<>(cfg.getStringList("worlds"));
        this.maxBlocksPerTick = Math.max(1, cfg.getInt("maxBlocksPerTick", 400));
        this.maxBlocksPerChunkCap = Math.max(0, cfg.getInt("maxBlocksPerChunkCap", 0));
        this.minY = cfg.getInt("minY", 60);
        this.maxY = cfg.getInt("maxY", 320);
        this.considerLiquidsAsSurface = cfg.getBoolean("considerLiquidsAsSurface", true);

        // clamp min/max
        int worldMin = getServer().getWorlds().isEmpty() ? -64 : getServer().getWorlds().get(0).getMinHeight();
        int worldMax = getServer().getWorlds().isEmpty() ? 319 : getServer().getWorlds().get(0).getMaxHeight();
        if (this.minY < worldMin) this.minY = worldMin;
        if (this.maxY > worldMax) this.maxY = worldMax;

        // build exclusions (config + hardcoded categories)
        this.excluded.clear();
        for (String name : cfg.getStringList("excludeMaterials")) {
            try { this.excluded.add(Material.valueOf(name)); } catch (IllegalArgumentException ignored) {}
        }
        // Category-based hard excludes (1.19.2-safe)
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.endsWith("_SLAB") || n.endsWith("_STAIRS") || n.endsWith("_WALL") ||
                n.endsWith("_PANE") || n.endsWith("_CARPET") || n.endsWith("_BANNER") ||
                n.endsWith("_BED") || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") ||
                n.endsWith("_SIGN") || n.endsWith("_PRESSURE_PLATE") ||
                n.endsWith("_FENCE") || n.endsWith("_FENCE_GATE") ||
                n.endsWith("_BUTTON") || n.equals("LEVER") || n.contains("RAIL") ||
                n.equals("LADDER") || n.equals("VINE") || n.endsWith("_TORCH") ||
                n.endsWith("LANTERN") || n.equals("END_ROD") || n.equals("LIGHTNING_ROD") ||
                n.equals("IRON_BARS") || n.equals("CHAIN") ||
                n.endsWith("SHULKER_BOX") || !m.isSolid()) {
                excluded.add(m);
            }
        }
        // Containers / tile entities / mechanics
        excluded.addAll(EnumSet.of(
            Material.FARMLAND,
            Material.CAULDRON, Material.WATER_CAULDRON, Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.COMPOSTER, Material.LECTERN,
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.BREWING_STAND, Material.BEACON, Material.CONDUIT, Material.JUKEBOX,
            Material.SCULK_SENSOR, Material.SCULK_SHRIEKER,
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.ENDER_CHEST,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.HOPPER, Material.DISPENSER, Material.DROPPER,
            Material.SPAWNER
        ));
        // Redstone & pistons
        excluded.addAll(EnumSet.of(
            Material.REDSTONE_BLOCK, Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.REDSTONE_LAMP, Material.REPEATER, Material.COMPARATOR,
            Material.OBSERVER, Material.TARGET, Material.DAYLIGHT_DETECTOR,
            Material.PISTON, Material.STICKY_PISTON, Material.TNT
        ));
        // Heads / skulls
        excluded.addAll(EnumSet.of(
            Material.PLAYER_HEAD, Material.ZOMBIE_HEAD, Material.CREEPER_HEAD, Material.DRAGON_HEAD,
            Material.SKELETON_SKULL, Material.WITHER_SKELETON_SKULL
        ));
        // Falling blocks & oddballs
        excluded.addAll(EnumSet.of(
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.DRAGON_EGG
        ));
        for (Material m : Material.values()) {
            if (m.name().endsWith("_CONCRETE_POWDER")) excluded.add(m);
        }

        long cfgSeed = cfg.getLong("seed", -1);
        this.seed = (cfgSeed == -1) ? new SecureRandom().nextLong() : cfgSeed;
        this.rng = new Random(seed);

        buildMapping();
    }

    private void buildMapping() {
        mapping.clear();
        Set<Material> allAllowed = Arrays.stream(Material.values())
                .filter(Material::isBlock)
                .filter(Material::isSolid)
                .filter(m -> includeAir || !m.isAir())
                .filter(m -> !excluded.contains(m))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));
        if (allAllowed.isEmpty()) {
            getLogger().warning("No allowed materials to map! Check exclusions.");
            return;
        }
        List<Material> pool = new ArrayList<>(allAllowed);
        if (oneToOne) Collections.shuffle(pool, rng);
        for (Material src : allAllowed) {
            Material dst = oneToOne ? pool.get(rng.nextInt(pool.size())) : pool.get(rng.nextInt(pool.size()));
            mapping.put(src, dst);
        }
        getLogger().info("Allowed pool size: " + pool.size());
    }

    public Map<Material, Material> getMapping() { return mapping; }
    public boolean isWorldAllowed(String worldName) { return worldFilter.isEmpty() || worldFilter.contains(worldName); }
    public Set<Material> getExcluded() { return excluded; }
    public int getMaxBlocksPerTick() { return maxBlocksPerTick; }
    public int getMaxBlocksPerChunkCap() { return maxBlocksPerChunkCap; }
    public int getMinY() { return minY; }
    public int getMaxY() { return maxY; }
    public boolean isConsiderLiquidsAsSurface() { return considerLiquidsAsSurface; }
}
