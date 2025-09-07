package dev.konrad.brr;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class PotionChaosListener implements Listener {
    private final BlockRandomizerReloaded plugin;

    public PotionChaosListener(BlockRandomizerReloaded plugin) {
        this.plugin = plugin;
    }

    // Randomize drink-applied effects by canceling and substituting
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPotionEffect(EntityPotionEffectEvent event) {
        if (!isEnabled()) return;
        EntityPotionEffectEvent.Cause cause = event.getCause();
        if (cause != EntityPotionEffectEvent.Cause.POTION_DRINK) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        LivingEntity le = (LivingEntity) event.getEntity();
        World w = le.getWorld();
        if (!plugin.isWorldEnabled(w)) return;
        // avoid interfering with plugin-applied effects
        if (cause == EntityPotionEffectEvent.Cause.PLUGIN) return;
        // Cancel the vanilla effect and apply random
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> applyRandomEffect(le));
    }

    // Replace splash potion effects
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!isEnabled()) return;
        World w = event.getEntity().getWorld();
        if (!plugin.isWorldEnabled(w)) return;
        event.setCancelled(true);
        for (LivingEntity le : event.getAffectedEntities()) {
            applyRandomEffect(le);
        }
    }

    // Replace lingering potion by applying random effect instantly and removing the cloud
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLingering(LingeringPotionSplashEvent event) {
        if (!isEnabled()) return;
        World w = event.getEntity().getWorld();
        if (!plugin.isWorldEnabled(w)) return;
        event.setCancelled(true);
        org.bukkit.entity.AreaEffectCloud cloud = event.getAreaEffectCloud();
        double radius = cloud.getRadius();
        org.bukkit.Location loc = cloud.getLocation();
        for (LivingEntity le : loc.getWorld().getNearbyLivingEntities(loc, radius + 0.5, radius + 0.5, radius + 0.5)) {
            applyRandomEffect(le);
        }
        cloud.remove();
    }

    private boolean isEnabled() {
        org.bukkit.configuration.ConfigurationSection t = plugin.getConfig().getConfigurationSection("trolling");
        if (t == null) return true;
        org.bukkit.configuration.ConfigurationSection pr = t.getConfigurationSection("potion-randomize");
        return pr == null || pr.getBoolean("enabled", true);
    }

    private static final List<PotionEffectType> ALLOWED = new ArrayList<>(Arrays.asList(
            PotionEffectType.SPEED,
            PotionEffectType.SLOW,
            PotionEffectType.FAST_DIGGING,
            PotionEffectType.SLOW_DIGGING,
            PotionEffectType.INCREASE_DAMAGE,
            PotionEffectType.JUMP,
            PotionEffectType.REGENERATION,
            PotionEffectType.DAMAGE_RESISTANCE,
            PotionEffectType.FIRE_RESISTANCE,
            PotionEffectType.WATER_BREATHING,
            PotionEffectType.INVISIBILITY,
            PotionEffectType.NIGHT_VISION,
            PotionEffectType.WEAKNESS,
            PotionEffectType.POISON,
            PotionEffectType.WITHER,
            PotionEffectType.LEVITATION,
            PotionEffectType.BLINDNESS,
            PotionEffectType.HUNGER,
            PotionEffectType.SATURATION,
            PotionEffectType.GLOWING,
            PotionEffectType.CONFUSION
    ));

    private static boolean isNegative(PotionEffectType t) {
        return t == PotionEffectType.SLOW || t == PotionEffectType.SLOW_DIGGING || t == PotionEffectType.WEAKNESS ||
               t == PotionEffectType.POISON || t == PotionEffectType.WITHER || t == PotionEffectType.BLINDNESS ||
               t == PotionEffectType.HUNGER || t == PotionEffectType.CONFUSION || t == PotionEffectType.LEVITATION;
    }

    private void applyRandomEffect(LivingEntity le) {
        // Choose an effect and duration/amplifier
        PotionEffectType type = ALLOWED.get(ThreadLocalRandom.current().nextInt(ALLOWED.size()));
        int duration; // in ticks
        int amplifier;
        if (isNegative(type)) {
            duration = (6 + ThreadLocalRandom.current().nextInt(9)) * 20; // 6-14s
            amplifier = ThreadLocalRandom.current().nextInt(0, 2); // 0-1
        } else {
            duration = (10 + ThreadLocalRandom.current().nextInt(36)) * 20; // 10-45s
            amplifier = ThreadLocalRandom.current().nextInt(0, 3); // 0-2
        }
        // Special-case SATURATION since it acts instantly; keep short
        if (type == PotionEffectType.SATURATION) {
            duration = 1;
            amplifier = 0;
        }
        PotionEffect pe = new PotionEffect(type, duration, amplifier, true, true, true);
        le.addPotionEffect(pe);
    }
}
