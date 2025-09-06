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
    private boolean preserveCategories;
    private boolean oneToOne;
    private int yStepPerTick;
    private Set<String> worldFilter;
    private long seed;
    private Random rng;

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
                        sender.sendMessage("§aBlockRandomizerReloaded: config & mapping reloaded.");
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
        this.preserveCategories = cfg.getBoolean("preserveCategories", true);
        this.oneToOne = cfg.getBoolean("oneToOneMapping", false);
        this.yStepPerTick = Math.max(1, cfg.getInt("yStepPerTick", 16));
        this.worldFilter = new HashSet<>(cfg.getStringList("worlds"));

        this.excluded.clear();
        for (String name : cfg.getStringList("excludeMaterials")) {
            try { this.excluded.add(Material.valueOf(name)); } catch (IllegalArgumentException ignored) {}
        }

        // Hardcoded exclusions
        excluded.addAll(EnumSet.of(
            Material.PLAYER_HEAD, Material.ZOMBIE_HEAD, Material.CREEPER_HEAD, Material.DRAGON_HEAD,
            Material.SKELETON_SKULL, Material.WITHER_SKELETON_SKULL,
            Material.LIGHTNING_ROD, Material.IRON_BARS, Material.BELL, Material.CONDUIT,
            Material.JUKEBOX, Material.REDSTONE_BLOCK, Material.REDSTONE_LAMP, Material.OBSERVER,
            Material.REPEATER, Material.COMPARATOR, Material.TARGET, Material.DAYLIGHT_DETECTOR,

            // Fences & gates
            Material.OAK_FENCE, Material.SPRUCE_FENCE, Material.BIRCH_FENCE, Material.JUNGLE_FENCE,
            Material.ACACIA_FENCE, Material.DARK_OAK_FENCE, Material.MANGROVE_FENCE,
            Material.CRIMSON_FENCE, Material.WARPED_FENCE,
            Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE, Material.BIRCH_FENCE_GATE, Material.JUNGLE_FENCE_GATE,
            Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE, Material.MANGROVE_FENCE_GATE,
            Material.CRIMSON_FENCE_GATE, Material.WARPED_FENCE_GATE
        ));

        // Beds, banners, slabs, stairs, shulkers
        for (Material m : Material.values()) {
            String name = m.name();
            if (name.endsWith("_BED") || name.endsWith("_BANNER") || name.endsWith("_SLAB") || name.endsWith("_STAIRS") || name.endsWith("SHULKER_BOX")) {
                excluded.add(m);
            }
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
            getLogger().warning("No allowed materials to map! Check config.");
            return;
        }

        List<Material> allPool = new ArrayList<>(allAllowed);
        for (Material src : allAllowed) {
            Material dst = allPool.get(rng.nextInt(allPool.size()));
            mapping.put(src, dst);
        }
    }

    public Map<Material, Material> getMapping() { return mapping; }
    public boolean isWorldAllowed(String worldName) { return worldFilter.isEmpty() || worldFilter.contains(worldName); }
    public int getYStepPerTick() { return yStepPerTick; }
}
