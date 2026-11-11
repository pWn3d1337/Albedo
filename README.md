Albedo Updated
==
[>> Dev builds <<](https://github.com/pWn3d1337/Albedo/releases/tag/dev) · [Original Albedo on CurseForge](https://www.curseforge.com/minecraft/mc-mods/albedo)

*API-Powered luminous curiosities with rendering trickery.*

**This mod is open source and under a permissive license.** As such, it can be included in any modpack on any platform without prior permission. See the [LICENSE file](https://github.com/MysticMods/Albedo/blob/master/LICENSE) for more details.

**Albedo Updated** is a maintained fork of Albedo for **Minecraft 1.12.2**, released as `albedo-updated-<mc>-<version>.jar`.
It is a drop-in replacement for the original: it keeps the mod id `albedo` and the API, so remove the original
Albedo jar when installing it. This is the 1.12.2 version of Albedo (package `elucent.albedo`). The 1.12.2 source was
restored from the last released jar (`albedo-1.12.2-1.1.0`), since the 1.12 code was never pushed here, and the
abandoned 1.13.2 port was replaced. Mods built against Albedo 1.1.0 or 0.1.x keep working: the public API
(`elucent.albedo.*`) is unchanged, and the old 0.1.x methods are back as deprecated bridges.

## Fixes compared to Albedo 1.1.0

Issue numbers refer to the [original issue tracker](https://github.com/MysticMods/Albedo/issues).

### Fixed issues

- Crash `NoSuchMethodError: LightManager.addLight` with mods built for the old 0.1.x API, e.g. WeissAlbedo
  ([#2](https://github.com/MysticMods/Albedo/issues/2), [#11](https://github.com/MysticMods/Albedo/issues/11))
- Crash when a null light is added ([#5](https://github.com/MysticMods/Albedo/issues/5))
- The block-light scanning thread read the world off the main thread and kept a CPU core at 100%; it is now a
  budgeted scan on the client thread ([#3](https://github.com/MysticMods/Albedo/issues/3))
- Mobs turning dark/fog-coloured more than ~10 blocks away in the Nether ([#10](https://github.com/MysticMods/Albedo/issues/10))
- Light positions snapping to a grid far from the world origin ([#8](https://github.com/MysticMods/Albedo/issues/8))
- Albedo now pauses its shaders while another mod (e.g. BetterPortals) uses vanilla's shader system
  ([#4](https://github.com/MysticMods/Albedo/issues/4))
- Lights fully enclosed in solid blocks no longer shine through; the new optional `blockLightOcclusion` setting stops
  coloured light passing through walls ([#1](https://github.com/MysticMods/Albedo/issues/1), [#6](https://github.com/MysticMods/Albedo/issues/6))

### Other fixes

- Lights created with the old 8-argument `Light` constructor were invisible
- All-direction lights were 4x dimmer below the light than above it
- Possible black/garbage pixels from spot lights
- `maxLights` above 100 overflowed the shaders' light array; it is now capped at 100
- Held or worn light-giving items had no effect on players
- The first-person hand and particles were lit from the wrong positions
- Players, mobs, dropped items and block entities lost vanilla's directional shading: all faces were equally
  bright, so they looked too bright and flat (e.g. a dropped ice block)
- Damaged mobs did not flash red, and creepers about to explode did not flash white
- Render code was triggered from the singleplayer server thread
- Albedo no longer patches anything on dedicated servers
- More robust coremod patches; a failed patch is logged instead of crashing the game
- A shader error disables Albedo lighting instead of crashing; no shader or file handle leaks on resource reload
- Faster uniform updates; in-game config changes are now saved

### Not fixed

- [#9](https://github.com/MysticMods/Albedo/issues/9) (crash next to water) is a bug in AlbedoTorches, not Albedo
- [#7](https://github.com/MysticMods/Albedo/issues/7) (black screen on join) could not be reproduced

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
