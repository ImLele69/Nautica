package it.gabrieledev.nautica.sail;

import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

// one wind per world, drifting slowly: a random walk on direction and a pull of the strength toward a
// target set by the weather. Ticked once a second
public final class WindSystem {

    private static final class State {
        double heading;
        double strength;
    }

    private final Map<UUID, State> worlds = new HashMap<>();
    private final Random random = new Random();

    public Wind get(World world) {
        State s = worlds.computeIfAbsent(world.getUID(), id -> {
            State n = new State();
            n.heading = random.nextDouble() * 360;
            n.strength = 0.4 + random.nextDouble() * 0.3;
            return n;
        });
        return new Wind((float) s.heading, s.strength);
    }

    public void tickSecond(Iterable<World> active) {
        for (World world : active) {
            get(world);
            State s = worlds.get(world.getUID());
            double target = world.isThundering() ? 0.95 : world.hasStorm() ? 0.7 : 0.45;
            double gust = world.isThundering() ? 3.0 : 1.0;
            s.heading = (s.heading + random.nextGaussian() * 2.5 * gust + 360) % 360;
            s.strength += (target - s.strength) * 0.02 + random.nextGaussian() * 0.015 * gust;
            s.strength = Math.max(0.05, Math.min(1.0, s.strength));
        }
    }
}
