package dev.konrad.brr;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;

import java.util.*;
import java.util.logging.Logger;

/**
 * Main plugin class for Block Randomizer Reloaded (1.19.2-safe).
 * Builds a blacklist of "donor" materials we never touch and a pool of
 * safe full-block materials we may place as replacements.
 */
public final class BlockRandomizerReloaded extends JavaPlugin implements Listener {

    // Pools built from config + hard rules
    private volatile List<Material> replacementPool = null;  // what we can PLACE
    private volatile Set<Material> donorBlacklist = null;    // what we REFUSE TO REPLACE
    private volatile Set<String> enabledWorlds = Collections.emptySet();

    private final Random rng = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();       // creates config.yml if absent
        rebuildFromConfig();       // build pools and read settings

        // Register your listeners. Adjust class name/package if different.
        getServer().getPluginManager().registerEvents(new ChunkRandomizeListener(this), this);

        getLogger().info("[BRR] Enabled. Replacement pool size=" + replacementPool.size()
                + ", donor blacklist size=" + donorBlacklist.size());
    }

    @Override
    public void onDisable() {
        // nothing specific
    }

    /**
     * Public helper if you add a /brrreload command (optional).
     */
    public void reloadPools() {
        reloadConfig();
        rebuildFromConfig();
    }

    // =========================================================
    // === Methods the listener expects ========================
    // =========================================================

    public boolean isWorldEnabled(World world) {
        ensurePoolsBuilt();
        if (world == null) return false;
        if (enabledWorlds == null || enabledWorlds.isEmpty()) return true; // empty => all enabled
        return enabledWorlds.contains(world.getName());
    }

    /**
     * Whether the EXISTING block type is allowed to be replaced.
     * We refuse if it is air, liquid, gravity/unstable, thin/partial,
     * redstone/interactive/tile-entity-like, etc.
     */
    public boolean isAllowedReplacement(Material material) {
        ensurePoolsBuilt();
        return material != null
                && material.isBlock()
                && !material.isAir()
                && !donorBlacklist.contains(material);
    }

    /**
     * Pick a RANDOM material from the safe replacement pool.
     * Listener can pass its own RNG or use this.rng.
     */
    public Material pickRandomMaterial(Random rng) {
        ensurePoolsBuilt();
        if (replacementPool.isEmpty()) return Material.STONE;
        Random r = (rng != null ? rng : this.rng);
        return replacementPool.get(r.nextInt(replacementPool.size()));
    }

    // Convenience overload if your code ever needs it
    public Material pickRandomMaterial() {
        return pickRandomMaterial(this.rng);
    }

    // =========================================================
    // === Pool building / config ==============================
    // =========================================================

    private void rebuildFromConfig() {
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();
        Logger log = getLogger();

        // Worlds: empty list means "all worlds"
        enabledWorlds = new HashSet<>(cfg.getStringList("enabledWorlds"));

        // ---------------------------
        // Build donor blacklist (things we will NOT replace in the world)
        // ---------------------------
        EnumSet<Material> hard = EnumSet.noneOf(Material.class);

        // Air & liquids
        addIfPresent(hard, "AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA");

        // Gravity / unstable
        addIfPresent(hard, "SAND", "RED_SAND", "GRAVEL", "DRAGON_EGG");
        addConcretePowders(hard); // all 16 *_CONCRETE_POWDER

        // Thin/partial / interactives / updatables you banned (by suffix)
        addBySuffix(hard,
                "_SLAB", "_STAIRS", "_WALL", "_PANE",
                "_FENCE", "_FENCE_GATE",
                "_DOOR", "_TRAPDOOR",
                "_SIGN", "_WALL_SIGN",
                "_BANNER", "_BED",
                "_CARPET", "_BUTTON",
                "_PRESSURE_PLATE", "_RAIL"
        );

        // Specific interactives / TE-like blocks / redstone / misc
        addIfPresent(hard,
                // redstone bits
                "REDSTONE_BLOCK", "REDSTONE_TORCH", "REDSTONE_WALL_TORCH", "REPEATER", "COMPARATOR", "OBSERVER", "DAYLIGHT_DETECTOR",
                // power / physics
                "LIGHTNING_ROD", "END_ROD", "BELL", "TARGET",
                // containers & machines
                "CHEST", "TRAPPED_CHEST", "BARREL", "HOPPER",
                "DROPPER", "DISPENSER",
                "FURNACE", "SMOKER", "BLAST_FURNACE",
                // utility/tile entities
                "BREWING_STAND", "ENCHANTING_TABLE", "LECTERN", "CARTOGRAPHY_TABLE", "SMITHING_TABLE", "GRINDSTONE", "LOOM",
                "STONECUTTER", "COMPOSTER",
                // sculk + conduit + bell already
                "CONDUIT", "SCULK_SENSOR", "SCULK_SHRIEKER", "SCULK_CATALYST",
                // portal/frame-ish safety exclusions
                "END_PORTAL", "NETHER_PORTAL",
                // portals/frames you said fine: END_PORTAL_FRAME is allowed (do NOT add it here)
                // liquids/cauldrons
                "CAULDRON", "WATER_CAULDRON", "LAVA_CAULDRON", "POWDER_SNOW_CAULDRON",
                // hazards / special
                "TNT", "TRIPWIRE", "TRIPWIRE_HOOK", "LEVER",
                "SLIME_BLOCK", "HONEY_BLOCK",
                // heads
                "PLAYER_HEAD", "ZOMBIE_HEAD", "CREEPER_HEAD", "DRAGON_HEAD",
                "SKELETON_SKULL", "WITHER_SKELETON_SKULL",
                // snow layers & powder
                "SNOW", "POWDER_SNOW",
                // farmland / crops soil
                "FARMLAND",
                // campfires
                "CAMPFIRE", "SOUL_CAMPFIRE",
                // structure/command (just in case)
                "STRUCTURE_BLOCK", "JIGSAW", "COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK",
                // lodestone
                "LODESTONE",
                // bars/panes (suffix already covers many)
                "IRON_BARS"
        );

        // Optional extra safety: leave bedrock alone
        addIfPresent(hard, "BEDROCK");

        // Merge configurable exclusions
        List<String> cfgExStrings = cfg.getStringList("excludeMaterials");
        if (cfgExStrings != null) {
            for (String s : cfgExStrings) {
                if (s == null) continue;
                try {
                    Material m = Material.valueOf(s.trim().toUpperCase(Locale.ROOT));
                    hard.add(m);
                } catch (IllegalArgumentException ex) {
                    log.warning("[BRR] Unknown excludeMaterials entry ignored: " + s);
                }
            }
        }
        donorBlacklist = Collections.unmodifiableSet(hard);

        // ---------------------------
        // Build replacement pool (safe full blocks to place)
        // ---------------------------
        ArrayList<Material> pool = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isBlock()) continue;
            if (m.isAir()) continue;
            if (donorBlacklist.contains(m)) continue;

            // Basic solidity (keeps glass out too, unless explicitly re-added below)
            if (!m.isSolid()) continue;

            // Extra gravity/liquid guard
            if (m == Material.WATER || m == Material.LAVA
                    || m == Material.SAND || m == Material.RED_SAND || m == Material.GRAVEL
                    || isConcretePowder(m)) continue;

            pool.add(m);
        }

        // If you DO want some full, transparent light blocks, explicitly add them:
        addIfPresent(pool, "GLOWSTONE", "SEA_LANTERN", "SHROOMLIGHT");
        // If you want GLASS/TINTED_GLASS included, uncomment the next line:
        // addIfPresent(pool, "GLASS", "TINTED_GLASS");

        if (pool.isEmpty()) {
            pool.add(Material.STONE);
            log.warning("[BRR] Replacement pool ended empty; defaulting to STONE only.");
        }
        replacementPool = Collections.unmodifiableList(pool);

        log.info("[BRR] Built pools. replacementPool=" + replacementPool.size()
                + ", donorBlacklist=" + donorBlacklist.size()
                + ", enabledWorlds=" + (enabledWorlds == null ? 0 : enabledWorlds.size()));
    }

    private void ensurePoolsBuilt() {
        if (replacementPool == null || donorBlacklist == null) {
            rebuildFromConfig();
        }
    }

    // =========================================================
    // === Small helpers =======================================
    // =========================================================

    private static void addIfPresent(Collection<Material> target, String... names) {
        for (String n : names) {
            try {
                target.add(Material.valueOf(n));
            } catch (IllegalArgumentException ignored) {
                // ignore materials that don't exist in 1.19.2
            }
        }
    }

    private static void addConcretePowders(Collection<Material> target) {
        addIfPresent(target,
                "WHITE_CONCRETE_POWDER","ORANGE_CONCRETE_POWDER","MAGENTA_CONCRETE_POWDER","LIGHT_BLUE_CONCRETE_POWDER",
                "YELLOW_CONCRETE_POWDER","LIME_CONCRETE_POWDER","PINK_CONCRETE_POWDER","GRAY_CONCRETE_POWDER",
                "LIGHT_GRAY_CONCRETE_POWDER","CYAN_CONCRETE_POWDER","PURPLE_CONCRETE_POWDER","BLUE_CONCRETE_POWDER",
                "BROWN_CONCRETE_POWDER","GREEN_CONCRETE_POWDER","RED_CONCRETE_POWDER","BLACK_CONCRETE_POWDER"
        );
    }

    private static boolean isConcretePowder(Material m) {
        return m != null && m.name().endsWith("_CONCRETE_POWDER");
    }

    private static void addBySuffix(Collection<Material> target, String... suffixes) {
        if (suffixes == null || suffixes.length == 0) return;
        for (Material m : Material.values()) {
            String name = m.name();
            for (String suf : suffixes) {
                if (name.endsWith(suf)) {
                    target.add(m);
                    break;
                }
            }
        }
    }
}
