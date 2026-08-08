package com.bobby.bobbypipes.sound;

/**
 * Note block instruments as pitch registers.
 *
 * <p>Every instrument plays the same 25 notes, but each sits at a different octave, so a
 * melody wider than one instrument's two octaves can still be played at true pitch by moving
 * between them. That is what keeps the prelude honest: its 27-semitone span does not fit any
 * single instrument, and folding the outliers by an octave was audibly wrong in the bass.
 *
 * <p>Deliberately free of Minecraft types. The mapping from a voice to its sound event lives
 * at the call site; this class is just the arithmetic, so it can be tested directly.
 */
public enum NoteVoice {

    /** Double bass: F#1 to F#3. */
    BASS(30),
    /** Guitar: F#2 to F#4. */
    GUITAR(42),
    /** Harp/piano: F#3 to F#5, the note block default. */
    HARP(54),
    /** Flute: F#4 to F#6. */
    FLUTE(66),
    /** Bell: F#5 to F#7. */
    BELL(78);

    /** Notes each instrument spans, inclusive of both ends. */
    public static final int NOTES_PER_VOICE = 25;

    /**
     * Search order when more than one voice can play a note.
     *
     * <p>Guitar first because it is a plucked string, which is the closest family to a cello,
     * and because its register covers almost the whole piece on its own - the fewer times the
     * timbre changes mid-phrase, the better it reads as one instrument. Bass and harp pick up
     * the handful of notes that fall off either end.
     */
    private static final NoteVoice[] PREFERENCE = {GUITAR, BASS, HARP, FLUTE, BELL};

    private final int lowestMidi;

    NoteVoice(int lowestMidi) {
        this.lowestMidi = lowestMidi;
    }

    public int lowestMidi() {
        return lowestMidi;
    }

    public boolean covers(int midi) {
        return midi >= lowestMidi && midi < lowestMidi + NOTES_PER_VOICE;
    }

    /** This voice's note number, 0-24, for a MIDI pitch it covers. */
    public int noteOf(int midi) {
        return midi - lowestMidi;
    }

    /**
     * The voice that should play {@code midi}, or null when no instrument reaches it.
     *
     * <p>Null rather than a silent clamp: a note that cannot be played is a data problem worth
     * noticing, not something to quietly transpose.
     */
    public static NoteVoice forMidi(int midi) {
        for (NoteVoice voice : PREFERENCE) {
            if (voice.covers(midi)) {
                return voice;
            }
        }
        return null;
    }
}
