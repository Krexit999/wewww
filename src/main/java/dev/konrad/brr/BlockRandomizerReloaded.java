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
    private int maxBlocksPerTick;

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
                        sender.sendMessage("§aConfig & mapping reloaded.");
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
        this.worldFilter = new HashSet<>(cfg.getStringList("worlds"));
        this.maxBlocksPerTick = cfg.getInt("maxBlocksPerTick", 500);

        this.excluded.clear();
        for (String name : cfg.getStringList("excludeMaterials")) {
            try { this.excluded.add(Material.valueOf(name)); } catch (IllegalArgumentException ignored) {}
        }

        // Auto-exclude categories
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.endsWith("_SLAB") || n.endsWith("_STAIRS") || n.endsWith("_WALL") ||
                n.endsWith("_PANE") || n.endsWith("_CARPET") || n.endsWith("_BANNER") ||
                n.endsWith("_BED") || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") ||
                n.endsWith("_SIGN") || n.endsWith("_PRESSURE_PLATE") ||
                n.endsWith("SHULKER_BOX") || !m.isSolid()) {
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
        List<Material> pool = new ArrayList<>(allAllowed);
        for (Material src : allAllowed) {
            mapping.put(src, pool.get(rng.nextInt(pool.size())));
        }
    }

    public Map<Material, Material> getMapping() { return mapping; }
    public boolean isWorldAllowed(String worldName) { return worldFilter.isEmpty() || worldFilter.contains(worldName); }
    public Set<Material> getExcluded() { return excluded; }
    public int getMaxBlocksPerTick() { return maxBlocksPerTick; }
}
