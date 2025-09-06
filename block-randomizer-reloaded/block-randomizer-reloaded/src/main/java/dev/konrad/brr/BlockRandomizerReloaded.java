package dev.konrad.brr;

import org.bukkit.Material;
import org.bukkit.Tag;
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

    getLogger().info("BlockRandomizerReloaded enabled. seed=" + seed +
        " preserveCategories=" + preserveCategories +
        " oneToOne=" + oneToOne +
        " yStepPerTick=" + yStepPerTick);
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

    long cfgSeed = cfg.getLong("seed", -1);
    this.seed = (cfgSeed == -1) ? new SecureRandom().nextLong() : cfgSeed;
    this.rng = new Random(seed);

    buildMapping();
  }

  private void buildMapping() {
    mapping.clear();

    Set<Material> allAllowed = Arrays.stream(Material.values())
        .filter(Material::isBlock)
        .filter(m -> includeAir || !m.isAir())
        .filter(m -> !excluded.contains(m))
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));

    if (allAllowed.isEmpty()) {
      getLogger().warning("No allowed materials to map! Check your config.");
      return;
    }

    Set<Material> falling = EnumSet.copyOf(Tag.FALLING_BLOCKS.getValues());
    Set<Material> liquids = EnumSet.of(Material.WATER, Material.LAVA);

    List<Material> solidsPool = allAllowed.stream()
        .filter(m -> !liquids.contains(m) && !falling.contains(m) && !m.isAir())
        .collect(Collectors.toCollection(ArrayList::new));

    List<Material> fallingPool = allAllowed.stream()
        .filter(falling::contains)
        .collect(Collectors.toCollection(ArrayList::new));

    List<Material> liquidPool = allAllowed.stream()
        .filter(liquids::contains)
        .collect(Collectors.toCollection(ArrayList::new));

    if (preserveCategories && oneToOne) {
      shuffleAndPairByCategory(solidsPool, m -> isSolid(m, falling, liquids));
      shuffleAndPairByCategory(fallingPool, falling::contains);
      shuffleAndPairByCategory(liquidPool, liquids::contains);
      return;
    }

    List<Material> allPool = new ArrayList<>(allAllowed);
    for (Material src : allAllowed) {
      Material dst;
      if (preserveCategories) {
        if (liquids.contains(src)) {
          dst = pickOrFallback(liquidPool, src, allPool);
        } else if (falling.contains(src)) {
          dst = pickOrFallback(fallingPool, src, allPool);
        } else {
          dst = pickOrFallback(solidsPool, src, allPool);
        }
      } else {
        dst = allPool.get(rng.nextInt(allPool.size()));
      }
      mapping.put(src, dst);
    }
  }

  private void shuffleAndPairByCategory(List<Material> pool, java.util.function.Predicate<Material> categoryCheck) {
    if (pool.isEmpty()) return;
    Collections.shuffle(pool, rng);
    int i = 0;
    for (Material src : Arrays.stream(Material.values())
        .filter(Material::isBlock)
        .filter(categoryCheck::test)
        .filter(m -> includeAir || !m.isAir())
        .filter(m -> !excluded.contains(m))
        .toList()) {
      Material dst = pool.get(i % pool.size());
      mapping.put(src, dst);
      i++;
    }
  }

  private Material pickOrFallback(List<Material> primaryPool, Material src, List<Material> fallbackPool) {
    if (!primaryPool.isEmpty()) return primaryPool.get(rng.nextInt(primaryPool.size()));
    return fallbackPool.get(rng.nextInt(fallbackPool.size()));
  }

  private boolean isSolid(Material m, Set<Material> falling, Set<Material> liquids) {
    return m.isBlock() && !m.isAir() && !falling.contains(m) && !liquids.contains(m);
  }

  public Map<Material, Material> getMapping() { return mapping; }
  public boolean isWorldAllowed(String worldName) { return worldFilter.isEmpty() || worldFilter.contains(worldName); }
  public int getYStepPerTick() { return yStepPerTick; }
}
