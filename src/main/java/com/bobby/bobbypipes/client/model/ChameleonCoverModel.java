package com.bobby.bobbypipes.client.model;

import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.entity.PipeBlockEntity;
import com.bobby.bobbypipes.client.ClientPipeCovers;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;

import java.util.List;

/**
 * Draws a covered pipe as whatever block its cover is imitating.
 *
 * <p>Rather than generating quads of its own, this looks up the baked model of the
 * mimicked block state and hands its parts straight through. A cover therefore inherits the
 * real thing's textures, shading and render layer for free, and stays correct when a
 * resource pack changes them.
 *
 * <p>This runs on the chunk mesher, not per frame, which is the whole point: a covered pipe
 * costs exactly what the block it imitates costs, and nothing extra at draw time.
 */
public final class ChameleonCoverModel implements DynamicBlockStateModel {

    /**
     * What a cover with no block assigned to it yet looks like.
     *
     * <p>A cover has to show <em>something</em> the moment it is fitted, and it cannot be a
     * block the player supplied because they have not supplied one. Nothing is ever handed
     * back for this state, so it cannot be turned into free stone.
     */
    private static final BlockState BLANK = Blocks.SMOOTH_STONE.defaultBlockState();

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state,
                             RandomSource random, List<BlockStateModelPart> parts) {
        if (!state.getValue(PipeBlock.COVERED)) {
            // The bare pipe's own multipart cases draw it; this model contributes nothing.
            return;
        }
        // Goggles show the pipe instead of the disguise. Asking for the same state with the
        // cover flag off resolves to the pipe's ordinary multipart model, arms, marks and
        // all, so the view through a cover is the real pipe rather than an approximation
        // of it.
        BlockState appearance = ClientPipeCovers.seeThroughCovers()
                ? state.setValue(PipeBlock.COVERED, false)
                : appearanceAt(level, pos);
        int before = parts.size();
        collectFrom(appearance, level, pos, random, parts);
        if (parts.size() == before && appearance != BLANK) {
            // Nothing came out. A handful of blocks are full cubes yet draw entirely
            // through a special renderer this cover does not run - shulker boxes are the
            // usual one - and their baked model is empty. Falling back keeps the cover
            // solid-looking instead of leaving a hole in the world where a valid block was
            // accepted but could not be drawn.
            collectFrom(BLANK, level, pos, random, parts);
        }
    }

    private static void collectFrom(BlockState appearance, BlockAndTintGetter level,
                                    BlockPos pos, RandomSource random,
                                    List<BlockStateModelPart> parts) {
        BlockStateModel model = models().get(appearance);
        if (model instanceof DynamicBlockStateModel dynamic) {
            // Ask under the mimicked state, not the pipe's, or a model that inspects its
            // own block state would read pipe properties it knows nothing about.
            dynamic.collectParts(level, pos, appearance, random, parts);
        } else {
            model.collectParts(random, parts);
        }
    }

    private static BlockState appearanceAt(BlockAndTintGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PipeBlockEntity pipe && pipe.getCover() != null) {
            return pipe.getCover();
        }
        return BLANK;
    }

    @Override
    public Material.Baked particleMaterial() {
        // No position is offered here, so break and footstep particles fall back to the
        // blank cover rather than the block being imitated.
        return models().getParticleMaterial(BLANK);
    }

    /**
     * Claims both material flags rather than the imitated block's.
     *
     * <p>One instance of this model serves every block a cover might wear, so it cannot
     * know at bake time whether it will end up translucent or animated. Claiming both makes
     * the mesher keep those layers available; the quads themselves still carry the truth,
     * so the cost is a buffer that may go unused, never a cover that fails to draw.
     */
    @Override
    public int materialFlags() {
        return BakedQuad.FLAG_TRANSLUCENT | BakedQuad.FLAG_ANIMATED;
    }

    private static BlockStateModelSet models() {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    }

    /** Blockstate JSON form: {@code {"type": "bobbypipes:chameleon_cover"}}. */
    public record Unbaked() implements CustomUnbakedBlockStateModel {

        public static final MapCodec<Unbaked> CODEC = MapCodec.unit(Unbaked::new);

        @Override
        public MapCodec<? extends CustomUnbakedBlockStateModel> codec() {
            return CODEC;
        }

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            return new ChameleonCoverModel();
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            // Every model this one can delegate to is already baked for its own block.
        }
    }
}
