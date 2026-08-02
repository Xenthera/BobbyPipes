# BobbyPipes

Request-driven item routing for modern Minecraft. Items move when you ask for them, not
before.

**Minecraft 26.1.2 , NeoForge , Java 25**

> **Pre-alpha.** Nothing is playable yet. See [PLAN.md](PLAN.md) for what's built and
> what's next.

## The idea

Most item-transport mods push: you insert items at one end and hope they land somewhere
useful. BobbyPipes pulls. You ask the network for 64 iron ingots, and the network works
out who has them, who can craft them, and routes exactly that much to you - then goes
quiet again.

That gives you:

- **Request-based delivery** - no constant item flow, no items circulating forever
- **Recursive crafting** - request a machine, get its whole crafting tree resolved
- **Modular chassis pipes** - behaviour is composed from modules, not baked into pipe types
- **Networks that scale** - routes are computed on topology change, never per tick

## Status

Phase 0 complete: toolchain, licensing, and project structure. No gameplay yet.

## Relationship to LogisticsPipes

BobbyPipes is inspired by LogisticsPipes but shares no code with it. It is an independent
clean-room implementation of the same *genre*, not a port or a fork. The rules the project
holds itself to are written down in [CLEANROOM.md](CLEANROOM.md).

If you want LogisticsPipes itself, go support the original.

## Building

Requires a JDK to launch Gradle; the Java 25 toolchain is provisioned automatically via
the foojay resolver.

```sh
./gradlew build          # build the jar
./gradlew runClient      # launch a dev client
./gradlew runServer      # launch a dev server
```

No absolute paths are baked into the build. If it doesn't build on a fresh clone, that's a
bug - please report it.

## License

[LGPL-3.0-only](COPYING.LESSER). Forks stay open; other mods may depend on it freely.
