package it.gabrieledev.nautica.config;

import it.gabrieledev.nautica.ship.ModuleType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class NauticaConfig {

    public final int maxBlocks, maxWidth, maxLength, maxHeight;

    public final int updateTicks;
    public final double maxSpeedCap, acceleration, deceleration, turnRateMax, reverseFraction;
    public final double[] gears;
    public final double referenceTonnage, sailRatioForFullSpeed, bareHullFactor;

    public final int teleportDuration, rotationUpdateTicks;
    public final float viewRange;
    public final double seatYOffset;
    public final Map<String, Float> itemYawOffset = new HashMap<>();

    public final boolean wind, bobbing, rollOnTurn, particles, sounds, hud, windBossbar;
    public final double bobAmplitude, rollMaxDegrees, stormMultiplier;
    public final int bobPeriodTicks;

    public final int checkpointSeconds, archiveKeep;
    public final boolean requireClaimPermission, passengersCrewOnly;
    public final double matchThreshold;

    public final BlockRules blocks;

    public final double[] sailMultiplier, helmMultiplier;
    public final double standstillTurn, oarsMinSpeed, steamSpeed;
    public final int steamSecondsPerFuel;
    public final Map<ModuleType, Map<Integer, List<ItemStack>>> moduleCosts = new EnumMap<>(ModuleType.class);

    public final boolean recipes;

    public NauticaConfig(FileConfiguration c, Logger log) {
        maxBlocks = c.getInt("ship-size.max-blocks", 1500);
        maxWidth = c.getInt("ship-size.max-width", 24);
        maxLength = c.getInt("ship-size.max-length", 64);
        maxHeight = c.getInt("ship-size.max-height", 32);

        updateTicks = Math.max(1, c.getInt("movement.update-ticks", 1));
        maxSpeedCap = c.getDouble("movement.max-speed-cap", 0.6);
        acceleration = c.getDouble("movement.acceleration", 0.004);
        deceleration = c.getDouble("movement.deceleration", 0.002);
        turnRateMax = c.getDouble("movement.turn-rate-max", 3.0);
        List<Double> g = c.getDoubleList("movement.gears");
        gears = g.size() == 3 ? g.stream().mapToDouble(Double::doubleValue).toArray() : new double[]{0.45, 0.75, 1.0};
        reverseFraction = c.getDouble("movement.reverse-fraction", 0.3);
        referenceTonnage = Math.max(1, c.getDouble("movement.reference-tonnage", 300));
        sailRatioForFullSpeed = Math.max(0.01, c.getDouble("movement.sail-ratio-for-full-speed", 0.25));
        bareHullFactor = c.getDouble("movement.bare-hull-factor", 0.15);

        teleportDuration = Math.max(0, Math.min(59, c.getInt("render.teleport-duration", 2)));
        rotationUpdateTicks = Math.max(1, c.getInt("render.rotation-update-ticks", 2));
        viewRange = (float) c.getDouble("render.view-range", 1.5);
        seatYOffset = c.getDouble("render.seat-y-offset", -0.4);
        ConfigurationSection yaw = c.getConfigurationSection("render.item-yaw-offset");
        if (yaw != null) {
            for (String k : yaw.getKeys(false)) {
                itemYawOffset.put(k, (float) yaw.getDouble(k));
            }
        }

        wind = c.getBoolean("feel.wind", true);
        bobbing = c.getBoolean("feel.bobbing", true);
        rollOnTurn = c.getBoolean("feel.roll-on-turn", true);
        particles = c.getBoolean("feel.particles", true);
        sounds = c.getBoolean("feel.sounds", true);
        hud = c.getBoolean("feel.hud", true);
        windBossbar = c.getBoolean("feel.wind-bossbar", true);
        bobAmplitude = c.getDouble("feel.bob-amplitude", 0.06);
        bobPeriodTicks = Math.max(10, c.getInt("feel.bob-period-ticks", 80));
        rollMaxDegrees = c.getDouble("feel.roll-max-degrees", 4.0);
        stormMultiplier = c.getDouble("feel.storm-multiplier", 2.2);

        checkpointSeconds = Math.max(2, c.getInt("safety.snapshot-checkpoint-seconds", 10));
        requireClaimPermission = c.getBoolean("safety.require-claim-permission", true);
        passengersCrewOnly = c.getBoolean("safety.passengers-crew-only", false);
        archiveKeep = Math.max(0, c.getInt("safety.archive-keep", 5));
        matchThreshold = c.getDouble("safety.match-threshold", 0.9);

        ConfigurationSection bs = c.getConfigurationSection("blocks");
        blocks = BlockRules.load(bs == null ? c.createSection("blocks") : bs, log);

        sailMultiplier = levels(c.getDoubleList("modules.sails.multiplier"), new double[]{1.15, 1.3, 1.5});
        helmMultiplier = levels(c.getDoubleList("modules.helm.multiplier"), new double[]{1.2, 1.4, 1.4});
        standstillTurn = c.getDouble("modules.helm.standstill-turn", 0.8);
        oarsMinSpeed = c.getDouble("modules.oars.min-speed", 0.12);
        steamSpeed = c.getDouble("modules.steam.speed", 0.4);
        steamSecondsPerFuel = Math.max(1, c.getInt("modules.steam.seconds-per-fuel", 30));
        for (ModuleType type : ModuleType.values()) {
            Map<Integer, List<ItemStack>> byLevel = new HashMap<>();
            ConfigurationSection cost = c.getConfigurationSection("modules." + type.id() + ".cost");
            if (cost != null) {
                for (String lvl : cost.getKeys(false)) {
                    List<ItemStack> items = new ArrayList<>();
                    for (String s : cost.getStringList(lvl)) {
                        ItemStack item = parseCost(s, log);
                        if (item != null) {
                            items.add(item);
                        }
                    }
                    try {
                        byLevel.put(Integer.parseInt(lvl), items);
                    } catch (NumberFormatException e) {
                        log.warning("modules." + type.id() + ".cost: level is not a number: " + lvl);
                    }
                }
            }
            moduleCosts.put(type, byLevel);
        }

        recipes = c.getBoolean("recipes.enabled", true);
    }

    public List<ItemStack> cost(ModuleType type, int level) {
        return moduleCosts.getOrDefault(type, Map.of()).getOrDefault(level, List.of());
    }

    public float itemYaw(String kind) {
        return itemYawOffset.getOrDefault(kind, 0f);
    }

    private static double[] levels(List<Double> list, double[] def) {
        return list.isEmpty() ? def : list.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static ItemStack parseCost(String s, Logger log) {
        String[] p = s.split(":");
        Material m = Material.matchMaterial(p[0].trim());
        if (m == null || !m.isItem()) {
            log.warning("module cost: unknown item " + s + ", skipped");
            return null;
        }
        int amount = 1;
        if (p.length > 1) {
            try {
                amount = Integer.parseInt(p[p.length - 1].trim());
            } catch (NumberFormatException e) {
                log.warning("module cost: bad amount in " + s);
            }
        }
        return new ItemStack(m, Math.max(1, amount));
    }
}
