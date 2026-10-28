package it.gabrieledev.nautica.world;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.fixture.Fixtures;
import it.gabrieledev.nautica.math.BlockPos;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.List;

// what an assembly read from the world: the blueprint plus where each of its parts came from, so the
// same blocks and entities can be checked again and removed once the snapshot is safely on disk.
//
// cells: world position of blueprint cell i
// dry: world positions of the dry hull interior
// partners: land halves of double chests split by the capture, to be made single on removal
public record Capture(World world, BlockPos pivot, int waterY, Blueprint blueprint, List<BlockPos> cells,
                      List<BlockPos> dry, List<Entity> entities, List<Fixtures.Found> fixtures,
                      List<BlockPos> partners) {
}
