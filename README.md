Albedo
==
[>> Downloads <<](https://www.curseforge.com/minecraft/mc-mods/albedo)

*API-Powered luminous curiosities with rendering trickery.*

**This mod is open source and under a permissive license.** As such, it can be included in any modpack on any platform without prior permission. See the [LICENSE file](https://github.com/MysticMods/Albedo/blob/master/LICENSE) for more details.

This branch is the **Minecraft 1.12.2** version of Albedo (package `elucent.albedo`). The 1.12.2 source was
restored from the last released jar (`albedo-1.12.2-1.1.0`), since the 1.12 code was never pushed here, and the
abandoned 1.13.2 port was replaced. Mods built against Albedo 1.1.0 or 0.1.x keep working: the public API
(`elucent.albedo.*`) is unchanged, and the old 0.1.x methods are back as deprecated bridges.

## Building

The build uses [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) (RFG) 1.4.x on Gradle 8.14.

| Purpose | JDK |
|---|---|
| Running Gradle itself | Java 17 or 21 (`JAVA_HOME` / `java` on `PATH`) |
| Compiling the mod and running Minecraft | Java 8 (Gradle toolchain, found or downloaded automatically) |

| Task | Command |
|---|---|
| Build the mod jar (`build/libs/`) | `./gradlew build` |
| Start a 1.12.2 dev client with Albedo | `./gradlew runClient` |
| Run the reobfuscated jar in a client | `./gradlew runObfClient` |
| Unattended smoke test: builds a light scene, saves screenshots to `run/screenshots`, quits | `./gradlew runClient -PalbedoAutoTest` |

### MCP bridge (dev client only)

`src/devtools` holds a small dev-only mod, `mcpbridge`, that is on the `runClient` classpath but never in the Albedo
jar. It serves the HTTP protocol of the [mcpfabric](https://github.com/denfry/mcpfabric) mod on
`http://127.0.0.1:25599`, so the mcpfabric MCP server (built for 1.21+) can drive this 1.12.2 dev client: status,
world/session join, commands, blocks, entities, player state, look/move, screenshots and a scene description.
Other mcpfabric tools answer `unsupported`. Config: `run/config/mcpfabric.config.json` (auth is off by default;
the bridge only binds to loopback and refuses requests from web pages).

The server command `albedo` (through `run_command`) prints Albedo's lighting state and the lights of the last
frame; `albedo set <disableLights|maxLights|maxDistance|blockLightOcclusion> <value>` changes settings live.
`curl -d '{"method":"client.shutdown"}' http://127.0.0.1:25599/rpc` closes the dev client.

`runClient` turns on debug lights (`-Dalbedo.debugLights=true`): torches glow orange, sea lanterns blue,
glowstone yellow, and holding a blaze rod casts a cone light.

The first run downloads Minecraft, Forge and the MCP mappings and decompiles Minecraft; this takes several minutes.
The game directory is `run/`. The coremod `elucent.albedo.asm.FMLPlugin` is loaded in dev via
`-Dfml.coreMods.load` and declared in the jar manifest for production.

## Using Albedo in your mod

Add the jar as a compile-only dependency (for example through [CurseMaven](https://cursemaven.com)) and either

* attach an `elucent.albedo.lighting.ILightProvider` capability (`Albedo.LIGHT_PROVIDER_CAPABILITY`) to your
  tile entities, entities or item stacks and add lights in `gatherLights`,
* subscribe to `elucent.albedo.event.GatherLightsEvent` and call `event.add(light)`, or
* register a per-block handler with `Albedo.registerBlockHandler(block, (pos, state, event) -> ...)` for blocks
  without a tile entity.

Lights are built with `Light.builder().pos(...).color(...).radius(...).build()`.

## Configuration (`config/Albedo.cfg`)

| Option | Default | Meaning |
|---|---|---|
| `maxLights` | 40 | Lights rendered per frame, nearest first (the shaders hold at most 100) |
| `maxDistance` | 64 | Distance beyond which lights are culled |
| `disableLights` | false | Turns Albedo lighting off |
| `blockLightOcclusion` | false | Fades coloured light where vanilla block light is 0, so walls block it. Lights without a vanilla light level (e.g. held items) are then hidden too |
