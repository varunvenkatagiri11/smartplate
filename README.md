# SmartPlate

Real-time personalized dining recommendation engine for UGA. Ranks meals by user history, nutrition profile, collaborative filtering, and live popularity signals across 5 dining halls.

---

## Architecture

```
Nutrislice API (UGA dining data)
        ↓ daily sync
   PostgreSQL ←──────────────────────────────┐
        ↓                                     │
  Spring Boot API (port 8080)                 │
        ↓                                     │
   Kafka topic: smartplate.events             │
        ↓                                     │
  ┌─────┴──────────────────────┐              │
  ↓           ↓                ↓              │
Trending   CoOccurrence    Session            │
Consumer   Consumer        Consumer           │
  ↓           ↓                ↓              │
  └─────┬──────────────────────┘              │
        ↓                                     │
      Redis (sorted sets, lists, cache)       │
        ↓                                     │
  Recommendation endpoints                    │
        ↓                                     │
  FastAPI ML service (LightGBM re-ranking)    │
        ↓                                     │
  Next.js frontend (port 3000) ───────────────┘
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| API | Spring Boot 4.0 (Java 21) |
| Auth | JWT + BCrypt |
| Database | PostgreSQL 16 |
| Cache / Feature Store | Redis 7 |
| Event Streaming | Kafka (Confluent 7.6) |
| ML Inference | FastAPI + LightGBM |
| Frontend | Next.js 15, TypeScript, Tailwind, shadcn/ui |
| Data Pipeline | Python 3.12, psycopg2 |
| Containerization | Docker Compose (8 services) |
| Cloud | AWS (ECS, RDS, ElastiCache, MSK) |

---

## How the Recommendation Pipeline Works

**1. Event Ingestion**
Every user interaction (view, click, rate, favorite) hits `POST /api/v1/events`. Spring Boot writes it to Postgres and publishes to the Kafka topic `smartplate.events`.

**2. Stream Processing**
Three independent Kafka consumer groups read from the topic in parallel:
- **TrendingConsumer** — computes time-decayed scores: `score = weight × e^(-λ × minutes_ago)` with λ=0.005 (half-life ~2.3 hours). Writes to Redis sorted set `trending:{hallId}:{daypart}`.
- **CoOccurrenceConsumer** — builds item-item graph. When a user engages with item B, increments `cooccur:{itemA}` for every item A in their session. Capped at top-500 neighbors.
- **SessionConsumer** — maintains `session:{userId}` as a list of the last 20 item IDs the user touched.

**3. Candidate Generation (Spring Boot)**
`GET /api/v1/recommendations/foryou`:
1. Pull user's session from Redis
2. For each liked item, fetch top co-occurrence neighbors → candidate pool
3. Filter to today's available items at the requested hall
4. Apply dietary hard filters
5. Score: co-occurrence (50%) + nutrition cosine similarity (30%) + trending (20%)

**4. ML Re-ranking (FastAPI)**
Candidates are sent to the LightGBM service at `POST /rerank`. The model re-orders them using 14 learned features including nutrition deltas, dietary match booleans, behavioral signals, and upstream rank. Falls back to algorithmic ranking if the ML service is unavailable.

**5. Cold Start**
Users with fewer than 3 interactions fall back to the trending feed automatically.

---

## Performance

| Metric | Value |
|---|---|
| p95 latency (1,000 concurrent requests) | 45ms |
| Median latency | 22ms |
| Kafka consumer lag | 0 (real-time) |
| LightGBM NDCG@5 | 0.877 |
| LightGBM vs baseline | +50% Precision@10 |

---

## Running Locally

**Prerequisites:** Docker Desktop, Java 21, Node.js 20, Python 3.12

```bash
git clone https://github.com/varunvenkatagiri/smartplate
cd smartplate

# Start full stack
docker compose up -d

# Frontend runs on localhost:3000
# API runs on localhost:8080
# ML service runs on localhost:8001
```

**Run the Nutrislice sync manually:**
```bash
docker exec smartplate-sync-1 python nutrislice_sync.py
```

**Train the LightGBM model:**
```bash
docker exec smartplate-ml-1 python train.py \
  --db postgresql://smartplate:smartplate@postgres:5432/smartplate
docker compose restart ml
```

**Run evaluation:**
```bash
pip install requests numpy psycopg2-binary
python3 scripts/evaluate.py
```

---

## Project Structure

```
smartplate/
  src/                          Spring Boot API
    main/java/com/smartplate/
      auth/                     JWT filter + utilities
      config/                   Security, Kafka, Redis config
      controller/               REST endpoints
      consumer/                 Kafka consumers
      service/                  Business logic
  frontend/                     Next.js app
  ml/                           FastAPI + LightGBM service
    main.py                     Inference endpoint
    train.py                    Model training
    features.py                 Feature engineering
  scripts/
    nutrislice_sync.py          Daily menu sync
    evaluate.py                 Evaluation suite
  docker-compose.yml
  Dockerfile.api
  Dockerfile.sync
```

---

## Data Source

Live menu data from [UGA Dining's Nutrislice API](https://uga.nutrislice.com) — synced daily at 6am across 5 dining halls (Bolton, O-House, Snelling, Niche, Village Summit) for both breakfast and lunch/dinner periods.
