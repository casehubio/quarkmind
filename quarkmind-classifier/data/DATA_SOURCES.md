# SC2 Replay Data Sources

## Active Sources (data acquired)

### SC2EGSet (71 tournaments)
- **Location:** `data/sc2egset/` (75 tournament dirs after Spawning Tool additions)
- **Format:** Pre-processed JSON inside nested ZIPs
- **Pipeline:** `prepare_real_data.py`
- **Size:** ~17,930 replays
- **Acquired:** 2026-09-23 (issue #306)

### Spawning Tool Packs (4 of 6 tournaments)
- **Location:** `data/replay_packs/` (3 ZIPs + 1 folder)
- **Downloaded:** 2025_DreamHack_Dallas (64), 2025_Esports_World_Cup (113), 2025_FEL_Cracow (77), 2025_HomeStory_Cup_XXVII (61)
- **Not downloaded (Google Drive folder access issues):** 2026_HomeStory_Cup_XXVIII, 2026_HomeStory_Cup_XXIX
- **Format:** Raw .SC2Replay files in ZIPs or folders
- **Pipeline:** `prepare_replay_pack.py`
- **Acquired:** 2026-09-25 (issue #312)

### AI Arena Bot Replays (29 PvP)
- **Location:** `quarkmind/replays/aiarena_protoss/`
- **Format:** Raw .SC2Replay files
- **Used by:** Java calibration tests only (not in Python training pipeline)

## Pending Sources (APIs documented, not yet accessed)

### Blizzard Ladder Packs (HIGHEST PRIORITY)
- **What:** Hundreds of thousands of anonymous 1v1 ladder replays per game version
- **API:** `download_replays.py` from [s2client-proto](https://github.com/Blizzard/s2client-proto/tree/master/samples/replay-api)
- **Auth:** Blizzard Developer Portal account — register at [dev.battle.net](https://dev.battle.net)
- **Credentials:** Client key + secret from "My Account" page
- **Command:** `python download_replays.py --key=<key> --secret=<secret> --version=<version> --replays_dir=<dir> --extract`
- **EULA:** Files password-protected with `iagreetotheeula`
- **Version list:** `s2client-proto/buildinfo/versions.json`
- **Pipeline:** `prepare_replay_pack.py` (same as Spawning Tool — raw .SC2Replay)
- **Why:** Ladder games have far more cheese/rush/all-in strategies than tournament data

### SC2ReplayStats (4.4M replays in 2020)
- **What:** Community-uploaded ladder replays with stats
- **API:** [api.sc2replaystats.com/docs](https://api.sc2replaystats.com/docs/index.html)
- **Auth:** Account + API key (Elite membership for filtered search)
- **Bulk tool:** [sc2-rsu](https://github.com/AlbinoGeek/sc2-rsu)
- **Pipeline:** `prepare_replay_pack.py`
- **Why:** Massive volume, filterable by matchup and game length

### AI Arena Data API (bulk bot replays)
- **What:** Bot vs bot replays from the SC2 AI ladder
- **API:** `https://aiarena.net/api/match-participations/?bot=<BOT_ID>`
- **Auth:** Token from [aiarena.net/profile/token/](https://aiarena.net/profile/token/)
- **Example:** See `aiarena.net/wiki/data-api/` for Python bulk download script
- **Pipeline:** `prepare_replay_pack.py`
- **Why:** Bots play unconventional strategies; good for rare archetypes

## Data Retention Policy

**Keep all copies of all datasets.** Never delete raw replay files or extracted NPZ data.
Source data in `data/replay_packs/` and `data/sc2egset/raw/` is irreplaceable.
Processed data in `data/sc2egset/<tournament>/` and `data/combined/` can be regenerated.
