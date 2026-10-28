package it.gabrieledev.nautica.math;

import org.bukkit.World;
import org.bukkit.block.Block;

public record BlockPos(int x, int y, int z) {

    public static BlockPos of(Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public Block block(World world) {
        return world.getBlockAt(x, y, z);
    }

    public long key() {
        return pack(x, y, z);
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int unpackX(long key) {
        return (int) (key >> 38);
    }

    public static int unpackY(long key) {
        return (int) (key << 52 >> 52);
    }

    public static int unpackZ(long key) {
        return (int) (key << 26 >> 38);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }

    public static BlockPos parse(String s) {
        String[] p = s.split(",");
        return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }
}
