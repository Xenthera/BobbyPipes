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
| Routing model | LP-shaped: routed pipes are routers; plain pipe is unrouted corridor fabric |

Progress markers: `[ ]` todo, `[x]` done, `[~]` in progress

## Where things stand

Phases 0 to 3 complete. Phase 4 (GUI), 4B (rendering) and much of Phase 5 are done, and
several Phase 6 items were pulled forward: crafting pipes, pattern tables, satellite pipes
and JEI integration all exist.

**Working end to end:** request GUI, provider and request pipes, active and passive supplier
pipes, parcels synced and drawn in transit, satellite naming, pattern import from JEI,
provider pulse budgeting, default routes.

**Seven pipe types exist** and each has its own texture family, hue shifted by
`tools/tint_pipes.py` so the green and red connection marks keep their exact colours:
provider 22, request 158, passive supplier 195, basic 220, supplier 250, crafting 285,
satellite 330.

**Nothing costs power yet, nothing moves fluid, and there is no chassis.** Those three are
the bulk of what is left and each is broken out under Phase 6 below.

**Multi-step crafting chains work.** Confirmed by play on 2026-08-03, including chains
whose steps do not come from a Pattern Table. This was the long-standing broken feature and
it is the rewrite in "Crafting chains" below that fixed it. The one thing still owed is
automated coverage: `CraftJobManager` has no test of its own, so the next regression in it
will be found the same way this bug was, by playing.

> **On phase order.** The numbering is the original plan, kept so commit messages still
> line up. Execution has interleaved 4, 4B, 5 and 6 rather than running them in order.

---

## Crafting chains - fixed

> **Status: working.** Every row of the table below was addressed, and chains were then
> confirmed by play on 2026-08-03, multi-step and including steps not authored at a Pattern
> Table. `CraftJobManager` no longer calls `supplyFor` at all, promises are bound at plan
> time, and the planner runs provider, then surplus, then crafting.
>
> The diagnosis is kept as the record of what was wrong and why the current shape is the way
> it is. Two items in the fix plan are still open, and neither is a known fault: the
> executor has no test of its own, and upstream gating is still per-crafter ordering with a
> timeout freeze rather than real dependency gating.

101 unit tests passed while the feature failed in game. The reason was structural:
`CraftChainSimulator` is described in its own javadoc as a "Minecraft-free stand-in for
CraftJobManager". It is a **parallel reimplementation** of the orchestration that shares
only the small pure helpers in `CraftJobPolicy`. Its five chain tests prove the simulator
is self-consistent; they say nothing about `CraftJobManager`, so any divergence between
the two is invisible.

Diagnosis against how LogisticsPipes actually does it (read from
`~/Development/Logistic-Pipes-2`, branch 1.20.1, `logisticspipes/request/RequestTreeNode`):

| LP behaviour | BobbyPipes as diagnosed | Consequence at the time |
|---|---|---|
| Resolve the whole tree, then `fullFill()`. Nothing moves until the root is satisfied. | Plan, then `CraftJobManager` re-derives pulls at run time via `supplyFor(dest).available(item)`. | The plan's decisions are discarded; execution races against a changed world. |
| Promises are bound to a specific provider and amount. | Craft-input withdrawals are skipped at commit, then re-looked-up later. | Intermediates do not exist in any inventory yet, so `available()` reports nothing and downstream jobs stall. |
| `checkExtras` credits surplus from one node to another in the same tree. | No extras concept. Surplus is routed to a default route or dropped. | A recipe yielding 4 when 2 are needed wastes the rest instead of feeding a sibling. |
| `getSubRequests` backs off to `workSetsAvailable` when inputs fall short. | All-or-nothing per craft step. | Partial feasibility is lost. |
| Order is `checkProvider` then `checkExtras` then `checkCrafting`, per node. | Planner drains stock then recurses, with no extras pass. | Same items get planned twice across branches. |

### Fix plan

- [x] Delete `CraftChainSimulator` and its five tests. They asserted the simulator was
      self-consistent and could never fail on a real bug.
- [x] Bind promises at plan time. `RequestPlan.Sourced` carries an `Origin` of either
      `Stock(provider)` or `Craft(crafter)`; `CraftJobManager` pulls from the bound
      provider instead of re-querying supply.
- [x] Model intermediates as promised future output. An input met by an upstream craft is
      bound to that crafter, and the executor waits instead of hunting for an item that
      does not exist yet.
- [x] Stock feeding a craft is no longer also a withdrawal, which removes the item-name
      filter in `RequestService.commit` that skipped legitimate direct pulls whenever the
      same item appeared anywhere in a recipe.
- [x] A job blocked on upstream work no longer counts down its timeout, so a long chain
      cannot expire waiting for work that has not started.
- [ ] Re-point chain coverage at the real `CraftJobManager` behind a thin world
      abstraction. Planning is now covered by `PlanBindingTest`; execution still is not.
- [x] Surplus credit across the tree, LP's `extrapromises`. A craft that overproduces
      banks the remainder against its crafter; a later demand spends that before starting
      a second craft, and the binding names the producer so it is delivered rather than
      routed away as excess. Byproducts (a recipe's secondary outputs) are still to do and
      need `Supply.Craft` to carry more than one output.
- [x] Back off to a feasible run count: `craftUpTo` binary searches the largest workable
      run count rather than failing the step outright.
- [ ] Gate a job on its upstream dependencies rather than only on per-crafter ordering, so
      long chains cannot time out waiting on work that has not started

---

## Phase 0 - Foundation

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

**Portability rules adopted:**
- No absolute paths in `gradle.properties` - JDK comes from the Gradle toolchain + foojay resolver
- `gradlew` committed with mode `100755`
- Build must succeed on a machine that is not the author's

---

## Phase 1 - Empty mod loads on 26.1

- [x] `./gradlew build` succeeds from a clean checkout
- [x] One block, one item, one creative tab registered
- [x] `./gradlew runClient` launches, block places and renders, item renders,
      zero missing-model or missing-texture warnings
- [x] `./gradlew runServer` reaches `Done`, mod listed as `BobbyPipes 0.1.0`,
      no errors and no missing registry requirements
- [x] CI workflow builds on push and uploads the jar

**Phase 1 complete.**

Still placeholder: `wrench` has no behaviour (lands with Chassis). Pipe models are no
longer placeholder; see Phase 4B.

---

## Phase 2 - Routing engine

Loader-agnostic core, least affected by 26.1's API changes.

- [x] Network discovery - pipe adjacency lattice, incremental rebuild on place/break
- [x] **LP router model** - only routed pipes (`RoutedPipeBlock`: Basic, Provider, Request)
      are routing nodes. Plain `PipeBlock` is unrouted corridor fabric.
- [x] **Direct corridors** - unbranched degree-2 runs of plain pipe between routers become
      transit edges (`DirectCorridors`). A plain T-junction breaks the corridor; place a
      Basic pipe on the junction to reconnect (same build rule as classic LP).
- [x] Path computation - Dijkstra on the transit topology, once per topology change,
      **never per tick**
- [x] Atomic route-table swap so in-flight items survive a rebuild
- [x] Item-in-transit model - items exist as network state, not entities
- [x] Request tree - recursive resolution of a request into providers + crafting steps
- [x] Order manager - outstanding promises, timeouts, failure/rollback
- [x] Coverage for delivery, provider selection, request failure, topology change
      mid-flight, and corridor/junction behaviour: unit tests rather than gametests

**Phase 2 complete, engine and world wiring both.**

The engine carries no Minecraft types at all (`Topology`, `RouteSolver`,
`RoutingSnapshot`, `RoutingCache`, `ParcelTracker`, `RequestPlanner`, `DeliveryLedger`,
`DirectCorridors`). World side: `PipeNetwork` scans the lattice, publishes the transit
topology, paints routed-exit marks on routers only, and owns ledger + parcels per level.

**Verified in a real world** over RCON (`tools/rcon.py`), not only in unit tests:

| Check | Result |
|---|---|
| Straight line of pipes between routers | routes; green exits on routers |
| Plain T-junction between routers | corridor breaks; destination unreachable until Basic is placed |
| Request 5 iron, near chest has 7 and far chest has 40 | takes all 5 from the near chest |
| Request against shortfall | takes what exists, reports the short |
| Provider is opt-in | plain pipe past a chest does not expose it |

> Design note: written from observed LP behaviour, not from reading LP source, with its
> own vocabulary. Unrouted junctions disconnect the router graph rather than randomly
> bouncing parcels (parcels only travel discovered corridors). Same player build rule.

---

## Phase 3 - Modern-native redesign passes

- [x] **Module config -> data components.** `ModDataComponents.ITEM_FILTER` carries module
      configuration on the item, not in block entity NBT.
- [x] **Filtering -> tags + component predicates.** `ItemFilterEntry` / `FilterList`.

**Phase 3 complete.**

Networking moved to Phase 4; datapack-driven types and pure FE power moved to Phase 6.

---

## Phase 4 - GUI layer

Written **once**, natively in 26.1's render-state model
(`Screen#extractRenderState`, `GuiGraphicsExtractor`).

- [x] **Networking - `CustomPacketPayload` + `StreamCodec`.** Parcel sync plus request
      stock / submit / result screen packets.
- [x] Menu and container plumbing for the Request screen
- [ ] Shared widget/layout toolkit
- [~] Request screen - search, quantity, submit, shortfall status (MVP). Crafting preview
      and richer missing-items report still to come.
- [ ] Chassis screen - module slots + per-module config (see Phase 8)
- [ ] Provider / Item Sink filter screens
- [x] Crafter / pattern table / satellite screens (ghost slots, import, JEI transfer)
- [x] Supplier screen, shared by the active and passive supplier. One menu, one screen, and
      a `passive` flag choosing the backdrop.
- [x] **Panel styling pass.** Vanilla hardcodes container labels to a dark grey picked for
      stone-coloured backgrounds, which reads as a smudge on this mod's saturated panels.
      Screens draw their own labels instead of calling `super.extractLabels`, and
      `PanelStyle` holds the palette: white labels, grey hints, and the pipe's own green and
      red taken pixel for pixel from the arm textures so a screen saying "routed" uses the
      same colour the pipe does.
- [x] Empty-state text is centred and scrimmed rather than laid over painted slot art, and
      a search that matched nothing no longer claims the network is empty.

---

## Phase 4B - Pipe rendering

### Settled decisions

- [x] **Baked model for the pipe body**, BER later for parcels only.
- [x] Uniform-width tube (no wider junction core). Caps on open faces; arms on connected faces.
- [x] Connection state - per-face `PipeConnection` enum (`none` / `inventory` / `direct` /
      `indirect`), recomputed one side at a time on neighbour change
- [x] Multipart blockstate: cap / arm per face from connection state
- [x] Voxel shape composed from the same connection state, cached per collapsed state
- [x] Pipes connect to inventories (capability) as well as to other pipes
- [x] **LP routed-exit marks** - green/red stripes on **routed pipes only**, selected by
      `direct` / `indirect` arm models (stripe baked into the arm texture, not a separate
      overlay). Plain transport pipe has no marks.
- [x] Aspect-correct arm UVs (face UV window matches the 7x4.5 arm stub, not a squashed 16x16)

### Still open

- [ ] Translucent or glass-style body through the per-quad chunk layer
- [ ] Distinguish pipe types by more than colour (emblem / shape for colourblind + low light)
- [x] Rudimentary parcel debug render - sync snapshots each tick, draw items along
      `atNode`->`nextHop` via extract/submit (not the final BER)
- [ ] Full parcel renderer (proper BER / host, animation polish)
- [x] Sync parcels to the client (debug-quality full replace; Phase 4 may refine)
- [ ] Connected-texture or seam handling where pipes meet blocks
- [ ] Performance pass on large networks (chunk rebuilds, flowing parcels)

### Deliberately out of scope here

Item models in the world, held-item rendering and the guidebook.

---

## Phase 5 - MVP vertical slice

- [x] Unrouted transport pipe (`PipeBlock`)
- [x] Basic routed pipe (`BasicPipeBlock`) - junction / routing glue, no inventory role
- [x] Provider pipe (exposes an inventory to the network)
- [x] Request pipe, driving the full pull-extract-route-deliver loop
- [x] Request GUI MVP - right-click Request pipe to browse stock and submit (see Phase 4)
- [x] Supplier pipe (active) - nine ghost stock targets, requests its own shortfall once a
      second, cancels an unstarted craft when providers can cover the slot instead
- [x] Passive supplier pipe - the same targets as a sink rather than a requester, see Phase 6
- [ ] Chassis pipe with one module slot
- [ ] Item Sink module (routes matching items to an inventory, using the Phase 3 filter)
- [x] Default route  -  Basic pipe GUI checkbox; space-aware excess sink
- [x] `SinkFinder` - one answer to "where does an item with no destination go", used by
      drift, hopper intake and craft surplus alike. Passive suppliers outrank default
      routes, cheapest route wins within a tier, and a sink takes only what it is short by

**Items move end to end** between routers across corridors. Demonstrable without commands
via the Request screen; debug commands remain for plan inspection and parcel dumps.

Safety behaviour in place:
- Provider is opt-in (plain pipe past storage does not drain it)
- Breaking the network mid-flight strands the parcel and returns items beside that pipe

Everything below is breadth.

---

## Phase 6 - Breadth

- [x] Crafter pipes + multi-step crafting chains - pattern table, crafting pipe,
      `CraftJobManager`, `NetworkSupply.recipesFor` publishes live patterns. Confirmed by
      play 2026-08-03; see "Crafting chains" at the top of the file for the rewrite.
- [x] Satellite pipes (unique names, one satellite / slots 6-8, deliver-and-wait).
      Pattern Table is LP LCT-style (ghost matrix, resource buffer, real craft).
      Firewall / quicksort still open
- [x] Passive supplier pipe - same ghost targets as the Supplier, but a sink rather than a
      requester. `SinkFinder` now answers "where does an item with no destination go", and
      ranks a passive supplier that is still short above a default route, matching how LP
      orders its sinks. Every destination-less item already funnelled through one call, so
      drift, hopper intake and craft surplus all pick it up at once.
- [ ] **Pipe and module types -> datapack-driven**
- [ ] Security / permissions
- [~] Integrations  -  optional JEI recipe transfer into pattern ghosts; fuller JEI later

The three big ones below are the bulk of what is left. Each is a phase in its own right.

---

## Phase 7 - Power

Nothing in the mod costs energy today, so a network is free to run and there is no reason
to build a power infrastructure next to it. LP's power system is what makes a large network
a build rather than a formality.

**Pure FE.** `IEnergyStorage` only, no bespoke energy unit and no conversion ratio to
explain. Machines from every other mod can feed it directly.

- [ ] **Power block** - accepts FE from any cable, buffers it, and is the network's supply.
      Sized so one block serves a modest network and a large one needs several.
- [ ] Power reaches pipes over the routed graph, not over adjacency. A pipe draws from the
      cheapest reachable power block, the same ordering the request planner already uses,
      so `RouteTable.destinationsByCost` does the work again.
- [ ] **Per-pipe draw.** Cost is per action, not per tick, so an idle network is free:
      - provider extract and request delivery charge per parcel dispatched
      - crafting pipes charge per craft started
      - supplier and passive supplier charge per restock parcel
      - basic and plain pipe are free; they are fabric, not actors
- [ ] Brownout behaviour. Out of power is a stall, never a loss: a pipe that cannot pay
      does not act this tick and retries, and no parcel or promise is dropped for it.
- [ ] Power GUI on the block: buffer level, draw rate, and which pipes are drawing.
- [ ] Unit coverage on the pure part (draw accounting, cheapest-supplier selection,
      brownout ordering) with no Minecraft types, the way the routing core is covered.

Open question: whether power is per level or per network. Per network is the LP feel;
per level is far simpler and cannot be gamed by splitting a network in two.

---

## Phase 8 - Chassis pipes

The chassis is what turns a fixed set of pipe types into a system. A chassis is a routed
pipe with module slots; the modules supply the behaviour that is currently welded into each
pipe class.

- [ ] Chassis pipe Mk1, one module slot, using the Phase 3 `ITEM_FILTER` data component so
      module config already travels on the item
- [ ] Module as an item + a behaviour interface, resolved server side per slot
- [ ] Item Sink module (Phase 5 carry-over) - the filter-driven counterpart to the passive
      supplier's explicit targets
- [ ] Provider, Active Supplier, Passive Supplier and Terminus modules. These should be the
      existing pipe logic lifted into modules rather than reimplemented; the standalone
      pipes stay as the convenient preset.
- [ ] Chassis screen - module slots plus the selected module's own config panel (Phase 4)
- [ ] Mk2 to Mk5: more slots, and the power cost per tier if Phase 7 has landed
- [ ] Wrench finally does something beyond opening screens (module insert / extract)

Ordering note: this wants `SinkFinder` to consult modules, not just passive suppliers. The
priority tiers it already has are the place that plugs into.

---

## Phase 9 - Fluid pipes

- [ ] Fluid transport pipe, the plain-pipe equivalent for `FluidResource`
- [ ] Fluid provider and request pipes, reusing the routing graph rather than a second one.
      The topology is about pipes, not about what flows through them.
- [ ] Fluid supplier (LP's `ModuleFluidSupplier`) once Phase 8 exists
- [ ] Decide the transit model: fluid as discrete parcels reuses `ParcelTracker` whole,
      which is the cheap answer, but does not look like flow. A continuous model looks
      right and needs its own tracker.
- [ ] Tank interop through NeoForge's fluid capability, both directions
- [ ] Request screen shows fluids alongside items, with buckets as the unit

---

## Risks

| Risk | Mitigation |
|---|---|
| 26.1 API churn (NeoForge still `-beta`) | Exact version pins; upgrade deliberately, not automatically |
| Scope - LP is one of the largest mods ever written | Phase 5 slice before any Phase 6 breadth |
| Seven pipe hues and only so much room between the green and red marks | `tools/tint_pipes.py` windows the shift by hue so marks survive; past about eight families, shape or emblem has to carry the difference (Phase 4B) |
| GUI work cannot be verified the way item movement was | Screens need eyeballing; logic stays in testable non-Minecraft classes where possible |
| Ecosystem not yet on 26.1 | Costs nothing - no integrations planned until Phase 6 |
