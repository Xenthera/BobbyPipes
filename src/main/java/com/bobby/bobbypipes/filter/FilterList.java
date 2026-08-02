package com.bobby.bobbypipes.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A list of entries plus a {@link MatchMode}, answering "does this pass".
 *
 * <p>Deliberately free of any Minecraft type. Callers supply a predicate saying whether a
 * given entry matches whatever candidate they hold, and this decides what that means for
 * acceptance. The awkward part of filtering has never been comparing two items, it is the
 * mode and empty-list semantics, and that is exactly the part kept here where it can be
 * tested directly.
 *
 * <p>Empty lists follow from the mode rather than being a special case:
 * an empty {@link MatchMode#ALLOW} list names nothing as permitted so nothing passes, and
 * an empty {@link MatchMode#DENY} list names nothing as forbidden so everything passes.
 *
 * @param <E> entry type
 */
public record FilterList<E>(List<E> entries, MatchMode mode) {

    public FilterList {
        entries = List.copyOf(entries);
    }

    public static <E> FilterList<E> allowNothing() {
        return new FilterList<>(List.of(), MatchMode.ALLOW);
    }

    public static <E> FilterList<E> allowEverything() {
        return new FilterList<>(List.of(), MatchMode.DENY);
    }

    @SafeVarargs
    public static <E> FilterList<E> allowing(E... entries) {
        return new FilterList<>(List.of(entries), MatchMode.ALLOW);
    }

    @SafeVarargs
    public static <E> FilterList<E> denying(E... entries) {
        return new FilterList<>(List.of(entries), MatchMode.DENY);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /**
     * Whether a candidate passes, given a predicate that says which entries it matches.
     *
     * @param matchesEntry true when the candidate matches that entry
     */
    public boolean accepts(Predicate<E> matchesEntry) {
        boolean listed = entries.stream().anyMatch(matchesEntry);
        return switch (mode) {
            case ALLOW -> listed;
            case DENY -> !listed;
        };
    }

    /** Convenience for entries compared by equality. */
    public boolean acceptsExactly(E candidate) {
        return accepts(entry -> entry.equals(candidate));
    }

    public FilterList<E> withMode(MatchMode newMode) {
        return new FilterList<>(entries, newMode);
    }

    /** Adds an entry, ignoring duplicates so a UI can add freely. */
    public FilterList<E> with(E entry) {
        if (entries.contains(entry)) {
            return this;
        }
        List<E> updated = new ArrayList<>(entries);
        updated.add(entry);
        return new FilterList<>(updated, mode);
    }

    public FilterList<E> without(E entry) {
        if (!entries.contains(entry)) {
            return this;
        }
        List<E> updated = new ArrayList<>(entries);
        updated.remove(entry);
        return new FilterList<>(updated, mode);
    }

    public FilterList<E> cleared() {
        return new FilterList<>(List.of(), mode);
    }
}
