package com.bobby.bobbypipes.sound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelloPreludeTest {

    private static final int RESET = 80;

    @Test
    @DisplayName("every note is playable by some instrument, at written pitch")
    void everyNoteHasAVoice() {
        for (int midi : CelloPrelude.NOTES) {
            NoteVoice voice = NoteVoice.forMidi(midi);
            assertNotNull(voice, "no instrument reaches MIDI " + midi);
            int note = voice.noteOf(midi);
            assertTrue(note >= 0 && note < NoteVoice.NOTES_PER_VOICE,
                    "MIDI " + midi + " maps outside " + voice + "'s range");
        }
    }

    @Test
    @DisplayName("the guitar carries the piece, with bass and harp only at the extremes")
    void guitarCarriesTheLine() {
        int guitar = 0;
        int other = 0;
        for (int midi : CelloPrelude.NOTES) {
            if (NoteVoice.forMidi(midi) == NoteVoice.GUITAR) {
                guitar++;
            } else {
                other++;
            }
        }
        // Timbre should stay put; a rewrite that started flipping instruments mid-phrase
        // would be a regression even though every note still sounds.
        assertEquals(629, guitar);
        assertEquals(18, other);
    }

    @Test
    @DisplayName("a voice's own note numbering round-trips")
    void voiceArithmetic() {
        assertEquals(0, NoteVoice.GUITAR.noteOf(42), "bottom of the guitar's range");
        assertEquals(24, NoteVoice.GUITAR.noteOf(66), "top of it");
        assertTrue(NoteVoice.GUITAR.covers(43));
        assertFalse(NoteVoice.GUITAR.covers(41));
        assertFalse(NoteVoice.GUITAR.covers(67));
        assertEquals(NoteVoice.BASS, NoteVoice.forMidi(40), "below the guitar, use the bass");
        assertEquals(NoteVoice.HARP, NoteVoice.forMidi(67), "above it, the harp");
    }

    @Test
    @DisplayName("the melody opens on the tonic and repeats each bar's figure")
    void openingIsTheFamiliarFigure() {
        // G D B A B D B D - the arpeggio everyone recognises, transposed up an octave.
        int[] bar = {43, 50, 59, 57, 59, 50, 59, 50};   // G2 D3 B3 A3 B3 D3 B3 D3
        for (int i = 0; i < bar.length; i++) {
            assertEquals(bar[i], CelloPrelude.NOTES[i], "note " + i);
            assertEquals(bar[i], CelloPrelude.NOTES[i + 8], "repeat of note " + i);
        }
    }

    @Test
    @DisplayName("the whole prelude is 647 notes")
    void lengthIsTheWholePiece() {
        assertEquals(647, CelloPrelude.NOTES.length);
    }

    @Test
    @DisplayName("pitch matches the vanilla note block formula")
    void pitchFormula() {
        assertEquals(1.0f, CelloPrelude.pitchOf(12), 1e-6, "note 12 is the unshifted sample");
        assertEquals(0.5f, CelloPrelude.pitchOf(0), 1e-6, "an octave below");
        assertEquals(2.0f, CelloPrelude.pitchOf(24), 1e-6, "an octave above");
    }

    @Test
    @DisplayName("a continuous dance walks the melody forward")
    void continuousPlayAdvances() {
        int index = CelloPrelude.nextIndex(0, 100, -1L, RESET);
        assertEquals(0, index, "the first note of all is the first note of the piece");
        index = CelloPrelude.nextIndex(index, 102, 100, RESET);
        assertEquals(1, index);
        index = CelloPrelude.nextIndex(index, 104, 102, RESET);
        assertEquals(2, index);
    }

    @Test
    @DisplayName("a long enough pause starts the phrase over")
    void silenceResetsThePhrase() {
        int index = 30;
        // Just inside the window: carry on.
        assertEquals(31, CelloPrelude.nextIndex(index, 1000 + RESET, 1000, RESET));
        // Past it: back to the top.
        assertEquals(0, CelloPrelude.nextIndex(index, 1001 + RESET, 1000, RESET));
    }

    @Test
    @DisplayName("dancing past the end loops rather than falling silent")
    void melodyWraps() {
        int last = CelloPrelude.NOTES.length - 1;
        assertEquals(0, CelloPrelude.nextIndex(last, 102, 100, RESET));
        assertEquals(CelloPrelude.NOTES[0], CelloPrelude.noteAt(CelloPrelude.NOTES.length));
    }

    @Test
    @DisplayName("a huge game time does not break the pause arithmetic")
    void survivesLargeGameTimes() {
        long late = 5_000_000_000L;
        assertEquals(1, CelloPrelude.nextIndex(0, late + 2, late, RESET));
    }
}
