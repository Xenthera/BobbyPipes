package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.network.payload.ParcelSyncPayload;
import com.bobby.bobbypipes.registry.ModItems;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * In-pipe item drawing for parcels in transit.
 *
 * <p>Routed logistics parcels get a dumb-pipe centre cube (97% scale) around the cargo;
 * drifting items in plain pipe do not.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class ParcelDebugRenderer {

    private static final ContextKey<List<DrawnParcel>> RENDER_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "parcels"));

    /** Scale relative to a full-block ground item. */
    private static final float SCALE = 0.55f;

    /**
     * Routed-parcel cage scale in the same GROUND pose space as cargo ({@link #SCALE}).
     *
     * <p>GROUND already shrinks item models; values above 1 are needed for the frame to
     * read larger than the cargo.
     */
    private static final float CAGE_SCALE = 1.75f;

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
     * Cargo scale per parcel tier, indexed by
     * {@link com.bobby.bobbypipes.transit.ParcelTier#wireId()} with 0 (items, drift) at 1.
     *
     * <p>Energy and fluid parcels all draw one fixed model with no stack count to read, so
     * {@link #copiesFor} cannot show their size the way it does for items - a parcel of a
     * million FE and one of a thousand were pixel identical. Scale is the one channel left.
     * Kept modest deliberately: a T3 has to still fit visually inside the pipe cage, and the
     * cage grows alongside it below so the frame does not clip through the cargo.
     */
    private static final float[] TIER_SCALE = {1.0f, 1.0f, 1.25f, 1.5f};

    /**
     * How far an arm tip sits from the pipe centre (must match {@link #armPoint}).
     *
     * <p>One block reaches the centre of the neighbouring inventory, so the item finishes
     * inside the chest rather than vanishing halfway down the stub. Same length as a
     * centre-to-centre pipe hop, so arm legs use the same travel time per block.
     */
    static final double ARM_OFFSET = 1.0;

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

        ItemStackRenderState cageState = null;
        ItemStack cageStack = new ItemStack(ModItems.PARCEL_CAGE.get());
        if (!cageStack.isEmpty()) {
            cageState = new ItemStackRenderState();
            // Same display context as cargo so ItemStackRenderState centres both alike.
            resolver.updateForTopItem(
                    cageState, cageStack, ItemDisplayContext.GROUND, event.getLevel(), null, 0);
            if (cageState.isEmpty()) {
                cageState = null;
            }
        }

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
                // One cage per routed parcel (not per stack-copy clutter).
                ItemStackRenderState cage = c == 0 && entry.routed() ? cageState : null;
                float tierScale = tierScale(entry.tier());
                drawn.add(new DrawnParcel(
                        offset, itemState, light, scaleFor(stack) * tierScale, isBlock, cage,
                        CAGE_SCALE * tierScale));
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
            // One world pose for cargo and cage, no extra corner offsets on the cage.
            poseStack.pushPose();
            poseStack.translate(
                    parcel.pos().x - camera.x,
                    parcel.pos().y - camera.y,
                    parcel.pos().z - camera.z);

            if (parcel.cage() != null) {
                poseStack.pushPose();
                poseStack.scale(parcel.cageScale(), parcel.cageScale(), parcel.cageScale());
                // Cage is a block-shaped item under GROUND, same cancel as block cargo.
                poseStack.translate(0.0f, -BLOCK_GROUND_OFFSET, 0.0f);
                parcel.cage().submit(
                        poseStack, collector, parcel.light(), OverlayTexture.NO_OVERLAY, 0);
                poseStack.popPose();
            }

            poseStack.pushPose();
            float scale = parcel.scale();
            poseStack.scale(scale, scale, scale);
            if (parcel.block()) {
                // Cancels GROUND's +3/16 in the same scaled space the display transform uses.
                poseStack.translate(0.0f, -BLOCK_GROUND_OFFSET, 0.0f);
            }
            parcel.item().submit(
                    poseStack, collector, parcel.light(), OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
            poseStack.popPose();
        }
    }

    /**
     * Where a parcel sits for the current hop progress.
     *
     * <p>Progress (0 to 1) always spans the hop's authoritative, server-reported duration
     * (the synced {@code ticksForHop}), and is split across the polyline (enter arm to
     * centres to exit arm) below in proportion to each leg's length, so every leg moves at
     * one uniform speed. A hop with an arm is quicker overall than a plain one, since it
     * covers more ground in the same fixed time, but never speeds up partway through
     * itself.
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

        float enterDist = entry.enterFrom().isPresent() ? (float) ARM_OFFSET : 0.0f;
        float exitDist = entry.exitTo().isPresent() ? (float) ARM_OFFSET : 0.0f;
        float midDist = 1.0f;
        float total = Math.max(1.0e-4f, enterDist + midDist + exitDist);
        float d = progress * total;

        if (enterDist > 0.0f && d <= enterDist) {
            Direction side = entry.enterFrom().orElseThrow();
            return armPoint(entry.at(), side).lerp(atCenter, d / enterDist);
        }
        d -= enterDist;
        if (d <= midDist || exitDist <= 0.0f) {
            return atCenter.lerp(nextCenter, Mth.clamp(d / midDist, 0.0f, 1.0f));
        }
        d -= midDist;
        Direction side = entry.exitTo().orElseThrow();
        return nextCenter.lerp(armPoint(next, side), Mth.clamp(d / exitDist, 0.0f, 1.0f));
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

    /** Cargo scale for a parcel tier, 1 for anything untiered or out of range. */
    private static float tierScale(int tier) {
        return tier >= 0 && tier < TIER_SCALE.length ? TIER_SCALE[tier] : 1.0f;
    }

    private record DrawnParcel(Vec3 pos, ItemStackRenderState item, int light, float scale,
                               boolean block, @Nullable ItemStackRenderState cage,
                               float cageScale) {
    }
}
