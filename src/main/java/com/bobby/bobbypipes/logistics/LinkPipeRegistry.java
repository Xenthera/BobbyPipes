package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.BobbyPipes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-wide pair registry for link pipes.
 *
 * <p>Each positive channel holds at most two endpoints. A complete pair whose both ends
 * are loaded contributes one cost-1 virtual lattice edge so corridor routing treats them
 * like adjacent dumb pipes.
 */
public final class LinkPipeRegistry extends SavedData {

    public static final int MIN_CHANNEL = 1;
    public static final int MAX_CHANNEL = 1_000_000;

    private record EndpointData(String dimension, BlockPos pos) {
        static final Codec<EndpointData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("Dimension").forGetter(EndpointData::dimension),
                BlockPos.CODEC.fieldOf("Pos").forGetter(EndpointData::pos)
        ).apply(instance, EndpointData::new));

        PipeNodeId toNode() {
            ResourceKey<Level> key = ResourceKey.create(
                    Registries.DIMENSION, Identifier.parse(dimension));
            return PipeNodeId.of(key, pos);
        }

        static EndpointData from(PipeNodeId node) {
            return new EndpointData(node.dimensionLocation().toString(), node.pos());
        }
    }

    private record ChannelData(int channel, List<EndpointData> ends) {
        static final Codec<ChannelData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("Channel").forGetter(ChannelData::channel),
                EndpointData.CODEC.listOf().fieldOf("Ends").forGetter(ChannelData::ends)
        ).apply(instance, ChannelData::new));
    }

    public static final Codec<LinkPipeRegistry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ChannelData.CODEC.listOf().optionalFieldOf("Channels", List.of())
                    .forGetter(LinkPipeRegistry::toChannelList)
    ).apply(instance, LinkPipeRegistry::fromChannels));

    public static final SavedDataType<LinkPipeRegistry> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "link_pipes"),
            LinkPipeRegistry::new,
            CODEC);

    /** channel -> up to two endpoints. */
    private final Map<Integer, List<PipeNodeId>> channels = new HashMap<>();

    /** Reverse index so break/unclaim is O(1). */
    private final Map<PipeNodeId, Integer> byEndpoint = new HashMap<>();

    public LinkPipeRegistry() {
    }

    private LinkPipeRegistry(Map<Integer, List<PipeNodeId>> loaded) {
        for (Map.Entry<Integer, List<PipeNodeId>> entry : loaded.entrySet()) {
            int channel = entry.getKey();
            List<PipeNodeId> ends = new ArrayList<>(entry.getValue());
            channels.put(channel, ends);
            for (PipeNodeId end : ends) {
                byEndpoint.put(end, channel);
            }
        }
    }

    private static LinkPipeRegistry fromChannels(List<ChannelData> list) {
        Map<Integer, List<PipeNodeId>> loaded = new HashMap<>();
        for (ChannelData data : list) {
            if (data.channel() < MIN_CHANNEL || data.ends().size() > 2) {
                continue;
            }
            List<PipeNodeId> ends = new ArrayList<>();
            for (EndpointData end : data.ends()) {
                ends.add(end.toNode());
            }
            loaded.put(data.channel(), ends);
        }
        return new LinkPipeRegistry(loaded);
    }

    private List<ChannelData> toChannelList() {
        List<ChannelData> list = new ArrayList<>();
        for (Map.Entry<Integer, List<PipeNodeId>> entry : channels.entrySet()) {
            List<EndpointData> ends = entry.getValue().stream().map(EndpointData::from).toList();
            list.add(new ChannelData(entry.getKey(), ends));
        }
        return list;
    }

    public static LinkPipeRegistry get(ServerLevel anyLevel) {
        return get(anyLevel.getServer());
    }

    public static LinkPipeRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static boolean isValidChannel(int channel) {
        return channel >= MIN_CHANNEL && channel <= MAX_CHANNEL;
    }

    /** Channel this endpoint is on, if claimed. */
    public Optional<Integer> channelOf(PipeNodeId endpoint) {
        return Optional.ofNullable(byEndpoint.get(endpoint));
    }

    /** Peer of a complete pair, if this endpoint is paired. */
    public Optional<PipeNodeId> peerOf(PipeNodeId endpoint) {
        Integer channel = byEndpoint.get(endpoint);
        if (channel == null) {
            return Optional.empty();
        }
        List<PipeNodeId> ends = channels.get(channel);
        if (ends == null || ends.size() != 2) {
            return Optional.empty();
        }
        PipeNodeId a = ends.get(0);
        PipeNodeId b = ends.get(1);
        return Optional.of(a.equals(endpoint) ? b : a);
    }

    /**
     * Every complete pair (both slots filled), regardless of load state.
     */
    public List<PipePair> completePairs() {
        List<PipePair> pairs = new ArrayList<>();
        for (Map.Entry<Integer, List<PipeNodeId>> entry : channels.entrySet()) {
            List<PipeNodeId> ends = entry.getValue();
            if (ends.size() == 2) {
                pairs.add(new PipePair(entry.getKey(), ends.get(0), ends.get(1)));
            }
        }
        return pairs;
    }

    /**
     * Claims {@code endpoint} onto {@code channel}, releasing any previous claim first.
     * Idempotent when the endpoint is already on {@code channel}.
     */
    public LinkClaimResult claim(PipeNodeId endpoint, int channel) {
        if (!isValidChannel(channel)) {
            return LinkClaimResult.INVALID_CHANNEL;
        }
        Integer current = byEndpoint.get(endpoint);
        if (current != null && current == channel) {
            return LinkClaimResult.OK;
        }
        release(endpoint);
        List<PipeNodeId> ends = channels.computeIfAbsent(channel, ignored -> new ArrayList<>(2));
        if (ends.size() >= 2) {
            return LinkClaimResult.CHANNEL_FULL;
        }
        ends.add(endpoint);
        byEndpoint.put(endpoint, channel);
        setDirty();
        return LinkClaimResult.OK;
    }

    /** Drops this endpoint from whatever channel it held. */
    public void release(PipeNodeId endpoint) {
        Integer channel = byEndpoint.remove(endpoint);
        if (channel == null) {
            return;
        }
        List<PipeNodeId> ends = channels.get(channel);
        if (ends != null) {
            ends.remove(endpoint);
            if (ends.isEmpty()) {
                channels.remove(channel);
            }
        }
        setDirty();
    }

    /**
     * Moves an endpoint's registry key when a block is somehow relocated (normally unused;
     * break+place goes through release/claim).
     */
    public void replaceEndpoint(PipeNodeId oldId, PipeNodeId newId) {
        Integer channel = byEndpoint.remove(oldId);
        if (channel == null) {
            return;
        }
        List<PipeNodeId> ends = channels.get(channel);
        if (ends != null) {
            int index = ends.indexOf(oldId);
            if (index >= 0) {
                ends.set(index, newId);
            }
        }
        byEndpoint.put(newId, channel);
        setDirty();
    }

    /**
     * Endpoints whose block entity is really in the level right now.
     *
     * <p>Maintained from the block entity's own load/unload callbacks, which is the whole
     * point. The obvious test - {@code level.hasChunkAt(pos)} - is not a loaded test at all:
     * it resolves to {@code ChunkHolder.getTicketLevel() <= ChunkLevel.byStatus(FULL)} read
     * from the chunk map's *visible* (double-buffered) copy. After a dimension change the far
     * side's ticket level decays through several values while the visible map is swapped
     * asynchronously, so {@code hasChunkAt} flickers true/false for a while even though the
     * chunk is never actually unloaded and no block entity callback fires. Deriving LIVE from
     * it made the pipe strobe between LIVE and SEVERED on every dimension trip.
     *
     * <p>{@code onLoad} (from {@code Level.tickBlockEntities}) and {@code onChunkUnloaded}
     * (from {@code ServerLevel.unload}) each fire exactly once per real transition, so this
     * set changes only when something genuinely loaded or unloaded.
     *
     * <p>Static and server-wide because a pair spans dimensions, while the registry itself is
     * per-level saved data. Never persisted - nothing is loaded until block entities say so,
     * which is exactly the state a fresh server should start in.
     */
    private static final java.util.Set<PipeNodeId> LOADED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Called from the endpoint's block entity once it is really in the level. */
    public static void markLoaded(PipeNodeId node) {
        LOADED.add(node);
    }

    /** Called from the endpoint's block entity as it leaves the level. */
    public static void markUnloaded(PipeNodeId node) {
        LOADED.remove(node);
    }

    /**
     * Forgets every endpoint in {@code dimension}.
     *
     * <p>A single-player world that is quit and reloaded keeps this class loaded, so without
     * this the second session would start believing the first session's endpoints are still
     * in the level.
     */
    public static void forgetDimension(ResourceKey<Level> dimension) {
        LOADED.removeIf(node -> node.dimension().equals(dimension));
    }

    /** True when both ends of the pair are loaded - the virtual edge should exist. */
    public static boolean bothLoaded(PipeNodeId a, PipeNodeId b) {
        return isLoaded(a) && isLoaded(b);
    }

    /** True when this endpoint's block entity is loaded. */
    public static boolean isLoaded(PipeNodeId node) {
        return LOADED.contains(node);
    }

    /**
     * True when this endpoint has a peer and both ends are loaded - the virtual edge
     * should be present. The pair claim itself survives unload; only the edge severs.
     */
    public boolean isLive(PipeNodeId endpoint) {
        return peerOf(endpoint)
                .filter(peer -> bothLoaded(endpoint, peer))
                .isPresent();
    }

    public static ServerLevel levelOf(MinecraftServer server, PipeNodeId node) {
        return server.getLevel(node.dimension());
    }

    /**
     * Invalidates pipe networks at both ends of every complete pair that touches
     * {@code endpoint}, so virtual edges refresh after claim/release.
     */
    public void invalidatePairNetworks(MinecraftServer server, PipeNodeId endpoint) {
        Optional<PipeNodeId> peer = peerOf(endpoint);
        invalidateNode(server, endpoint);
        peer.ifPresent(p -> invalidateNode(server, p));
        Integer channel = byEndpoint.get(endpoint);
        if (channel != null) {
            CrossDimPipeGraph.dropChannel(channel);
        }
        peer.flatMap(p -> Optional.ofNullable(byEndpoint.get(p)))
                .ifPresent(CrossDimPipeGraph::dropChannel);
    }

    /**
     * Chunk load/unload of a link endpoint: drop or restore the virtual edge on the
     * surviving side without releasing the channel claim.
     */
    public void onEndpointChunkChange(MinecraftServer server, PipeNodeId endpoint) {
        invalidatePairNetworks(server, endpoint);
    }

    /**
     * When a whole dimension unloads, sever every pair that touched it (peer networks
     * rebuild without the wormhole; claims stay).
     */
    public void onDimensionUnload(MinecraftServer server,
                                  net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        for (PipePair pair : completePairs()) {
            boolean touches = pair.a().dimension().equals(dimension)
                    || pair.b().dimension().equals(dimension);
            if (!touches) {
                continue;
            }
            CrossDimPipeGraph.dropChannel(pair.channel());
            PipeNodeId other = pair.a().dimension().equals(dimension) ? pair.b() : pair.a();
            if (!other.dimension().equals(dimension)) {
                invalidateNode(server, other);
            }
        }
    }

    /**
     * Every registered endpoint inside {@code chunk} of {@code level}.
     */
    public List<PipeNodeId> endpointsInChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunk) {
        List<PipeNodeId> found = new ArrayList<>();
        for (PipeNodeId endpoint : byEndpoint.keySet()) {
            if (!endpoint.dimension().equals(level.dimension())) {
                continue;
            }
            BlockPos pos = endpoint.pos();
            if (SectionPos.blockToSectionCoord(pos.getX()) == chunk.getMinBlockX() >> 4
                    && SectionPos.blockToSectionCoord(pos.getZ()) == chunk.getMinBlockZ() >> 4) {
                found.add(endpoint);
            }
        }
        return found;
    }

    private static void invalidateNode(MinecraftServer server, PipeNodeId node) {
        if (!isLoaded(node)) {
            // Writing a blockstate into a chunk that is on its way out only dirties it for
            // no visible benefit - it recomputes from scratch when it loads again.
            return;
        }
        ServerLevel level = server.getLevel(node.dimension());
        if (level != null && level.hasChunkAt(node.pos())) {
            PipeNetwork.get(level).invalidate(node.pos());
            com.bobby.bobbypipes.block.LinkPipeBlock.refreshLinkStatus(level, node.pos());
        }
    }

    public record PipePair(int channel, PipeNodeId a, PipeNodeId b) {
    }
}
