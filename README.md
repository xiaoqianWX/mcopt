# mcopt

A Minecraft performance mod for Apple Silicon Macs. **Very much alpha:** expect bugs, and back up your worlds.

## Requirements

- An Apple Silicon Mac on macOS 26 or later
- Java 25
- Minecraft 26.3 with Fabric Loader 0.19.5
- Sodium 0.9.3, a separate download:
  [sodium-fabric-0.9.3-alpha.1+mc26.3.jar](https://cdn.modrinth.com/data/AANobbMI/versions/v4PSXean/sodium-fabric-0.9.3-alpha.1%2Bmc26.3.jar)

## Install

Drop the mcopt jar and the Sodium jar into `~/Library/Application Support/minecraft/mods`. Done.

Shows an fps counter in the top-left corner (hidden with F1 or F3).

## Settings

In Video Settings (Sodium's screen):

- **General > Graphics API** picks the renderer, and does what it says. Restart to apply.
  - **Metal (mcopt)**, the default: Sodium still builds, sorts and culls the chunks; mcopt draws them, and everything else,
    on Metal. If Metal can't start, the game falls back to OpenGL, then Vulkan.
  - **OpenGL** or **Vulkan (MoltenVK)**: the game's own renderers. mcopt's other optimizations stay on.
  - The tooltip says what's running now. When something installed can't run on Metal (Iris, or Distant Horizons set to
    its OpenGL renderer), Metal steps aside on its own and the option says why.
- **mcopt** page: what's running and how mcopt works with Sodium, the perf profile, and far terrain.
  - The perf profile is on by default and picks its settings for your Mac.
  - Far terrain is an experimental opt-in, for Macs with 10 or more GPU cores. In singleplayer it's generated from the
    world. On a server it shows the terrain you've been near: mcopt keeps the chunks the server sends (in
    `mcopt-lod/servers`), so far view grows as you explore, and the fog stays put until there's something to show. It
    asks nothing extra of the server.
  - Under it, applied at once: **Far distance** (64 to 1024 chunks), **Far detail** (how far out block-sized detail
    reaches before it coarsens; the tooltip gives the memory each choice takes), **Far haze**, and **Far terrain on
    servers** (Saved, This session with nothing written to disk, or Off). After a restart: **Far block textures** and
    **Far plants**. **Forget this world's far terrain** deletes what mcopt has kept of the world or server you're in,
    after asking, and starts it over.

All of these are saved in `config/mcopt.properties` (`profile=none` turns the profile off, `mcopt.lod=true` turns far
terrain on). The first launch writes that file.

## Numbers

fps on a MacBook Pro M5 Pro (20-core GPU, 24 GB), macOS 26.6, built-in 120 Hz display. Fixed-seed world, render distance
16, VSync off, Max Framerate unlimited; 15 s each standing still / spinning / flying at 20 blocks a second. Median of 3-5
interleaved runs, 1% low in brackets. Measured with `tools/bench.py` (the `bench/` mod flies the camera).

Fullscreen (3024x1964):

| | still | spin | flight |
|---|---|---|---|
| Sodium, OpenGL | 205 (82) | 214 (94) | 184 (83) |
| Sodium, Vulkan (MoltenVK) | 120 (58) | 120 (57) | 120 (56) |
| mcopt, Metal | 2303 (971) | 2161 (911) | 1986 (707) |
| mcopt, OpenGL | 168 (84) | 150 (98) | 156 (91) |

1280x720 window (2560x1440):

| | still | spin | flight |
|---|---|---|---|
| Sodium, OpenGL | 570 (173) | 509 (162) | 506 (171) |
| Sodium, Vulkan (MoltenVK) | 120 (66) | 120 (78) | 120 (56) |
| mcopt, Metal | 2615 (1001) | 2459 (981) | 2248 (767) |
| mcopt, OpenGL | 607 (234) | 502 (172) | 445 (140) |

With VSync off the display still shows at most 120 frames a second; fps counts the frames the game renders. Vulkan stays
at the refresh rate even with VSync off. Fullscreen OpenGL varied a lot between runs (130-450 fps).

## Known issues

- Only Sodium 0.9.3 works. With another version, Fabric stops at launch and says which one to install.
- Mods that call OpenGL directly can't draw on the Metal backend. Set Graphics API to OpenGL for them (or
  `-Dmcopt.metal=false`). Iris is detected, and Metal steps aside for it on its own.
- Distant Horizons draws on Metal with its default renderer (Blaze3D), and looks as it does on Vulkan (its LODs are a
  little hazier there than on OpenGL; that's Distant Horizons). Set to its OpenGL renderer, or next to Iris, it needs
  OpenGL, and Metal steps aside. mcopt's own far terrain stays off while Distant Horizons is installed.
- Far terrain is experimental. On Macs with fewer than 10 GPU cores, it costs most of the fps. On servers, it's kept per
  server address and dimension: a server that swaps its map shows the old one until the new one's chunks have been around
  (or until you press Forget).
- Far terrain is a height per column, with one floating layer: a build over 12 or more blocks of open space (a
  platform, a bridge, an overhang) floats over what's under it, but a second one under it stands on the ground. Past Far
  detail's reach, cells get twice as wide at each step: a build shows where it covers at least half a cell, and builds
  over open space aren't drawn there.
- With fewer than 10 GPU cores, or 8 GB of memory or less, the profile leaves out the bigger chunk cache.
- With Lithium, C2ME or ScalableLux installed, some of mcopt's chunk patches step aside on purpose.

## Plans

- Far terrain on by default, with tiers for smaller Macs
- Our own shaders (BSL-level) as an option
- Faster world generation (native noise, bit-exact)
- More exact server-tick and chunk optimizations, held for later alphas
- Faster startup
- Wider hardware testing

## Licence and credits

[Apache License 2.0](LICENSE). Keep the [NOTICE](NOTICE) file with any copy, and credit mcopt. Files adapted from
[Sodium](https://github.com/CaffeineMC/sodium) keep the [PolyForm Shield License 1.0.0](LICENSES/PolyForm-Shield-1.0.0.md).
Each of those files says so in its header, and NOTICE lists them. mcopt runs on top of Sodium, by JellySquid and its
contributors.
