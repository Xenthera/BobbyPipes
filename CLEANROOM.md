# Clean-room policy

BobbyPipes is a reimagining of the request-driven item-routing genre popularised by
LogisticsPipes. **No code in this repository is derived from LogisticsPipes or any of its
forks.**

This document is the standing rule set. It exists because the protection is only as good
as the discipline, and discipline erodes quietly.

## Why this matters

LogisticsPipes is licensed under the **MMPL-1.0.1**, which is viral: §6 requires that
modified versions, and *"files containing sections copied from this mod,"* remain under
MMPL. §5 separately grants the right to write *independent* code that interoperates with
it, and explicitly permits that code to carry a different license.

Staying strictly on the §5 side of that line is what lets this project ship under
LGPL-3.0 rather than inheriting MMPL.

## The rules

1. **No LogisticsPipes source in this repository. Ever.**
   Not in `src/`, not in a `reference/` folder, not commented out, not in git history.
   If you need to consult it, check it out somewhere else on disk.

2. **Specs, not sources.**
   You may study *what* the original does - that a Provider pipe advertises its inventory
   to the network, that a request resolves recursively into providers and crafting steps.
   You may not reproduce *how* it does it: class decomposition, method breakdown,
   algorithm structure, field layout.

3. **Write the behaviour down first.**
   When porting a concept, describe it in prose in an issue or a design note, then
   implement from that description. If the description isn't enough to implement from,
   the description is incomplete - go back and study the behaviour more, not the code.

4. **Don't inherit their names.**
   `ServerRouter`, `ExitRoute`, `PathFinder`, `RequestTree`, `LogisticsOrderManager`,
   `DictResource` are LogisticsPipes' vocabulary. Individually, names aren't protectable -
   but a wholesale-matching type inventory is strong evidence of structural copying, and
   it makes the claim expensive to defend. Pick our own vocabulary.

5. **AI assistants are bound by these rules too.**
   Do not point a coding agent at a LogisticsPipes checkout and ask it to port, mirror, or
   "compare implementations." An agent that has read the original and then writes the
   replacement is not a clean-room implementation - it is the exact thing clean-room
   procedure is designed to prevent.

6. **Assets are code.**
   Textures, models, OBJ geometry, sounds and lang files carry the same restriction.

## What *is* fine

- Playing the original mod and taking notes on behaviour.
- Reading its documentation, wiki, changelogs, and issue tracker.
- Reproducing game mechanics. Mechanics are not copyrightable; a mod may implement
  "request items over a pipe network" freely.
- Matching player-facing terminology where it is genuinely descriptive
  (a "Provider" pipe provides; a "Chassis" holds modules). Interface vocabulary that
  players need in order to understand the mod is not the same as internal type names.

## If a rule gets broken

Say so, and revert the affected code rather than patching over it. A quiet violation
compounds: every file written on top of it inherits the problem, and the cost of
unwinding grows with every commit.
