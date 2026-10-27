# BedWars1058-Revive

An addon for [BedWars1058](https://github.com/andrei1058/BedWars1058) that lets players run a command to
jump into a **running** game on **any team** - including teams that are already full, teams that are
completely dead, and games the player was never part of. Optionally the team's bed comes back too,
placed exactly where it was when the match started.

Kotlin 2.2.21 · Gradle · group `dev.etran` · Java 8 bytecode (one jar for every server version).

## Commands

| Command | What it does |
|---|---|
| `/revive <team> [bed\|nobed] [arena]` | Join / switch to `<team>`. `bed` restores the team's bed, `nobed` doesn't. |
| `/revive teams [arena]` | Lists the teams, their size and bed state. |
| `/revive reload` | Reloads `config.yml`. |

Alias: `/bwrevive`. `[arena]` is optional when you are already inside a game, or when only one game is
running. Tab completion is included.

## Permissions (all default to op)

| Node | Allows |
|---|---|
| `bwrevive.use` | Using `/revive` at all |
| `bwrevive.outsider` | Joining a game you were never part of |
| `bwrevive.bed` | Choosing to restore the bed (without it, the player joins without a bed) |
| `bwrevive.admin` | `/revive reload` |

## Behaviour

* **Team size is never checked** - full teams accept the player.
* **Dead teams are joinable.** The bed stays destroyed unless `bed` was chosen.
* **Never in the game?** The player is added through BedWars1058 and then moved onto the team.
* **Dead / spectating?** They are taken out of spectator mode and put on the team.
* **Alive on another team?** They switch (`allow-team-switch`).
* **Bed restore** uses a snapshot taken at the start of the match, so the bed returns on the exact
  blocks, facing and colour it had. If the plugin was installed mid-game, teams whose bed is already
  broken have no snapshot and are joined without a bed (the player is told).

See `src/main/resources/config.yml` for every option and message.

## Building

```
./gradlew build
```

The plugin jar is `build/libs/BedWars1058-Revive-1.0.0.jar`.

The BedWars1058 API jar is vendored in `libs/` (it is GPL-3.0, taken from the public BedWars1058
project) because `repo.andrei1058.dev` sits behind a bot challenge that Gradle cannot pass. If you can
reach that repository you may replace the `files(...)` line in `build.gradle.kts` with
`compileOnly("com.andrei1058.bedwars:bedwars-api:25.2")`.

## Version support

* The code is compiled against the **1.8.8** Bukkit API, so nothing newer than 1.8 can be linked by
  accident. Anything that only exists on newer servers (1.13+ block data for beds) is reached through
  reflection, and legacy (1.8-1.12) vs modern (1.13+, including the 26.x releases) is chosen at runtime.
* `api-version: '1.13'` is set so modern servers don't apply the legacy-plugin compatibility layer;
  1.8-1.12 servers ignore it.
* The ceiling is set by BedWars1058 itself: its last release (25.2) lists support up to 1.20.
  This addon needs BedWars1058 to be running on your server version.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.