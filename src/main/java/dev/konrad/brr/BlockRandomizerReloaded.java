package dev.konrad.brr;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Location;
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
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.WorldBorder;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

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
    private BukkitTask soundsTask;
    private BukkitTask fakeMessageTask;
    private BukkitTask teleportTask;
    private BukkitTask handSwapTask;
    private BukkitTask ghostItemTask;
    private BukkitTask hungerTask;
    private BukkitTask requeueTask;
    private final ArrayDeque<Chunk> requeueQueue = new ArrayDeque<>();
    private int requeuePerTick = 5;

    // Prank config caches
    private List<Sound> prankSounds = new ArrayList<>();
    private List<String> fakeMessages = new ArrayList<>();
    private int soundCheckPeriodTicks = 100; // 5s
    private double soundPerCheckChance = 0.03; // 3% per player per check
    private boolean soundsEnabled = true;
    private boolean fakeMessagesEnabled = true;
    private int fakeMsgMinMinutes = 2;
    private int fakeMsgMaxMinutes = 20;
    private boolean randomizeWeatherOnRotate = true;
    private boolean teleportEnabled = true;
    private int tpMinMinutes = 1;
    private int tpMaxMinutes = 20;
    private int tpMinDistance = 1;
    private int tpMaxDistance = 10;
    private int tpMinCooldownSeconds = 30;
    private final Map<java.util.UUID, Long> lastTeleportAt = new HashMap<>();

    // Hand swap
    private boolean handSwapEnabled = true;
    private int handSwapMinMinutes = 2;
    private int handSwapMaxMinutes = 15;
    private int handSwapCooldownSeconds = 30;
    private final Map<java.util.UUID, Long> lastHandSwapAt = new HashMap<>();

    // Ghost items
    private boolean ghostItemsEnabled = true;
    private int ghostMinMinutes = 2;
    private int ghostMaxMinutes = 10;
    private int ghostPerEventMin = 1;
    private int ghostPerEventMax = 2;
    private int ghostDurationSeconds = 30;
    private NamespacedKey ghostKey;

    // Hunger jumps
    private boolean hungerEnabled = true;
    private int hungerMinMinutes = 2;
    private int hungerMaxMinutes = 12;
    private int hungerMinDelta = -6;
    private int hungerMaxDelta = 6;
    private int hungerCooldownSeconds = 20;
    private final Map<java.util.UUID, Long> lastHungerAt = new HashMap<>();

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
        Bukkit.getPluginManager().registerEvents(new BlockBreakDropListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PotionChaosListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GhostItemListener(this), this);
        Bukkit.getPluginManager().registerEvents(new NetherLavaWaterListener(this), this);

        ghostKey = new NamespacedKey(this, "ghost-item");

        // Optional periodic timer
        scheduleOrCancelPeriodic();
        // Mischief schedulers (sounds, fake messages)
        scheduleOrCancelPranks();
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
        if (paletteTask != null) {
            paletteTask.cancel();
            paletteTask = null;
        }
        if (soundsTask != null) {
            soundsTask.cancel();
            soundsTask = null;
        }
        if (fakeMessageTask != null) {
            fakeMessageTask.cancel();
            fakeMessageTask = null;
        }
        if (teleportTask != null) {
            teleportTask.cancel();
            teleportTask = null;
        }
        if (handSwapTask != null) {
            handSwapTask.cancel();
            handSwapTask = null;
        }
        if (ghostItemTask != null) {
            ghostItemTask.cancel();
            ghostItemTask = null;
        }
        if (hungerTask != null) {
            hungerTask.cancel();
            hungerTask = null;
        }
        if (requeueTask != null) {
            requeueTask.cancel();
            requeueTask = null;
            requeueQueue.clear();
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

        // Read prank-related configuration
        readPrankConfig(cfg);

        // Protected source blocks (never modify original if matches)
        protectedSourceBlocks.clear();
        buildProtectedSourceBlocks();

        // Build whitelist by exclusions
        replacementWhitelist.clear();
        buildWhitelistFromConfig();
        replacementList = new ArrayList<>(replacementWhitelist);
        buildDropCandidates();

        // Reset stats? keep across reloads
        scheduleOrCancelPeriodic();
        scheduleOrCancelPranks();
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
    private final Map<Material, Material> dropPaletteMap = new EnumMap<>(Material.class);

    private void rotatePalette() {
        paletteMap.clear();
        dropPaletteMap.clear();
        paletteEpoch++;
        getLogger().info("BRR palette rotated. Epoch=" + paletteEpoch + ". Re-queueing loaded chunks...");
        // Optional: randomize weather and time on rotate
        if (randomizeWeatherOnRotate) {
            randomizeWeatherAndTime();
        }
        // Re-queue loaded chunks gradually to avoid lag spikes
        requeueQueue.clear();
        for (World w : Bukkit.getWorlds()) {
            if (!isWorldEnabled(w)) continue;
            for (Chunk c : w.getLoadedChunks()) {
                requeueQueue.add(c);
            }
        }
        if (requeueTask != null) { requeueTask.cancel(); requeueTask = null; }
        // read knob (fallback to current value)
        int perTick = requeuePerTick;
        ConfigurationSection palSec = getConfig().getConfigurationSection("palette");
        if (palSec != null) perTick = Math.max(1, palSec.getInt("requeue-per-tick", requeuePerTick));
        requeuePerTick = perTick;
        requeueTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int n = 0;
            while (n < requeuePerTick && !requeueQueue.isEmpty()) {
                Chunk c = requeueQueue.pollFirst();
                if (c != null) queueChunk(c);
                n++;
            }
            if (requeueQueue.isEmpty()) {
                BukkitTask t = requeueTask;
                if (t != null) t.cancel();
                requeueTask = null;
            }
        }, 1L, 1L);
    }

    private void randomizeWeatherAndTime() {
        for (World w : Bukkit.getWorlds()) {
            if (!isWorldEnabled(w)) continue;
            // Weather
            double r = rng.nextDouble();
            if (r < 0.2) {
                w.setStorm(true);
                w.setThundering(true);
            } else if (r < 0.6) {
                w.setStorm(true);
                w.setThundering(false);
            } else {
                w.setStorm(false);
                w.setThundering(false);
            }
            // Time of day: pick one of key anchors with some jitter
            long[] anchors = new long[]{0L, 6000L, 12000L, 18000L};
            long pick = anchors[rng.nextInt(anchors.length)];
            long jitter = rng.nextInt(2000) - 1000; // +/- 1000 ticks
            long t = (pick + jitter) & 23999L;
            w.setTime(t);
        }
    }

    public Material getPaletteReplacement(Material source) {
        Material rep = paletteMap.get(source);
        if (rep != null) return rep;
        // Choose a deterministic-ish random per source per epoch
        // But for simplicity, just pick a random allowed material not equal to source
        Material picked = pickReplacementNotSource(source);
        paletteMap.put(source, picked);
        return picked;
    }

    // -------------------- Pranks (sounds + fake messages) --------------------
    private void readPrankConfig(FileConfiguration cfg) {
        ConfigurationSection troll = cfg.getConfigurationSection("trolling");
        if (troll == null) troll = cfg.createSection("trolling");

        // Sounds
        ConfigurationSection s = troll.getConfigurationSection("sounds");
        soundsEnabled = s == null || s.getBoolean("enabled", true);
        soundCheckPeriodTicks = s != null ? s.getInt("check-period-ticks", 100) : 100;
        soundPerCheckChance = s != null ? s.getDouble("per-check-chance", 0.03) : 0.03;
        prankSounds.clear();
        List<String> names = s != null ? s.getStringList("list") : Collections.emptyList();
        if (names != null && !names.isEmpty()) {
            for (String n : names) {
                try {
                    Sound snd = Sound.valueOf(n);
                    prankSounds.add(snd);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (prankSounds.isEmpty()) {
            // Fallback curated defaults; filter for existence by catching above
            String[] defaults = new String[]{
                    "ENTITY_CREEPER_PRIMED",
                    "ENTITY_GHAST_SCREAM",
                    "ENTITY_GHAST_WARN",
                    "ENTITY_ENDERMAN_SCREAM",
                    "ENTITY_ENDERMAN_STARE",
                    "ENTITY_PHANTOM_AMBIENT",
                    "BLOCK_PORTAL_TRAVEL",
                    "ENTITY_GENERIC_EXPLODE",
                    "ENTITY_TNT_PRIMED"
            };
            for (String n : defaults) {
                try { prankSounds.add(Sound.valueOf(n)); } catch (IllegalArgumentException ignored) {}
            }
        }

        // Fake messages
        ConfigurationSection fm = troll.getConfigurationSection("fake-messages");
        fakeMessagesEnabled = fm == null || fm.getBoolean("enabled", true);
        fakeMsgMinMinutes = fm != null ? Math.max(1, fm.getInt("min-minutes", 2)) : 2;
        fakeMsgMaxMinutes = fm != null ? Math.max(fakeMsgMinMinutes, fm.getInt("max-minutes", 20)) : 20;
        fakeMessages = fm != null ? fm.getStringList("messages") : new ArrayList<>();
        if (fakeMessages == null || fakeMessages.isEmpty()) {
            fakeMessages = new ArrayList<>();
            Collections.addAll(fakeMessages,
                    "§eYou feel dizzy…",
                    "§cYour memory fades…",
                    "§7A cold breeze passes by.",
                    "§5Whispers echo in the distance.",
                    "§6Your hands tremble briefly.");
        }

        // Weather/time hook
        ConfigurationSection wt = troll.getConfigurationSection("weather-time");
        randomizeWeatherOnRotate = wt == null || wt.getBoolean("randomize-on-palette-rotate", true);

        // Random teleports
        ConfigurationSection tp = troll.getConfigurationSection("random-teleport");
        teleportEnabled = tp == null || tp.getBoolean("enabled", true);
        tpMinMinutes = tp != null ? Math.max(1, tp.getInt("min-minutes", 1)) : 1;
        tpMaxMinutes = tp != null ? Math.max(tpMinMinutes, tp.getInt("max-minutes", 20)) : 20;
        tpMinDistance = tp != null ? Math.max(1, tp.getInt("min-distance-blocks", 1)) : 1;
        tpMaxDistance = tp != null ? Math.max(tpMinDistance, tp.getInt("max-distance-blocks", 10)) : 10;
        tpMinCooldownSeconds = tp != null ? Math.max(1, tp.getInt("min-cooldown-seconds", 30)) : 30;

        // Hand swap
        ConfigurationSection hs = troll.getConfigurationSection("hand-swap");
        handSwapEnabled = hs == null || hs.getBoolean("enabled", true);
        handSwapMinMinutes = hs != null ? Math.max(1, hs.getInt("min-minutes", 2)) : 2;
        handSwapMaxMinutes = hs != null ? Math.max(handSwapMinMinutes, hs.getInt("max-minutes", 15)) : 15;
        handSwapCooldownSeconds = hs != null ? Math.max(1, hs.getInt("min-cooldown-seconds", 30)) : 30;

        // Ghost items
        ConfigurationSection gi = troll.getConfigurationSection("ghost-items");
        ghostItemsEnabled = gi == null || gi.getBoolean("enabled", true);
        ghostMinMinutes = gi != null ? Math.max(1, gi.getInt("min-minutes", 2)) : 2;
        ghostMaxMinutes = gi != null ? Math.max(ghostMinMinutes, gi.getInt("max-minutes", 10)) : 10;
        ghostPerEventMin = gi != null ? Math.max(1, gi.getInt("count-min", 1)) : 1;
        ghostPerEventMax = gi != null ? Math.max(ghostPerEventMin, gi.getInt("count-max", 2)) : 2;
        ghostDurationSeconds = gi != null ? Math.max(5, gi.getInt("duration-seconds", 30)) : 30;

        // Hunger jumps
        ConfigurationSection hj = troll.getConfigurationSection("hunger-jumps");
        hungerEnabled = hj == null || hj.getBoolean("enabled", true);
        hungerMinMinutes = hj != null ? Math.max(1, hj.getInt("min-minutes", 2)) : 2;
        hungerMaxMinutes = hj != null ? Math.max(hungerMinMinutes, hj.getInt("max-minutes", 12)) : 12;
        hungerMinDelta = hj != null ? hj.getInt("delta-min", -6) : -6;
        hungerMaxDelta = hj != null ? Math.max(hungerMinDelta, hj.getInt("delta-max", 6)) : 6;
        hungerCooldownSeconds = hj != null ? Math.max(1, hj.getInt("min-cooldown-seconds", 20)) : 20;
    }

    private void scheduleOrCancelPranks() {
        // Sounds task
        if (soundsTask != null) { soundsTask.cancel(); soundsTask = null; }
        if (soundsEnabled && !prankSounds.isEmpty() && soundCheckPeriodTicks > 0) {
            long period = Math.max(1L, soundCheckPeriodTicks);
            soundsTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    World w = p.getWorld();
                    if (!isWorldEnabled(w)) continue;
                    if (rng.nextDouble() < soundPerCheckChance) {
                        Sound snd = prankSounds.get(rng.nextInt(prankSounds.size()));
                        Location base = p.getLocation();
                        double dx = (rng.nextDouble() * 10.0) - 5.0;
                        double dz = (rng.nextDouble() * 10.0) - 5.0;
                        Location at = base.clone().add(dx, 0, dz);
                        // Play only to the player to make it feel local and not spam others
                        p.playSound(at, snd, 1.2f, 1.0f + (float)((rng.nextDouble() - 0.5) * 0.4));
                    }
                }
            }, period, period);
        }

        // Fake messages task (recursive schedule with random delay)
        if (fakeMessageTask != null) { fakeMessageTask.cancel(); fakeMessageTask = null; }
        if (fakeMessagesEnabled && !fakeMessages.isEmpty()) {
            scheduleNextFakeMessage();
        }

        // Random teleport task (recursive schedule with random delay)
        if (teleportTask != null) { teleportTask.cancel(); teleportTask = null; }
        if (teleportEnabled) {
            scheduleNextTeleport();
        }

        // Hand swap task
        if (handSwapTask != null) { handSwapTask.cancel(); handSwapTask = null; }
        if (handSwapEnabled) {
            scheduleNextHandSwap();
        }

        // Ghost items task
        if (ghostItemTask != null) { ghostItemTask.cancel(); ghostItemTask = null; }
        if (ghostItemsEnabled) {
            scheduleNextGhostItems();
        }

        // Hunger jumps task
        if (hungerTask != null) { hungerTask.cancel(); hungerTask = null; }
        if (hungerEnabled) {
            scheduleNextHungerJump();
        }
    }

    private void scheduleNextFakeMessage() {
        int minTicks = fakeMsgMinMinutes * 60 * 20;
        int maxTicks = fakeMsgMaxMinutes * 60 * 20;
        int delay = ThreadLocalRandom.current().nextInt(minTicks, maxTicks + 1);
        fakeMessageTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (fakeMessagesEnabled && !fakeMessages.isEmpty()) {
                String msg = fakeMessages.get(rng.nextInt(fakeMessages.size()));
                Bukkit.broadcastMessage(msg);
            }
            // schedule the next one
            scheduleNextFakeMessage();
        }, Math.max(20L, delay));
    }

    private void scheduleNextTeleport() {
        int minTicks = tpMinMinutes * 60 * 20;
        int maxTicks = tpMaxMinutes * 60 * 20;
        int delay = ThreadLocalRandom.current().nextInt(minTicks, maxTicks + 1);
        teleportTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                performRandomTeleportEvent();
            } catch (Throwable ignored) {}
            scheduleNextTeleport();
        }, Math.max(20L, delay));
    }

    private void performRandomTeleportEvent() {
        List<Player> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (!isWorldEnabled(w)) continue;
            long last = lastTeleportAt.getOrDefault(p.getUniqueId(), 0L);
            if ((now - last) >= tpMinCooldownSeconds * 1000L) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return;
        Player target = candidates.get(rng.nextInt(candidates.size()));
        Location dest = findSafeTeleportNear(target, tpMinDistance, tpMaxDistance);
        if (dest != null) {
            // add slight yaw change for disorientation
            dest.setYaw((float) rng.nextInt(360));
            dest.setPitch(target.getLocation().getPitch());
            if (target.teleport(dest)) {
                lastTeleportAt.put(target.getUniqueId(), System.currentTimeMillis());
            }
        }
    }

    

    private boolean isSafeStand(World w, int x, int y, int z) {
        Material feet = w.getBlockAt(x, y, z).getType();
        Material head = w.getBlockAt(x, y + 1, z).getType();
        Material below = w.getBlockAt(x, y - 1, z).getType();
        if (!feet.isAir()) return false;
        if (!head.isAir()) return false;
        if (!below.isSolid()) return false;
        if (isDangerous(below)) return false;
        return true;
    }

    private boolean isDangerous(Material m) {
        String n = m.name();
        if (m == Material.LAVA || m == Material.WATER) return true;
        if (n.equals("MAGMA_BLOCK") || n.equals("FIRE") || n.equals("CAMPFIRE") || n.equals("SOUL_CAMPFIRE") || n.equals("CACTUS") || n.equals("SWEET_BERRY_BUSH")) return true;
        return false;
    }

    // Prefer surface-level teleports; avoid underground or sky, avoid Nether roof
    private Location findSafeTeleportNear(Player p, int minDist, int maxDist) {
        World w = p.getWorld();
        Location base = p.getLocation();
        WorldBorder border = w.getWorldBorder();
        int attempts = 24;
        boolean isNether = w.getEnvironment() == World.Environment.NETHER;
        for (int i = 0; i < attempts; i++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            int dist = minDist + rng.nextInt(Math.max(1, (maxDist - minDist + 1)));
            int tx = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int tz = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            Integer yCandidate;
            if (isNether) {
                yCandidate = findNetherSurfaceY(w, tx, tz);
            } else {
                yCandidate = findOverworldSurfaceY(w, tx, tz);
            }
            if (yCandidate == null) continue;
            Location loc = new Location(w, tx + 0.5, yCandidate + 0.01, tz + 0.5);
            if (border != null && !border.isInside(loc)) continue;
            return loc;
        }
        return null;
    }

    private Integer findOverworldSurfaceY(World w, int x, int z) {
        int topY = w.getHighestBlockYAt(x, z);
        if (!withinWorldY(w, topY)) return null;
        int y = topY + 1;
        if (!withinWorldY(w, y)) return null;
        if (isSafeStand(w, x, y, z)) return y;
        // small downward search to avoid leaves/carpets
        for (int dy = 0; dy < 6; dy++) {
            int yy = topY - dy;
            if (!withinWorldY(w, yy + 1)) break;
            if (isSafeStand(w, x, yy + 1, z)) return yy + 1;
        }
        return null;
    }

    private Integer findNetherSurfaceY(World w, int x, int z) {
        int maxTop = Math.min(126, w.getMaxHeight() - 2); // avoid roof >126
        // Scan downward from ceiling to find first safe standing spot under bedrock roof
        for (int y = maxTop; y >= w.getMinHeight() + 2; y--) {
            if (isSafeStand(w, x, y, z)) {
                // Ensure below isn't bedrock ceiling with air pocket above (prevents roof)
                Material below = w.getBlockAt(x, y - 1, z).getType();
                if (below != Material.BEDROCK) return y;
            }
        }
        return null;
    }

    private void scheduleNextHandSwap() {
        int minTicks = handSwapMinMinutes * 60 * 20;
        int maxTicks = handSwapMaxMinutes * 60 * 20;
        int delay = ThreadLocalRandom.current().nextInt(minTicks, maxTicks + 1);
        handSwapTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            try { performHandSwapEvent(); } catch (Throwable ignored) {}
            scheduleNextHandSwap();
        }, Math.max(20L, delay));
    }

    private void performHandSwapEvent() {
        List<Player> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isWorldEnabled(p.getWorld())) continue;
            long last = lastHandSwapAt.getOrDefault(p.getUniqueId(), 0L);
            if ((now - last) >= handSwapCooldownSeconds * 1000L) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return;
        Player target = candidates.get(rng.nextInt(candidates.size()));
        org.bukkit.inventory.PlayerInventory inv = target.getInventory();
        org.bukkit.inventory.ItemStack main = inv.getItemInMainHand();
        org.bukkit.inventory.ItemStack off = inv.getItemInOffHand();
        inv.setItemInMainHand(off);
        inv.setItemInOffHand(main);
        lastHandSwapAt.put(target.getUniqueId(), System.currentTimeMillis());
    }

    private void scheduleNextGhostItems() {
        int minTicks = ghostMinMinutes * 60 * 20;
        int maxTicks = ghostMaxMinutes * 60 * 20;
        int delay = ThreadLocalRandom.current().nextInt(minTicks, maxTicks + 1);
        ghostItemTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            try { performGhostItemEvent(); } catch (Throwable ignored) {}
            scheduleNextGhostItems();
        }, Math.max(20L, delay));
    }

    private void performGhostItemEvent() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) return;
        Player p = online.get(rng.nextInt(online.size()));
        if (!isWorldEnabled(p.getWorld())) return;

        int count = ghostPerEventMin + rng.nextInt(Math.max(1, ghostPerEventMax - ghostPerEventMin + 1));
        for (int i = 0; i < count; i++) {
            int slot = pickRandomEmptyInventorySlot(p);
            if (slot < 0) break;
            org.bukkit.inventory.ItemStack ghost = makeGhostItem();
            p.getInventory().setItem(slot, ghost);
            long removeDelay = Math.max(20L, ghostDurationSeconds * 20L);
            Bukkit.getScheduler().runTaskLater(this, () -> {
            org.bukkit.inventory.ItemStack cur = p.getInventory().getItem(slot);
            if (cur != null && isGhostItem(cur)) {
                p.getInventory().setItem(slot, null);
            }
        }, removeDelay);
    }
    }

    private int pickRandomEmptyInventorySlot(Player p) {
        List<Integer> empty = new ArrayList<>();
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        // Avoid armor/offhand slots; use main inventory 0..35 and hotbar 0..8 included
        for (int i = 0; i < 36; i++) {
            org.bukkit.inventory.ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) empty.add(i);
        }
        if (empty.isEmpty()) return -1;
        return empty.get(rng.nextInt(empty.size()));
    }

    private org.bukkit.inventory.ItemStack makeGhostItem() {
        Material m = pickValuableMaterial();
        if (m == null || m == Material.AIR) m = Material.DIAMOND_BLOCK;
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(m, 1);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            // Keep vanilla-looking name; only tag via PDC
            if (ghostKey != null) {
                meta.getPersistentDataContainer().set(ghostKey, PersistentDataType.BYTE, (byte)1);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static final Material[] VALUABLES = new Material[]{
            // Blocks and ores
            Material.NETHERITE_BLOCK, Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.ANCIENT_DEBRIS,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.GOLD_BLOCK, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.IRON_BLOCK,
            // Items
            Material.ELYTRA, Material.NETHERITE_INGOT, Material.NETHER_STAR, Material.TOTEM_OF_UNDYING,
            Material.ENCHANTED_GOLDEN_APPLE, Material.GOLDEN_APPLE,
            // Tools/armor
            Material.NETHERITE_SWORD, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE, Material.NETHERITE_SHOVEL,
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_SHOVEL,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            // Rares
            Material.DRAGON_EGG, Material.DRAGON_HEAD, Material.BEACON, Material.SHULKER_BOX
    };

    private Material pickValuableMaterial() {
        // ensure materials exist for this MC version; fallback to diamond block
        for (int attempts = 0; attempts < 6; attempts++) {
            Material m = VALUABLES[rng.nextInt(VALUABLES.length)];
            if (m != null) return m;
        }
        return Material.DIAMOND_BLOCK;
    }

    public boolean isGhostItem(org.bukkit.inventory.ItemStack it) {
        if (it == null) return false;
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        if (ghostKey == null) return false;
        Byte b = meta.getPersistentDataContainer().get(ghostKey, PersistentDataType.BYTE);
        return b != null && b == (byte)1;
    }

    private void scheduleNextHungerJump() {
        int minTicks = hungerMinMinutes * 60 * 20;
        int maxTicks = hungerMaxMinutes * 60 * 20;
        int delay = ThreadLocalRandom.current().nextInt(minTicks, maxTicks + 1);
        hungerTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            try { performHungerJumpEvent(); } catch (Throwable ignored) {}
            scheduleNextHungerJump();
        }, Math.max(20L, delay));
    }

    private void performHungerJumpEvent() {
        List<Player> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isWorldEnabled(p.getWorld())) continue;
            long last = lastHungerAt.getOrDefault(p.getUniqueId(), 0L);
            if ((now - last) >= hungerCooldownSeconds * 1000L) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return;
        Player target = candidates.get(rng.nextInt(candidates.size()));
        int delta = hungerMinDelta + rng.nextInt(Math.max(1, hungerMaxDelta - hungerMinDelta + 1));
        int newFood = Math.max(0, Math.min(20, target.getFoodLevel() + delta));
        target.setFoodLevel(newFood);
        lastHungerAt.put(target.getUniqueId(), System.currentTimeMillis());
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

    // Drop palette (allows disallowed categories for drops only; still forbids WATER/LAVA)
    public Material getDropPaletteReplacement(Material source) {
        Material rep = dropPaletteMap.get(source);
        if (rep != null) return rep;
        Material picked = pickDropReplacementNotSource(source);
        dropPaletteMap.put(source, picked);
        return picked;
    }

    private final Set<Material> dropCandidates = EnumSet.noneOf(Material.class);
    private List<Material> dropList = new ArrayList<>();

    private void buildDropCandidates() {
        dropCandidates.clear();
        for (Material m : Material.values()) {
            if (!m.isBlock()) continue; // drops should be block items
            if (m.isAir()) continue;
            if (m == Material.WATER || m == Material.LAVA) continue;
            String n = m.name();
            // Exclude problematic creative-only meta blocks
            if (n.equals("STRUCTURE_VOID") || n.equals("JIGSAW") || n.equals("LIGHT")) continue;
            // Allow everything else (containers, redstone, waterloggable, etc.)
            dropCandidates.add(m);
        }
        dropList = new ArrayList<>(dropCandidates);
        if (dropList.isEmpty()) {
            dropList.add(Material.STONE);
        }
    }

    private Material pickDropReplacementNotSource(Material source) {
        if (dropList.isEmpty()) buildDropCandidates();
        Material pick = dropList.get(rng.nextInt(dropList.size()));
        for (int i = 0; i < 8 && pick == source; i++) {
            pick = dropList.get(rng.nextInt(dropList.size()));
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
        addIfPresent(protectedSourceBlocks, "LECTERN");
        addIfPresent(protectedSourceBlocks, "CRAFTING_TABLE");
        addIfPresent(protectedSourceBlocks, "FLETCHING_TABLE");
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
        // If '*' present, or list empty, treat as all worlds enabled
        if (enabledWorldNames.isEmpty() || enabledWorldNames.contains("*")) return true;
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
        // Never replace any door block as a source
        String tn = t.name();
        if (tn.endsWith("_DOOR")) return true;
        // Do not replace flowers or grass (tall and small variants)
        if (isFlowerOrGrass(t)) return true;
        // Preserve common workstations even if not block entities
        if (t == Material.LECTERN || t == Material.CRAFTING_TABLE || tn.equals("FLETCHING_TABLE")) return true;
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
        int yFrom;
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER || env == World.Environment.THE_END) {
            yFrom = Math.max(0, worldMin);
        } else {
            yFrom = Math.max(minY, worldMin);
        }
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
            sender.sendMessage("/brr reload | stats | here | rotate | sound [player] | ghost [player] | teleport [player] | handswap [player] | hunger [player] | message");
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
        } else if (sub.equals("sound")) {
            if (!sender.hasPermission("brr.admin")) { sender.sendMessage("You don't have permission."); return true; }
            Player target = resolveTargetPlayer(sender, args, 1);
            if (target == null) { sender.sendMessage("Usage: /brr sound [player]"); return true; }
            triggerRandomSound(target);
            sender.sendMessage("Played random sound near " + target.getName());
            return true;
        } else if (sub.equals("ghost")) {
            if (!sender.hasPermission("brr.admin")) { sender.sendMessage("You don't have permission."); return true; }
            Player target = resolveTargetPlayer(sender, args, 1);
            if (target == null) { sender.sendMessage("Usage: /brr ghost [player]"); return true; }
            insertGhostItems(target, Math.max(1, ghostPerEventMin));
            sender.sendMessage("Inserted ghost item(s) into " + target.getName() + "'s inventory");
            return true;
        } else if (sub.equals("teleport")) {
            if (!sender.hasPermission("brr.admin")) { sender.sendMessage("You don't have permission."); return true; }
            Player target = resolveTargetPlayer(sender, args, 1);
            if (target == null) { sender.sendMessage("Usage: /brr teleport [player]"); return true; }
            randomTeleportPlayer(target);
            sender.sendMessage("Teleported " + target.getName());
            return true;
        } else if (sub.equals("handswap")) {
            if (!sender.hasPermission("brr.admin")) { sender.sendMessage("You don't have permission."); return true; }
            Player target = resolveTargetPlayer(sender, args, 1);
            if (target == null) { sender.sendMessage("Usage: /brr handswap [player]"); return true; }
            handSwapOnce(target);
            sender.sendMessage("Swapped hands for " + target.getName());
            return true;
        } else if (sub.equals("hunger")) {
            if (!sender.hasPermission("brr.admin")) { sender.sendMessage("You don't have permission."); return true; }
            Player target = resolveTargetPlayer(sender, args, 1);
            if (target == null) { sender.sendMessage("Usage: /brr hunger [player]"); return true; }
            hungerJumpOnce(target);
            sender.sendMessage("Adjusted hunger for " + target.getName());
            return true;
        } else if (sub.equals("message")) {
            if (!sender.hasPermission("brr.admin")) { sender.sendMessage("You don't have permission."); return true; }
            broadcastRandomFakeMessage();
            sender.sendMessage("Broadcasted a fake message.");
            return true;
        }
        return false;
    }

    private Player resolveTargetPlayer(CommandSender sender, String[] args, int idx) {
        if (args.length > idx) {
            Player p = Bukkit.getPlayerExact(args[idx]);
            if (p != null) return p;
            // try partial match
            for (Player op : Bukkit.getOnlinePlayers()) {
                if (op.getName().toLowerCase().startsWith(args[idx].toLowerCase())) return op;
            }
            return null;
        }
        if (sender instanceof Player) return (Player) sender;
        return null;
    }

    private void triggerRandomSound(Player p) {
        if (!soundsEnabled || prankSounds.isEmpty()) return;
        if (!isWorldEnabled(p.getWorld())) return;
        Sound snd = prankSounds.get(rng.nextInt(prankSounds.size()));
        Location base = p.getLocation();
        double dx = (rng.nextDouble() * 10.0) - 5.0;
        double dz = (rng.nextDouble() * 10.0) - 5.0;
        Location at = base.clone().add(dx, 0, dz);
        p.playSound(at, snd, 1.2f, 1.0f + (float)((rng.nextDouble() - 0.5) * 0.4));
    }

    private void insertGhostItems(Player p, int count) {
        if (!ghostItemsEnabled) return;
        if (!isWorldEnabled(p.getWorld())) return;
        for (int i = 0; i < count; i++) {
            int slot = pickRandomEmptyInventorySlot(p);
            if (slot < 0) break;
            org.bukkit.inventory.ItemStack ghost = makeGhostItem();
            p.getInventory().setItem(slot, ghost);
            long removeDelay = Math.max(20L, ghostDurationSeconds * 20L);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                org.bukkit.inventory.ItemStack cur = p.getInventory().getItem(slot);
                if (cur != null && isGhostItem(cur)) {
                    p.getInventory().setItem(slot, null);
                }
            }, removeDelay);
        }
    }

    private void randomTeleportPlayer(Player p) {
        if (!teleportEnabled) return;
        if (!isWorldEnabled(p.getWorld())) return;
        Location dest = findSafeTeleportNear(p, tpMinDistance, tpMaxDistance);
        if (dest != null) {
            dest.setYaw((float) rng.nextInt(360));
            dest.setPitch(p.getLocation().getPitch());
            p.teleport(dest);
        }
    }

    private void handSwapOnce(Player p) {
        if (!handSwapEnabled) return;
        if (!isWorldEnabled(p.getWorld())) return;
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        org.bukkit.inventory.ItemStack main = inv.getItemInMainHand();
        org.bukkit.inventory.ItemStack off = inv.getItemInOffHand();
        inv.setItemInMainHand(off);
        inv.setItemInOffHand(main);
    }

    private void hungerJumpOnce(Player p) {
        if (!hungerEnabled) return;
        if (!isWorldEnabled(p.getWorld())) return;
        int delta = hungerMinDelta + rng.nextInt(Math.max(1, hungerMaxDelta - hungerMinDelta + 1));
        int newFood = Math.max(0, Math.min(20, p.getFoodLevel() + delta));
        p.setFoodLevel(newFood);
    }

    private void broadcastRandomFakeMessage() {
        if (!fakeMessagesEnabled || fakeMessages.isEmpty()) return;
        String msg = fakeMessages.get(rng.nextInt(fakeMessages.size()));
        Bukkit.broadcastMessage(msg);
    }
}
