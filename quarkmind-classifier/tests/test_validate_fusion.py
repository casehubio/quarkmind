"""Tests for fusion validation functions."""
import numpy as np
import pytest

from src.validate_fusion import (
    build_cpts_from_manifest,
    compute_priors,
    naive_bayes_posterior,
    softmax,
)


def test_softmax_sums_to_one():
    x = np.array([[1.0, 2.0, 3.0]])
    result = softmax(x)
    assert abs(result.sum() - 1.0) < 1e-6


def test_softmax_correct_ordering():
    x = np.array([[1.0, 3.0, 2.0]])
    result = softmax(x)
    assert result[0, 1] > result[0, 2] > result[0, 0]


def test_naive_bayes_posterior_strong_rush_evidence():
    priors = {"RUSH": 0.3, "MACRO": 0.5, "TECH": 0.2}
    cpts = {
        "rush_det": {"RUSH": 0.9, "MACRO": 0.1, "TECH": 0.3},
        "eco_det": {"RUSH": 0.1, "MACRO": 0.9, "TECH": 0.3},
    }
    evidence = {"rush_det": 0.9, "eco_det": 0.1}
    posterior = naive_bayes_posterior(priors, cpts, evidence,
                                     ["RUSH", "MACRO", "TECH"])
    assert max(posterior, key=posterior.get) == "RUSH"
    assert posterior["RUSH"] > 0.5


def test_naive_bayes_posterior_strong_macro_evidence():
    priors = {"RUSH": 0.3, "MACRO": 0.5, "TECH": 0.2}
    cpts = {
        "rush_det": {"RUSH": 0.9, "MACRO": 0.1, "TECH": 0.3},
        "eco_det": {"RUSH": 0.1, "MACRO": 0.9, "TECH": 0.3},
    }
    evidence = {"rush_det": 0.1, "eco_det": 0.9}
    posterior = naive_bayes_posterior(priors, cpts, evidence,
                                     ["RUSH", "MACRO", "TECH"])
    assert max(posterior, key=posterior.get) == "MACRO"


def test_naive_bayes_posterior_sums_to_one():
    priors = {"A": 0.5, "B": 0.5}
    cpts = {"d1": {"A": 0.8, "B": 0.2}}
    evidence = {"d1": 0.7}
    posterior = naive_bayes_posterior(priors, cpts, evidence, ["A", "B"])
    assert abs(sum(posterior.values()) - 1.0) < 1e-6


def test_naive_bayes_uniform_evidence_preserves_prior():
    priors = {"A": 0.7, "B": 0.3}
    cpts = {"d1": {"A": 0.5, "B": 0.5}}
    evidence = {"d1": 0.5}
    posterior = naive_bayes_posterior(priors, cpts, evidence, ["A", "B"])
    assert abs(posterior["A"] - 0.7) < 0.05


def test_compute_priors():
    labels = np.array([0, 0, 0, 1, 2])
    classes = ["RUSH", "MACRO", "TECH"]
    priors = compute_priors(labels, classes)
    assert abs(priors["RUSH"] - 0.6) < 0.01
    assert abs(priors["MACRO"] - 0.2) < 0.01
    assert abs(priors["TECH"] - 0.2) < 0.01


def test_build_cpts_from_manifest():
    manifest = {
        "early_rush_vs_terran": {
            "key": "early_rush_vs_terran",
            "matchup": "vs_terran",
            "recall": 0.85,
            "fpr": 0.10,
            "positive_archetypes": ["RUSH"],
            "skipped": False,
        }
    }
    classes = ["RUSH", "MACRO", "TECH"]
    cpts = build_cpts_from_manifest(manifest, "vs_terran", classes)
    assert "early_rush_vs_terran" in cpts
    cpt = cpts["early_rush_vs_terran"]
    assert abs(cpt["RUSH"] - 0.85) < 0.01
    assert abs(cpt["MACRO"] - 0.10) < 0.01
    assert abs(cpt["TECH"] - 0.10) < 0.01


def test_build_cpts_skips_wrong_matchup():
    manifest = {
        "early_rush_vs_zerg": {
            "key": "early_rush_vs_zerg",
            "matchup": "vs_zerg",
            "recall": 0.85,
            "fpr": 0.10,
            "positive_archetypes": ["RUSH"],
            "skipped": False,
        }
    }
    cpts = build_cpts_from_manifest(manifest, "vs_terran", ["RUSH"])
    assert len(cpts) == 0


def test_naive_bayes_multiple_detectors_complementary():
    """When detectors provide complementary info, fusion should be
    more confident than either individual detector."""
    priors = {"RUSH": 0.3, "MACRO": 0.7}
    cpts = {
        "rush_det": {"RUSH": 0.8, "MACRO": 0.2},
        "eco_det": {"RUSH": 0.15, "MACRO": 0.85},
    }
    # Both detectors agree: rush is happening
    evidence = {"rush_det": 0.8, "eco_det": 0.15}
    posterior = naive_bayes_posterior(priors, cpts, evidence,
                                     ["RUSH", "MACRO"])

    # Single detector only
    single = naive_bayes_posterior(priors,
                                   {"rush_det": cpts["rush_det"]},
                                   {"rush_det": 0.8},
                                   ["RUSH", "MACRO"])
    assert posterior["RUSH"] > single["RUSH"], \
        "Complementary evidence should increase confidence"
