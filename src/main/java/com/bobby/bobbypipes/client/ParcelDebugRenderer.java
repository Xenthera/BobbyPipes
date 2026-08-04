package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.network.payload.ParcelSyncPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
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
 * Rudimentary in-pipe item drawing for debugging transit.
 *
 * <p>Extracts item models into level render state, then submits them along the hop.
 * Not the final parcel renderer  -  just enough to watch items move.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class ParcelDebugRenderer {

    private static final ContextKey<List<DrawnParcel>> RENDER_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "parcels"));

    /** Scale relative to a full-block ground item. */
    private static final float SCALE = 0.55f;

    /**
     * Extra scale for block items.
     *
     * <p>A block renders as a solid cube in the ground display context while a plain item
     * renders as a flat billboard, so at one shared scale the blocks read as noticeably
     * smaller in the pipe. This brings them back to matching visual weight.
     */
    private static final float BLOCK_SCALE = 1.6f;

    /**
     * Undoes the upward shift a block model's ground display transform applies.
     *
     * <p>That transform translates a block +3/16 before drawing, and everything here is
     * scaled on top of it, so a scaled up block floats above the point it is meant to
     * occupy. Cancelling it keeps the block centred on the pipe. Plain items carry a
     * smaller offset and already look right, so they are left alone.
     */
    private static final float BLOCK_GROUND_OFFSET = 3.0f / 16.0f;

    /**
     * How far an arm tip sits from the pipe centre (must match {@link #armPoint}).
     *
     * <p>One block reaches the centre of the neighbouring inventory, so the item finishes
     * inside the chest rather than vanishing halfway down the stub.
     */
    private static final double ARM_OFFSET = 1.0;

    /**
     * Fraction of one hop spent on an enter/exit arm.
     *
     * <p>Kept well below a full centre-to-centre share so the longer arm still moves at
     * about pipe speed ({@code ARM_OFFSET / ARM_SHARE ≈ 1} block per hop).
     */
    private static final float ARM_SHARE = 0.45f;

    private ParcelDebugRenderer() {
    }

    @SubscribeEvent
    public static void registerPayloadHandler(RegisterClientPayloadHandlersEvent event) {
        event.register(ParcelSyncPayload.TYPE, ClientParcels::handle);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientParcels.clear();
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        if (ClientParcels.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ItemModelResolver resolver = minecraft.getItemModelResolver();
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long clientGameTime = event.getLevel().getGameTime();
        List<ClientParcels.Drawn> entries = ClientParcels.sample(clientGameTime, partialTick);

        List<DrawnParcel> drawn = new ArrayList<>(entries.size());
        for (ClientParcels.Drawn entry : entries) {
            ItemStack stack = entry.stack();
            if (stack.isEmpty()) {
                continue;
            }

            Vec3 pos = hopPosition(entry);

            ItemStackRenderState itemState = new ItemStackRenderState();
            resolver.updateForTopItem(
                    itemState, stack, ItemDisplayContext.GROUND, event.getLevel(), null,
                    (int) entry.id());
            if (itemState.isEmpty()) {
                continue;
            }

            BlockPos lightPos = BlockPos.containing(pos);
            int light = LightCoordsUtil.pack(
                    event.getLevel().getBrightness(LightLayer.BLOCK, lightPos),
                    event.getLevel().getBrightness(LightLayer.SKY, lightPos));

            // A parcel carries a whole stack but an item model shows no count, so two
            // logs in one parcel looked exactly like one. Draw extra copies the way a
            // dropped item entity does, so the size of a shipment is visible.
            int copies = copiesFor(stack.getCount());
            for (int c = 0; c < copies; c++) {
                Vec3 offset = c == 0
                        ? pos
                        : pos.add(((c * 7919) % 13 - 6) / 90.0,
                                  ((c * 6271) % 11 - 5) / 90.0,
                                  ((c * 4211) % 13 - 6) / 90.0);
                boolean isBlock = stack.getItem() instanceof net.minecraft.world.item.BlockItem;
                drawn.add(new DrawnParcel(offset, itemState, light, scaleFor(stack), isBlock));
            }
        }

        if (!drawn.isEmpty()) {
            event.getRenderState().setRenderData(RENDER_KEY, drawn);
        }
    }

    @SubscribeEvent
    public static void submit(SubmitCustomGeometryEvent event) {
        List<DrawnParcel> drawn = event.getLevelRenderState().getRenderData(RENDER_KEY);
        if (drawn == null || drawn.isEmpty()) {
            return;
        }

        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();

        for (DrawnParcel parcel : drawn) {
            poseStack.pushPose();
            poseStack.translate(
                    parcel.pos().x - camera.x,
                    parcel.pos().y - camera.y,
                    parcel.pos().z - camera.z);
            float scale = parcel.scale();
            poseStack.scale(scale, scale, scale);
            if (parcel.block()) {
                // Applied after the scale so it lands in the same space the display
                // transform's own translation does, cancelling it exactly.
                poseStack.translate(0.0f, -BLOCK_GROUND_OFFSET, 0.0f);
            }
            parcel.item().submit(
                    poseStack, collector, parcel.light(), OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
    }

    /**
     * Where a parcel sits for the current hop progress.
     *
     * <p>Centre-to-centre motion keeps nearly the whole hop (same pace as a normal pipe
     * leg). Enter/exit arms only take {@link #ARM_SHARE} of the hop each, so they move at
     * about one block per hop  -  folding the arm into the same distance-weighted polyline
     * as the trunk made the short stub eat a third of the tick budget and look sluggish
     * going into a chest.
     */
    private static Vec3 hopPosition(ClientParcels.Drawn entry) {
        float progress = Mth.clamp(entry.progress(), 0.0f, 1.0f);
        Vec3 atCenter = Vec3.atCenterOf(entry.at());
        if (entry.next().isEmpty()) {
            return entry.enterFrom()
                    .map(side -> armPoint(entry.at(), side))
                    .orElse(atCenter);
        }

        BlockPos next = entry.next().get();
        Vec3 nextCenter = Vec3.atCenterOf(next);
        // Drift "leaving" legs send next == at and only animate down the arm.
        if (entry.at().equals(next)) {
            return entry.exitTo()
                    .map(side -> atCenter.lerp(armPoint(next, side), progress))
                    .orElse(atCenter);
        }

        float enterShare = entry.enterFrom().isPresent() ? ARM_SHARE : 0.0f;
        float exitShare = entry.exitTo().isPresent() ? ARM_SHARE : 0.0f;
        // A one-hop chest→chest still needs a readable centre stretch.
        float armTotal = enterShare + exitShare;
        if (armTotal > 0.5f && armTotal > 0.0f) {
            float scale = 0.5f / armTotal;
            enterShare *= scale;
            exitShare *= scale;
        }
        float midShare = Math.max(1.0e-4f, 1.0f - enterShare - exitShare);

        float t = progress;
        if (enterShare > 0.0f && t <= enterShare) {
            Direction side = entry.enterFrom().orElseThrow();
            return armPoint(entry.at(), side).lerp(atCenter, t / enterShare);
        }
        t -= enterShare;
        if (t <= midShare || exitShare <= 0.0f) {
            return atCenter.lerp(nextCenter, Mth.clamp(t / midShare, 0.0f, 1.0f));
        }
        t -= midShare;
        Direction side = entry.exitTo().orElseThrow();
        return nextCenter.lerp(armPoint(next, side), Mth.clamp(t / exitShare, 0.0f, 1.0f));
    }

    /**
     * The centre of {@code pipe}, pushed out to the face where a container sits.
     *
     * <p>Stops just short of the block edge so the item reads as being inside the pipe arm
     * rather than buried in the container.
     */
    private static Vec3 armPoint(BlockPos pipe, Direction side) {
        return Vec3.atCenterOf(pipe).add(
                side.getStepX() * ARM_OFFSET,
                side.getStepY() * ARM_OFFSET,
                side.getStepZ() * ARM_OFFSET);
    }

    /** Matches how a dropped item entity scales its visible clump with stack size. */
    private static int copiesFor(int count) {
        if (count > 48) {
            return 5;
        }
        if (count > 32) {
            return 4;
        }
        if (count > 16) {
            return 3;
        }
        return count > 1 ? 2 : 1;
    }

    private static float scaleFor(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem
                ? SCALE * BLOCK_SCALE
                : SCALE;
    }

    private record DrawnParcel(Vec3 pos, ItemStackRenderState item, int light, float scale,
                               boolean block) {
    }
}
