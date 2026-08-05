package com.bobby.bobbypipes.client;

import com.bobby.bobbycore.client.render.BillboardText;
import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.network.payload.PipeProbePayload;
import com.bobby.bobbypipes.network.payload.ProbePipePayload;
import com.bobby.bobbypipes.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * While Pipe Goggles are worn, probes the looked-at pipe and draws billboard status text.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class PipeProbeRenderer {

    private static final ContextKey<Drawn> RENDER_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "pipe_probe"));

    /** How often to ask the server for a fresh readout. */
    private static final int REQUEST_INTERVAL = 5;

    /** Height above the pipe centre for the first line. */
    private static final double TEXT_HEIGHT = 0.85;

    private static BlockPos lastRequested;
    private static long lastRequestGameTime = Long.MIN_VALUE;

    private PipeProbeRenderer() {
    }

    @SubscribeEvent
    public static void registerPayloadHandler(RegisterClientPayloadHandlersEvent event) {
        event.register(PipeProbePayload.TYPE, ClientPipeProbe::handle);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPipeProbe.clear();
        lastRequested = null;
        lastRequestGameTime = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null) {
            ClientPipeProbe.clear();
            lastRequested = null;
            return;
        }
        if (!player.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.PIPE_GOGGLES.get())) {
            ClientPipeProbe.clear();
            lastRequested = null;
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() == HitResult.Type.MISS) {
            ClientPipeProbe.clear();
            lastRequested = null;
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        if (!(state.getBlock() instanceof PipeBlock)) {
            ClientPipeProbe.clear();
            lastRequested = null;
            return;
        }

        long gameTime = minecraft.level.getGameTime();
        if (!pos.equals(lastRequested) || gameTime - lastRequestGameTime >= REQUEST_INTERVAL) {
            lastRequested = pos.immutable();
            lastRequestGameTime = gameTime;
            ClientPacketDistributor.sendToServer(new ProbePipePayload(pos));
        }

        // Drop stale text if the server answered for a different block.
        if (ClientPipeProbe.hasData() && !pos.equals(ClientPipeProbe.pos())) {
            ClientPipeProbe.clear();
        }
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        if (!ClientPipeProbe.hasData()) {
            return;
        }
        BlockPos pos = ClientPipeProbe.pos();
        List<String> raw = ClientPipeProbe.lines();
        List<Component> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            lines.add(Component.literal(line));
        }
        event.getRenderState().setRenderData(
                RENDER_KEY,
                new Drawn(
                        Vec3.atCenterOf(pos).add(0.0, TEXT_HEIGHT, 0.0),
                        lines,
                        BillboardText.scaleForLineCount(lines.size())));
    }

    @SubscribeEvent
    public static void submit(SubmitCustomGeometryEvent event) {
        Drawn drawn = event.getLevelRenderState().getRenderData(RENDER_KEY);
        if (drawn == null || drawn.lines().isEmpty()) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        BillboardText.submitDebug(
                event.getSubmitNodeCollector(),
                poseStack,
                event.getLevelRenderState().cameraRenderState,
                drawn.worldPos(),
                drawn.lines(),
                drawn.scale());
    }

    private record Drawn(Vec3 worldPos, List<Component> lines, float scale) {
    }
}
