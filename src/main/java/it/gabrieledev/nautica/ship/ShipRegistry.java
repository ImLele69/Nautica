package it.gabrieledev.nautica.ship;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.math.BlockPos;
import it.gabrieledev.nautica.math.Pose;
import it.gabrieledev.nautica.math.Rot;
import org.bukkit.Location;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// ships by id, plus an index of the cells each docked ship occupies, so that two ships moored side by
// side never merge in a flood fill. The index reflects the ship as it was docked: blocks added later by
// players belong to whoever assembles first
public final class ShipRegistry {

    private final Map<UUID, ShipRecord> records = new HashMap<>();
    private final Map<String, Map<Long, UUID>> footprint = new HashMap<>();
    private final Map<UUID, long[]> footprintByShip = new HashMap<>();

    public void add(ShipRecord record) {
        records.put(record.id(), record);
    }

    public ShipRecord get(UUID id) {
        return id == null ? null : records.get(id);
    }

    public ShipRecord byShortId(String prefix) {
        ShipRecord found = null;
        for (ShipRecord r : records.values()) {
            if (r.id().toString().startsWith(prefix.toLowerCase(Locale.ROOT))) {
                if (found != null) {
                    return null;
                }
                found = r;
            }
        }
        return found;
    }

    public Collection<ShipRecord> all() {
        return records.values();
    }

    public void remove(UUID id) {
        unindex(id);
        records.remove(id);
    }

    public void index(ShipRecord record, Blueprint bp) {
        unindex(record.id());
        Pose pose = record.docked();
        if (pose == null || record.world() == null || bp == null) {
            return;
        }
        BlockPos pivot = pose.pivotBlock();
        int k = Rot.quarterTurns(bp.heading(), pose.heading());
        long[] keys = new long[bp.size()];
        Map<Long, UUID> map = footprint.computeIfAbsent(record.world(), w -> new HashMap<>());
        for (int i = 0; i < bp.size(); i++) {
            int x = pivot.x() + Rot.rotX(bp.dx(i), bp.dz(i), k);
            int z = pivot.z() + Rot.rotZ(bp.dx(i), bp.dz(i), k);
            keys[i] = BlockPos.pack(x, pivot.y() + bp.dy(i), z);
            map.put(keys[i], record.id());
        }
        footprintByShip.put(record.id(), keys);
    }

    public void unindex(UUID id) {
        long[] keys = footprintByShip.remove(id);
        ShipRecord r = records.get(id);
        if (keys == null || r == null || r.world() == null) {
            return;
        }
        Map<Long, UUID> map = footprint.get(r.world());
        if (map != null) {
            for (long key : keys) {
                map.remove(key, id);
            }
        }
    }

    public UUID shipAt(String world, int x, int y, int z) {
        Map<Long, UUID> map = footprint.get(world);
        return map == null ? null : map.get(BlockPos.pack(x, y, z));
    }

    // the docked ship whose helm is closest to at, within range blocks
    public ShipRecord nearestDocked(Location at, double range) {
        ShipRecord best = null;
        double bestSq = range * range;
        for (ShipRecord r : records.values()) {
            if (r.state() != ShipState.DOCKED || r.docked() == null || !at.getWorld().getName().equals(r.world())) {
                continue;
            }
            double dx = r.docked().x() - at.getX();
            double dy = r.docked().y() - at.getY();
            double dz = r.docked().z() - at.getZ();
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = r;
            }
        }
        return best;
    }
}
