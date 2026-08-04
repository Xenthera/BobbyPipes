package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.network.payload.CraftStatusPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Floats the ingredients a crafting pipe is still waiting for above it.
 *
 * <p>A stalled chain gives no feedback in game: a pipe waiting on an ingredient that will
 * never arrive looks the same as one that is simply busy. Showing the missing items in
 * world means the broken link can be spotted by walking past it.
 *
 * <p>Items that an upstream crafter owes are drawn higher and turning the other way, so a
 * pipe waiting on another crafter reads differently from one waiting on a provider.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class CraftHologramRenderer {

    private static final ContextKey<List<Drawn>> RENDER_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "craft_holograms"));

    private static final float SCALE = 0.45f;
    /** Height above the pipe centre for the first row of items. */
    private static final double BASE_HEIGHT = 1.0;
    /** Horizontal gap between items so a multi ingredient pattern stays readable. */
    private static final double SPACING = 0.42;
    private static final float SPIN_DEGREES_PER_TICK = 2.0f;

    private static List<CraftStatusPayload.Entry> entries = List.of();

    private CraftHologramRenderer() {
    }

    @SubscribeEvent
    public static void registerPayloadHandler(RegisterClientPayloadHandlersEvent event) {
        event.register(CraftStatusPayload.TYPE,
                (payload, context) -> context.enqueueWork(() -> entries = payload.jobs()));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        entries = List.of();
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        List<CraftStatusPayload.Entry> snapshot = entries;
        if (snapshot.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ItemModelResolver resolver = minecraft.getItemModelResolver();
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float spin = (event.getLevel().getGameTime() + partialTick) * SPIN_DEGREES_PER_TICK;

        List<Drawn> drawn = new ArrayList<>();
        for (CraftStatusPayload.Entry entry : snapshot) {
            List<CraftStatusPayload.Want> wants = entry.wants();
            BlockPos pipe = entry.pipe();
            // Centre the row so two ingredients sit either side of the pipe rather than
            // trailing off to one corner.
            double offset = -SPACING * (wants.size() - 1) / 2.0;

            for (int i = 0; i < wants.size(); i++) {
                CraftStatusPayload.Want want = wants.get(i);
                ItemStack stack = want.stack();
                if (stack.isEmpty()) {
                    continue;
                }
                ItemStackRenderState itemState = new ItemStackRenderState();
                resolver.updateForTopItem(itemState, stack, ItemDisplayContext.GROUND,
                        event.getLevel(), null, pipe.hashCode() + i);
                if (itemState.isEmpty()) {
                    continue;
                }
                double height = BASE_HEIGHT + (want.blocked() ? 0.34 : 0.0);
                Vec3 pos = Vec3.atCenterOf(pipe).add(offset + i * SPACING, height, 0.0);
                drawn.add(new Drawn(pos, itemState, want.blocked() ? -spin : spin));
            }
        }
        if (!drawn.isEmpty()) {
            event.getRenderState().setRenderData(RENDER_KEY, drawn);
        }
    }

    @SubscribeEvent
    public static void submit(SubmitCustomGeometryEvent event) {
        List<Drawn> drawn = event.getLevelRenderState().getRenderData(RENDER_KEY);
        if (drawn == null || drawn.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();

        for (Drawn item : drawn) {
            poseStack.pushPose();
            poseStack.translate(
                    item.pos().x - camera.x,
                    item.pos().y - camera.y,
                    item.pos().z - camera.z);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(item.spin()));
            poseStack.scale(SCALE, SCALE, SCALE);
            // Full bright: these are a readout, not part of the world's lighting.
            item.item().submit(poseStack, collector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
    }

    private record Drawn(Vec3 pos, ItemStackRenderState item, float spin) {
    }
}
