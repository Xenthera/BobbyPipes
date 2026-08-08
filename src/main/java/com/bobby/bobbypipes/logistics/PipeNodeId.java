package com.bobby.bobbypipes.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * A pipe node that may sit in any dimension.
 *
 * <p>Ordinary routing stays inside one level; link pipes need an identity that can name
 * the far end of a channel pair when it is interdimensional.
 */
public record PipeNodeId(ResourceKey<Level> dimension, BlockPos pos) {

    /**
     * For save data that has to name a node across dimensions.
     *
     * <p>{@link LinkPipeRegistry} keeps its own hand-rolled equivalent so its existing save
     * format is untouched; new save data should use this.
     */
    public static final com.mojang.serialization.Codec<PipeNodeId> CODEC =
            com.mojang.serialization.codecs.RecordCodecBuilder.create(instance -> instance.group(
                    Identifier.CODEC.fieldOf("dimension").forGetter(PipeNodeId::dimensionLocation),
                    BlockPos.CODEC.fieldOf("pos").forGetter(PipeNodeId::pos)
            ).apply(instance, (dimension, pos) -> new PipeNodeId(dimensionKey(dimension), pos)));

    public PipeNodeId {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pos, "pos");
        pos = pos.immutable();
    }

    public static PipeNodeId of(ServerLevel level, BlockPos pos) {
        return new PipeNodeId(level.dimension(), pos);
    }

    public static PipeNodeId of(ResourceKey<Level> dimension, BlockPos pos) {
        return new PipeNodeId(dimension, pos);
    }

    /** True when both ends share a dimension (a long link inside one world). */
    public boolean sameDimension(PipeNodeId other) {
        return dimension.equals(other.dimension);
    }

    public Identifier dimensionLocation() {
        return dimension.identifier();
    }

    public static ResourceKey<Level> dimensionKey(Identifier location) {
        return ResourceKey.create(Registries.DIMENSION, location);
    }
}
