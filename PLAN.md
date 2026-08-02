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

## Where things stand

Phases 0 to 3 are complete. Phase 5 is half done and got there ahead of Phase 4, because
item movement had to work before there was anything worth putting a screen on.

**Items move end to end today.** A request pulls from a chest behind a Provider pipe,
routes across the network, and delivers into a chest behind a Request pipe. Driven by
`/bobbypipes plan`, `/bobbypipes request` and `/bobbypipes parcels` until the GUI lands.

Remaining work is mostly GUI-shaped: packets, a menu, screens, then Chassis and Item Sink.

> **On phase order.** The numbering is the original plan, kept so commit messages still
> line up. Execution has interleaved 4 and 5 rather than running them in order. Work is
> pulled forward when it unblocks something and pushed back when doing it early would mean
> guessing; each deferral says which is which.

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
- [x] `./gradlew runClient` launches, block places and renders, item renders,
      zero missing-model or missing-texture warnings
- [x] `./gradlew runServer` reaches `Done`, mod listed as `BobbyPipes 0.1.0`,
      no errors and no missing registry requirements
- [x] CI workflow builds on push and uploads the jar

**Phase 1 complete.**

Still placeholder: all three pipes draw as plain cubes, and `wrench` has no behaviour.
Connected pipe models are Phase 4 work; the wrench gets its job with the Chassis.

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
      mid-flight: unit tests rather than gametests (see note below)

**Phase 2 complete, engine and world wiring both.**

The engine carries no Minecraft types at all, which is why plain JUnit covers it instead
of gametests: no game runtime, and the suite runs in about a second. `Topology`,
`RouteSolver`, `RoutingSnapshot`, `RoutingCache`, `ParcelTracker`, `RequestPlanner` and
`DeliveryLedger` are all generic over node and item identity.

World side: `PipeBlock` invalidates on place and break, `PipeNetwork` reads pipes out of a
level and owns that level's ledger and parcel tracker, `NetworkEvents` drives it off the
level tick, `NetworkSupply` reads real inventories through the 26.1 capability API using
`ItemResource` as item identity, and `InventoryAccess` handles extraction and insertion.

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

> Design note: this is the subsystem where a naive implementation would most closely
> mirror LP1's structure. Written from observed behaviour, not from reading their source,
> and with its own vocabulary throughout.

---

## Phase 3 - Modern-native redesign passes

Where the "reimagining" actually lands, replacing 1.12-era patterns with modern ones.

- [x] **Module config -> data components.** `ModDataComponents.ITEM_FILTER` carries module
      configuration on the item, not in block entity NBT. Config travels with the module
      when it is pulled out of a chassis, survives being carried, and syncs for free.
- [x] **Filtering -> tags + component predicates.** `ItemFilterEntry` matches by item or by
      item tag, so a filter saying "any plank" keeps working when a mod adds a wood type.
      The mode and empty-list semantics live in `FilterList`, which is generic and unit
      tested; only stack comparison touches Minecraft.

**Phase 3 complete.**

Two further redesign passes were originally listed here and have moved to the phase that
can actually do them: **networking** to Phase 4, **datapack-driven types** and **pure FE
power** to Phase 6. Neither was skipped; both were waiting on something that would tell
them what shape to take, and that thing lives in the later phase.

---

## Phase 4 - GUI layer

Written **once**, natively in 26.1's render-state model
(`Screen#extractRenderState`, `GuiGraphicsExtractor`).

This phase is why the whole plan is sequenced this way: writing GUIs on 1.21.1 first
would mean writing every one of them twice.

- [ ] **Networking - `CustomPacketPayload` + `StreamCodec`.** Records throughout.
      *Moved here from Phase 3.* Packet shapes follow from what the screens actually need
      to send and show, so designing them first would have meant guessing twice. This is
      the prerequisite for everything below it.
- [ ] Menu and container plumbing for the screens
- [ ] Shared widget/layout toolkit (the piece LP1 reinvented per-screen)
- [ ] Request screen - search, quantity, crafting preview, missing-items report
- [ ] Chassis screen - module slots + per-module config
- [ ] Provider / Item Sink filter screens
- [ ] Crafter screen (needs the Crafter pipe from Phase 6)

Pipe rendering is its own phase, below. It shares the render-state migration but is
otherwise independent of the screens and can run in either order.

---

## Phase 4B - Pipe rendering

Pipes currently draw as a fixed 6x6x6 core with a flat texture. That is a deliberate
stopgap, not a design: a thin core is see-past where a full cube was not, and the
collision and highlight boxes follow it so what you can hit matches what you can see.
Everything below replaces it.

### What 26.1 changed, and why this needs planning

The render layer moved to be **per-quad**, set at bake time
(`MutableQuad.chunkLayer()` / `setSprite(sprite, ChunkSectionLayer, RenderType)`). It is no
longer a `render_type` key in the model JSON and no longer a block-level registration, both
of which is how every pre-26.1 tutorial does it. Anything wanting a translucent or cutout
pipe body has to go through the baked-model pipeline, so this is not a one-line change and
should not be attempted as one.

Open question to settle first: **baked model or block entity renderer.** A baked model is
far cheaper because it batches into the chunk mesh, but it cannot animate. Parcels moving
need per-frame positions, so the likely answer is a baked model for the pipe body and a BER
only for parcels. Worth confirming before building either.

### Work

- [ ] Decide baked model vs BER per element, and write the decision down with its reasoning
- [ ] Connection state - six boolean blockstate properties, updated on neighbour change,
      so a pipe knows which sides to draw arms toward
- [ ] Multipart blockstate: core plus one arm model per connected side
- [ ] Voxel shape composed from the same connection state, so collision follows the model
      instead of the two drifting apart
- [ ] Translucent or glass-style body through the per-quad chunk layer, so contents are
      visible from outside
- [ ] Distinguish pipe types visually by more than a tint, since colour alone fails for
      colourblind players and in low light
- [ ] Parcels rendered in transit. `Parcel` already exposes `atNode`, `nextHop` and
      `progress(ticksPerHop)`, which is exactly what an interpolated position needs.
- [ ] Sync parcels to the client. They are server-side network state today, so the client
      cannot see them at all; this depends on the Phase 4 networking item.
- [ ] Connected-texture or seam handling where pipes meet blocks
- [ ] Performance pass: a large network must not rebuild chunk meshes every tick. Verify
      with a few hundred pipes and parcels flowing.

### Deliberately out of scope here

Item models in the world, held-item rendering and the guidebook. Those are cosmetic and
none of them block a playable mod.

---

## Phase 5 - MVP vertical slice

Do **not** build all pipe types before anything is playable.

Minimum shippable loop:

- [x] Basic transport pipe
- [x] Provider pipe (exposes an inventory to the network)
- [x] Request pipe, driving the full pull-extract-route-deliver loop
- [ ] Request GUI - see Phase 4, which is what remains before this is playable without commands
- [ ] Chassis pipe with one module slot
- [ ] Item Sink module (routes matching items to an inventory, using the Phase 3 filter)

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

Once the Request screen exists the mod is demonstrable. Everything below is breadth, added
only after the slice works end to end.

---

## Phase 6 - Breadth

- [ ] Crafter pipes + multi-step crafting chains. The planner already resolves recipes
      recursively; `NetworkSupply.recipesFor` returns nothing only because no block on the
      network claims it can craft.
- [ ] Satellite / firewall / quicksort pipes
- [ ] Higher chassis tiers, remaining modules
- [ ] **Pipe and module types -> datapack-driven.** *Moved here from Phase 3.* Defining a
      JSON-driven registry over the three pipe types that exist today would be abstracting
      over a shape not yet visible. This phase is where enough types exist for the shared
      structure to be real rather than guessed.
- [ ] Fluid routing
- [ ] **Power -> pure FE.** *Moved here from Phase 3.* `IEnergyStorage` only, no bespoke
      energy unit. Deliberately not built earlier: nothing in the MVP slice draws power, so
      it would have been an energy system with no consumer, which is dead code that still
      has to be maintained. It lands with the pipe types that actually cost energy to run.
- [ ] Security / permissions
- [ ] Integrations (JEI first) - deliberately last; nothing depends on them

---

## Risks

| Risk | Mitigation |
|---|---|
| 26.1 API churn (NeoForge still `-beta`) | Exact version pins; upgrade deliberately, not automatically |
| Scope - LP is one of the largest mods ever written | Phase 5 slice before any Phase 6 breadth. Half done. |
| GUI work cannot be verified the way item movement was | Screens need eyeballing; logic behind them stays in testable non-Minecraft classes where possible |
| Ecosystem not yet on 26.1 | Costs nothing - no integrations planned until Phase 6 |
