package elucent.albedo.lighting;

import elucent.albedo.Albedo;
import elucent.albedo.ConfigManager;
import elucent.albedo.EventManager;
import elucent.albedo.event.GatherLightsEvent;
import elucent.albedo.util.TriConsumer;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds blocks with a registered light handler ({@link Albedo#registerBlockHandler}) around the camera and
 * collects their lights into {@link EventManager#EXISTING}.
 * <p>
 * Replaces the 1.1.0 background thread, which read the client world from outside the main thread (unsafe: chunks
 * are loaded and unloaded concurrently), scanned the whole box again and again in a busy loop that kept one CPU
 * core at 100%, allocated an event and a list for every block, never removed lights of blocks that went out of
 * range, and kept the old world's lights after a dimension change. This scan runs on the client thread, a bounded
 * number of 16x16x16 sections per tick, skips empty sections, and swaps in the result once a pass is complete.
 */
public final class BlockLightScanner {
    /** Sections (4096 blocks each) scanned per client tick. */
    private static final int SECTIONS_PER_TICK = 24;

    private World world;
    private final List<int[]> sections = new ArrayList<>();
    private int nextSection;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private Vec3d scanCenter;
    private Map<BlockPos, List<Light>> found = new HashMap<>();

    /** Call once per client tick (client thread). */
    public void tick(World currentWorld, Entity viewer) {
        if (currentWorld == null || viewer == null || !ConfigManager.isLightingEnabled() || !Albedo.hasBlockHandlers()) {
            reset();
            return;
        }
        if (currentWorld != world) {
            reset();
            world = currentWorld;
        }
        if (nextSection >= sections.size()) {
            if (scanCenter != null) {
                publish();
            }
            startPass(viewer);
        }
        int budget = SECTIONS_PER_TICK;
        while (budget-- > 0 && nextSection < sections.size()) {
            int[] s = sections.get(nextSection++);
            scanSection(s[0], s[1], s[2]);
        }
    }

    private void reset() {
        world = null;
        sections.clear();
        nextSection = 0;
        scanCenter = null;
        found = new HashMap<>();
        if (!EventManager.EXISTING.isEmpty()) {
            EventManager.EXISTING.clear();
        }
    }

    private void publish() {
        synchronized (EventManager.EXISTING) {
            EventManager.EXISTING.clear();
            EventManager.EXISTING.putAll(found);
        }
        found = new HashMap<>();
    }

    private void startPass(Entity viewer) {
        sections.clear();
        nextSection = 0;
        scanCenter = viewer.getPositionEyes(1.0F);
        BlockPos center = new BlockPos(viewer);
        int r = ConfigManager.maxDistance / 2;
        minX = center.getX() - r;
        maxX = center.getX() + r;
        minY = Math.max(0, center.getY() - r);
        maxY = Math.min(255, center.getY() + r);
        minZ = center.getZ() - r;
        maxZ = center.getZ() + r;
        if (minY > maxY) {
            return;
        }
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                for (int cy = minY >> 4; cy <= maxY >> 4; cy++) {
                    sections.add(new int[]{cx, cy, cz});
                }
            }
        }
    }

    private void scanSection(int cx, int cy, int cz) {
        Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        ExtendedBlockStorage storage = chunk.getBlockStorageArray()[cy];
        if (storage == Chunk.NULL_BLOCK_STORAGE || storage.isEmpty()) {
            return;
        }
        int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) + 15);
        int y0 = Math.max(minY, cy << 4), y1 = Math.min(maxY, (cy << 4) + 15);
        int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) + 15);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    IBlockState state = storage.get(x & 15, y & 15, z & 15);
                    Block block = state.getBlock();
                    TriConsumer<BlockPos, IBlockState, GatherLightsEvent> handler = Albedo.getLightHandler(block);
                    if (handler != null) {
                        collect(new BlockPos(x, y, z), state, handler);
                    }
                }
            }
        }
    }

    private void collect(BlockPos pos, IBlockState state, TriConsumer<BlockPos, IBlockState, GatherLightsEvent> handler) {
        // A light completely walled in by opaque blocks cannot light anything visible (issue: lamps shining
        // through the stone they are buried in)
        if (isEnclosed(pos)) {
            return;
        }
        ArrayList<Light> lights = new ArrayList<>(1);
        // No frustum here: lights are culled against the camera of each frame in LightManager.update
        GatherLightsEvent event = new GatherLightsEvent(lights, (float) ConfigManager.maxDistance, scanCenter, null);
        try {
            handler.apply(pos, state, event);
        } catch (RuntimeException e) {
            LightManager.reportFailure(state.getBlock().getClass(), e);
            return;
        }
        if (!lights.isEmpty()) {
            found.put(pos, lights);
        }
    }

    private boolean isEnclosed(BlockPos pos) {
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (!world.getBlockState(pos.offset(facing)).isOpaqueCube()) {
                return false;
            }
        }
        return true;
    }
}
