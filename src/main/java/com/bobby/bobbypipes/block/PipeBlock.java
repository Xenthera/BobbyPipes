package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.network.PipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.EnumMap;
import java.util.Map;

/**
 * A transport pipe.
 *
 * <p>Carries no block entity. Its job is to be recognisable to {@link PipeNetwork} when the
 * graph is read, to tell the network when the layout around it changed, and to track which
 * neighbours it is joined to so it can be drawn connected.
 *
 * <p>Connection state is visual and structural only. Routing reads the world directly, so a
 * mismatch between the drawn arms and the real graph cannot misroute anything, it would
 * only look wrong.
 */
public class PipeBlock extends Block {

    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;

    private static final Map<Direction, BooleanProperty> BY_DIRECTION =
            new EnumMap<>(Map.of(
                    Direction.NORTH, NORTH,
                    Direction.EAST, EAST,
                    Direction.SOUTH, SOUTH,
                    Direction.WEST, WEST,
                    Direction.UP, UP,
                    Direction.DOWN, DOWN));

    // The core is wider than the arms so a junction reads as a node rather than a bulge
    // in a uniform tube. These match the model exactly; if one changes so must the other.
    private static final double CORE_MIN = 5.0;
    private static final double CORE_MAX = 11.0;
    private static final double ARM_MIN = 6.0;
    private static final double ARM_MAX = 10.0;

    private static final VoxelShape CORE =
            Block.box(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);

    /** Arm boxes reaching from the core out to each face. */
    private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(ARM_MIN, ARM_MIN, 0.0, ARM_MAX, ARM_MAX, CORE_MIN),
            Direction.SOUTH, Block.box(ARM_MIN, ARM_MIN, CORE_MAX, ARM_MAX, ARM_MAX, 16.0),
            Direction.WEST, Block.box(0.0, ARM_MIN, ARM_MIN, CORE_MIN, ARM_MAX, ARM_MAX),
            Direction.EAST, Block.box(CORE_MAX, ARM_MIN, ARM_MIN, 16.0, ARM_MAX, ARM_MAX),
            Direction.DOWN, Block.box(ARM_MIN, 0.0, ARM_MIN, ARM_MAX, CORE_MIN, ARM_MAX),
            Direction.UP, Block.box(ARM_MIN, CORE_MAX, ARM_MIN, ARM_MAX, 16.0, ARM_MAX)));

    /** One cached shape per connection combination, so shapes are never rebuilt in game. */
    private final Map<BlockState, VoxelShape> shapeCache = new java.util.HashMap<>();

    public PipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(UP, false)
                .setValue(DOWN, false));
    }

    public static BooleanProperty propertyFor(Direction direction) {
        return BY_DIRECTION.get(direction);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction direction : Direction.values()) {
            state = state.setValue(propertyFor(direction),
                    connectsTo(context.getLevel(), context.getClickedPos(), direction));
        }
        return state;
    }

    /**
     * Recomputes the connection toward a neighbour that just changed.
     *
     * <p>Only the one side is touched. Rebuilding all six would be correct but pointless
     * work on every neighbour update.
     */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level,
                                     ScheduledTickAccess scheduledTicks, BlockPos pos,
                                     Direction direction, BlockPos neighbourPos,
                                     BlockState neighbourState, RandomSource random) {
        return state.setValue(propertyFor(direction), connectsTo(level, pos, direction));
    }

    /**
     * Whether the pipe should draw an arm toward {@code direction}.
     *
     * <p>Pipes join other pipes, and they join anything exposing an item inventory, which
     * is what makes a pipe visibly meet the chest it serves.
     */
    protected boolean connectsTo(LevelReader level, BlockPos pos, Direction direction) {
        BlockPos neighbour = pos.relative(direction);
        if (!level.hasChunkAt(neighbour)) {
            return false;
        }
        if (level.getBlockState(neighbour).getBlock() instanceof PipeBlock) {
            return true;
        }
        // Capability lookup needs a real Level; during placement previews it may not be one.
        if (level instanceof Level realLevel && !realLevel.isClientSide()) {
            return realLevel.getCapability(
                    Capabilities.Item.BLOCK, neighbour, direction.getOpposite()) != null;
        }
        return level.getBlockState(neighbour).hasBlockEntity();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state) {
        // A pipe never fills its block, so neighbours must keep drawing their faces.
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    private VoxelShape shapeFor(BlockState state) {
        synchronized (shapeCache) {
            return shapeCache.computeIfAbsent(state, key -> {
                VoxelShape shape = CORE;
                for (Direction direction : Direction.values()) {
                    if (key.getValue(propertyFor(direction))) {
                        shape = Shapes.or(shape, ARMS.get(direction));
                    }
                }
                return shape;
            });
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel
                && !oldState.is(this)) {
            PipeNetwork.get(serverLevel).invalidate(pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state,
                                               net.minecraft.server.level.ServerLevel level,
                                               BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        // The block is already gone from the world here, so the network reads the hole
        // and rebuilds from whatever pipes are still adjacent to it.
        PipeNetwork.get(level).invalidate(pos);
    }
}
