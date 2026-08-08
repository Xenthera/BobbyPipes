package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.block.LinkPipeBlock;
import com.bobby.bobbypipes.menu.LinkPipeMenu;
import com.bobby.bobbypipes.logistics.LinkClaimResult;
import com.bobby.bobbypipes.logistics.LinkPipeRegistry;
import com.bobby.bobbypipes.logistics.PipeNodeId;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

public class LinkPipeBlockEntity extends PipeBlockEntity implements MenuProvider {

    /** 0 means unpaired / no channel claimed. */
    private int channel;

    /**
     * Set when the chunk is unloading so {@link #setRemoved()} does not release the
     * channel claim - only a real break frees the slot.
     */
    private boolean unloadedByChunk;

    public LinkPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LINK_PIPE.get(), pos, state);
    }

    public int channel() {
        return channel;
    }

    public boolean isPaired() {
        if (channel <= 0 || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        return LinkPipeRegistry.get(serverLevel)
                .peerOf(PipeNodeId.of(serverLevel, worldPosition))
                .isPresent();
    }

    /**
     * True when the pair claim is complete and both endpoint chunks are loaded.
     * An unloaded peer is still paired, but the wormhole edge is severed.
     */
    public boolean isLive() {
        if (channel <= 0 || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        return LinkPipeRegistry.get(serverLevel)
                .isLive(PipeNodeId.of(serverLevel, worldPosition));
    }

    /**
     * Claims {@code newChannel} on the global registry.
     *
     * @return registry result; on {@link LinkClaimResult#CHANNEL_FULL} the local channel
     *         is left unchanged
     */
    public LinkClaimResult setChannel(int newChannel) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return LinkClaimResult.INVALID_CHANNEL;
        }
        LinkPipeRegistry registry = LinkPipeRegistry.get(serverLevel);
        PipeNodeId self = PipeNodeId.of(serverLevel, worldPosition);
        // Capture before release/claim so the vacated peer still gets a status/network refresh.
        Optional<PipeNodeId> previousPeer = registry.peerOf(self);

        if (newChannel <= 0) {
            registry.release(self);
            channel = 0;
            setChanged();
            registry.invalidatePairNetworks(serverLevel.getServer(), self);
            refreshVacatedPeer(serverLevel, registry, previousPeer);
            sync();
            LinkPipeBlock.refreshLinkStatus(serverLevel, worldPosition);
            return LinkClaimResult.OK;
        }
        LinkClaimResult result = registry.claim(self, newChannel);
        if (result != LinkClaimResult.OK) {
            return result;
        }
        channel = newChannel;
        setChanged();
        registry.invalidatePairNetworks(serverLevel.getServer(), self);
        refreshVacatedPeer(serverLevel, registry, previousPeer);
        sync();
        LinkPipeBlock.refreshLinkStatus(serverLevel, worldPosition);
        return result;
    }

    /**
     * After leaving a pair, the old peer must drop its live edge / status even though it
     * is no longer reachable via {@link LinkPipeRegistry#peerOf} from {@code self}.
     */
    private static void refreshVacatedPeer(ServerLevel level,
                                           LinkPipeRegistry registry,
                                           Optional<PipeNodeId> previousPeer) {
        previousPeer.ifPresent(peer -> {
            registry.invalidatePairNetworks(level.getServer(), peer);
            ServerLevel peerLevel = LinkPipeRegistry.levelOf(level.getServer(), peer);
            // isLoaded, not hasChunkAt: repainting a peer that is not really in the level
            // would read its block entity and so force its chunk back in.
            if (peerLevel != null && LinkPipeRegistry.isLoaded(peer)) {
                LinkPipeBlock.refreshLinkStatus(peerLevel, peer.pos());
            }
        });
    }

    private void sync() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Chunk unload: keep the channel claim so the peer goes {@code SEVERED} (not
     * {@code WAITING}) and can restore {@code LIVE} when this end loads again.
     */
    @Override
    public void onChunkUnloaded() {
        unloadedByChunk = true;
        if (level instanceof ServerLevel serverLevel) {
            PipeNodeId self = PipeNodeId.of(serverLevel, worldPosition);
            // Before the refresh, not after: onEndpointChunkChange recomputes the peer's
            // status, and that must already see this end as gone. Unconditional for the same
            // reason marking it loaded is - presence is not about owning a channel.
            LinkPipeRegistry.markUnloaded(self);
            if (channel > 0) {
                LinkPipeRegistry.get(serverLevel)
                        .onEndpointChunkChange(serverLevel.getServer(), self);
            }
        }
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            // Gone from the level whichever way it left.
            LinkPipeRegistry.markUnloaded(PipeNodeId.of(serverLevel, worldPosition));
        }
        if (!unloadedByChunk && level instanceof ServerLevel serverLevel) {
            PipeNodeId self = PipeNodeId.of(serverLevel, worldPosition);
            LinkPipeRegistry registry = LinkPipeRegistry.get(serverLevel);
            Optional<PipeNodeId> previousPeer = registry.peerOf(self);
            registry.release(self);
            registry.invalidatePairNetworks(serverLevel.getServer(), self);
            refreshVacatedPeer(serverLevel, registry, previousPeer);
        }
        super.setRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        unloadedByChunk = false;
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        PipeNodeId self = PipeNodeId.of(serverLevel, worldPosition);
        // Presence in the level has nothing to do with owning a channel, and gating it on
        // one meant a freshly placed pipe never registered: onLoad runs while its channel is
        // still zero, and setting a channel afterwards claims the pair without ever saying
        // "this end is here". Its peer then read it as unloaded and sat severed with both
        // ends in plain sight.
        LinkPipeRegistry.markLoaded(self);
        if (channel > 0) {
            LinkPipeRegistry registry = LinkPipeRegistry.get(serverLevel);
            LinkClaimResult result = registry.claim(self, channel);
            if (result == LinkClaimResult.CHANNEL_FULL) {
                // World-load race or a third endpoint: keep NBT so the player can fix it.
                // Do not clear the channel - that would permanently strand a valid pair.
            } else {
                registry.invalidatePairNetworks(serverLevel.getServer(), self);
                registry.peerOf(self).ifPresent(peer -> {
                    ServerLevel peerLevel = LinkPipeRegistry.levelOf(serverLevel.getServer(), peer);
                    if (peerLevel != null && LinkPipeRegistry.isLoaded(peer)) {
                        LinkPipeBlock.refreshLinkStatus(peerLevel, peer.pos());
                    }
                });
            }
            LinkPipeBlock.refreshLinkStatus(serverLevel, worldPosition);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.bobbypipes.link_pipe");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new LinkPipeMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("channel", channel);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        channel = input.getIntOr("channel", 0);
    }
}
