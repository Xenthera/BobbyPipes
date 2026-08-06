package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.ChunkLoaderBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDebugRenderersEvent;

/**
 * Client-only wireframes around chunks that are actually loaded within a chunk loader's
 * radius when that loader has debug outline enabled.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class ClientChunkLoaderDebug {

    private static final int COLOR = ARGB.colorFromFloat(0.9f, 0.2f, 0.85f, 1.0f);
    private static final float WIDTH = 2.0f;
    /** How far (in chunks) from the player to scan for chunk-loader block entities. */
    private static final int SCAN_RANGE = 8;

    private ClientChunkLoaderDebug() {
    }

    @SubscribeEvent
    public static void register(RegisterDebugRenderersEvent event) {
        event.register(ClientChunkLoaderDebug::create);
    }

    private static DebugRenderer.SimpleDebugRenderer create(Minecraft minecraft) {
        return (double camX, double camY, double camZ, DebugValueAccess debugValues,
                Frustum frustum, float partialTicks) -> emit(minecraft, frustum);
    }

    private static void emit(Minecraft minecraft, Frustum frustum) {
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }

        BlockPos playerPos = minecraft.player.blockPosition();
        int playerCx = playerPos.getX() >> 4;
        int playerCz = playerPos.getZ() >> 4;
        for (int cx = playerCx - SCAN_RANGE; cx <= playerCx + SCAN_RANGE; cx++) {
            for (int cz = playerCz - SCAN_RANGE; cz <= playerCz + SCAN_RANGE; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (be instanceof ChunkLoaderBlockEntity loader && loader.debugOutline()) {
                        drawLoader(level, loader, frustum);
                    }
                }
            }
        }
    }

    private static void drawLoader(Level level, ChunkLoaderBlockEntity loader, Frustum frustum) {
        ChunkPos center = ChunkPos.containing(loader.getBlockPos());
        int radius = loader.radius();
        int minY = level.getMinY();
        int maxY = level.getMaxY() + 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.x() + dx;
                int z = center.z() + dz;
                if (!level.hasChunk(x, z)) {
                    continue;
                }
                double minX = x << 4;
                double minZ = z << 4;
                AABB box = new AABB(minX, minY, minZ, minX + 16.0, maxY, minZ + 16.0);
                if (!frustum.isVisible(box)) {
                    continue;
                }
                Gizmos.cuboid(box, GizmoStyle.stroke(COLOR, WIDTH));
            }
        }
    }
}
