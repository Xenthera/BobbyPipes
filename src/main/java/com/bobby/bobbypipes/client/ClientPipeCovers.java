package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client-side state for chameleon covers: when to redraw them, and when to see through them.
 *
 * <p>Kept out of {@code block/entity} so a dedicated server never touches a client class.
 * The call into here is behind an {@code isClientSide} branch, and the JVM only resolves the
 * reference the first time that branch runs.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class ClientPipeCovers {

    private static boolean wearingGoggles;

    private ClientPipeCovers() {
    }

    /**
     * Whether the player is wearing pipe goggles, and so should see through covers.
     *
     * <p>A cover hides the pipe from anyone looking at the build; the goggles are how the
     * person maintaining it gets the pipe back. Without this a covered network is
     * undebuggable without tearing the covers off.
     */
    public static boolean seeThroughCovers() {
        return wearingGoggles;
    }

    /**
     * Rebuilds the chunk mesh around a cover whose appearance just changed.
     *
     * <p>Needed because re-texturing a cover changes only block entity data, not the block
     * state. Nothing in the vanilla client redraws for that on its own: the block update
     * that carries the new cover names the same state on both sides, so
     * {@code ModelManager.requiresRender} sees no difference and skips the rebuild. The
     * cover would keep whatever it was baked with until something else forced the section
     * to remesh.
     */
    public static void refresh(Level level, BlockPos pos) {
        if (!(level instanceof ClientLevel clientLevel)) {
            return;
        }
        clientLevel.setSectionDirtyWithNeighbors(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /**
     * Watches the head slot so putting the goggles on or taking them off redraws the world.
     *
     * <p>Covers are baked into the chunk mesh, so a change of what they should show is not
     * visible until the mesh is rebuilt. Only the transition triggers it, never the steady
     * state.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        boolean worn = player != null
                && player.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.PIPE_GOGGLES.get());
        if (worn == wearingGoggles) {
            return;
        }
        wearingGoggles = worn;
        if (minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }
    }
}
