package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.PipeBlockEntity;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import com.bobby.bobbypipes.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

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
public class PipeBlock extends Block implements SimpleWaterloggedBlock, EntityBlock {

    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    /**
     * Whether a chameleon cover is hiding this pipe.
     *
     * <p>Only whether, not what: the appearance is a {@link BlockState} on the block entity,
     * because putting it here would multiply every pipe's state count by every block in the
     * game. The flag has to be on the state all the same, since occlusion and the block
     * shape are asked for by state alone with no position to look a block entity up from.
     */
    public static final BooleanProperty COVERED = BooleanProperty.create("covered");

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
                .setValue(COVERED, false)
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

    /**
     * Pipes a Power Junction feeds, and which therefore draw an arm toward one.
     *
     * <p>Routers plus link mouths: a link hop is charged FE like any other routing action, so
     * a link pipe is as much a consumer as a router. Plain transport pipe is not - it does no
     * routing, spends nothing, and a junction has nothing to offer it.
     *
     * <p>One predicate rather than a check per call site, because the arm and the power have
     * to agree: an arm that promises power the junction will not supply is worse than no arm.
     */
    public static boolean isPowerable(Block block) {
        return isSmartPipe(block) || block instanceof LinkPipeBlock;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED, COVERED, NORTH, EAST, SOUTH, WEST, UP, DOWN);
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
        if (level.getBlockState(neighbour).getBlock() instanceof PowerJunctionBlock) {
            // Deliberately not a capability question. A junction powers exactly the smart
            // pipes beside it, and the arm is how a player sees that - nothing flows down it,
            // it is the visual continuity of the power connection. Plain pipe draws no arm
            // because a junction has nothing to offer it.
            return isPowerable(this) ? PipeConnection.INVENTORY : PipeConnection.NONE;
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
        // A bare pipe never fills its block, so neighbours must keep drawing their faces.
        // A covered one does fill it, and culling those faces is most of what the cover
        // is for.
        return state.getValue(COVERED) ? Shapes.block() : Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return !state.getValue(COVERED);
    }

    /**
     * Reports the cover to neighbours that ask what this block looks like.
     *
     * <p>Connected-texture blocks and other appearance-sensitive neighbours then treat a
     * covered pipe as one of their own, so a pipe threaded through a wall does not leave a
     * seam where the wall's texture breaks around it.
     */
    @Override
    public BlockState getAppearance(BlockState state, BlockAndLightGetter level, BlockPos pos,
                                    Direction side, @Nullable BlockState queryState,
                                    @Nullable BlockPos queryPos) {
        if (state.getValue(COVERED)
                && level.getBlockEntity(pos) instanceof PipeBlockEntity pipe
                && pipe.getCover() != null) {
            return pipe.getCover();
        }
        return super.getAppearance(state, level, pos, side, queryState, queryPos);
    }

    /**
     * A cover holder, but only once there is a cover to hold.
     *
     * <p>Returning null is how a bare transport pipe stays block-entity free: there are a
     * great many of them in a built network and none of them need one. Subclasses that
     * always carry a block entity override this and inherit cover storage from
     * {@link PipeBlockEntity} instead.
     */
    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(COVERED)
                ? new PipeBlockEntity(ModBlockEntities.PIPE_COVER.get(), pos, state)
                : null;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                          BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hitResult) {
        InteractionResult cover = tryCoverInteraction(stack, state, level, pos, player);
        if (cover != InteractionResult.PASS) {
            return cover;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        InteractionResult cover = tryStripCover(state, level, pos, player);
        if (cover != InteractionResult.PASS) {
            return cover;
        }
        return super.useWithoutItem(state, level, pos, player, hitResult);
    }

    /**
     * Undoes a cover a step at a time: sneak and click with empty hands.
     *
     * <p>First press takes the disguise off and hands the block back, leaving the cover
     * fitted but blank. A second press takes the cover itself off. Changing what a run is
     * disguised as therefore never means prising covers off and putting them back on, and
     * fully removing one is still a single gesture repeated rather than a second tool.
     *
     * <p>Both hands must be empty, and that is not a style choice. Vanilla suppresses block
     * interaction entirely when a sneaking player is holding anything
     * ({@code ServerPlayerGameMode.useItemOn}), so a sneak gesture that involves a held item
     * never reaches a block at all. Any cover removal bound to sneak-plus-tool would look
     * reasonable and silently do nothing.
     *
     * @return {@link InteractionResult#PASS} when there is no cover here to undo
     */
    protected InteractionResult tryStripCover(BlockState state, Level level, BlockPos pos,
                                              Player player) {
        if (!state.getValue(COVERED) || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        // Read on both sides. The cover is synced, so the client reaches the same verdict
        // and does not swing at a cover that has nothing to take off.
        if (!(level.getBlockEntity(pos) instanceof PipeBlockEntity pipe)) {
            return InteractionResult.PASS;
        }
        if (pipe.getCover() == null) {
            return removeCover(state, level, pos, player);
        }
        if (!level.isClientSide()) {
            BlockState previous = pipe.getCover();
            pipe.setCover(null);
            giveBack(level, pos, player, new ItemStack(previous.getBlock()));
        }
        level.playSound(player, pos, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
        return InteractionResult.SUCCESS;
    }

    /**
     * Fits a cover, or gives an existing one a new disguise.
     *
     * <p>Routed pipes call this before their own wrench handling so the cover can be worked
     * on without the pipe's screen swallowing the click. A plain wrench click on a covered
     * pipe still opens the screen, which is the only way back into a pipe that is hidden.
     *
     * <p>Removal is not here: it lives in {@link #tryStripCover} on the empty-handed sneak
     * path, because vanilla never delivers a sneak-with-item click to a block.
     *
     * @return {@link InteractionResult#PASS} when the click was not about a cover
     */
    protected InteractionResult tryCoverInteraction(ItemStack stack, BlockState state,
                                                    Level level, BlockPos pos, Player player) {
        boolean covered = state.getValue(COVERED);
        if (player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!covered && stack.is(ModItems.CHAMELEON_COVER.get())) {
            return fitCover(state, level, pos, player, stack);
        }
        if (covered && stack.getItem() instanceof BlockItem blockItem) {
            return setCoverAppearance(level, pos, player, stack, blockItem);
        }
        return InteractionResult.PASS;
    }

    private InteractionResult fitCover(BlockState state, Level level, BlockPos pos,
                                       Player player, ItemStack stack) {
        if (!level.isClientSide()) {
            // State first: the block entity is only created once COVERED is set, so the
            // cover has somewhere to live.
            level.setBlockAndUpdate(pos, state.setValue(COVERED, true));
            stack.consume(1, player);
        }
        level.playSound(player, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);
        return InteractionResult.SUCCESS;
    }

    private InteractionResult setCoverAppearance(Level level, BlockPos pos, Player player,
                                                 ItemStack stack, BlockItem blockItem) {
        BlockState appearance = blockItem.getBlock().defaultBlockState();
        if (!canBeWorn(appearance, level, pos)) {
            return InteractionResult.PASS;
        }
        if (!(level.getBlockEntity(pos) instanceof PipeBlockEntity pipe)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            BlockState previous = pipe.getCover();
            pipe.setCover(appearance);
            stack.consume(1, player);
            // The old disguise comes back rather than being eaten, so re-texturing a long
            // run does not quietly cost a block every time you change your mind.
            if (previous != null) {
                giveBack(level, pos, player, new ItemStack(previous.getBlock()));
            }
        }
        level.playSound(player, pos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0f, 1.2f);
        return InteractionResult.SUCCESS;
    }

    private InteractionResult removeCover(BlockState state, Level level, BlockPos pos,
                                          Player player) {
        if (!level.isClientSide()) {
            BlockState appearance = level.getBlockEntity(pos) instanceof PipeBlockEntity pipe
                    ? pipe.getCover()
                    : null;
            if (level.getBlockEntity(pos) instanceof PipeBlockEntity pipe) {
                pipe.setCover(null);
            }
            level.setBlockAndUpdate(pos, state.setValue(COVERED, false));
            // Pipes that only had a block entity to hold the cover lose it again. Vanilla
            // will not do this for us: hasBlockEntity is decided per block, not per state,
            // so an uncovered pipe still looks like it should have one.
            if (level.getBlockEntity(pos) instanceof PipeBlockEntity pipe
                    && pipe.getType() == ModBlockEntities.PIPE_COVER.get()) {
                level.removeBlockEntity(pos);
            }
            giveBack(level, pos, player, new ItemStack(ModItems.CHAMELEON_COVER.get()));
            if (appearance != null) {
                giveBack(level, pos, player, new ItemStack(appearance.getBlock()));
            }
        }
        level.playSound(player, pos, SoundEvents.METAL_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
        return InteractionResult.SUCCESS;
    }

    /**
     * Whether a block is a sane thing to wear.
     *
     * <p>The test is the shape, not the mod it came from and not whether it has a block
     * entity: any full cube from anywhere is fair game, which is the point of the cover.
     * Modded machines are overwhelmingly full cubes with ordinary baked models and work
     * fine, so refusing everything with a block entity would have ruled out most of what
     * anyone actually wants to disguise a pipe as.
     *
     * <p>Shape also happens to exclude the blocks that would have come out wrong anyway.
     * Chests, beds, signs and banners draw through a special renderer this cover does not
     * run, and none of them are full cubes, so they fall out here for a reason that is
     * about how they look rather than how they are implemented.
     *
     * <p>Other pipes are refused so a cover cannot mimic the thing it is hiding.
     */
    private static boolean canBeWorn(BlockState appearance, Level level, BlockPos pos) {
        return !appearance.isAir()
                && !(appearance.getBlock() instanceof PipeBlock)
                && appearance.isCollisionShapeFullBlock(level, pos);
    }

    private static void giveBack(Level level, BlockPos pos, Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            Block.popResource(level, pos, stack);
        }
    }

    private VoxelShape shapeFor(BlockState state) {
        if (state.getValue(COVERED)) {
            // Arms are hidden under the cover, so every covered pipe is the same cube
            // whatever it is connected to.
            return Shapes.block();
        }
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
