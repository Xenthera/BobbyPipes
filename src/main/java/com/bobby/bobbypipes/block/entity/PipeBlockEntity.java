package com.bobby.bobbypipes.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import com.bobby.bobbypipes.client.ClientPipeCovers;
import com.bobby.bobbypipes.registry.ModItems;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Base for every pipe block entity.
 *
 * <p>Holds what all pipes share regardless of role: the chameleon cover, and the
 * client-sync pair every pipe was repeating verbatim. Routing state does not live here,
 * because routing reads the world rather than block entities.
 *
 * <p>Plain transport pipe has no block entity until it is covered, so an instance of this
 * class with nothing added is a complete, working pipe block entity in its own right.
 */
public class PipeBlockEntity extends BlockEntity {

    /**
     * What this pipe is disguised as, or null when bare.
     *
     * <p>Paired with {@code PipeBlock.COVERED} on the block state, which is the copy the
     * renderer and the occlusion shape read. The two must be set together: the property
     * decides whether a cover exists at all, this decides what it looks like. Keeping the
     * appearance out of the block state is what stops it multiplying pipe states by every
     * block in the game.
     */
    private @Nullable BlockState cover;

    public PipeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public @Nullable BlockState getCover() {
        return cover;
    }

    public boolean hasCover() {
        return cover != null;
    }

    /**
     * Sets the disguise and pushes it to clients.
     *
     * <p>The block state is unchanged, so vanilla would not re-mesh the chunk on its own;
     * {@code sendBlockUpdated} is what makes the new appearance show up without a reload.
     */
    public void setCover(@Nullable BlockState cover) {
        this.cover = cover;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * A broken pipe gives its cover back.
     *
     * <p>The pipe itself drops from its loot table, which knows nothing about the block
     * entity, so without this both the cover and the block it was imitating would be
     * destroyed along with it.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null && !level.isClientSide() && hasCover()) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(),
                    new ItemStack(ModItems.CHAMELEON_COVER.get()));
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(),
                    new ItemStack(cover.getBlock()));
        }
        super.preRemoveSideEffects(pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (cover != null) {
            output.store("cover", BlockState.CODEC, cover);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        BlockState previous = cover;
        // A cover naming a block from a since-removed mod decodes to nothing rather than
        // failing the whole block entity, so the pipe itself survives the mod going away.
        cover = input.read("cover", BlockState.CODEC)
                .filter(state -> !state.is(Blocks.AIR))
                .orElse(null);
        // This is also how a re-textured cover arrives from the server. The block state is
        // identical either side of that change, so nothing else on the client would think
        // to redraw it.
        if (previous != cover && level != null && level.isClientSide()) {
            ClientPipeCovers.refresh(level, worldPosition);
        }
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
