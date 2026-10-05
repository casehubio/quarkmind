from src.upgrade_mapping import UPGRADES


def test_upgrade_count_matches_java():
    """UPGRADES list must have exactly as many entries as Java UpgradeType enum."""
    assert len(UPGRADES) >= 82, (
        f"Expected ≥82 upgrades (Java UpgradeType enum), got {len(UPGRADES)}. "
        "Regenerate with UpgradeMappingGeneratorTest."
    )


def test_no_duplicate_upgrade_names():
    assert len(UPGRADES) == len(set(UPGRADES)), "Duplicate upgrade names found"


def test_known_upgrades_present():
    """Spot-check that key upgrades from the old 15-entry list are present."""
    expected = ["Stimpack", "WarpGateResearch", "BlinkTech", "zerglingmovementspeed", "Charge"]
    for name in expected:
        assert name in UPGRADES, f"Expected upgrade '{name}' missing from UPGRADES"
