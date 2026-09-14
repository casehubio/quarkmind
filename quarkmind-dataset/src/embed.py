"""Sentence embedding generation for build-time RAG curation."""
import hashlib

MOCK_DIM = 32


def embed_text(text: str, model_name: str = "all-MiniLM-L6-v2") -> list[float]:
    """Embed a single text string."""
    if model_name == "mock":
        return _mock_embed(text)
    from sentence_transformers import SentenceTransformer
    model = _get_model(model_name)
    return model.encode(text).tolist()


def embed_examples(
    examples: list[dict],
    model_name: str = "all-MiniLM-L6-v2",
) -> list[list[float]]:
    """Embed a batch of training examples."""
    texts = [_example_to_text(ex) for ex in examples]
    if model_name == "mock":
        return [_mock_embed(t) for t in texts]
    from sentence_transformers import SentenceTransformer
    model = _get_model(model_name)
    return model.encode(texts).tolist()


def _example_to_text(example: dict) -> str:
    """Convert a training example to embedding text."""
    commentary = example.get("commentary", "")
    state = example.get("game_state", {})
    player = state.get("player", {})
    parts = [commentary]
    if player.get("race"):
        parts.append(player["race"])
    if player.get("army_composition"):
        parts.extend(player["army_composition"].keys())
    return " ".join(parts)


_model_cache = {}


def _get_model(name: str):
    if name not in _model_cache:
        from sentence_transformers import SentenceTransformer
        _model_cache[name] = SentenceTransformer(name)
    return _model_cache[name]


def _mock_embed(text: str) -> list[float]:
    """Deterministic mock embedding for testing."""
    h = hashlib.sha256(text.encode()).hexdigest()
    return [int(h[i:i + 2], 16) / 255.0 for i in range(0, MOCK_DIM * 2, 2)][:MOCK_DIM]
