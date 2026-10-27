# BedWars1058-Revive

An addon for [BedWars1058](https://github.com/andrei1058/BedWars1058) that lets players run a command to
jump into a **running** game on **any team** - including teams that are already full, teams that are
completely dead, and games the player was never part of. Optionally the team's bed comes back too,
placed exactly where it was when the match started.

Kotlin 2.2.21 · Gradle · group `dev.etran` · Java 8 bytecode (one jar for every server version).

## Commands

| Command | What it does |
|---|---|
| `/revive <team> [bed\|nobed] [arena]` | Join or switch to `<team>` yourself. `bed` also restores the team's bed. |
| `/revive bed [team] [arena]` | Recreate a bed **on its own**, independent of any revival. Defaults to your own team. |
| `/revive set <player> <team> [bed\|nobed] [arena]` | Admin: put another player on a team (works for dead players and outsiders too). |
| `/revive eliminate <player>` | Admin: put a player into the dead-spectator role, as if they died without a bed - even if their team's bed is intact. The bed is left alone. |
| `/revive teams [arena]` | Lists teams, their size and bed state. |
| `/revive reload` | Reloads `config.yml`. |

Alias: `/bwrevive`. `[arena]` is optional when you (or the target) are inside a game, or when only one game
is running. `bed`, `set`, `eliminate` and `teams` also work from the console with an explicit arena.

## Permissions (all default to op)

| Node | Allows |
|---|---|
| `bwrevive.use` | Using `/revive <team>` and `/revive teams` |
| `bwrevive.outsider` | Joining a game you were never part of |
| `bwrevive.switch` | Switching teams while **alive** |
| `bwrevive.bed` | Choosing `bed` on revive, and `/revive bed` for your **own** team's bed (only if it is gone) |
| `bwrevive.admin.bed` | `/revive bed <any team>` at any time - re-places the bed even if it still stands |
| `bwrevive.admin.move` | `/revive set` |
| `bwrevive.admin.eliminate` | `/revive eliminate` |
| `bwrevive.admin` | `/revive reload` |
| `bwrevive.*` | Everything above |

## Behaviour

* **Team size is never checked** - full teams accept the player.
* **Dead teams are joinable.** The bed stays destroyed unless `bed` was chosen.
* **Never in the game?** The player is added through BedWars1058 and then moved onto the team.
* **Dead / spectating?** They are taken out of spectator mode and put on the team.
* **Alive on another team?** They switch, if they have `bwrevive.switch` (admins using `/revive set` always can).
* **Beds are independent of players.** `/revive bed` needs no revival, and clears the team's
  "bed destroyed" state. The bed is rebuilt from a snapshot taken at the start of the match, so it
  returns on the exact blocks, facing and colour it had. If the plugin was installed mid-game, teams
  whose bed was already broken have no snapshot (the command says so).
* **Eliminate** removes the player from their team, makes them a dead spectator and re-checks the
  win condition, so eliminating the last member of a bedless team ends that team like a normal death.

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