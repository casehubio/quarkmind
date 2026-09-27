import numpy as np
import pytest
from src.fog_of_war import generate_scouting_mask


class TestScoutingMask:
    def test_pre_scout_is_hidden(self):
        rng = np.random.default_rng(42)
        mask = generate_scouting_mask(total_seconds=300, rng=rng)
        assert np.all(mask[:90] == 0)

    def test_post_scout_has_visibility(self):
        rng = np.random.default_rng(42)
        mask = generate_scouting_mask(total_seconds=300, rng=rng)
        assert mask[180:].mean() > 0.3

    def test_visibility_is_cumulative(self):
        rng = np.random.default_rng(42)
        mask = generate_scouting_mask(total_seconds=300, rng=rng)
        # Once visibility appears, it never drops back to zero
        first_nonzero = np.argmax(mask > 0)
        assert np.all(mask[first_nonzero:] > 0)

    def test_visibility_increases_over_time(self):
        rng = np.random.default_rng(42)
        mask = generate_scouting_mask(total_seconds=300, rng=rng)
        mid = mask[150]
        late = mask[280]
        assert late >= mid

    def test_mask_length_matches_seconds(self):
        rng = np.random.default_rng(42)
        mask = generate_scouting_mask(total_seconds=240, rng=rng)
        assert len(mask) == 240

    def test_stochastic_variation(self):
        mask1 = generate_scouting_mask(300, np.random.default_rng(1))
        mask2 = generate_scouting_mask(300, np.random.default_rng(2))
        assert not np.array_equal(mask1, mask2)

    def test_values_in_zero_one_range(self):
        rng = np.random.default_rng(42)
        mask = generate_scouting_mask(total_seconds=300, rng=rng)
        assert np.all(mask >= 0.0)
        assert np.all(mask <= 1.0)
