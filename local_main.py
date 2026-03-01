#!/usr/bin/env python3
"""
GAC Cloud Server - Polar-Style Machine Learning Backend

Philosophy:
- NO bans from ML - only confidence scores and mitigation recommendations
- Subtle, asymmetric mitigations that degrade cheat effectiveness
- Cross-server learning with anonymized data
- Time-series analysis (Isolation Forest + pattern detection)
"""

import os
import time
import json
import hashlib
import hmac
from datetime import datetime
from typing import Dict, List, Optional, Any
from collections import defaultdict

import numpy as np
from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.responses import FileResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler

# ==================== CONFIG ====================

API_VERSION = "2.0.0"
DEFAULT_TRUST_SCORE = 50.0
MIN_SAMPLES_FOR_ANALYSIS = 12  # ~1 minute of data

# Mitigation thresholds
ANOMALY_THRESHOLD_LOW = 0.3
ANOMALY_THRESHOLD_MEDIUM = 0.5
ANOMALY_THRESHOLD_HIGH = 0.7

# ==================== APP ====================

app = FastAPI(
    title="GAC Cloud",
    description="Polar-Style Anticheat ML Backend",
    version=API_VERSION
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ==================== IN-MEMORY STORAGE ====================
# In production, use Redis or a database

player_data: Dict[str, Dict] = {}  # player_hash -> data
player_samples: Dict[str, List[Dict]] = defaultdict(list)  # player_hash -> samples
server_stats: Dict[str, Dict] = {}  # server_id -> stats
global_stats = {
    "total_players": 0,
    "total_servers": 0,
    "total_samples": 0,
    "total_violations": 0,
}

# ML Models (one per feature set)
models: Dict[str, IsolationForest] = {}
scalers: Dict[str, StandardScaler] = {}

# ==================== MODELS ====================

class BehaviorSample(BaseModel):
    timestamp: int
    sessionDuration: int = 0

    # Combat
    avgHitDistance: float = 0
    maxHitDistance: float = 0
    hitDistanceVariance: float = 0
    hitCount: int = 0
    avgHitTiming: float = 0
    hitTimingVariance: float = 0
    cps: int = 0
    cpsVariance: float = 0

    # Movement
    avgSpeed: float = 0
    maxSpeed: float = 0
    speedVariance: float = 0
    avgDeltaY: float = 0
    maxDeltaY: float = 0
    airTicks: int = 0
    groundTicks: int = 0
    airGroundRatio: float = 0

    # Rotation
    avgYawChange: float = 0
    avgPitchChange: float = 0
    yawVariance: float = 0
    pitchVariance: float = 0
    rotationSmoothness: float = 0
    snapCount: int = 0

    # Velocity
    avgKnockbackTaken: float = 0
    knockbackVariance: float = 0
    knockbackComplianceRate: float = 1.0

    # Packets
    pps: int = 0
    packetTimingVariance: float = 0
    duplicatePackets: int = 0
    invalidPackets: int = 0

    # Blocks
    blocksPlaced: int = 0
    avgPlaceSpeed: float = 0
    placeAngleVariance: float = 0
    impossiblePlacements: int = 0

    # Context
    ping: int = 0
    serverTps: float = 20.0
    sprinting: bool = False
    sneaking: bool = False
    flying: bool = False
    gliding: bool = False
    inWater: bool = False
    inLava: bool = False
    gamemode: str = "SURVIVAL"

    # Violations
    localViolations: int = 0
    violationTypes: str = ""

    # Sequences (optional)
    speedSequence: List[float] = []
    verticalSequence: List[float] = []
    yawSequence: List[float] = []
    pitchSequence: List[float] = []


class BehaviorUpload(BaseModel):
    player_hash: str
    server_id: str
    sample_count: int
    timestamp: int
    samples: List[BehaviorSample]


class ViolationReport(BaseModel):
    player_hash: str
    server_id: str
    check_name: str
    severity: float
    details: str
    timestamp: int


class TrainingLabel(BaseModel):
    player_hash: str
    server_id: str
    playtime_minutes: int = 0
    label: str  # "legit" or "cheat"
    timestamp: int


class MitigationResponse(BaseModel):
    trust_score: float = 50.0
    anomaly_score: float = 0.0
    temporal_confidence: float = 0.0
    pattern_match_score: float = 0.0
    reason: str = "unknown"

    # Mitigations
    mitigations: Dict[str, float] = {}

    # Category mitigations
    combat_mitigation: float = 0.0
    movement_mitigation: float = 0.0
    building_mitigation: float = 0.0

    # Flags
    watch_closely: bool = False
    has_enough_data: bool = False
    known_cheater: bool = False
    cross_server_violations: int = 0

    # Cloud Kick (only when very confident)
    should_kick: bool = False
    kick_reason: str = ""


# ==================== HELPER FUNCTIONS ====================

def get_or_create_player(player_hash: str) -> Dict:
    """Get or create player data entry"""
    if player_hash not in player_data:
        player_data[player_hash] = {
            "trust_score": DEFAULT_TRUST_SCORE,
            "total_violations": 0,
            "total_samples": 0,
            "first_seen": time.time(),
            "last_seen": time.time(),
            "servers_seen": set(),
            "known_cheater": False,
            "labels": [],  # "legit" or "cheat" labels from servers
        }
        global_stats["total_players"] += 1
    return player_data[player_hash]


def extract_features(sample: BehaviorSample) -> np.ndarray:
    """Extract feature vector from a sample"""
    return np.array([
        sample.avgSpeed,
        sample.maxSpeed,
        sample.speedVariance,
        sample.avgDeltaY,
        sample.airGroundRatio,
        sample.avgYawChange,
        sample.avgPitchChange,
        sample.yawVariance,
        sample.pitchVariance,
        sample.snapCount,
        sample.cps,
        sample.cpsVariance,
        sample.avgHitDistance,
        sample.hitDistanceVariance,
        sample.avgHitTiming,
        sample.hitTimingVariance,
        sample.knockbackComplianceRate,
        sample.pps,
        sample.packetTimingVariance,
        sample.blocksPlaced,
        sample.impossiblePlacements,
    ])


def analyze_samples(samples: List[BehaviorSample]) -> Dict[str, float]:
    """Analyze behavior samples and return scores"""
    if len(samples) < MIN_SAMPLES_FOR_ANALYSIS:
        return {
            "anomaly_score": 0.0,
            "temporal_confidence": 0.0,
            "pattern_match_score": 0.0,
            "has_enough_data": False,
            "reason": "insufficient_data"
        }

    # Extract features from all samples
    features = np.array([extract_features(s) for s in samples])

    # === ISOLATION FOREST (Anomaly Detection) ===
    anomaly_score = 0.0
    try:
        if "main" not in models:
            models["main"] = IsolationForest(
                n_estimators=100,
                contamination=0.1,
                random_state=42
            )
            scalers["main"] = StandardScaler()

        # Fit scaler if needed
        if not hasattr(scalers["main"], 'mean_') or scalers["main"].mean_ is None:
            scalers["main"].fit(features)

        scaled = scalers["main"].transform(features)

        # Fit model if needed
        if not hasattr(models["main"], 'offset_') or models["main"].offset_ is None:
            models["main"].fit(scaled)

        # Get anomaly scores (-1 = anomaly, 1 = normal)
        raw_scores = models["main"].decision_function(scaled)
        # Convert to 0-1 where 1 = anomaly
        anomaly_score = float(np.clip(1 - (raw_scores.mean() + 0.5), 0, 1))
    except Exception as e:
        print(f"Anomaly detection error: {e}")

    # === TEMPORAL ANALYSIS (Pattern Detection) ===
    temporal_confidence = 0.0
    pattern_reasons = []

    # === SPEED DETECTION ===
    speeds = [s.avgSpeed for s in samples]
    max_speeds = [s.maxSpeed for s in samples]
    if len(speeds) > 3:
        avg_speed = np.mean(speeds)
        max_speed = np.max(max_speeds)
        speed_std = np.std(speeds)

        # High consistent speed = Speed hack
        if avg_speed > 0.35:
            temporal_confidence += 0.4
            pattern_reasons.append("high_speed")
        elif avg_speed > 0.28 and speed_std < 0.05:
            temporal_confidence += 0.3
            pattern_reasons.append("constant_speed")

        # Max speed too high
        if max_speed > 0.5:
            temporal_confidence += 0.3
            pattern_reasons.append("speed_spike")

    # === FLY DETECTION ===
    non_flight = [s for s in samples if not s.flying and not s.gliding]
    if len(non_flight) > 3:
        avg_air = np.mean([s.airTicks for s in non_flight])
        avg_ground = np.mean([s.groundTicks for s in non_flight])
        air_ratios = [s.airGroundRatio for s in non_flight]
        avg_ratio = np.mean(air_ratios) if air_ratios else 0

        # Too much air time = Fly
        if avg_air > 30:
            temporal_confidence += 0.5
            pattern_reasons.append("fly_detected")
        elif avg_air > 15 and avg_ratio > 0.8:
            temporal_confidence += 0.3
            pattern_reasons.append("excessive_air_time")

    # === KILLAURA DETECTION ===
    cps_values = [s.cps for s in samples]
    if len(cps_values) > 3:
        cps_mean = np.mean(cps_values)
        cps_std = np.std(cps_values)

        # High CPS
        if cps_mean > 15:
            temporal_confidence += 0.4
            pattern_reasons.append("high_cps")
        elif cps_mean > 10 and cps_std < 1.0:
            temporal_confidence += 0.3
            pattern_reasons.append("consistent_cps")

    # Low rotation variance (aimbot)
    yaw_vars = [s.yawVariance for s in samples]
    pitch_vars = [s.pitchVariance for s in samples]
    snap_counts = [s.snapCount for s in samples]
    if len(yaw_vars) > 3:
        avg_yaw_var = np.mean(yaw_vars)
        avg_pitch_var = np.mean(pitch_vars)
        total_snaps = sum(snap_counts)

        if avg_yaw_var < 2.0 and avg_pitch_var < 1.0:
            temporal_confidence += 0.3
            pattern_reasons.append("low_rotation_variance")

        if total_snaps > 10:
            temporal_confidence += 0.2
            pattern_reasons.append("rotation_snaps")

    # === VELOCITY DETECTION ===
    kb_rates = [s.knockbackComplianceRate for s in samples if s.knockbackComplianceRate > 0]
    if len(kb_rates) > 2:
        avg_kb = np.mean(kb_rates)
        if avg_kb < 0.3:
            temporal_confidence += 0.5
            pattern_reasons.append("velocity_detected")
        elif avg_kb < 0.6:
            temporal_confidence += 0.3
            pattern_reasons.append("low_knockback")

    # === SCAFFOLD DETECTION ===
    impossible = sum(s.impossiblePlacements for s in samples)
    blocks_placed = sum(s.blocksPlaced for s in samples)
    if impossible > 3:
        temporal_confidence += 0.4
        pattern_reasons.append("scaffold_detected")
    elif blocks_placed > 50 and impossible > 0:
        temporal_confidence += 0.2
        pattern_reasons.append("suspicious_building")

    temporal_confidence = min(1.0, temporal_confidence)

    # === PATTERN MATCH (Known Cheat Signatures) ===
    pattern_match_score = 0.0

    # High CPS + Low pitch variance = Killaura
    high_cps_samples = [s for s in samples if s.cps > 15]
    if len(high_cps_samples) > 3:
        avg_pitch_var = np.mean([s.pitchVariance for s in high_cps_samples])
        if avg_pitch_var < 2.0:
            pattern_match_score += 0.5
            pattern_reasons.append("killaura_signature")

    # Speed > 0.5 consistently = Speed hack
    fast_samples = [s for s in samples if s.maxSpeed > 0.5 and not s.flying]
    if len(fast_samples) > len(samples) * 0.3:
        pattern_match_score += 0.4
        pattern_reasons.append("speed_hack_signature")

    pattern_match_score = min(1.0, pattern_match_score)

    # Determine main reason
    reason = "normal"
    if pattern_reasons:
        reason = pattern_reasons[0]
    elif anomaly_score > ANOMALY_THRESHOLD_MEDIUM:
        reason = "anomalous_behavior"

    return {
        "anomaly_score": anomaly_score,
        "temporal_confidence": temporal_confidence,
        "pattern_match_score": pattern_match_score,
        "has_enough_data": True,
        "reason": reason
    }


def calculate_mitigations(analysis: Dict, player: Dict) -> Dict[str, float]:
    """Calculate mitigation recommendations based on ML analysis ONLY"""
    mitigations = {
        "reach_reduction": 0.0,
        "hit_reg_loss": 0.0,
        "block_fail_rate": 0.0,
        "speed_reduction": 0.0,
        "knockback_amplify": 0.0,
        "timing_jitter": 0.0,
        "ghost_block_rate": 0.0,
    }

    # Must have enough data for ML analysis
    if not analysis.get("has_enough_data", False):
        return mitigations

    # Combined suspicion score - ONLY from ML analysis
    suspicion = (
        analysis.get("anomaly_score", 0) * 0.3 +
        analysis.get("temporal_confidence", 0) * 0.5 +
        analysis.get("pattern_match_score", 0) * 0.2
    )

    # Only apply mitigations if ML is VERY confident (80%+)
    if suspicion < 0.8:
        return mitigations

    # Scale mitigations based on suspicion level
    reason = analysis.get("reason", "")

    # Scale factor based on how far above threshold (0.8)
    # At 0.8 = 0%, at 1.0 = 100%
    scale = min(1.0, (suspicion - 0.8) / 0.2)

    # Very light mitigations - player should still be able to play
    # Max 5-10% effect, not game-breaking

    # Combat-related patterns
    if "killaura" in reason or "aura" in reason or "cps" in reason:
        mitigations["hit_reg_loss"] = scale * 0.05  # Max 5% hits missed
        mitigations["reach_reduction"] = scale * 0.05  # Max 5% reach reduction

    # Speed-related patterns
    if "speed" in reason:
        mitigations["speed_reduction"] = scale * 0.05  # Max 5% slower

    # Fly-related patterns
    if "fly" in reason or "air" in reason:
        mitigations["knockback_amplify"] = scale * 0.1  # Max 10% more knockback

    # Velocity-related patterns
    if "velocity" in reason or "knockback" in reason:
        mitigations["knockback_amplify"] = scale * 0.1

    # Building-related patterns
    if "scaffold" in reason or "block" in reason or "building" in reason:
        mitigations["block_fail_rate"] = scale * 0.05  # Max 5% blocks fail

    return mitigations


def update_trust_score(player: Dict, analysis: Dict, is_violation: bool = False):
    """Update player trust score based on ML analysis ONLY (not violations)"""
    current = player.get("trust_score", DEFAULT_TRUST_SCORE)

    # Trust score is ONLY based on ML analysis, NOT on violations
    # Violations are tracked separately but don't affect trust
    if is_violation:
        player["total_violations"] = player.get("total_violations", 0) + 1
        # Don't change trust score based on violations
        return

    # ML-based trust update
    suspicion = (
        analysis.get("anomaly_score", 0) * 0.3 +
        analysis.get("temporal_confidence", 0) * 0.5 +
        analysis.get("pattern_match_score", 0) * 0.2
    )

    if suspicion < 0.2:
        # Clean ML analysis, increase trust
        player["trust_score"] = min(100, current + 2.0)
    elif suspicion < 0.4:
        # Slightly suspicious, slow increase
        player["trust_score"] = min(100, current + 0.5)
    elif suspicion > 0.7:
        # ML is confident player is cheating, decrease trust
        player["trust_score"] = max(0, current - 3)
    elif suspicion > 0.5:
        # Somewhat suspicious
        player["trust_score"] = max(0, current - 1)

    # Mark as known cheater only if ML is very confident over time
    if player["trust_score"] < 15:
        player["known_cheater"] = True


# ==================== API ENDPOINTS ====================

@app.get("/api/v2/health")
async def health_check():
    """Health check endpoint"""
    return {
        "status": "healthy",
        "version": API_VERSION,
        "total_players": global_stats["total_players"],
        "total_servers": global_stats["total_servers"],
        "data_points": global_stats["total_samples"],
        "uptime": time.time(),
    }


@app.post("/api/v2/behavior/upload")
async def upload_behavior(data: BehaviorUpload, x_api_key: str = Header(None)):
    """Upload behavior samples for analysis"""
    player = get_or_create_player(data.player_hash)
    player["last_seen"] = time.time()
    player["servers_seen"].add(data.server_id)

    # Store samples
    for sample in data.samples:
        player_samples[data.player_hash].append(sample.dict())
        global_stats["total_samples"] += 1

    # Keep only last 200 samples per player
    if len(player_samples[data.player_hash]) > 200:
        player_samples[data.player_hash] = player_samples[data.player_hash][-200:]

    player["total_samples"] = len(player_samples[data.player_hash])

    # Update server stats
    if data.server_id not in server_stats:
        server_stats[data.server_id] = {"players": set(), "samples": 0}
        global_stats["total_servers"] += 1
    server_stats[data.server_id]["players"].add(data.player_hash)
    server_stats[data.server_id]["samples"] += len(data.samples)

    # Analyze samples
    samples = [BehaviorSample(**s) for s in player_samples[data.player_hash][-60:]]
    analysis = analyze_samples(samples)

    # Update trust score
    update_trust_score(player, analysis)

    # Calculate mitigations
    mitigations = calculate_mitigations(analysis, player)

    return {
        "status": "ok",
        "samples_received": len(data.samples),
        "trust_score": player["trust_score"],
        "analysis": analysis,
        "mitigations": mitigations,
    }


@app.get("/api/v2/player/{player_hash}/analysis")
async def get_player_analysis(player_hash: str):
    """Get analysis and mitigation recommendations for a player"""
    player = get_or_create_player(player_hash)

    # Get samples
    samples_data = player_samples.get(player_hash, [])
    samples = [BehaviorSample(**s) for s in samples_data[-60:]]

    # Analyze
    analysis = analyze_samples(samples)

    # Calculate mitigations
    mitigations = calculate_mitigations(analysis, player)

    # Determine if cloud should kick (100% confidence)
    should_kick = False
    kick_reason = ""

    combined_confidence = (
        analysis.get("anomaly_score", 0) * 0.3 +
        analysis.get("temporal_confidence", 0) * 0.5 +
        analysis.get("pattern_match_score", 0) * 0.2
    )

    # Only kick if:
    # 1. Combined confidence >= 0.95 (nearly 100%)
    # 2. Has enough data
    # 3. Temporal confidence very high (pattern confirmed over time)
    if (combined_confidence >= 0.95 and
        analysis.get("has_enough_data", False) and
        analysis.get("temporal_confidence", 0) >= 0.9):
        should_kick = True
        kick_reason = "Cloud Detection"

    return MitigationResponse(
        trust_score=player.get("trust_score", DEFAULT_TRUST_SCORE),
        anomaly_score=analysis.get("anomaly_score", 0),
        temporal_confidence=analysis.get("temporal_confidence", 0),
        pattern_match_score=analysis.get("pattern_match_score", 0),
        reason=analysis.get("reason", "unknown"),
        mitigations=mitigations,
        combat_mitigation=(mitigations.get("hit_reg_loss", 0) + mitigations.get("reach_reduction", 0)) / 2,
        movement_mitigation=mitigations.get("speed_reduction", 0),
        building_mitigation=(mitigations.get("block_fail_rate", 0) + mitigations.get("ghost_block_rate", 0)) / 2,
        watch_closely=analysis.get("temporal_confidence", 0) > 0.5,
        has_enough_data=analysis.get("has_enough_data", False),
        known_cheater=player.get("known_cheater", False),
        cross_server_violations=player.get("total_violations", 0),
        should_kick=should_kick,
        kick_reason=kick_reason,
    )


@app.post("/api/v2/violation/report")
async def report_violation(data: ViolationReport):
    """Report a violation from a local check - for stats only, does NOT affect trust"""
    player = get_or_create_player(data.player_hash)
    player["last_seen"] = time.time()
    player["total_violations"] = player.get("total_violations", 0) + 1

    global_stats["total_violations"] += 1

    # Trust score is NOT affected by violations - only by ML analysis
    # Violations are just tracked for statistics

    return {"status": "ok", "trust_score": player["trust_score"]}


@app.post("/api/v2/training/label")
async def submit_training_label(data: TrainingLabel):
    """Submit a training label (legit/cheat) for a player"""
    player = get_or_create_player(data.player_hash)

    player["labels"].append({
        "label": data.label,
        "server_id": data.server_id,
        "playtime": data.playtime_minutes,
        "timestamp": data.timestamp,
    })

    # If marked as legit with significant playtime, increase trust
    if data.label == "legit" and data.playtime_minutes > 10:
        player["trust_score"] = min(100, player.get("trust_score", 50) + 2)

    return {"status": "ok"}


# Legacy endpoints for backwards compatibility
@app.get("/api/health")
async def legacy_health():
    return await health_check()


@app.get("/api/player/{uuid}")
async def legacy_player_info(uuid: str):
    player = get_or_create_player(uuid)
    return {
        "trust_score": player.get("trust_score", 50),
        "total_violations": player.get("total_violations", 0),
        "playtime_minutes": int((time.time() - player.get("first_seen", time.time())) / 60),
    }


@app.post("/api/player/check")
async def legacy_check(request: Request):
    body = await request.json()
    uuid = body.get("uuid", "")
    player = get_or_create_player(uuid)
    return {
        "trust_score": player.get("trust_score", 50),
        "risk_level": "high" if player.get("trust_score", 50) < 30 else "medium" if player.get("trust_score", 50) < 60 else "low",
        "should_kick": False,
        "recommendation": "",
    }


@app.get("/api/ml/analyze/{uuid}")
async def legacy_analyze(uuid: str):
    player = get_or_create_player(uuid)
    samples_data = player_samples.get(uuid, [])
    samples = [BehaviorSample(**s) for s in samples_data[-60:]]
    analysis = analyze_samples(samples)

    return {
        "ml_confidence": analysis.get("temporal_confidence", 0),
        "reason": analysis.get("reason", "unknown"),
        "trust_score": player.get("trust_score", 50),
        "should_mitigate": analysis.get("temporal_confidence", 0) > 0.5,
    }


@app.get("/api/ml/mitigation/{uuid}")
async def legacy_mitigation(uuid: str, type: str = "general"):
    player = get_or_create_player(uuid)
    samples_data = player_samples.get(uuid, [])
    samples = [BehaviorSample(**s) for s in samples_data[-60:]]
    analysis = analyze_samples(samples)
    mitigations = calculate_mitigations(analysis, player)

    return mitigations


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 17865))
    uvicorn.run(app, host="0.0.0.0", port=port)


# ==================== UPDATE SYSTEM ====================

@app.get("/api/v2/update/version")
async def check_update():
    # Check for plugin updates
    return {
        "available": True,
        "version": "1.1",
        "hash": "latest"
    }

@app.get("/api/v2/update/download")
async def download_update():
    # Download plugin update
    # Assuming updates folder is relative to main.py
    base_dir = os.path.dirname(os.path.abspath(__file__))
    file_path = os.path.join(base_dir, "updates", "GAC.jar")
    
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="Update file not found")
        
    return FileResponse(file_path, media_type="application/java-archive", filename="GAC.jar")
