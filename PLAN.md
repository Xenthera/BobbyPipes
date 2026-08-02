# BobbyPipes - Build Plan

Request-driven item routing, built natively for **NeoForge 26.1**.

**Decisions locked in:**

| Decision | Choice |
|---|---|
| Scope | Modern-native redesign - LP's core loop, modern MC internals |
| Loaders | NeoForge only (no Architectury, no Fabric) |
| Provenance | No LogisticsPipes code, written from scratch |
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
- [x] One block, one item, one creative tab registered
      (`bobbypipes:pipe`, `bobbypipes:wrench`, tab `bobbypipes:main`)
- [x] `./gradlew runClient` launches, block places and renders, item renders,
      zero missing-model or missing-texture warnings
- [x] `./gradlew runServer` reaches `Done`, mod listed as `BobbyPipes 0.1.0`,
      no errors and no missing registry requirements
- [x] CI workflow builds on push and uploads the jar

**Phase 1 complete.**

Placeholder content, to be replaced later: `pipe` is a plain cube (real model plus
block entity lands in Phase 2), `wrench` has no behaviour yet.

---

## Phase 2 - Routing engine

The core value. Loader-agnostic logic, least affected by 26.1's API changes.

- [x] Network discovery - pipe adjacency graph, incremental rebuild on place/break
- [x] Router model - each routed pipe is a node with a stable identity across reloads
- [x] Path computation - Dijkstra, computed once per topology change, **never per tick**
- [x] Atomic route-table swap so in-flight items survive a rebuild
- [x] Item-in-transit model - items exist as network state, not entities
- [x] Request tree - recursive resolution of a request into providers + crafting steps
- [x] Order manager - outstanding promises, timeouts, failure/rollback
- [x] Coverage for delivery, provider selection, request failure and topology change
      mid-flight: 57 unit tests, not gametests (see note below)

**Phase 2 engine complete. Only the routing graph is wired to the world.**

The engine carries no Minecraft types at all, which is why plain JUnit covers it instead
of gametests: no game runtime, and the suite runs in about a second. `Topology`,
`RouteSolver`, `RoutingSnapshot`, `RoutingCache`, `ParcelTracker`, `RequestPlanner` and
`DeliveryLedger` are all generic over node and item identity.

Connected to the world: `PipeBlock` invalidates on place and break, `PipeNetwork` reads
pipes out of a level and owns that level's ledger and parcel tracker, `NetworkEvents`
drives it off the level tick, and `NetworkSupply` reads real inventories through the 26.1
capability API using `ItemResource` as item identity.

**Verified in a real world** over RCON (`tools/rcon.py`), not only in unit tests:

| Check | Result |
|---|---|
| Straight line of 5 pipes | 4 hops, first step correct |
| Break a pipe mid-line | destination goes unreachable, restoring fixes it |
| Detour around a gap | reroutes 6 hops up and over |
| Request 5 iron, near chest has 7 and far chest has 40 | takes all 5 from the near chest |
| Request 45 | 7 from near, then 38 from far |
| Request 100 against 47 available | takes 47, reports short by 53 |
| Request an item nothing holds | nothing found, short by the full amount |

Still not connected, because the blocks do not exist yet:
- no level injects parcels, so nothing visibly moves
- nothing commits a `RequestPlan` into `DeliveryLedger` promises
- every pipe with an adjacent inventory currently counts as a provider, pending a real
  Provider pipe type

Those hook up in Phase 5 when the Provider and Request pipes get built.

> Design note: this is the subsystem where a naive implementation would most closely
> mirror LP1's structure. Written from observed behaviour, not from reading their source,
> and with its own vocabulary throughout.

---

## Phase 3 - Modern-native redesign passes

Where the "reimagining" actually lands. Each row replaces a 1.12-era pattern.

- [x] **Module config -> data components.** `ModDataComponents.ITEM_FILTER` carries module
      configuration on the item, not in block entity NBT. Config travels with the module
      when it is pulled out of a chassis, survives being carried, and syncs for free.
- [x] **Filtering -> tags + component predicates.** `ItemFilterEntry` matches by item or by
      item tag, so a filter saying "any plank" keeps working when a mod adds a wood type.
      The mode and empty-list semantics live in `FilterList`, which is generic and unit
      tested; only stack comparison touches Minecraft.
- [ ] **Networking -> `CustomPacketPayload` + `StreamCodec`.** Deferred to Phase 4.
- [ ] **Pipe & module types -> datapack-driven.** Deferred, see below.
- [ ] **Power -> pure FE.** Deferred, see below.

**Three items deliberately deferred rather than done early.**

- *Networking* has nothing to carry until a screen exists. Designing packet shapes before
  the GUI that uses them means guessing twice. It lands with Phase 4.
- *Datapack-driven types* would be abstracting a registry over a single implementation.
  Worth doing at three or four pipe types, when the shared shape is actually visible.
- *Pure FE power* has no consumer in the Phase 5 slice. An energy system nothing draws
  from is dead code that still has to be maintained.

None of these is blocked; each is waiting for the thing that would tell it what shape to
take.

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

- [x] Basic transport pipe
- [x] Provider pipe (exposes an inventory to the network)
- [x] Request pipe, driving the full pull-extract-route-deliver loop
- [ ] Request GUI (Phase 4)
- [ ] Chassis pipe with one module slot
- [ ] Item Sink module (routes matching items to an inventory)

**Items move end to end.** Verified in a real world over RCON: a request pulled 10 iron
out of a chest behind a Provider pipe, routed it four hops, and dropped it into the chest
behind the Request pipe. Source went 40 to 30, destination 0 to 10, promise settled,
parcel retired.

Safety behaviour verified too:
- Being a provider is opt-in. Swapping the Provider pipe for a plain one makes the same
  chest invisible to the network, so a pipe routed past storage does not drain it.
- Breaking the network mid-flight strands the parcel and puts the items back into an
  inventory beside the pipe it gave up on. Source plus destination still totalled the
  original 40, so nothing was lost or duplicated.

Driven for now by `/bobbypipes plan` (dry run), `/bobbypipes request` (actually ships) and
`/bobbypipes parcels` (what is in flight), until the GUI replaces them.

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
| Ecosystem not yet on 26.1 | Costs nothing - no integrations planned until Phase 6 |
