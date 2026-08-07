package com.bobby.bobbypipes.datagen;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.LinkPipeBlock;
import com.bobby.bobbypipes.block.LinkStatus;
import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.PipeConnection;
import com.bobby.bobbypipes.block.RoutedPipeBlock;
import com.bobby.bobbypipes.registry.ModBlocks;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.ConditionBuilder;
import net.minecraft.client.data.models.blockstates.MultiPartGenerator;
import net.minecraft.client.renderer.block.dispatch.VariantMutator;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Generates the pipe blockstates.
 *
 * <p>Fifteen pipes shared one of three multipart layouts, written out by hand, thirty-one
 * cases each. Adding a single case - which the chameleon cover needed - meant editing every
 * file and getting all fifteen right. Here a layout is written once and applied to every
 * pipe that uses it.
 *
 * <p>Only blockstates. The pipe models carry hand-tuned geometry and aspect-corrected UVs
 * that are not worth re-deriving through a builder, so those stay authored by hand.
 *
 * <p>Conditions are built from the real {@link net.minecraft.world.level.block.state.properties.Property}
 * objects, so a layout naming a property its block does not have cannot compile. The
 * hand-written files had no such protection.
 */
public final class PipeModelProvider extends ModelProvider {

    /** Rotation that points a north-facing model at each face. */
    private static final Map<Direction, VariantMutator> FACING =
            new EnumMap<>(Map.of(
                    Direction.NORTH, BlockModelGenerators.NOP,
                    Direction.EAST, BlockModelGenerators.Y_ROT_90,
                    Direction.SOUTH, BlockModelGenerators.Y_ROT_180,
                    Direction.WEST, BlockModelGenerators.Y_ROT_270,
                    Direction.UP, BlockModelGenerators.X_ROT_270,
                    Direction.DOWN, BlockModelGenerators.X_ROT_90));

    /** Arm marks. A plain pipe draws one arm for all three; a routed pipe draws each. */
    private static final List<PipeConnection> CONNECTED =
            List.of(PipeConnection.INVENTORY, PipeConnection.DIRECT, PipeConnection.INDIRECT);

    /** Pipes whose body carries the logistics-power indicator. */
    private static final List<Holder<Block>> ROUTED = List.of(
            ModBlocks.BASIC_PIPE, ModBlocks.PROVIDER_PIPE, ModBlocks.REQUEST_PIPE,
            ModBlocks.SUPPLIER_PIPE, ModBlocks.PASSIVE_SUPPLIER_PIPE,
            ModBlocks.CRAFTING_PIPE, ModBlocks.SATELLITE_PIPE,
            ModBlocks.ENERGY_PROVIDER_PIPE, ModBlocks.ENERGY_REQUEST_PIPE,
            ModBlocks.ENERGY_SUPPLIER_PIPE, ModBlocks.FLUID_PROVIDER_PIPE,
            ModBlocks.FLUID_REQUEST_PIPE, ModBlocks.FLUID_SUPPLIER_PIPE);

    public PipeModelProvider(PackOutput output) {
        super(output, BobbyPipes.MOD_ID);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators items) {
        plainPipe(blockModels, ModBlocks.PIPE.get(), "pipe");
        linkPipe(blockModels, ModBlocks.LINK_PIPE.get(), "link_pipe");
        for (Holder<Block> pipe : ROUTED) {
            Block block = pipe.value();
            routedPipe(blockModels, block, name(block));
        }
    }

    /**
     * Only the pipes are generated, so only the pipes may be validated.
     *
     * <p>The default is every block this mod registers, which would fail the run over the
     * pattern table and friends still being authored by hand.
     */
    @Override
    protected Stream<? extends Holder<Block>> getKnownBlocks() {
        return Stream.concat(
                Stream.of(ModBlocks.PIPE, ModBlocks.LINK_PIPE),
                ROUTED.stream());
    }

    /** No item models are generated yet. */
    @Override
    protected Stream<? extends Holder<Item>> getKnownItems() {
        return Stream.empty();
    }

    /** Unrouted transport pipe: one arm per connected side, a cap per open side. */
    private static void plainPipe(BlockModelGenerators blockModels, Block block, String name) {
        MultiPartGenerator parts = MultiPartGenerator.multiPart(block);
        for (Direction side : Direction.values()) {
            parts.with(when(side, CONNECTED), model(name + "_arm", side));
            parts.with(when(side, PipeConnection.NONE), model(name + "_cap", side));
        }
        emit(blockModels, parts);
    }

    /**
     * Routed pipe: arms carry the direct/indirect exit marks, caps carry the power light.
     */
    private static void routedPipe(BlockModelGenerators blockModels, Block block, String name) {
        MultiPartGenerator parts = MultiPartGenerator.multiPart(block);
        for (Direction side : Direction.values()) {
            for (PipeConnection mark : CONNECTED) {
                parts.with(when(side, mark), model(name + "_arm" + suffix(mark), side));
            }
            for (boolean powered : new boolean[] {true, false}) {
                parts.with(
                        when(side, PipeConnection.NONE)
                                .term(RoutedPipeBlock.POWERED, powered),
                        model(name + "_cap_" + (powered ? "powered" : "unpowered"), side));
            }
        }
        emit(blockModels, parts);
    }

    /** Link pipe: plain arms, but the cap shows the wormhole channel state. */
    private static void linkPipe(BlockModelGenerators blockModels, Block block, String name) {
        MultiPartGenerator parts = MultiPartGenerator.multiPart(block);
        for (Direction side : Direction.values()) {
            parts.with(when(side, CONNECTED), model(name + "_arm", side));
            for (LinkStatus status : LinkStatus.values()) {
                parts.with(
                        when(side, PipeConnection.NONE).term(LinkPipeBlock.LINK, status),
                        model(name + "_cap" + capSuffix(status), side));
            }
        }
        emit(blockModels, parts);
    }

    /**
     * Adds the cover case and hands the finished layout over.
     *
     * <p>Every pipe gets it, and every other case is already qualified with
     * {@code covered=false}, so a cover always replaces the pipe rather than drawing on top
     * of it. Doing it here rather than in each layout is what stops the three from drifting.
     */
    private static void emit(BlockModelGenerators blockModels, MultiPartGenerator parts) {
        parts.with(
                new ConditionBuilder().term(PipeBlock.COVERED, true),
                MultiVariant.of(new ChameleonCoverBuilder()));
        blockModels.blockStateOutput.accept(parts);
    }

    private static ConditionBuilder when(Direction side, PipeConnection value) {
        return new ConditionBuilder().term(PipeBlock.propertyFor(side), value)
                .term(PipeBlock.COVERED, false);
    }

    private static ConditionBuilder when(Direction side, List<PipeConnection> anyOf) {
        return new ConditionBuilder()
                .term(PipeBlock.propertyFor(side), anyOf.get(0),
                        anyOf.subList(1, anyOf.size()).toArray(new PipeConnection[0]))
                .term(PipeBlock.COVERED, false);
    }

    private static MultiVariant model(String path, Direction side) {
        return BlockModelGenerators
                .plainVariant(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "block/" + path))
                .with(FACING.get(side));
    }

    private static String suffix(PipeConnection mark) {
        return switch (mark) {
            case INVENTORY -> "";
            case DIRECT -> "_direct";
            case INDIRECT -> "_indirect";
            default -> throw new IllegalArgumentException("not an arm mark: " + mark);
        };
    }

    private static String capSuffix(LinkStatus status) {
        return status == LinkStatus.IDLE ? "" : "_" + status.getSerializedName();
    }

    private static String name(Block block) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
    }
}
