package com.bobby.bobbypipes.sound;

/**
 * Bach's Cello Suite No. 1 Prelude (BWV 1007) in full, at written pitch.
 *
 * <p>All 41 bars, 647 notes, ending where the piece ends; playback then loops back to the
 * opening. Public domain - Bach died in 1750.
 *
 * <h2>How the notes were derived</h2>
 *
 * <p>Decoded from a published G-major, standard-tuning guitar tablature of the piece rather
 * than written from memory. The first three bars decode to the canonical opening
 * ({@code G2 D3 B3 A3 B3 D3 B3 D3}, then the C-natural turn, then the F# against the C),
 * which is what confirms both the source and the decoder. The source is a guitar arrangement,
 * so a few inner voicings may differ from the cello urtext.
 *
 * <h2>Pitch</h2>
 *
 * <p>Stored as MIDI note numbers, untransposed. The piece spans E2 to G4, wider than any one
 * note block instrument's two octaves, so {@link NoteVoice} picks a register per note instead:
 * guitar carries 629 of the 647, with the double bass taking the low E2s and the harp the high
 * G4s. Nothing is folded or transposed, which an earlier single-instrument version had to do.
 */
public final class CelloPrelude {

    /**
     * The whole prelude as MIDI note numbers, in playing order.
     *
     * <p>Sixteen to a line, which is one bar of the opening figure. 60 is middle C.
     */
    public static final int[] NOTES = {
            43, 50, 59, 57, 59, 50, 59, 50, 43, 50, 59, 57, 59, 50, 59, 50,
            43, 52, 60, 59, 60, 52, 60, 52, 43, 52, 60, 59, 60, 52, 60, 52,
            43, 54, 60, 59, 60, 54, 60, 54, 43, 54, 60, 59, 60, 54, 60, 54,
            43, 55, 59, 57, 59, 55, 59, 55, 43, 55, 59, 57, 59, 55, 59, 54,
            43, 52, 59, 57, 59, 55, 54, 55, 52, 55, 54, 55, 47, 50, 49, 47,
            49, 55, 57, 55, 57, 55, 57, 55, 49, 55, 57, 55, 57, 55, 57, 55,
            54, 57, 62, 61, 62, 57, 55, 57, 54, 57, 55, 57, 50, 54, 52, 50,
            40, 47, 55, 54, 55, 47, 55, 47, 40, 47, 55, 54, 55, 47, 55, 47,
            40, 49, 50, 52, 50, 49, 47, 45, 55, 54, 52, 62, 61, 59, 57, 55,
            54, 52, 50, 62, 57, 62, 54, 57, 50, 52, 54, 57, 55, 54, 52, 50,
            56, 50, 53, 52, 53, 50, 56, 50, 59, 50, 53, 52, 53, 50, 56, 50,
            48, 52, 57, 59, 60, 57, 52, 50, 48, 52, 57, 59, 60, 57, 54, 52,
            51, 54, 51, 54, 57, 54, 57, 54, 51, 54, 51, 54, 57, 54, 57, 54,
            55, 54, 52, 55, 54, 55, 57, 54, 55, 54, 52, 50, 48, 47, 45, 43,
            42, 48, 50, 48, 50, 48, 50, 48, 42, 48, 50, 48, 50, 48, 50, 48,
            43, 47, 53, 52, 53, 47, 53, 47, 43, 47, 53, 52, 53, 47, 53, 47,
            43, 48, 52, 50, 52, 48, 52, 48, 43, 48, 52, 50, 52, 48, 52, 48,
            43, 54, 60, 59, 60, 54, 60, 54, 43, 54, 60, 59, 60, 54, 60, 54,
            43, 50, 59, 57, 59, 55, 54, 52, 50, 48, 47, 45, 43, 42, 40, 45,
            52, 54, 55, 52, 54, 55, 45, 52, 54, 55, 52, 54, 55, 45, 50, 52,
            54, 50, 52, 54, 45, 50, 52, 54, 50, 52, 54, 45, 50, 54, 59, 61,
            62, 45, 47, 48, 50, 52, 54, 55, 57, 54, 50, 52, 54, 55, 57, 59,
            60, 57, 54, 55, 57, 59, 60, 62, 63, 62, 61, 62, 62, 60, 59, 60,
            60, 57, 54, 52, 50, 45, 47, 48, 45, 50, 54, 57, 59, 60, 57, 59,
            55, 50, 48, 47, 43, 45, 47, 43, 47, 50, 55, 57, 59, 55, 61, 59,
            57, 58, 58, 57, 56, 57, 57, 55, 54, 55, 55, 52, 49, 47, 45, 49,
            52, 55, 57, 61, 62, 61, 62, 57, 54, 52, 54, 57, 50, 54, 45, 50,
            49, 47, 45, 43, 42, 40, 60, 59, 57, 55, 54, 52, 50, 60, 59, 57,
            55, 54, 52, 50, 48, 59, 57, 55, 54, 52, 50, 48, 47, 57, 55, 54,
            52, 50, 48, 47, 45, 55, 54, 52, 54, 52, 50, 57, 52, 57, 54, 57,
            55, 57, 52, 57, 54, 57, 50, 57, 55, 57, 52, 57, 54, 57, 50, 57,
            55, 57, 52, 57, 54, 57, 50, 57, 52, 57, 54, 57, 55, 57, 57, 57,
            59, 57, 50, 57, 57, 57, 59, 57, 60, 57, 50, 57, 59, 57, 60, 57,
            62, 57, 59, 57, 60, 57, 59, 57, 60, 57, 57, 57, 59, 57, 57, 57,
            59, 57, 55, 57, 57, 57, 55, 57, 57, 57, 54, 57, 55, 57, 54, 57,
            55, 57, 52, 57, 54, 57, 50, 52, 53, 50, 54, 50, 55, 50, 56, 50,
            57, 50, 58, 50, 59, 50, 60, 50, 61, 50, 62, 50, 63, 50, 64, 50,
            65, 50, 66, 50, 67, 59, 50, 59, 67, 59, 67, 59, 67, 59, 50, 59,
            67, 59, 67, 59, 67, 57, 50, 57, 67, 57, 67, 57, 67, 57, 50, 57,
            67, 57, 67, 57, 66, 60, 50, 60, 66, 60, 66, 60, 66, 60, 50, 60,
            66, 60, 66, 60, 43, 59, 67
    };

    private CelloPrelude() {
    }

    /**
     * Playback pitch for a note-block note, the same formula vanilla note blocks use.
     *
     * <p>Takes a note number within an instrument's range, 0-24, not a MIDI pitch - see
     * {@link NoteVoice#noteOf(int)}. Note 12 is the untransposed sample, so it returns 1.0.
     */
    public static float pitchOf(int note) {
        return (float) Math.pow(2.0, (note - 12) / 12.0);
    }

    /** The MIDI note at {@code index}, wrapping so a long dance loops rather than stopping. */
    public static int noteAt(int index) {
        return NOTES[Math.floorMod(index, NOTES.length)];
    }

    /**
     * Where the melody goes next.
     *
     * <p>Restarts from the beginning when the gap since the last note is longer than
     * {@code resetTicks}, so picking it up again after a break starts the piece over instead
     * of resuming mid-arpeggio. {@code lastNoteTick} below zero means nothing has played yet.
     */
    public static int nextIndex(int currentIndex, long now, long lastNoteTick, int resetTicks) {
        if (lastNoteTick < 0L || now - lastNoteTick > resetTicks) {
            return 0;
        }
        return Math.floorMod(currentIndex + 1, NOTES.length);
    }
}
