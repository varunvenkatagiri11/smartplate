"""
FastAPI reranking service.

POST /rerank
  Body: { candidates: [...], user_profile: {...} }
  Returns: same list reordered by LightGBM score, highest first.

GET /health  →  { "status": "ok" }

If no model.pkl exists (first boot before training), returns candidates unchanged.
"""

import os
import pickle
import logging
from pathlib import Path

import numpy as np
from fastapi import FastAPI
from pydantic import BaseModel, Field

from features import build_features, FEATURE_COLS

logger = logging.getLogger("reranker")
logging.basicConfig(level=logging.INFO)

MODEL_PATH = Path(os.getenv("MODEL_PATH", "model.pkl"))

app = FastAPI(title="SmartPlate Reranker", version="1.0.0")

_model = None

def get_model():
    global _model
    if _model is None and MODEL_PATH.exists():
        with open(MODEL_PATH, "rb") as f:
            _model = pickle.load(f)
        logger.info("Loaded reranker model from %s", MODEL_PATH)
    return _model


# ── Request / Response schemas ────────────────────────────────────────────────

class Candidate(BaseModel):
    id:                 int
    name:               str
    calories:           float | None = None
    g_protein:          float | None = None
    g_carbs:            float | None = None
    g_fat:              float | None = None
    g_fiber:            float | None = None
    is_vegan:           bool = False
    is_meatless:        bool = False
    is_halal:           bool = False
    is_gluten_friendly: bool = False
    cooccur_score:           float = 0.0
    trending_score:          float = 0.0
    upstream_rank:           int = 0
    item_global_avg_rating:  float = 0.0
    # Pass through any extra fields Spring Boot sends
    model_config = {"extra": "allow"}


class UserProfile(BaseModel):
    pref_vegan:      bool = False
    pref_meatless:   bool = False
    pref_halal:      bool = False
    pref_gluten_free: bool = False
    avg_calories:    float | None = None
    avg_protein:     float | None = None
    avg_carbs:       float | None = None
    avg_fat:         float | None = None


class RerankRequest(BaseModel):
    candidates:   list[Candidate]
    user_profile: UserProfile = Field(default_factory=UserProfile)


class RerankResponse(BaseModel):
    items: list[dict]


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok", "model_loaded": get_model() is not None}


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    candidates = [c.model_dump() for c in req.candidates]
    model = get_model()

    if not candidates:
        return RerankResponse(items=[])

    if model is None:
        logger.warning("No model found at %s — returning candidates unchanged", MODEL_PATH)
        return RerankResponse(items=candidates)

    user_profile = req.user_profile.model_dump()
    df = build_features(candidates, user_profile)

    X = df[FEATURE_COLS].values
    scores = model.predict(X)

    # Attach scores and sort descending
    for i, candidate in enumerate(candidates):
        candidate["rerank_score"] = float(scores[i])

    reranked = sorted(candidates, key=lambda c: c["rerank_score"], reverse=True)
    return RerankResponse(items=reranked)
