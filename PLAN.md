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

**Seven pipe types exist** and each has its own texture family, tinted at atlas-stitch time
by `PipeCompositeSource` from the shared greyscale sources in `textures/pipe_base/`. No pipe
texture is stored per pipe: a colour is one entry in `assets/minecraft/atlases/blocks.json`
pointing at the same handful of base images. Hues are provider 22, request 158, passive
supplier 195, basic 220, supplier 250, crafting 285, satellite 330.

**Power and fluid have since landed; the chassis has not.** Power junctions with per-pipe
draw exist (Phase 7), and fluid and energy ride the same routing graph as items with their
own provider / request / supplier pipes (Phase 9). Those two phases below still carry
unticked boxes and want a status pass. **The chassis is now the single largest thing left**,
and Phase 8 is written out in full.

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
- [x] Connected-texture or seam handling where pipes meet blocks - a covered pipe reports
      its cover through `getAppearance`, so neighbours texture against it
- [ ] Performance pass on large networks (chunk rebuilds, flowing parcels)
- [x] **Chameleon Cover** - a full-block disguise fitted to any pipe. Hides the tube, wears
      any block's appearance, and is a solid occluder, so a covered run culls neighbouring
      faces and blocks light instead of being a hole in the world. Parcels inside a covered
      run are skipped before the per-frame model resolve in `ParcelRenderer`.

      **Any full cube disguises it**, from any mod. The test is the collision shape, not
      whether the block has a block entity, so modded machines work and chests, slabs and
      stairs fall out for being the wrong shape rather than the wrong implementation.

      **Pipe Goggles see through covers**, showing the real pipes and the parcels moving
      inside them, so a covered network stays maintainable without tearing the covers off.
      Wearing them suspends the parcel culling too, which is the cost of looking.

      The cover is a `COVERED` flag on the pipe's own block state plus the imitated
      `BlockState` on the block entity. The pipe block stays in the world, so the twenty-odd
      `instanceof PipeBlock` checks across `network/` needed no changes at all. Drawing goes
      through `ChameleonCoverModel`, a `DynamicBlockStateModel` that hands the imitated
      block's baked parts straight through on the chunk mesher.

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
- [ ] Chassis pipe with one module slot - **moved to Phase 8**, which now carries the design
- [ ] Item Sink module (routes matching items to an inventory, using the Phase 3 filter) -
      **moved to Phase 8**
- [x] Default route - Basic pipe GUI checkbox; space-aware excess sink
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
- [~] Integrations - optional JEI recipe transfer into pattern ghosts; fuller JEI later

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

### What a chassis is, in four mechanics

Read off observed LP behaviour, same rule as Phase 2: described in this mod's vocabulary,
not lifted from its source.

1. **Module is an item, and its config lives on the item.** Pull a module out with its
   filter set and it keeps that filter in another chassis. The chassis itself stores
   nothing but the stacks. Phase 3 already built exactly this and nothing uses it yet:
   `ModDataComponents.ITEM_FILTER` was added so module config could travel on the item.
2. **Slot order is priority order.** Deciding where a homeless item goes is one question
   asked of the whole network: every router bids, highest priority wins, ties broken by
   routing cost. A chassis polls its modules in slot order and bids the first one willing.
   That one mechanism is what lets a handful of module types replace a pipe type per role.
3. **Modules act on the chassis' attached inventories.** A provider module reads them, an
   extractor pulls from them, a sink inserts into them.
4. **Modules tick and cost power.** Active modules run on their own cadence and each spend
   hits the network, through the junctions Phase 7 already built.

### The blocker: roles are currently block classes

Role is decided today by the type of the block or block entity, in at least six places:

| Where | Check |
|---|---|
| `NetworkSupply` | `instanceof ProviderPipeBlock` |
| `SinkFinder` | `instanceof PassiveSupplierPipeBlockEntity` |
| `DefaultRouteFinder` | `instanceof BasicPipeBlockEntity && isDefaultRoute()` |
| `ProviderAccess` | `instanceof ProviderPipeBlockEntity` for settings |
| `PipeProbe` | block class to name the role in the probe overlay |
| `EnergyRequestService`, `FluidRequestService` | `instanceof Energy/FluidProviderPipeBlock` |

A chassis cannot answer any of them, because its role is whatever is in its slots this
tick. So step one is **role extraction, not chassis**: every one of those becomes a question
the block entity answers. Done while the standalone pipes are still the only implementers,
it is a behaviour-preserving refactor that can be verified against the current build, and
it is worth doing on its own merits even if the chassis slips - the fluid and energy pipes
are already growing the same roles and the same `instanceof` ladder with them.

```java
// logistics/role/ - one interface per role, generic over the resource
public interface ProviderRole<R>   { FilterList<R> providerFilter(); }
public interface SinkRole<R>       { Optional<SinkBid> bidFor(R resource, int count); }
public interface StockTargetRole<R>{ int targetFor(R resource); }
public interface ExtractRole       { void extractTick(ServerLevel level); }

public record SinkBid(int priority, int accept, int tiebreak) {}
```

Generic over `R` from the start, so `SinkFinder` becomes `SinkFinder<R>` and fluid gets one
for free rather than a copy. That in turn wants `FilterList` / `MatchMode` generified off
`ItemResource`, with `ItemFilterEntry` as the first implementation - see the fluid notes
below, which is the other caller waiting on it.

`SinkFinder`'s two hardcoded tiers collapse into one bid loop. The tiers become numbers:
passive supplier bids 100, a default route bids 0, an Item Sink module bids whatever its
config says. Current behaviour falls out of the general rule, which is the test that the
generalisation is the right one.

### Then the chassis

- [ ] **`ChassisPipeBlock` Mk1 to Mk4** extending `RoutedPipeBlock`, one block per tier,
      differing only in slot count. Tinted by `PipeCompositeSource` like everything else;
      the seven existing hues leave room around 45 to 90 for a family of four.
- [ ] **`ChassisPipeBlockEntity`** holds an `ItemStackHandler` and a `List<PipeModule>`
      re-resolved whenever a slot changes. It implements every role interface and delegates
      to whichever modules are present, folding the slot index into `SinkBid.tiebreak` so
      earlier slots win within one chassis.
- [ ] **`PipeModule`** behaviour interface plus a registry keyed by item. A plain
      `Map<Item, PipeModule>` to start; the datapack-driven types already wanted in Phase 6
      are the reason to make it a real registry later.
- [ ] **Modules, lifted not reimplemented.** The standalone pipes stay as the pre-configured
      early-game preset - they are already textured, screened and documented, and keeping
      them is what gives the tier ladder a bottom rung.

| Module | Lifted from |
|---|---|
| Provider | `ProviderPipeBlockEntity` + `ProviderAccess`, settings read from the module stack |
| Item Sink | new; the filter-driven sibling of the passive supplier's explicit targets |
| Passive Supplier | `PassiveSupplierPipeBlockEntity.targetFor` |
| Active Supplier | `SupplierPipeBlockEntity.restock`, already a clean tick + restock pair |
| Terminus | `BasicPipeBlockEntity.isDefaultRoute` |
| Extractor / QuickSort | new, but `PipeExtract` and `SinkFinder` already do the work |

`StockTargetPipeBlockEntity` is already the shared base of the active and passive supplier,
and `SupplierPipeScreen` already takes a `passive` flag. Most of a module boundary is there.

- [ ] **Chassis screen.** Slot row, and clicking a configured slot opens that role's
      *existing* screen with a back button - `ProviderPipeScreen`, `SupplierPipeScreen` -
      rather than a bespoke nested module GUI. The menus and payloads already exist; what
      changes is that `SetProviderSettingsPayload` and `SetSupplierRequestsPayload` address
      a `(pos, slot)` instead of a `pos`.
- [ ] **Power.** Add module spend kinds to `LogisticsPowerCosts` plus a per-tier idle draw,
      and charge them through `ComponentPower` the way `SUPPLIER` is charged today.
- [ ] Wrench finally does something beyond opening screens (module insert / extract)

### Deliberate deviation: keep multi-inventory

LP's chassis serves the one inventory it faces, set with a wrench. `ProviderAccess` here
deliberately offers *every* distinct adjacent inventory, with claim-set logic so two pipes
on one chest do not double-count it. Reusing that is less code and less new UX than adding
chassis orientation, and it costs nothing conceptually: a module operates on all adjacent
stores. The wrench stays a config tool rather than growing an orientation mode.

### Suggested order

1. Role interfaces and the `SinkBid` model, standalone pipes only. No behaviour change.
2. `PipeModule` + registry + Provider and Passive Supplier modules; Mk1, one slot, no GUI
   past the slot itself.
3. Chassis screen and sub-screen routing.
4. Remaining modules, Mk2 to Mk4, power costs.

Structure gap 5 below - `block/` at 39 flat files - is worth acting on at step 1, since this
phase adds a chassis family and a `logistics/role/` package at the same time.

---

## Phase 8B - Chassis for fluid and energy

Worth designing now even if it is built later, because it decides whether the Phase 8 role
interfaces are generic or item-shaped, and that is a decision made in the first commit.

**The ground truth.** There is one routing graph and three media ride it
(`PipeNetwork.energyParcels`, `fluidParcels`, "on the same routing graph as items"). What
separates the media is not routing, it is arms: `PipeMedium` gates which neighbours a pipe
grows an arm toward, and it is a method on the *block*, so an item pipe cannot touch a tank.
A chassis therefore cannot serve a chest and a tank unless its medium becomes dynamic.

**Two ways to take that.**

- **A. Three chassis families.** Item, fluid and energy chassis blocks, one module framework,
  each module declaring the medium it needs. Trivially safe, and it matches how the pipe
  families are already split. Costs the player three blocks and a lot of duplicated slots.
- **B. One chassis, medium set from its modules.** The union of the installed modules' media,
  defaulting to items when empty. One block does everything, which is the better toy.

**Recommended: B, with the medium set stored as blockstate properties**, three booleans set
when the slots change, rather than read from the block entity. `PipeMedium.presentAt`
already takes a level and a position; what must not happen is arm computation reaching for a
block entity during placement, when it may not exist yet - the classic way this goes wrong.
Blockstate properties keep the connection code reading only state, which is what it reads
today. If that proves fiddly, A is the fallback and no module code changes.

- [ ] Decide A vs B and record it here before writing the first module
- [ ] Generify `FilterList` / `MatchMode` off `ItemResource`. Fluid has a real resource
      identity and wants the same tag and component predicates; energy does not have one at
      all. This is the shared prerequisite and the reason the Phase 8 roles are `<R>`.
- [ ] `SinkFinder<FluidResource>` for a Fluid Sink module, once the item one is generic
- [ ] Fluid modules: Provider, Supplier (LP's `ModuleFluidSupplier`, the Phase 9 carry-over),
      Sink. Filters apply.
- [ ] Energy modules: Provider, Supplier (top an adjacent machine's buffer to a threshold),
      Terminus. **No filters at all** - energy has a single implicit kind, so an energy
      module's only config is a threshold and a priority number. Do not force it through the
      filter UI for symmetry's sake.
- [ ] Power costs map onto existing kinds. `PowerSpendKind` already has `ENERGY_PROVIDER`,
      `ENERGY_SUPPLIER`, `FLUID_PROVIDER`, `FLUID_SUPPLIER`; a module spend is the same kind
      the standalone pipe charges, so tiering is the only new number.

**The one real hazard: energy modules can deadlock the network.** Phase 7's rule is that a
brownout is a stall and never a loss, and a stall is safe for items - the parcel waits. It
is not safe for the module whose job is delivering power: a network that browns out cannot
pay for the energy supplier module that would end the brownout, and it never recovers
without manual intervention. Energy provider and supplier modules must either be exempt from
the power charge or be charged against the junction they are *delivering to* rather than the
one they draw from. Decide this when energy modules are written, not after the first bug
report, and cover it with a test in the pure power accounting layer.

---

## Phase 9 - Fluid pipes

- [ ] Fluid transport pipe, the plain-pipe equivalent for `FluidResource`
- [ ] Fluid provider and request pipes, reusing the routing graph rather than a second one.
      The topology is about pipes, not about what flows through them.
- [ ] Fluid supplier (LP's `ModuleFluidSupplier`) once Phase 8 exists - see Phase 8B, which
      also decides whether fluid gets its own chassis family or shares one block
- [ ] Decide the transit model: fluid as discrete parcels reuses `ParcelTracker` whole,
      which is the cheap answer, but does not look like flow. A continuous model looks
      right and needs its own tracker.
- [ ] Tank interop through NeoForge's fluid capability, both directions
- [ ] Request screen shows fluids alongside items, with buckets as the unit

---

## Structure - how this compares to the mods it sits beside

Reviewed 2026-08-07 against AE2, Mekanism, EnderIO and Create: the NeoForge mods of
comparable scope. 226 source files, 27k lines.

**Ahead of the pack.** Twenty-two test classes over routing, planning, topology and filters,
where most Minecraft mods have none, and they are real tests rather than smoke tests. The
`registry/` package is textbook `DeferredRegister` layout. Class javadoc explains *why*
rather than restating the signature, which is rarer than the test coverage is.
`PipeCompositeSource` - baking every pipe colour from one shared source image at
atlas-stitch time - is a better idea than the per-pipe PNG sets AE2 and Mekanism both ship.

**Gaps, worst first.**

1. ~~No shared block entity base.~~ **Fixed 2026-08-07.** Thirteen block entities each
   re-implemented the same save/load/sync boilerplate. AE2 has `AEBaseBlockEntity`,
   Mekanism `TileEntityMekanism`, EnderIO `EnderBlockEntity`; the eight pipe ones now share
   `PipeBlockEntity`, which is also where cover state lives.

2. ~~No datagen.~~ **Wired 2026-08-07, blockstates only.** `PipeModelProvider` generates all
   fifteen pipe blockstates into `src/generated/resources` from three shared layouts -
   plain, routed and link - replacing 450 hand-written multipart cases. Verified case for
   case against the files it replaced before those were deleted.

   Two things fell out of doing it. The layouts are built from the real `Property` objects,
   so a case naming a property its block does not have now fails to compile rather than
   failing silently at load. And the `src/generated/**/.cache` exclude in `build.gradle` had
   never matched anything - resource excludes are relative to the source directory - so the
   datagen cache shipped inside the mod jar the moment generation produced output.

   Still hand-written: the ~100 pipe *model* JSONs. Their geometry carries aspect-corrected
   UVs worth more than the repetition costs, and re-deriving it through a builder risks
   silently worse output. The way in, when it is worth it, is to extract seven shared
   geometry parents and generate thin texture-only children.

3. ~~`network/` means two unrelated things.~~ **Split 2026-08-07.** The logistics engine
   moved to `logistics/` (46 files, including `craft/` and `power/`), leaving `network/` as
   packets only - `ModPayloads` and `payload/`. Matches AE2 and Mekanism, where `network/`
   means networking.

4. ~~`ParcelDebugRenderer` is misnamed.~~ **Renamed 2026-08-07** to `ParcelRenderer`. It is
   the production in-pipe item renderer; only `ClientChunkLoaderDebug` and
   `PipeProbeRenderer` are genuinely debug.

5. **`block/` is 39 flat files.** Readable at this size; AE2 and Mekanism subdivide by domain
   past about thirty. Worth splitting when the chassis lands, not before.

6. **No `api/` package.** Correct for pre-alpha. Don't build one until an addon asks.

---

## Risks

| Risk | Mitigation |
|---|---|
| 26.1 API churn (NeoForge still `-beta`) | Exact version pins; upgrade deliberately, not automatically |
| Scope - LP is one of the largest mods ever written | Phase 5 slice before any Phase 6 breadth |
| Seven pipe hues and only so much room between the green and red marks | `PipeCompositeSource` tints only the greyscale body base and composites the marks on top unmodified, so they always survive; past about eight families, shape or emblem has to carry the difference (Phase 4B) |
| GUI work cannot be verified the way item movement was | Screens need eyeballing; logic stays in testable non-Minecraft classes where possible |
| Ecosystem not yet on 26.1 | Costs nothing - no integrations planned until Phase 6 |
