package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.chunk.ChunkLoaderTickets;
import com.bobby.bobbypipes.menu.ChunkLoaderMenu;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Creative chunk loader: keeps a Chebyshev radius of chunks loaded around itself.
 */
public class ChunkLoaderBlockEntity extends BlockEntity implements MenuProvider {

    public static final int MIN_RADIUS = 0;
    public static final int MAX_RADIUS = 16;

    private int radius = 1;
    private boolean active = true;
    private boolean debugOutline;
    private final LongSet forced = new LongOpenHashSet();

    public ChunkLoaderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHUNK_LOADER.get(), pos, state);
    }

    public int radius() {
        return radius;
    }

    public boolean isActive() {
        return active;
    }

    public boolean debugOutline() {
        return debugOutline;
    }

    public void applySettings(int newRadius, boolean newActive, boolean newDebug) {
        radius = Math.clamp(newRadius, MIN_RADIUS, MAX_RADIUS);
        active = newActive;
        debugOutline = newDebug;
        setChanged();
        sync();
        if (level instanceof ServerLevel serverLevel) {
            refreshTickets(serverLevel);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel && active) {
            refreshTickets(serverLevel);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            clearTickets(serverLevel);
        }
        super.setRemoved();
    }

    private void refreshTickets(ServerLevel level) {
        LongSet desired = new LongOpenHashSet();
        if (active) {
            ChunkPos center = ChunkPos.containing(worldPosition);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    desired.add(ChunkPos.pack(center.x() + dx, center.z() + dz));
                }
            }
        }
        for (long key : new LongOpenHashSet(forced)) {
            if (!desired.contains(key)) {
                ChunkLoaderTickets.setForced(level, worldPosition, ChunkPos.unpack(key), false);
                forced.remove(key);
            }
        }
        for (long key : desired) {
            if (forced.add(key)) {
                ChunkLoaderTickets.setForced(level, worldPosition, ChunkPos.unpack(key), true);
            }
        }
    }

    private void clearTickets(ServerLevel level) {
        for (long key : forced) {
            ChunkLoaderTickets.setForced(level, worldPosition, ChunkPos.unpack(key), false);
        }
        forced.clear();
    }

    private void sync() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.bobbypipes.chunk_loader");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ChunkLoaderMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("radius", radius);
        output.putBoolean("active", active);
        output.putBoolean("debugOutline", debugOutline);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        radius = Math.clamp(input.getIntOr("radius", 1), MIN_RADIUS, MAX_RADIUS);
        active = input.getBooleanOr("active", true);
        debugOutline = input.getBooleanOr("debugOutline", false);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
