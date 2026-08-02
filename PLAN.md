# BobbyPipes - Build Plan

A clean-room reimagining of request-driven item routing (the LogisticsPipes concept),
built natively for **NeoForge 26.1**.

**Decisions locked in:**

| Decision | Choice |
|---|---|
| Scope | Modern-native redesign - LP's core loop, modern MC internals |
| Loaders | NeoForge only (no Architectury, no Fabric) |
| Provenance | Strict clean-room - see [CLEANROOM.md](CLEANROOM.md) |
| License | LGPL-3.0-only |
| Target | Minecraft 26.1.2 / NeoForge 26.1.2.13-beta / Java 25 |

Progress markers: `[ ]` todo, `[x]` done, `[~]` in progress

---

## Phase 0 - Foundation

Get the repo legally and structurally correct while it is small enough to change cheaply.

- [x] Archive the old playground to `../bobbypipes-playground` (git history intact)
- [x] Create fresh repo directory at `~/Development/bobbypipes`
- [x] Import known-good MC 26.1.2 toolchain (ModDevGradle 2.0.141, Gradle 9.2.1, Java 25)
- [x] Set project identity: `mod_id=bobbypipes`, group `com.bobby.bobbypipes`, version `0.1.0`
- [x] Add LGPL-3.0 license texts (`COPYING`, `COPYING.LESSER`) and declare it in `gradle.properties`
- [x] Write `neoforge.mods.toml` template with real metadata
- [x] Write mod entry point `BobbyPipes.java`
- [x] Write `CLEANROOM.md` - the provenance rules this project operates under
- [x] Write this plan
- [x] `git init` + initial commit
- [x] Confirm `gradlew` has the executable bit set in the git index (verified `100755`)
- [x] Verify `./gradlew build` succeeds and produces `build/libs/bobbypipes-0.1.0.jar`

**Phase 0 complete.**

**Portability rules adopted** (learned from the reference project's failure modes):
- No absolute paths in `gradle.properties` - JDK comes from the Gradle toolchain + foojay resolver
- `gradlew` committed with mode `100755`
- Build must succeed on a machine that is not the author's

---

## Phase 1 - Empty mod loads on 26.1

Prove the stack before any game logic exists.

- [x] `./gradlew build` succeeds from a clean checkout
- [ ] One block, one item, one creative tab registered
- [ ] `./gradlew runClient` launches and the mod appears in the mod list
- [ ] `./gradlew runServer` starts clean (catches client-only leakage early)
- [ ] CI workflow builds on push

---

## Phase 2 - Routing engine

The core value. Loader-agnostic logic, least affected by 26.1's API changes.

- [ ] Network discovery - pipe adjacency graph, incremental rebuild on place/break
- [ ] Router model - each routed pipe is a node with a stable identity across reloads
- [ ] Path computation - Dijkstra, computed once per topology change, **never per tick**
- [ ] Atomic route-table swap so in-flight items survive a rebuild
- [ ] Item-in-transit model - items exist as network state, not entities
- [ ] Request tree - recursive resolution of a request into providers + crafting steps
- [ ] Order manager - outstanding promises, timeouts, failure/rollback
- [ ] Gametest coverage for: delivery, provider selection, request failure, topology change mid-flight

> Design note: this is the subsystem where a naive implementation would most closely
> mirror LP1's structure. Write it from the behavioral spec in `CLEANROOM.md`, not from
> reading anyone's source.

---

## Phase 3 - Modern-native redesign passes

Where the "reimagining" actually lands. Each row replaces a 1.12-era pattern.

- [ ] **Module config -> data components.** Modules are items; their configuration lives in
      `DataComponents`, not NBT blobs. Free client sync, tooltips, copy/paste, stacking.
- [ ] **Pipe & module types -> datapack-driven.** JSON-defined types + code-registered
      behaviors, instead of ~20 hardcoded classes.
- [ ] **Filtering -> tags + component predicates.** Replaces OreDict-era fuzzy matching.
      Use `ItemStackTemplate` for anything appearing in data files.
- [ ] **Power -> pure FE.** `IEnergyStorage` only. No bespoke energy unit, no custom
      wire/junction concept beyond an intake block.
- [ ] **Networking -> `CustomPacketPayload` + `StreamCodec`.** Records throughout.

---

## Phase 4 - GUI layer

Written **once**, natively in 26.1's render-state model
(`Screen#extractRenderState`, `GuiGraphicsExtractor`).

This phase is why the whole plan is sequenced this way: writing GUIs on 1.21.1 first
would mean writing every one of them twice.

- [ ] Shared widget/layout toolkit (the piece LP1 reinvented per-screen)
- [ ] Request screen - search, quantity, crafting preview, missing-items report
- [ ] Chassis screen - module slots + per-module config
- [ ] Provider / Item Sink filter screens
- [ ] Crafter screen

---

## Phase 5 - MVP vertical slice

Do **not** build all pipe types before anything is playable.

Minimum shippable loop:

- [ ] Basic transport pipe
- [ ] Provider pipe (exposes an inventory to the network)
- [ ] Request pipe + its GUI
- [ ] Chassis pipe with one module slot
- [ ] Item Sink module (routes matching items to an inventory)

At that point the mod is demonstrable. Everything below is breadth, added only after
the slice works end to end.

---

## Phase 6 - Breadth

- [ ] Crafter pipes + multi-step crafting chains
- [ ] Satellite / firewall / quicksort pipes
- [ ] Higher chassis tiers, remaining modules
- [ ] Fluid routing
- [ ] Security / permissions
- [ ] Integrations (JEI first) - deliberately last; nothing depends on them

---

## Risks

| Risk | Mitigation |
|---|---|
| 26.1 API churn (NeoForge still `-beta`) | Exact version pins; upgrade deliberately, not automatically |
| Scope - LP is one of the largest mods ever written | Phase 5 slice is non-negotiable before breadth |
| Clean-room discipline erodes under deadline | `CLEANROOM.md` rules; no reference source in this repo, ever |
| Ecosystem not yet on 26.1 | Costs nothing - no integrations planned until Phase 6 |
