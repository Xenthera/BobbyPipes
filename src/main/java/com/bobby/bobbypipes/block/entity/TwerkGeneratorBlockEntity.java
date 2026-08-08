package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.logistics.power.TwerkMeter;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import com.bobby.bobbypipes.sound.CelloPrelude;
import com.bobby.bobbypipes.sound.NoteVoice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Generates FE from players crouching repeatedly nearby. Yes, really.
 *
 * <p>One unit of work is a crouch <em>transition</em>, not a crouch state, so standing still
 * while held down produces nothing - you have to actually move. A per-player cooldown caps
 * the rate, so binding sneak to a macro is worth no more than a determined human.
 */
public class TwerkGeneratorBlockEntity extends GeneratorBlockEntity
        implements net.minecraft.world.MenuProvider {

    public static final int CAPACITY = 20_000;
    /** FE per counted crouch transition. */
    public static final int FE_PER_TWERK = 50;
    /** How far a player can be and still count, in blocks. */
    public static final double RADIUS = 4.0;
    /**
     * Minimum ticks between two counted transitions from the same player.
     *
     * <p>A crouch down and back up is two transitions, so this allows roughly five full
     * cycles a second - about as fast as a person can actually manage, and it puts a macro
     * on the same footing rather than letting it out-earn everyone.
     */
    private static final int TWERK_COOLDOWN_TICKS = 2;
    private static final int TRANSFER_PER_TICK = 200;
    /**
     * Silence that ends the phrase and sends the melody back to the beginning.
     *
     * <p>Four seconds: long enough to lose your rhythm, catch a breath, and carry on in the
     * same phrase, short enough that coming back to a generator later starts from the top
     * rather than halfway through a bar.
     */
    private static final int MELODY_RESET_TICKS = 80;

    /** Edge detection and per-player cooldown; see {@link TwerkMeter} for why it is separate. */
    private final TwerkMeter<UUID> meter = new TwerkMeter<>(FE_PER_TWERK, TWERK_COOLDOWN_TICKS);

    /** Counted transitions in the recent past, for the block's active look. */
    private int recentTwerks;
    private long recentWindowEnd;

    /** How far into the prelude this generator has got, and when it last sounded. */
    private int noteIndex;
    private long lastNoteTick = -1L;

    public TwerkGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TWERK_GENERATOR.get(), pos, state);
    }

    @Override
    public int capacity() {
        return CAPACITY;
    }

    @Override
    protected int transferPerTick() {
        return TRANSFER_PER_TICK;
    }

    /** True while someone has twerked recently enough to keep the block lit. */
    public boolean isActive() {
        return recentTwerks > 0;
    }

    /**
     * Watches nearby players and banks a little FE per crouch transition.
     *
     * @return true when the active state changed, so the block repaints only on a transition
     */
    public boolean serverTick(ServerLevel level, BlockPos pos) {
        boolean wasActive = isActive();
        long now = level.getGameTime();

        if (now >= recentWindowEnd) {
            recentTwerks = 0;
        }

        AABB area = new AABB(pos).inflate(RADIUS);
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (Player player : level.getEntitiesOfClass(Player.class, area)) {
            UUID id = player.getUUID();
            seen.add(id);
            // isShiftKeyDown is the sneak input the client reports (shared flag 1), which is
            // what a player is actually doing. isCrouching is a pose, and is also true when
            // something forces you down under a slab.
            int earned = meter.observe(id, player.isShiftKeyDown(), now);
            // Each player is metered independently, so N dancers really are N times the
            // output until the buffer fills.
            if (earned > 0 && generate(earned) > 0) {
                recentTwerks++;
                recentWindowEnd = now + 20;
                celebrate(level, pos);
            }
        }
        // Forget players who wandered off, so a busy server does not accumulate their state
        // here forever and a returning player starts from a fresh reading.
        meter.retainOnly(seen);

        pushToNeighbours(level, pos);

        if (wasActive != isActive()) {
            setChanged();
            return true;
        }
        return false;
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.bobbypipes.twerk_generator");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
            int id, net.minecraft.world.entity.player.Inventory inventory, Player player) {
        return new com.bobby.bobbypipes.menu.TwerkGeneratorMenu(id, inventory, this);
    }

    /**
     * Sounds the next note of the prelude and throws a note particle.
     *
     * <p>One note per counted transition rather than a fixed tempo, so the piece is played by
     * the dancing rather than alongside it. Several people twerking simply play it faster,
     * which is either a feature or a warning.
     */
    private void celebrate(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        noteIndex = CelloPrelude.nextIndex(noteIndex, now, lastNoteTick, MELODY_RESET_TICKS);
        lastNoteTick = now;
        int midi = CelloPrelude.noteAt(noteIndex);
        NoteVoice voice = NoteVoice.forMidi(midi);
        if (voice == null) {
            return;
        }
        int note = voice.noteOf(midi);

        // The register comes from the instrument, so the line plays at written pitch instead
        // of being folded into one instrument's two octaves.
        level.playSound(null, pos, soundFor(voice),
                SoundSource.RECORDS, 1.0f, CelloPrelude.pitchOf(note));
        // Count 0 with speed 1 makes the note particle read its colour from xSpeed, the same
        // way a vanilla note block tints its particle by pitch.
        level.sendParticles(ParticleTypes.NOTE,
                pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                0, note / 24.0, 0.0, 0.0, 1.0);
    }

    /** Kept beside the melody rather than in {@link NoteVoice}, which stays free of MC types. */
    private static net.minecraft.sounds.SoundEvent soundFor(NoteVoice voice) {
        return switch (voice) {
            case BASS -> SoundEvents.NOTE_BLOCK_BASS.value();
            case GUITAR -> SoundEvents.NOTE_BLOCK_GUITAR.value();
            case HARP -> SoundEvents.NOTE_BLOCK_HARP.value();
            case FLUTE -> SoundEvents.NOTE_BLOCK_FLUTE.value();
            case BELL -> SoundEvents.NOTE_BLOCK_BELL.value();
        };
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);
        // Crouch state is a live reading, not save data: whoever was here has long since
        // stopped, and a stale "was crouching" would award a free unit on the next tick.
        meter.clear();
        recentTwerks = 0;
        recentWindowEnd = 0L;
        // The phrase does not survive a reload either; whoever was dancing has gone.
        noteIndex = 0;
        lastNoteTick = -1L;
    }

    /** Convenience for the block ticker, which only has a {@link Level}. */
    public boolean serverTick(Level level, BlockPos pos) {
        return level instanceof ServerLevel serverLevel && serverTick(serverLevel, pos);
    }
}
