package it.gabrieledev.nautica.sail;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;

// plugin tickets on the chunks under and ahead of a sailing ship. Chunks are loaded asynchronously and
// only ticketed once loaded; until then the collision test treats them as solid, so a ship slows down
// at the edge of unloaded terrain instead of stalling the server
final class ChunkKeeper {

    private final Plugin plugin;
    private final World world;
    private final Set<Long> held = new HashSet<>();
    private final Set<Long> pending = new HashSet<>();
    private boolean released;

    ChunkKeeper(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    void update(double x, double z, double radius) {
        int r = (int) Math.ceil(radius);
        int cx0 = ((int) Math.floor(x) - r) >> 4, cx1 = ((int) Math.floor(x) + r) >> 4;
        int cz0 = ((int) Math.floor(z) - r) >> 4, cz1 = ((int) Math.floor(z) + r) >> 4;
        Set<Long> wanted = new HashSet<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                wanted.add(Chunk.getChunkKey(cx, cz));
            }
        }
        for (Long key : Set.copyOf(held)) {
            if (!wanted.contains(key)) {
                world.removePluginChunkTicket((int) (long) key, (int) (key >> 32), plugin);
                held.remove(key);
            }
        }
        for (long key : wanted) {
            if (held.contains(key) || !pending.add(key)) {
                continue;
            }
            int cx = (int) key, cz = (int) (key >> 32);
            world.getChunkAtAsync(cx, cz).thenAccept(chunk -> {
                pending.remove(key);
                if (!released && chunk != null && chunk.addPluginChunkTicket(plugin)) {
                    held.add(key);
                }
            });
        }
    }

    void releaseAll() {
        released = true;
        for (long key : held) {
            world.removePluginChunkTicket((int) key, (int) (key >> 32), plugin);
        }
        held.clear();
        pending.clear();
    }
}
