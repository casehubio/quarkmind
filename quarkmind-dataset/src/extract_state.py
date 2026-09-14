"""Extract structured game state snapshots from SC2EGSet tracker events.

Mirrors the Java GameStateTranslator.toMap() schema. Reads tracker events
linearly, accumulating unit/building/economy state, and produces a snapshot
at any requested frame.
"""
from dataclasses import dataclass, field
from src.parse_replays import ReplayData

LOOPS_PER_SEC = 22.4

WORKER_TYPES = {"Probe", "SCV", "Drone"}

BUILDING_TYPES = {
    "Nexus", "Pylon", "Assimilator", "Gateway", "Forge", "CyberneticsCore",
    "PhotonCannon", "ShieldBattery", "RoboticsFacility", "Stargate",
    "TwilightCouncil", "RoboticsBay", "FleetBeacon", "TemplarArchive",
    "DarkShrine", "WarpGate",
    "CommandCenter", "SupplyDepot", "Refinery", "Barracks", "Factory",
    "Starport", "EngineeringBay", "Armory", "GhostAcademy", "FusionCore",
    "Bunker", "MissileTurret", "SensorTower", "PlanetaryFortress",
    "OrbitalCommand",
    "Hatchery", "SpawningPool", "Extractor", "EvolutionChamber",
    "RoachWarren", "BanelingNest", "Lair", "HydraliskDen", "LurkerDen",
    "InfestationPit", "Spire", "GreaterSpire", "NydusNetwork", "Hive",
    "UltraliskCavern", "SpineCrawler", "SporeCrawler",
}

NEUTRAL_TYPES = {
    "VespeneGeyser", "RichVespeneGeyser", "ProtossVespeneGeyser",
    "SpacePlatformGeyser", "PurifierVespeneGeyser",
    "MineralField", "MineralField750", "RichMineralField",
    "RichMineralField750", "MineralFieldOpaque",
    "MineralFieldOpaque900", "PurifierMineralField",
    "PurifierMineralField750", "PurifierRichMineralField",
    "PurifierRichMineralField750", "BattleStationMineralField",
    "BattleStationMineralField750", "LabMineralField",
    "LabMineralField750",
    "XelNagaTower", "CollapsibleRockTower",
    "DestructibleDebris6x6", "DestructibleRock6x6",
    "UnbuildableBricksDestructible", "UnbuildablePlatesDestructible",
}


class GameStateExtractor:
    """Accumulates tracker events and produces snapshots at any frame."""

    def __init__(self, replay: ReplayData):
        self._replay = replay
        self._watched_id = replay.player1.player_id
        self._opponent_id = replay.player2.player_id

    def snapshot_at(self, target_frame: int) -> dict:
        """Build a structured game state snapshot up to target_frame."""
        state = _AccumulatorState()

        for ev in self._replay.tracker_events:
            if ev.get("loop", 0) > target_frame:
                break
            _apply_event(ev, state, self._watched_id, self._opponent_id)

        return {
            "game_frame": target_frame,
            "game_time_sec": round(target_frame / LOOPS_PER_SEC, 1),
            "player": {
                "race": self._replay.player1.race,
                "minerals": state.minerals,
                "gas": state.gas,
                "supply_used": state.supply_used,
                "supply_cap": state.supply_cap,
                "worker_count": state.worker_count,
                "army_composition": dict(state.army),
                "tech": list(state.upgrades),
                "buildings": dict(state.buildings),
                "recent_events": state.recent_events[-10:],
            },
            "opponent": {
                "race": self._replay.player2.race,
                "known_units": dict(state.enemy_units),
                "known_buildings": dict(state.enemy_buildings),
            },
        }

    def events_in_range(self, start_frame: int, end_frame: int) -> list[dict]:
        """Return significant game events detected within the frame range."""
        events = []
        for ev in self._replay.tracker_events:
            loop = ev.get("loop", 0)
            if loop < start_frame:
                continue
            if loop > end_frame:
                break
            evt_type = ev.get("evtTypeName", "")
            unit_name = ev.get("unitTypeName", "")
            if evt_type == "UnitBorn":
                ctrl = ev.get("controlPlayerId", 0)
                if ctrl == 0 or unit_name in NEUTRAL_TYPES or unit_name.startswith("Beacon"):
                    continue
                events.append({
                    "frame": loop,
                    "type": "UNIT_BORN",
                    "unit": unit_name,
                    "owner": "player" if ctrl == self._watched_id else "opponent",
                })
            elif evt_type == "UnitDied":
                tag = _make_tag(ev)
                events.append({
                    "frame": loop,
                    "type": "UNIT_DIED",
                    "unit_tag": tag,
                })
            elif evt_type == "UpgradeEvent":
                pid = ev.get("playerId", 0)
                events.append({
                    "frame": loop,
                    "type": "UPGRADE_COMPLETE",
                    "upgrade": ev.get("upgradeTypeName", ""),
                    "owner": "player" if pid == self._watched_id else "opponent",
                })
        return events


@dataclass
class _AccumulatorState:
    minerals: int = 0
    gas: int = 0
    supply_used: int = 0
    supply_cap: int = 0
    worker_count: int = 0
    army: dict = field(default_factory=dict)
    buildings: dict = field(default_factory=dict)
    upgrades: list = field(default_factory=list)
    enemy_units: dict = field(default_factory=dict)
    enemy_buildings: dict = field(default_factory=dict)
    recent_events: list = field(default_factory=list)
    alive_units: dict = field(default_factory=dict)


def _make_tag(ev: dict) -> str:
    return f"{ev.get('unitTagIndex', 0)}-{ev.get('unitTagRecycle', 0)}"


def _apply_event(ev: dict, state: _AccumulatorState, watched: int, opponent: int):
    evt_type = ev.get("evtTypeName", "")

    if evt_type == "PlayerStats":
        pid = ev.get("playerId", 0)
        stats = ev.get("stats", {})
        if pid == watched:
            state.minerals = stats.get("scoreValueMineralsCurrent", 0)
            state.gas = stats.get("scoreValueVespeneCurrent", 0)
            state.supply_used = stats.get("scoreValueFoodUsed", 0)
            state.supply_cap = stats.get("scoreValueFoodMade", 0)
            state.worker_count = stats.get("scoreValueWorkersActiveCount", 0)

    elif evt_type == "UnitBorn":
        unit_name = ev.get("unitTypeName", "")
        ctrl = ev.get("controlPlayerId", 0)
        tag = _make_tag(ev)
        if ctrl == 0 or unit_name in NEUTRAL_TYPES or unit_name.startswith("Beacon"):
            return
        if ctrl == watched:
            if unit_name in BUILDING_TYPES:
                state.buildings[unit_name] = state.buildings.get(unit_name, 0) + 1
            elif unit_name not in WORKER_TYPES:
                state.army[unit_name] = state.army.get(unit_name, 0) + 1
            state.alive_units[tag] = (unit_name, "player")
        elif ctrl == opponent:
            if unit_name in BUILDING_TYPES:
                state.enemy_buildings[unit_name] = state.enemy_buildings.get(unit_name, 0) + 1
            else:
                state.enemy_units[unit_name] = state.enemy_units.get(unit_name, 0) + 1
            state.alive_units[tag] = (unit_name, "opponent")
        state.recent_events.append({
            "frame": ev.get("loop", 0),
            "type": "UNIT_BORN",
            "unit": unit_name,
        })

    elif evt_type == "UnitDied":
        tag = _make_tag(ev)
        if tag in state.alive_units:
            unit_name, owner = state.alive_units.pop(tag)
            if owner == "player":
                if unit_name in BUILDING_TYPES:
                    state.buildings[unit_name] = max(0, state.buildings.get(unit_name, 0) - 1)
                elif unit_name not in WORKER_TYPES:
                    state.army[unit_name] = max(0, state.army.get(unit_name, 0) - 1)
        state.recent_events.append({
            "frame": ev.get("loop", 0),
            "type": "UNIT_DIED",
        })

    elif evt_type == "UpgradeEvent":
        pid = ev.get("playerId", 0)
        upgrade = ev.get("upgradeTypeName", "")
        if pid == watched:
            state.upgrades.append(upgrade)
        state.recent_events.append({
            "frame": ev.get("loop", 0),
            "type": "UPGRADE_COMPLETE",
            "upgrade": upgrade,
        })
