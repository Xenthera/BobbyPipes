package com.bobby.bobbypipes.datagen;

import com.bobby.bobbypipes.client.model.ChameleonCoverModel;
import net.minecraft.client.renderer.block.dispatch.VariantMutator;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;
import net.neoforged.neoforge.client.model.generators.blockstate.CustomBlockStateModelBuilder;
import net.neoforged.neoforge.client.model.generators.blockstate.UnbakedMutator;

/**
 * Lets the chameleon cover model be named from a generated blockstate.
 *
 * <p>Mutators are ignored rather than stored. The cover has no orientation of its own: it
 * shows whatever the block it imitates already looks like, rotation included, so rotating
 * the cover would rotate a disguise that was never facing anywhere in the first place.
 */
final class ChameleonCoverBuilder extends CustomBlockStateModelBuilder {

    @Override
    public CustomBlockStateModelBuilder with(VariantMutator mutator) {
        return this;
    }

    @Override
    public CustomBlockStateModelBuilder with(UnbakedMutator mutator) {
        return this;
    }

    @Override
    public CustomUnbakedBlockStateModel toUnbaked() {
        return new ChameleonCoverModel.Unbaked();
    }
}
