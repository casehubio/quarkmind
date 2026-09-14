from src.embed import embed_text, embed_examples


def test_embed_text_returns_vector():
    vector = embed_text("Stalker push with Blink", model_name="mock")
    assert len(vector) > 0
    assert isinstance(vector[0], float)


def test_embed_text_deterministic():
    v1 = embed_text("same text", model_name="mock")
    v2 = embed_text("same text", model_name="mock")
    assert v1 == v2


def test_embed_text_different_inputs():
    v1 = embed_text("Stalker push", model_name="mock")
    v2 = embed_text("Marine drop", model_name="mock")
    assert v1 != v2


def test_embed_examples_batch():
    examples = [
        {"commentary": "big push", "game_state": {"player": {"race": "Protoss"}}},
        {"commentary": "expanding", "game_state": {"player": {"race": "Terran"}}},
    ]
    vectors = embed_examples(examples, model_name="mock")
    assert len(vectors) == 2
    assert len(vectors[0]) > 0
