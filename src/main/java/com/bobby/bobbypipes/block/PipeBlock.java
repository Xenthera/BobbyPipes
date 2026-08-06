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
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 * only look wrong. Direct/indirect marks on pipe arms are filled in by the network after
 * each topology rebuild; placement and neighbour updates only distinguish none / inventory /
 * pipe.
 *
 * <p>Waterloggable so flowing water does not break the thin tube geometry; water fills the
 * empty space around the pipe instead.
 */
public class PipeBlock extends Block implements SimpleWaterloggedBlock {

    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    public static final EnumProperty<PipeConnection> NORTH =
            EnumProperty.create("north", PipeConnection.class);
    public static final EnumProperty<PipeConnection> EAST =
            EnumProperty.create("east", PipeConnection.class);
    public static final EnumProperty<PipeConnection> SOUTH =
            EnumProperty.create("south", PipeConnection.class);
    public static final EnumProperty<PipeConnection> WEST =
            EnumProperty.create("west", PipeConnection.class);
    public static final EnumProperty<PipeConnection> UP =
            EnumProperty.create("up", PipeConnection.class);
    public static final EnumProperty<PipeConnection> DOWN =
            EnumProperty.create("down", PipeConnection.class);

    private static final Map<Direction, EnumProperty<PipeConnection>> BY_DIRECTION =
            new EnumMap<>(Map.of(
                    Direction.NORTH, NORTH,
                    Direction.EAST, EAST,
                    Direction.SOUTH, SOUTH,
                    Direction.WEST, WEST,
                    Direction.UP, UP,
                    Direction.DOWN, DOWN));

    // One width for the whole tube: no wider node at junctions. These match the model
    // exactly; if one changes so must the other.
    private static final double MIN = 4.5;
    private static final double MAX = 11.5;

    private static final VoxelShape CENTRE = Block.box(MIN, MIN, MIN, MAX, MAX, MAX);

    /** Arm boxes reaching from the centre section out to each face. */
    private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(MIN, MIN, 0.0, MAX, MAX, MIN),
            Direction.SOUTH, Block.box(MIN, MIN, MAX, MAX, MAX, 16.0),
            Direction.WEST, Block.box(0.0, MIN, MIN, MIN, MAX, MAX),
            Direction.EAST, Block.box(MAX, MIN, MIN, 16.0, MAX, MAX),
            Direction.DOWN, Block.box(MIN, 0.0, MIN, MAX, MIN, MAX),
            Direction.UP, Block.box(MIN, MAX, MIN, MAX, 16.0, MAX)));

    /** One cached shape per connection combination, so shapes are never rebuilt in game. */
    private final Map<BlockState, VoxelShape> shapeCache = new java.util.HashMap<>();

    public PipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(WATERLOGGED, false)
                .setValue(NORTH, PipeConnection.NONE)
                .setValue(EAST, PipeConnection.NONE)
                .setValue(SOUTH, PipeConnection.NONE)
                .setValue(WEST, PipeConnection.NONE)
                .setValue(UP, PipeConnection.NONE)
                .setValue(DOWN, PipeConnection.NONE));
    }

    public static EnumProperty<PipeConnection> propertyFor(Direction direction) {
        return BY_DIRECTION.get(direction);
    }

    /**
     * Whether this block is a router (Basic / Provider / Request / ...).
     *
     * <p>Plain transport pipes are not routers: they only form corridors between routed
     * pipes and never own green/red exit marks or route tables.
     */
    public static boolean isSmartPipe(Block block) {
        return block instanceof RoutedPipeBlock;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED, NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        boolean waterlogged = context.getLevel().getFluidState(context.getClickedPos()).is(Fluids.WATER);
        BlockState state = defaultBlockState().setValue(WATERLOGGED, waterlogged);
        for (Direction direction : Direction.values()) {
            state = state.setValue(propertyFor(direction),
                    connectionToward(context.getLevel(), context.getClickedPos(), direction,
                            PipeConnection.INDIRECT));
        }
        return state;
    }

    /**
     * Recomputes the connection toward a neighbour that just changed.
     *
     * <p>Only the one side is touched. Rebuilding all six would be correct but pointless
     * work on every neighbour update. Pipe-facing sides keep their current mark when the
     * neighbour is still a pipe, so a local update does not flash red before the network
     * rebuild refreshes corridors.
     */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level,
                                     ScheduledTickAccess scheduledTicks, BlockPos pos,
                                     Direction direction, BlockPos neighbourPos,
                                     BlockState neighbourState, RandomSource random) {
        if (state.getValue(WATERLOGGED)) {
            scheduledTicks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        EnumProperty<PipeConnection> property = propertyFor(direction);
        return state.setValue(property,
                connectionToward(level, pos, direction, state.getValue(property)));
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    /**
     * What this pipe carries, and so which capability an arm looks for.
     *
     * <p>Items unless a subclass says otherwise: plain transport pipe and every item pipe
     * share this, and it keeps the medium in one overridable place rather than a type
     * switch inside the connection check.
     */
    public PipeMedium medium() {
        return PipeMedium.ITEM;
    }

    /**
     * What this face should report for its neighbour.
     *
     * <p>When the neighbour is a pipe, {@code existingPipeMark} is kept if it is already a
     * pipe mark; otherwise the face becomes {@link PipeConnection#INDIRECT} until the
     * network promotes direct corridors.
     *
     * <p>Non-pipe neighbours only earn an arm when they speak this pipe's {@link #medium}.
     */
    public PipeConnection connectionToward(LevelReader level, BlockPos pos,
                                           Direction direction,
                                           PipeConnection existingPipeMark) {
        BlockPos neighbour = pos.relative(direction);
        if (!level.hasChunkAt(neighbour)) {
            return PipeConnection.NONE;
        }
        if (level.getBlockState(neighbour).getBlock() instanceof PipeBlock) {
            return existingPipeMark.isPipe() ? existingPipeMark : PipeConnection.INDIRECT;
        }
        // Capability lookup needs a real Level; during placement previews it may not be one.
        if (level instanceof Level realLevel && !realLevel.isClientSide()) {
            return medium().presentAt(realLevel, neighbour, direction.getOpposite())
                    ? PipeConnection.INVENTORY
                    : PipeConnection.NONE;
        }
        return level.getBlockState(neighbour).hasBlockEntity()
                ? PipeConnection.INVENTORY
                : PipeConnection.NONE;
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
        // DIRECT vs INDIRECT vs INVENTORY share the same arm geometry; collapse them so
        // the cache is not tripled for identical shapes. Waterlogging does not change the
        // solid shape either.
        BlockState collapsed = state.setValue(WATERLOGGED, false);
        for (Direction direction : Direction.values()) {
            EnumProperty<PipeConnection> property = propertyFor(direction);
            if (collapsed.getValue(property).isConnected()) {
                collapsed = collapsed.setValue(property, PipeConnection.INVENTORY);
            }
        }
        final BlockState key = collapsed;
        synchronized (shapeCache) {
            return shapeCache.computeIfAbsent(key, ignored -> {
                VoxelShape shape = CENTRE;
                for (Direction direction : Direction.values()) {
                    if (key.getValue(propertyFor(direction)).isConnected()) {
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
        // Cancel here rather than waiting for a tick to notice, so provider stock stops
        // being held for a chain that can no longer complete.
        PipeNetwork network = PipeNetwork.get(level);
        network.craftJobs().cancelInvolving(level, network, pos);
        // The block is already gone from the world here, so the network reads the hole
        // and rebuilds from whatever pipes are still adjacent to it.
        PipeNetwork.get(level).invalidate(pos);
    }
}
