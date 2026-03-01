#!/usr/bin/env python3
"""
GAC Cloud Server - Polar-Style Machine Learning Backend

Philosophy:
- NO bans from ML - only confidence scores and mitigation recommendations
- Subtle, asymmetric mitigations that degrade cheat effectiveness
- Cross-server learning with anonymized data
- Time-series analysis (Isolation Forest + pattern detection)
- Physics simulation for blatant detection
- LSTM for temporal pattern recognition
- Packet sequence analysis
- Cheat client signature detection
"""

import os
import time
import json
import hashlib
import hmac
import math
from datetime import datetime
from typing import Dict, List, Optional, Any, Tuple
from collections import defaultdict, deque

import numpy as np
import threading
from fastapi import FastAPI, HTTPException, Header, Request, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sklearn.ensemble import IsolationForest, RandomForestClassifier, GradientBoostingClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score

# Try to import PyTorch for LSTM (optional)
try:
    import torch
    import torch.nn as nn
    PYTORCH_AVAILABLE = True
    print("[INIT] PyTorch available - LSTM enabled")
except ImportError:
    PYTORCH_AVAILABLE = False
    print("[INIT] PyTorch not available - using fallback RNN")

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

# ==================== SUPERVISED LEARNING ====================
# Labeled data from local checks

labeled_samples: Dict[str, List[Dict]] = {
    "cheat": [],   # Samples von bestätigten Cheatern
    "legit": [],   # Samples von legitimen Spielern
}

# Supervised Models (trained on labeled data)
supervised_models: Dict[str, Any] = {}
supervised_scaler: Optional[StandardScaler] = None
model_accuracy: float = 0.0
last_training_time: float = 0
MIN_LABELED_SAMPLES = 50  # Minimum samples needed for training
RETRAIN_INTERVAL = 300  # Retrain every 5 minutes if new data
training_lock = threading.Lock()

# ==================== SIGNATURE DATABASE ====================
# Known cheat signatures based on behavior patterns

CHEAT_SIGNATURES = {
    # Killaura Signatures
    "killaura_basic": {
        "name": "Killaura (Basic)",
        "conditions": {
            "cps_min": 12,
            "cps_max": 20,
            "pitch_variance_max": 3.0,
            "yaw_variance_max": 5.0,
            "hit_accuracy_min": 0.85,
        },
        "confidence": 0.7,
        "category": "combat"
    },
    "killaura_advanced": {
        "name": "Killaura (Advanced)",
        "conditions": {
            "cps_min": 10,
            "cps_max": 15,
            "aim_angle_variance_max": 10.0,
            "target_switch_speed_max": 150,  # ms
            "combo_min": 8,
        },
        "confidence": 0.8,
        "category": "combat"
    },
    "autoclicker": {
        "name": "Autoclicker",
        "conditions": {
            "cps_min": 15,
            "click_entropy_max": 0.3,  # Very consistent = bot
            "click_interval_variance_max": 100,
        },
        "confidence": 0.75,
        "category": "combat"
    },

    # Aim Assistance Signatures
    "aimbot_snap": {
        "name": "Aimbot (Snap)",
        "conditions": {
            "snap_count_min": 5,
            "aim_acceleration_min": 50,
            "aim_angle_max": 5.0,  # Always on target
        },
        "confidence": 0.85,
        "category": "combat"
    },
    "aimbot_smooth": {
        "name": "Aimbot (Smooth)",
        "conditions": {
            "yaw_variance_max": 2.0,
            "pitch_variance_max": 1.5,
            "aim_jitter_max": 0.5,
            "hit_accuracy_min": 0.9,
        },
        "confidence": 0.7,
        "category": "combat"
    },

    # Speed/Movement Signatures
    "speed_basic": {
        "name": "Speed Hack",
        "conditions": {
            "avg_speed_min": 0.35,
            "max_speed_min": 0.45,
            "speed_variance_max": 0.02,  # Very consistent
        },
        "confidence": 0.8,
        "category": "movement"
    },
    "bhop": {
        "name": "Bunny Hop",
        "conditions": {
            "air_ground_ratio_min": 0.7,
            "avg_delta_y_min": 0.1,
            "speed_variance_max": 0.03,
        },
        "confidence": 0.6,
        "category": "movement"
    },

    # Fly Signatures
    "fly_basic": {
        "name": "Fly Hack",
        "conditions": {
            "air_ticks_min": 40,
            "air_ground_ratio_min": 2.0,
            "max_delta_y_min": 0.2,
        },
        "confidence": 0.85,
        "category": "movement"
    },
    "glide": {
        "name": "Glide/Slow-Fall",
        "conditions": {
            "air_ticks_min": 30,
            "avg_delta_y_max": -0.02,  # Falling very slowly
            "avg_delta_y_min": -0.08,
        },
        "confidence": 0.7,
        "category": "movement"
    },

    # Velocity Signatures
    "velocity_full": {
        "name": "Anti-Knockback (Full)",
        "conditions": {
            "knockback_compliance_max": 0.2,
        },
        "confidence": 0.9,
        "category": "combat"
    },
    "velocity_reduce": {
        "name": "Anti-Knockback (Reduced)",
        "conditions": {
            "knockback_compliance_min": 0.2,
            "knockback_compliance_max": 0.5,
        },
        "confidence": 0.6,
        "category": "combat"
    },

    # Scaffold Signatures
    "scaffold_fast": {
        "name": "Scaffold (Fast)",
        "conditions": {
            "blocks_placed_min": 10,
            "avg_place_speed_min": 3.0,  # 3+ blocks/sec
            "impossible_placements_min": 2,
        },
        "confidence": 0.8,
        "category": "building"
    },
    "scaffold_tower": {
        "name": "Tower/Scaffold",
        "conditions": {
            "blocks_placed_min": 8,
            "avg_delta_y_min": 0.15,
            "place_angle_variance_max": 5.0,
        },
        "confidence": 0.7,
        "category": "building"
    },

    # Packet Signatures
    "timer": {
        "name": "Timer Hack",
        "conditions": {
            "pps_min": 25,  # Normal is ~20
            "packet_timing_variance_max": 10,
        },
        "confidence": 0.75,
        "category": "packet"
    },
    "blink": {
        "name": "Blink/Lag Switch",
        "conditions": {
            "pps_max": 5,  # Very few packets
            "packet_timing_variance_min": 500,
        },
        "confidence": 0.7,
        "category": "packet"
    },
}

# Combat Session Analysis Thresholds
COMBAT_THRESHOLDS = {
    "suspicious_win_rate": 0.9,
    "suspicious_accuracy": 0.9,
    "suspicious_combo": 12,
    "suspicious_reaction_time": 100,  # ms
    "suspicious_kdr": 5.0,
}

# ==================== PHYSICS SIMULATION ====================
# Minecraft physics constants for blatant detection

class MinecraftPhysics:
    """Simulates Minecraft physics to detect impossible movements"""

    # Movement constants
    WALK_SPEED = 0.1  # blocks/tick
    SPRINT_SPEED = 0.13  # blocks/tick
    SNEAK_SPEED = 0.03  # blocks/tick
    MAX_SPEED_GROUND = 0.28  # With speed potions etc
    MAX_SPEED_AIR = 0.36  # With momentum

    # Jump/Fall constants
    JUMP_HEIGHT = 0.42  # blocks/tick initial velocity
    GRAVITY = 0.08  # blocks/tick^2
    TERMINAL_VELOCITY = 3.92  # blocks/tick
    MAX_FALL_DISTANCE_NO_DAMAGE = 3.0  # blocks

    # Combat constants
    MAX_REACH = 3.0  # blocks (vanilla)
    MAX_REACH_CREATIVE = 5.0
    ATTACK_COOLDOWN = 0.5  # seconds (1.9+ combat)
    MAX_CPS_HUMANLY_POSSIBLE = 20  # clicks per second

    # Rotation constants
    MAX_ROTATION_SPEED = 180  # degrees per tick (humanly impossible)
    MAX_HUMAN_ROTATION = 60  # degrees per tick (fast but possible)

    @staticmethod
    def is_speed_possible(speed: float, sprinting: bool, sneaking: bool,
                          effects: List[str] = None) -> Tuple[bool, str]:
        """Check if a speed is physically possible"""
        effects = effects or []

        max_speed = MinecraftPhysics.WALK_SPEED
        if sprinting:
            max_speed = MinecraftPhysics.SPRINT_SPEED
        if sneaking:
            max_speed = MinecraftPhysics.SNEAK_SPEED

        # Speed effects (each level adds 20%)
        speed_level = sum(1 for e in effects if "speed" in e.lower())
        max_speed *= (1 + 0.2 * speed_level)

        # Add some tolerance (20%)
        max_speed *= 1.2

        if speed > max_speed:
            return False, f"speed_{speed:.3f}_max_{max_speed:.3f}"
        return True, ""

    @staticmethod
    def is_reach_possible(distance: float, gamemode: str = "SURVIVAL") -> Tuple[bool, str]:
        """Check if a hit reach is possible"""
        max_reach = MinecraftPhysics.MAX_REACH
        if gamemode == "CREATIVE":
            max_reach = MinecraftPhysics.MAX_REACH_CREATIVE

        # Add tolerance for latency (0.3 blocks)
        max_reach += 0.3

        if distance > max_reach:
            return False, f"reach_{distance:.2f}_max_{max_reach:.2f}"
        return True, ""

    @staticmethod
    def is_rotation_possible(delta_yaw: float, delta_pitch: float,
                             time_ms: float) -> Tuple[bool, str]:
        """Check if a rotation change is humanly possible"""
        total_rotation = math.sqrt(delta_yaw**2 + delta_pitch**2)

        # Convert to degrees per tick (50ms per tick)
        ticks = max(1, time_ms / 50)
        rotation_per_tick = total_rotation / ticks

        if rotation_per_tick > MinecraftPhysics.MAX_ROTATION_SPEED:
            return False, f"rotation_{rotation_per_tick:.1f}_deg/tick"

        # Check if humanly reasonable (very fast but possible)
        if rotation_per_tick > MinecraftPhysics.MAX_HUMAN_ROTATION:
            return False, f"inhuman_rotation_{rotation_per_tick:.1f}"

        return True, ""

    @staticmethod
    def is_cps_possible(cps: float) -> Tuple[bool, str]:
        """Check if CPS is humanly possible"""
        if cps > MinecraftPhysics.MAX_CPS_HUMANLY_POSSIBLE:
            return False, f"cps_{cps:.1f}_impossible"
        if cps > 16:  # Very high but technically possible
            return False, f"cps_{cps:.1f}_suspicious"
        return True, ""

    @staticmethod
    def is_flight_pattern(air_ticks: int, ground_ticks: int,
                          delta_y_sequence: List[float]) -> Tuple[bool, str]:
        """Detect fly hack patterns"""
        if not delta_y_sequence:
            return True, ""

        # Check for sustained air time without falling
        if air_ticks > 40 and ground_ticks < 5:
            avg_delta_y = np.mean(delta_y_sequence)
            # Should be falling (negative) if in air this long
            if avg_delta_y > -0.05:
                return False, "fly_no_fall"

        # Check for constant Y (glide/fly)
        if len(delta_y_sequence) > 10:
            y_variance = np.var(delta_y_sequence)
            if y_variance < 0.001 and air_ticks > 20:
                return False, "fly_constant_y"

        return True, ""


def physics_check(sample: Dict) -> Dict:
    """Run physics simulation on a sample"""
    violations = []
    confidence = 0.0

    # Speed check
    speed_ok, speed_reason = MinecraftPhysics.is_speed_possible(
        sample.get("maxSpeed", 0),
        sample.get("sprinting", False),
        sample.get("sneaking", False)
    )
    if not speed_ok:
        violations.append(speed_reason)
        confidence += 0.4

    # Reach check
    reach_ok, reach_reason = MinecraftPhysics.is_reach_possible(
        sample.get("maxHitDistance", 0),
        sample.get("gamemode", "SURVIVAL")
    )
    if not reach_ok:
        violations.append(reach_reason)
        confidence += 0.5

    # CPS check
    cps_ok, cps_reason = MinecraftPhysics.is_cps_possible(
        sample.get("cps", 0)
    )
    if not cps_ok:
        violations.append(cps_reason)
        confidence += 0.3

    # Rotation check (using snap count as proxy)
    if sample.get("snapCount", 0) > 10:
        violations.append(f"snap_count_{sample.get('snapCount')}")
        confidence += 0.3

    # Flight check
    fly_ok, fly_reason = MinecraftPhysics.is_flight_pattern(
        sample.get("airTicks", 0),
        sample.get("groundTicks", 1),
        sample.get("verticalSequence", [])
    )
    if not fly_ok:
        violations.append(fly_reason)
        confidence += 0.5

    return {
        "violations": violations,
        "confidence": min(1.0, confidence),
        "is_blatant": confidence >= 0.5
    }


# ==================== LSTM NEURAL NETWORK ====================
# Time-series analysis for subtle cheat detection

class SimpleLSTM:
    """Simple LSTM-like RNN for sequence analysis (fallback if PyTorch unavailable)"""

    def __init__(self, input_size: int, hidden_size: int = 64):
        self.input_size = input_size
        self.hidden_size = hidden_size
        self.trained = False

        # Simple weight matrices (random init)
        np.random.seed(42)
        self.Wf = np.random.randn(hidden_size, input_size + hidden_size) * 0.1
        self.Wi = np.random.randn(hidden_size, input_size + hidden_size) * 0.1
        self.Wc = np.random.randn(hidden_size, input_size + hidden_size) * 0.1
        self.Wo = np.random.randn(hidden_size, input_size + hidden_size) * 0.1
        self.Wy = np.random.randn(1, hidden_size) * 0.1

    def sigmoid(self, x):
        return 1 / (1 + np.exp(-np.clip(x, -500, 500)))

    def tanh(self, x):
        return np.tanh(np.clip(x, -500, 500))

    def forward(self, sequence: np.ndarray) -> float:
        """Forward pass through LSTM"""
        h = np.zeros(self.hidden_size)
        c = np.zeros(self.hidden_size)

        for x in sequence:
            combined = np.concatenate([x, h])

            f = self.sigmoid(self.Wf @ combined)
            i = self.sigmoid(self.Wi @ combined)
            c_tilde = self.tanh(self.Wc @ combined)
            c = f * c + i * c_tilde
            o = self.sigmoid(self.Wo @ combined)
            h = o * self.tanh(c)

        # Output layer
        output = self.sigmoid(self.Wy @ h)
        return float(output[0])

    def predict_cheat_probability(self, samples: List[Dict]) -> float:
        """Predict cheat probability from sample sequence"""
        if len(samples) < 5:
            return 0.0

        # Extract time series features
        sequence = []
        for s in samples[-20:]:  # Last 20 samples
            features = [
                s.get("avgSpeed", 0) * 10,
                s.get("cps", 0) / 20,
                s.get("yawVariance", 0) / 100,
                s.get("pitchVariance", 0) / 100,
                s.get("hitAccuracy", 0),
                s.get("knockbackComplianceRate", 1),
                s.get("clickEntropy", 1),
                s.get("aimAcceleration", 0) / 50,
            ]
            sequence.append(np.array(features))

        if not sequence:
            return 0.0

        return self.forward(np.array(sequence))


# PyTorch LSTM (if available)
if PYTORCH_AVAILABLE:
    class LSTMCheatDetector(nn.Module):
        def __init__(self, input_size=32, hidden_size=64, num_layers=2):
            super().__init__()
            self.lstm = nn.LSTM(input_size, hidden_size, num_layers, batch_first=True)
            self.fc = nn.Linear(hidden_size, 1)
            self.sigmoid = nn.Sigmoid()

        def forward(self, x):
            lstm_out, _ = self.lstm(x)
            last_output = lstm_out[:, -1, :]
            return self.sigmoid(self.fc(last_output))

    lstm_model = LSTMCheatDetector()
    lstm_model.eval()
else:
    lstm_model = SimpleLSTM(input_size=8)


def lstm_analyze(samples: List[Dict]) -> Dict:
    """Analyze samples using LSTM"""
    if len(samples) < 10:
        return {"probability": 0.0, "available": False}

    try:
        if PYTORCH_AVAILABLE:
            # Extract features for PyTorch
            features = []
            for s in samples[-30:]:
                f = [
                    s.get("avgSpeed", 0),
                    s.get("maxSpeed", 0),
                    s.get("cps", 0) / 20,
                    s.get("yawVariance", 0) / 100,
                    s.get("pitchVariance", 0) / 100,
                    s.get("snapCount", 0) / 10,
                    s.get("hitAccuracy", 0),
                    s.get("knockbackComplianceRate", 1),
                    s.get("clickEntropy", 1),
                    s.get("aimAcceleration", 0) / 50,
                    s.get("avgHitDistance", 0) / 5,
                    s.get("avgAimAngle", 0) / 90,
                    # Pad to 32 features
                ] + [0] * 20
                features.append(f[:32])

            x = torch.FloatTensor([features])
            with torch.no_grad():
                prob = lstm_model(x).item()
            return {"probability": prob, "available": True, "model": "pytorch_lstm"}
        else:
            prob = lstm_model.predict_cheat_probability(samples)
            return {"probability": prob, "available": True, "model": "simple_rnn"}

    except Exception as e:
        print(f"[LSTM] Error: {e}")
        return {"probability": 0.0, "available": False, "error": str(e)}


# ==================== PACKET SEQUENCE ANALYSIS ====================
# Detect packet manipulation patterns

PACKET_PATTERNS = {
    "blink": {
        "description": "Blink/Lag switch - holding packets then releasing",
        "pattern": "low_pps_then_burst",
        "threshold": 0.7
    },
    "timer": {
        "description": "Timer hack - faster game tick",
        "pattern": "consistently_high_pps",
        "threshold": 0.6
    },
    "velocity_cancel": {
        "description": "Velocity packet cancellation",
        "pattern": "low_knockback_after_hit",
        "threshold": 0.7
    },
    "fake_lag": {
        "description": "Fake lag - artificial packet delay",
        "pattern": "irregular_packet_timing",
        "threshold": 0.5
    },
    "packet_order": {
        "description": "Invalid packet ordering",
        "pattern": "wrong_packet_sequence",
        "threshold": 0.8
    }
}


def analyze_packet_patterns(samples: List[Dict]) -> Dict:
    """Analyze packet sequences for manipulation"""
    if len(samples) < 10:
        return {"detected": [], "confidence": 0.0}

    detected = []
    total_confidence = 0.0

    # Extract packet data
    pps_values = [s.get("pps", 20) for s in samples]
    timing_vars = [s.get("packetTimingVariance", 0) for s in samples]
    kb_rates = [s.get("knockbackComplianceRate", 1) for s in samples]

    # === BLINK DETECTION ===
    # Look for periods of low PPS followed by bursts
    if len(pps_values) >= 5:
        pps_std = np.std(pps_values)
        pps_mean = np.mean(pps_values)
        min_pps = min(pps_values)
        max_pps = max(pps_values)

        # Blink pattern: very low PPS followed by very high
        if min_pps < 5 and max_pps > 30 and pps_std > 10:
            detected.append({
                "type": "blink",
                "confidence": min(0.9, pps_std / 20),
                "details": f"PPS range: {min_pps}-{max_pps}, std: {pps_std:.1f}"
            })
            total_confidence += 0.4

    # === TIMER DETECTION ===
    # Consistently high PPS (faster than normal tick rate)
    avg_pps = np.mean(pps_values)
    if avg_pps > 22:  # Normal is ~20
        timer_confidence = min(1.0, (avg_pps - 20) / 10)
        detected.append({
            "type": "timer",
            "confidence": timer_confidence,
            "details": f"Avg PPS: {avg_pps:.1f} (normal: 20)"
        })
        total_confidence += timer_confidence * 0.3

    # === VELOCITY CANCEL DETECTION ===
    if kb_rates:
        avg_kb = np.mean(kb_rates)
        if avg_kb < 0.4:
            detected.append({
                "type": "velocity_cancel",
                "confidence": 1.0 - avg_kb,
                "details": f"KB compliance: {avg_kb:.0%}"
            })
            total_confidence += (1.0 - avg_kb) * 0.4

    # === FAKE LAG DETECTION ===
    if timing_vars:
        avg_timing_var = np.mean(timing_vars)
        if avg_timing_var > 200:  # High variance = irregular
            fake_lag_conf = min(1.0, avg_timing_var / 500)
            detected.append({
                "type": "fake_lag",
                "confidence": fake_lag_conf,
                "details": f"Timing variance: {avg_timing_var:.0f}ms"
            })
            total_confidence += fake_lag_conf * 0.2

    return {
        "detected": detected,
        "confidence": min(1.0, total_confidence),
        "patterns_checked": len(PACKET_PATTERNS)
    }


# ==================== CHEAT CLIENT DETECTION ====================
# Signatures for known cheat clients

CHEAT_CLIENT_SIGNATURES = {
    "vape_v4": {
        "name": "Vape V4",
        "indicators": {
            "cps_pattern": (12, 16, 0.8),  # min, max, low variance
            "aim_smoothness": (0.3, 0.7),  # very smooth aim
            "click_entropy": (0.4, 0.7),   # semi-random clicks
        },
        "confidence_weight": 0.8
    },
    "vape_lite": {
        "name": "Vape Lite",
        "indicators": {
            "cps_pattern": (10, 14, 1.0),
            "reach_extend": (3.1, 3.3),  # slight reach
        },
        "confidence_weight": 0.7
    },
    "rise": {
        "name": "Rise Client",
        "indicators": {
            "scaffold_speed": (8, 12),  # blocks per second
            "aim_snap": True,  # has aim snapping
            "velocity_mod": (0.7, 0.9),  # partial velocity
        },
        "confidence_weight": 0.85
    },
    "liquidbounce": {
        "name": "LiquidBounce",
        "indicators": {
            "killaura_cps": (8, 10),  # lower CPS
            "rotation_speed": (30, 60),  # deg/tick
            "reach": (3.0, 3.5),
        },
        "confidence_weight": 0.75
    },
    "novo": {
        "name": "Novo Client",
        "indicators": {
            "cps_pattern": (14, 18, 0.5),  # high, low variance
            "aim_acceleration": (20, 40),
            "strafe_pattern": True,
        },
        "confidence_weight": 0.8
    },
    "moon": {
        "name": "Moon Client",
        "indicators": {
            "velocity_mod": (0.8, 0.95),
            "timer_speed": (1.01, 1.05),  # slight timer
            "click_entropy": (0.5, 0.8),
        },
        "confidence_weight": 0.7
    },
    "autumn": {
        "name": "Autumn Client",
        "indicators": {
            "scaffold_pattern": "diagonal",
            "aim_assist_strength": (0.3, 0.5),
            "cps_randomization": True,
        },
        "confidence_weight": 0.75
    },
    "generic_autoclicker": {
        "name": "Autoclicker (Generic)",
        "indicators": {
            "click_entropy": (0.0, 0.3),  # very consistent
            "cps_variance": (0, 50),  # low variance
            "cps_pattern": (10, 20, 0.3),
        },
        "confidence_weight": 0.9
    },
    "generic_killaura": {
        "name": "Killaura (Generic)",
        "indicators": {
            "multi_target": True,
            "rotation_snap": True,
            "hit_through_walls": True,
        },
        "confidence_weight": 0.95
    }
}


def detect_cheat_client(samples: List[Dict]) -> Dict:
    """Detect specific cheat clients from behavior patterns"""
    if len(samples) < 15:
        return {"detected": None, "confidence": 0.0, "candidates": []}

    candidates = []

    # Aggregate sample data
    cps_values = [s.get("cps", 0) for s in samples if s.get("cps", 0) > 0]
    click_entropies = [s.get("clickEntropy", 1) for s in samples]
    aim_accels = [s.get("aimAcceleration", 0) for s in samples]
    kb_rates = [s.get("knockbackComplianceRate", 1) for s in samples]
    reach_values = [s.get("maxHitDistance", 0) for s in samples if s.get("maxHitDistance", 0) > 0]
    snap_counts = [s.get("snapCount", 0) for s in samples]

    avg_cps = np.mean(cps_values) if cps_values else 0
    cps_var = np.var(cps_values) if len(cps_values) > 1 else 0
    avg_entropy = np.mean(click_entropies)
    avg_aim_accel = np.mean(aim_accels)
    avg_kb = np.mean(kb_rates)
    max_reach = max(reach_values) if reach_values else 0
    total_snaps = sum(snap_counts)

    for client_id, client_data in CHEAT_CLIENT_SIGNATURES.items():
        match_score = 0.0
        matches = 0
        total_checks = 0

        indicators = client_data.get("indicators", {})

        # CPS Pattern check
        if "cps_pattern" in indicators:
            min_cps, max_cps, max_var = indicators["cps_pattern"]
            total_checks += 1
            if min_cps <= avg_cps <= max_cps:
                matches += 0.5
                if cps_var <= max_var * 100:  # Convert to actual variance
                    matches += 0.5

        # Click entropy check
        if "click_entropy" in indicators:
            min_ent, max_ent = indicators["click_entropy"]
            total_checks += 1
            if min_ent <= avg_entropy <= max_ent:
                matches += 1

        # Aim acceleration check
        if "aim_acceleration" in indicators:
            min_accel, max_accel = indicators["aim_acceleration"]
            total_checks += 1
            if min_accel <= avg_aim_accel <= max_accel:
                matches += 1

        # Velocity mod check
        if "velocity_mod" in indicators:
            min_kb, max_kb = indicators["velocity_mod"]
            total_checks += 1
            if min_kb <= avg_kb <= max_kb:
                matches += 1

        # Reach check
        if "reach_extend" in indicators or "reach" in indicators:
            reach_range = indicators.get("reach_extend") or indicators.get("reach")
            min_reach, max_reach_ind = reach_range
            total_checks += 1
            if min_reach <= max_reach <= max_reach_ind:
                matches += 1

        # Rotation snap check
        if "rotation_snap" in indicators or "aim_snap" in indicators:
            total_checks += 1
            if total_snaps > len(samples) * 0.3:  # 30%+ samples have snaps
                matches += 1

        # Calculate confidence
        if total_checks > 0:
            match_ratio = matches / total_checks
            confidence = match_ratio * client_data.get("confidence_weight", 0.5)

            if confidence >= 0.4:
                candidates.append({
                    "client": client_id,
                    "name": client_data["name"],
                    "confidence": confidence,
                    "matches": f"{matches}/{total_checks}"
                })

    # Sort by confidence
    candidates.sort(key=lambda x: x["confidence"], reverse=True)

    best_match = candidates[0] if candidates else None

    return {
        "detected": best_match["name"] if best_match and best_match["confidence"] >= 0.6 else None,
        "confidence": best_match["confidence"] if best_match else 0.0,
        "candidates": candidates[:3],  # Top 3 candidates
    }


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

    # === NEW: Advanced Aim Features ===
    aimAcceleration: float = 0
    aimJitter: float = 0
    targetSwitchSpeed: float = 0
    targetSwitchCount: int = 0
    avgAimAngle: float = 0
    aimAngleVariance: float = 0

    # === NEW: Strafe/Movement Patterns ===
    strafeCount: int = 0
    strafeFrequency: float = 0
    strafeConsistency: float = 0
    wTapCount: int = 0
    sprintResetCount: int = 0

    # === NEW: Click Pattern Features ===
    avgClickInterval: float = 0
    clickIntervalVariance: float = 0
    clickEntropy: float = 1.0
    doubleClickCount: int = 0

    # === NEW: Combat Session Data ===
    activeCombatSessions: int = 0
    combatWinRate: float = 0
    avgDamageDealt: float = 0
    avgDamageTaken: float = 0
    combatKDR: float = 0
    avgCombatDuration: float = 0
    reactionTime: float = 0
    comboCount: int = 0
    hitAccuracy: float = 0

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
    clickIntervalSequence: List[int] = []
    aimAngleSequence: List[float] = []
    targetSwitchTimeSequence: List[int] = []


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
    samples: List[Dict] = []  # Behavior samples to train on
    check_name: str = ""  # Which check triggered (for cheat labels)
    confidence: float = 0.0  # How confident is the local check


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

    # NEW: Signature and Combat Analysis
    signature_matches: List[Dict] = []
    combat_flags: List[str] = []
    detected_cheat_type: str = ""

    # NEW: Supervised Learning Results
    supervised_probability: float = 0.0  # ML model's cheat probability
    ml_score: float = 0.0  # Combined ML score
    supervised_available: bool = False  # Is supervised model trained?
    model_accuracy: float = 0.0  # Current model accuracy

    # NEW: Physics Simulation Results
    physics_violations: List[str] = []
    physics_confidence: float = 0.0
    is_blatant: bool = False

    # NEW: LSTM Neural Network Results
    lstm_probability: float = 0.0
    lstm_available: bool = False

    # NEW: Packet Analysis Results
    packet_detections: List[Dict] = []
    packet_confidence: float = 0.0

    # NEW: Cheat Client Detection Results
    detected_client: Optional[str] = None
    client_confidence: float = 0.0
    client_candidates: List[Dict] = []


# ==================== SUPERVISED LEARNING FUNCTIONS ====================

def extract_features_for_training(sample: Dict) -> np.ndarray:
    """Extract feature vector from a sample dict for training"""
    return np.array([
        sample.get("avgSpeed", 0),
        sample.get("maxSpeed", 0),
        sample.get("speedVariance", 0),
        sample.get("avgDeltaY", 0),
        sample.get("airGroundRatio", 0),
        sample.get("avgYawChange", 0),
        sample.get("avgPitchChange", 0),
        sample.get("yawVariance", 0),
        sample.get("pitchVariance", 0),
        sample.get("snapCount", 0),
        sample.get("cps", 0),
        sample.get("cpsVariance", 0),
        sample.get("avgHitDistance", 0),
        sample.get("hitDistanceVariance", 0),
        sample.get("avgHitTiming", 0),
        sample.get("hitTimingVariance", 0),
        sample.get("knockbackComplianceRate", 1.0),
        sample.get("pps", 0),
        sample.get("packetTimingVariance", 0),
        sample.get("blocksPlaced", 0),
        sample.get("impossiblePlacements", 0),
        sample.get("aimAcceleration", 0),
        sample.get("aimJitter", 0),
        sample.get("targetSwitchSpeed", 0),
        sample.get("avgAimAngle", 0),
        sample.get("aimAngleVariance", 0),
        sample.get("strafeFrequency", 0),
        sample.get("strafeConsistency", 0),
        sample.get("clickEntropy", 1.0),
        sample.get("hitAccuracy", 0),
        sample.get("reactionTime", 0),
        sample.get("comboCount", 0),
    ])


def train_supervised_model():
    """Train supervised model on labeled data from all servers"""
    global supervised_models, supervised_scaler, model_accuracy, last_training_time

    with training_lock:
        cheat_samples = labeled_samples["cheat"]
        legit_samples = labeled_samples["legit"]

        total_samples = len(cheat_samples) + len(legit_samples)

        # Need minimum samples from both classes
        if len(cheat_samples) < MIN_LABELED_SAMPLES // 2 or len(legit_samples) < MIN_LABELED_SAMPLES // 2:
            print(f"[TRAINING] Not enough data: {len(cheat_samples)} cheat, {len(legit_samples)} legit")
            return False

        print(f"[TRAINING] Starting with {len(cheat_samples)} cheat + {len(legit_samples)} legit samples")

        try:
            # Prepare training data
            X = []
            y = []

            for sample in cheat_samples:
                features = extract_features_for_training(sample)
                X.append(features)
                y.append(1)  # 1 = cheater

            for sample in legit_samples:
                features = extract_features_for_training(sample)
                X.append(features)
                y.append(0)  # 0 = legit

            X = np.array(X)
            y = np.array(y)

            # Handle NaN/Inf values
            X = np.nan_to_num(X, nan=0.0, posinf=0.0, neginf=0.0)

            # Split for validation
            X_train, X_test, y_train, y_test = train_test_split(
                X, y, test_size=0.2, random_state=42, stratify=y
            )

            # Scale features
            supervised_scaler = StandardScaler()
            X_train_scaled = supervised_scaler.fit_transform(X_train)
            X_test_scaled = supervised_scaler.transform(X_test)

            # Train multiple models and use ensemble

            # 1. Random Forest (good for feature importance)
            rf_model = RandomForestClassifier(
                n_estimators=100,
                max_depth=10,
                min_samples_split=5,
                random_state=42,
                n_jobs=-1
            )
            rf_model.fit(X_train_scaled, y_train)
            rf_accuracy = accuracy_score(y_test, rf_model.predict(X_test_scaled))

            # 2. Gradient Boosting (good for complex patterns)
            gb_model = GradientBoostingClassifier(
                n_estimators=100,
                max_depth=5,
                learning_rate=0.1,
                random_state=42
            )
            gb_model.fit(X_train_scaled, y_train)
            gb_accuracy = accuracy_score(y_test, gb_model.predict(X_test_scaled))

            # Store models
            supervised_models["random_forest"] = rf_model
            supervised_models["gradient_boosting"] = gb_model

            # Combined accuracy (average)
            model_accuracy = (rf_accuracy + gb_accuracy) / 2
            last_training_time = time.time()

            print(f"[TRAINING] Complete! RF accuracy: {rf_accuracy:.2%}, GB accuracy: {gb_accuracy:.2%}")
            print(f"[TRAINING] Combined accuracy: {model_accuracy:.2%}")

            # Log feature importance (top 10)
            feature_names = [
                "avgSpeed", "maxSpeed", "speedVariance", "avgDeltaY", "airGroundRatio",
                "avgYawChange", "avgPitchChange", "yawVariance", "pitchVariance", "snapCount",
                "cps", "cpsVariance", "avgHitDistance", "hitDistanceVariance", "avgHitTiming",
                "hitTimingVariance", "knockbackComplianceRate", "pps", "packetTimingVariance",
                "blocksPlaced", "impossiblePlacements", "aimAcceleration", "aimJitter",
                "targetSwitchSpeed", "avgAimAngle", "aimAngleVariance", "strafeFrequency",
                "strafeConsistency", "clickEntropy", "hitAccuracy", "reactionTime", "comboCount"
            ]
            importances = rf_model.feature_importances_
            indices = np.argsort(importances)[::-1][:10]
            print("[TRAINING] Top 10 important features:")
            for i in indices:
                print(f"  - {feature_names[i]}: {importances[i]:.4f}")

            return True

        except Exception as e:
            print(f"[TRAINING] Error: {e}")
            import traceback
            traceback.print_exc()
            return False


def predict_with_supervised(samples: List[Dict]) -> Dict:
    """Use supervised model to predict if player is cheating"""
    global supervised_models, supervised_scaler

    if not supervised_models or supervised_scaler is None:
        return {"available": False, "cheat_probability": 0.0}

    if len(samples) < 5:
        return {"available": False, "cheat_probability": 0.0}

    try:
        # Extract features from all samples
        features_list = [extract_features_for_training(s) for s in samples]
        X = np.array(features_list)
        X = np.nan_to_num(X, nan=0.0, posinf=0.0, neginf=0.0)
        X_scaled = supervised_scaler.transform(X)

        # Get predictions from all models
        predictions = {}
        probabilities = []

        for name, model in supervised_models.items():
            # Predict probability of being a cheater
            proba = model.predict_proba(X_scaled)[:, 1]  # Probability of class 1 (cheater)
            avg_proba = float(np.mean(proba))
            predictions[name] = avg_proba
            probabilities.append(avg_proba)

        # Ensemble: average of all models
        ensemble_probability = float(np.mean(probabilities))

        return {
            "available": True,
            "cheat_probability": ensemble_probability,
            "model_predictions": predictions,
            "model_accuracy": model_accuracy,
            "samples_analyzed": len(samples),
        }

    except Exception as e:
        print(f"[PREDICT] Error: {e}")
        return {"available": False, "cheat_probability": 0.0, "error": str(e)}


def add_labeled_sample(sample: Dict, label: str, player_hash: str):
    """Add a labeled sample for training"""
    global labeled_samples

    if label not in ["cheat", "legit"]:
        return

    # Add metadata
    sample["_label"] = label
    sample["_player_hash"] = player_hash
    sample["_timestamp"] = time.time()

    labeled_samples[label].append(sample)

    # Keep max 10000 samples per class (remove oldest)
    if len(labeled_samples[label]) > 10000:
        labeled_samples[label] = labeled_samples[label][-10000:]

    print(f"[LABELED] Added {label} sample. Total: {len(labeled_samples['cheat'])} cheat, {len(labeled_samples['legit'])} legit")

    # Check if we should retrain
    total = len(labeled_samples["cheat"]) + len(labeled_samples["legit"])
    time_since_training = time.time() - last_training_time

    if total >= MIN_LABELED_SAMPLES and time_since_training > RETRAIN_INTERVAL:
        # Train in background
        threading.Thread(target=train_supervised_model, daemon=True).start()


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
    """Extract feature vector from a sample (expanded with new features)"""
    return np.array([
        # Original features
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
        # New features
        sample.aimAcceleration,
        sample.aimJitter,
        sample.targetSwitchSpeed,
        sample.avgAimAngle,
        sample.aimAngleVariance,
        sample.strafeFrequency,
        sample.strafeConsistency,
        sample.clickEntropy,
        sample.hitAccuracy,
        sample.reactionTime,
        sample.comboCount,
    ])


def check_signature(samples: List[BehaviorSample], signature: Dict) -> float:
    """Check if samples match a cheat signature. Returns confidence 0-1."""
    conditions = signature.get("conditions", {})
    if not conditions:
        return 0.0

    matches = 0
    total_conditions = len(conditions)

    # Aggregate values from samples
    avg_cps = np.mean([s.cps for s in samples]) if samples else 0
    avg_pitch_var = np.mean([s.pitchVariance for s in samples]) if samples else 0
    avg_yaw_var = np.mean([s.yawVariance for s in samples]) if samples else 0
    avg_aim_angle_var = np.mean([s.aimAngleVariance for s in samples]) if samples else 0
    avg_target_switch = np.mean([s.targetSwitchSpeed for s in samples if s.targetSwitchSpeed > 0]) if samples else 0
    max_combo = max([s.comboCount for s in samples]) if samples else 0
    avg_click_entropy = np.mean([s.clickEntropy for s in samples]) if samples else 1.0
    avg_click_var = np.mean([s.clickIntervalVariance for s in samples]) if samples else 0
    avg_snap_count = np.mean([s.snapCount for s in samples]) if samples else 0
    avg_aim_accel = np.mean([s.aimAcceleration for s in samples]) if samples else 0
    avg_aim_angle = np.mean([s.avgAimAngle for s in samples]) if samples else 0
    avg_aim_jitter = np.mean([s.aimJitter for s in samples]) if samples else 0
    avg_hit_accuracy = np.mean([s.hitAccuracy for s in samples if s.hitAccuracy > 0]) if samples else 0
    avg_speed = np.mean([s.avgSpeed for s in samples]) if samples else 0
    max_speed = max([s.maxSpeed for s in samples]) if samples else 0
    speed_var = np.var([s.avgSpeed for s in samples]) if samples else 0
    avg_air_ground = np.mean([s.airGroundRatio for s in samples]) if samples else 0
    avg_air_ticks = np.mean([s.airTicks for s in samples]) if samples else 0
    avg_delta_y = np.mean([s.avgDeltaY for s in samples]) if samples else 0
    max_delta_y = max([s.maxDeltaY for s in samples]) if samples else 0
    avg_kb_compliance = np.mean([s.knockbackComplianceRate for s in samples if s.knockbackComplianceRate > 0]) if samples else 1.0
    total_blocks = sum([s.blocksPlaced for s in samples])
    avg_place_speed = np.mean([s.avgPlaceSpeed for s in samples]) if samples else 0
    total_impossible = sum([s.impossiblePlacements for s in samples])
    place_angle_var = np.mean([s.placeAngleVariance for s in samples]) if samples else 0
    avg_pps = np.mean([s.pps for s in samples]) if samples else 0
    avg_packet_var = np.mean([s.packetTimingVariance for s in samples]) if samples else 0

    # Check each condition
    for key, value in conditions.items():
        matched = False

        if key == "cps_min" and avg_cps >= value:
            matched = True
        elif key == "cps_max" and avg_cps <= value:
            matched = True
        elif key == "pitch_variance_max" and avg_pitch_var <= value:
            matched = True
        elif key == "yaw_variance_max" and avg_yaw_var <= value:
            matched = True
        elif key == "aim_angle_variance_max" and avg_aim_angle_var <= value:
            matched = True
        elif key == "target_switch_speed_max" and avg_target_switch > 0 and avg_target_switch <= value:
            matched = True
        elif key == "combo_min" and max_combo >= value:
            matched = True
        elif key == "click_entropy_max" and avg_click_entropy <= value:
            matched = True
        elif key == "click_interval_variance_max" and avg_click_var <= value:
            matched = True
        elif key == "snap_count_min" and avg_snap_count >= value:
            matched = True
        elif key == "aim_acceleration_min" and avg_aim_accel >= value:
            matched = True
        elif key == "aim_angle_max" and avg_aim_angle <= value:
            matched = True
        elif key == "aim_jitter_max" and avg_aim_jitter <= value:
            matched = True
        elif key == "hit_accuracy_min" and avg_hit_accuracy >= value:
            matched = True
        elif key == "avg_speed_min" and avg_speed >= value:
            matched = True
        elif key == "max_speed_min" and max_speed >= value:
            matched = True
        elif key == "speed_variance_max" and speed_var <= value:
            matched = True
        elif key == "air_ground_ratio_min" and avg_air_ground >= value:
            matched = True
        elif key == "air_ticks_min" and avg_air_ticks >= value:
            matched = True
        elif key == "avg_delta_y_min" and avg_delta_y >= value:
            matched = True
        elif key == "avg_delta_y_max" and avg_delta_y <= value:
            matched = True
        elif key == "max_delta_y_min" and max_delta_y >= value:
            matched = True
        elif key == "knockback_compliance_max" and avg_kb_compliance <= value:
            matched = True
        elif key == "knockback_compliance_min" and avg_kb_compliance >= value:
            matched = True
        elif key == "blocks_placed_min" and total_blocks >= value:
            matched = True
        elif key == "avg_place_speed_min" and avg_place_speed >= value:
            matched = True
        elif key == "impossible_placements_min" and total_impossible >= value:
            matched = True
        elif key == "place_angle_variance_max" and place_angle_var <= value:
            matched = True
        elif key == "pps_min" and avg_pps >= value:
            matched = True
        elif key == "pps_max" and avg_pps <= value:
            matched = True
        elif key == "packet_timing_variance_max" and avg_packet_var <= value:
            matched = True
        elif key == "packet_timing_variance_min" and avg_packet_var >= value:
            matched = True

        if matched:
            matches += 1

    # Calculate match ratio
    if total_conditions == 0:
        return 0.0

    match_ratio = matches / total_conditions

    # Return weighted confidence: match_ratio * signature_confidence
    base_confidence = signature.get("confidence", 0.5)
    return match_ratio * base_confidence


def match_signatures(samples: List[BehaviorSample]) -> Dict:
    """Match samples against all known cheat signatures."""
    if len(samples) < 5:
        return {"matched": [], "highest_confidence": 0, "category": "none"}

    matched_signatures = []
    highest_confidence = 0
    highest_category = "none"
    highest_name = ""

    for sig_id, signature in CHEAT_SIGNATURES.items():
        confidence = check_signature(samples, signature)
        if confidence >= 0.5:  # Only report if 50%+ confident
            matched_signatures.append({
                "id": sig_id,
                "name": signature.get("name", sig_id),
                "confidence": confidence,
                "category": signature.get("category", "unknown")
            })
            if confidence > highest_confidence:
                highest_confidence = confidence
                highest_category = signature.get("category", "unknown")
                highest_name = signature.get("name", sig_id)

    return {
        "matched": matched_signatures,
        "highest_confidence": highest_confidence,
        "highest_category": highest_category,
        "highest_name": highest_name
    }


def analyze_combat_sessions(samples: List[BehaviorSample]) -> Dict:
    """Analyze combat session data for suspicious patterns."""
    if len(samples) < 5:
        return {"suspicion": 0, "flags": [], "has_data": False}

    flags = []
    suspicion = 0

    # Aggregate combat stats
    win_rates = [s.combatWinRate for s in samples if s.activeCombatSessions > 0]
    accuracies = [s.hitAccuracy for s in samples if s.hitAccuracy > 0]
    combos = [s.comboCount for s in samples]
    reaction_times = [s.reactionTime for s in samples if s.reactionTime > 0]
    kdrs = [s.combatKDR for s in samples if s.combatKDR > 0]

    # Win rate analysis
    if win_rates:
        avg_win_rate = np.mean(win_rates)
        if avg_win_rate >= COMBAT_THRESHOLDS["suspicious_win_rate"]:
            suspicion += 0.3
            flags.append(f"high_win_rate_{avg_win_rate:.0%}")

    # Hit accuracy analysis
    if accuracies:
        avg_accuracy = np.mean(accuracies)
        if avg_accuracy >= COMBAT_THRESHOLDS["suspicious_accuracy"]:
            suspicion += 0.35
            flags.append(f"high_accuracy_{avg_accuracy:.0%}")

    # Combo analysis
    max_combo = max(combos) if combos else 0
    if max_combo >= COMBAT_THRESHOLDS["suspicious_combo"]:
        suspicion += 0.25
        flags.append(f"long_combo_{max_combo}")

    # Reaction time analysis
    if reaction_times:
        avg_reaction = np.mean(reaction_times)
        if avg_reaction > 0 and avg_reaction < COMBAT_THRESHOLDS["suspicious_reaction_time"]:
            suspicion += 0.4
            flags.append(f"fast_reaction_{avg_reaction:.0f}ms")

    # KDR analysis
    if kdrs:
        avg_kdr = np.mean(kdrs)
        if avg_kdr >= COMBAT_THRESHOLDS["suspicious_kdr"]:
            suspicion += 0.2
            flags.append(f"high_kdr_{avg_kdr:.1f}")

    # Click pattern analysis (entropy)
    entropies = [s.clickEntropy for s in samples if s.clickEntropy > 0]
    if entropies:
        avg_entropy = np.mean(entropies)
        if avg_entropy < 0.4:  # Very consistent clicking = bot
            suspicion += 0.3
            flags.append(f"low_click_entropy_{avg_entropy:.2f}")

    # Aim consistency analysis
    aim_vars = [s.aimAngleVariance for s in samples if s.avgAimAngle > 0]
    if aim_vars:
        avg_aim_var = np.mean(aim_vars)
        if avg_aim_var < 5.0:  # Very consistent aim = aimbot
            suspicion += 0.25
            flags.append(f"consistent_aim_{avg_aim_var:.1f}")

    return {
        "suspicion": min(1.0, suspicion),
        "flags": flags,
        "has_data": len(win_rates) > 0 or len(accuracies) > 0
    }


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
    # Use the signature database for matching
    signature_result = match_signatures(samples)
    pattern_match_score = signature_result["highest_confidence"]

    if signature_result["matched"]:
        for sig in signature_result["matched"]:
            pattern_reasons.append(f"{sig['id']}_{sig['confidence']:.0%}")

    # Fallback to basic pattern matching if no signatures matched
    if pattern_match_score < 0.3:
        # High CPS + Low pitch variance = Killaura
        high_cps_samples = [s for s in samples if s.cps > 15]
        if len(high_cps_samples) > 3:
            avg_pitch_var = np.mean([s.pitchVariance for s in high_cps_samples])
            if avg_pitch_var < 2.0:
                pattern_match_score = max(pattern_match_score, 0.5)
                pattern_reasons.append("killaura_signature")

        # Speed > 0.5 consistently = Speed hack
        fast_samples = [s for s in samples if s.maxSpeed > 0.5 and not s.flying]
        if len(fast_samples) > len(samples) * 0.3:
            pattern_match_score = max(pattern_match_score, 0.4)
            pattern_reasons.append("speed_hack_signature")

    # === COMBAT SESSION ANALYSIS ===
    combat_result = analyze_combat_sessions(samples)
    if combat_result["has_data"]:
        # Boost suspicion based on combat analysis
        combat_suspicion = combat_result["suspicion"]
        if combat_suspicion > 0.3:
            # Weight combat analysis into temporal confidence
            temporal_confidence = max(temporal_confidence, temporal_confidence * 0.7 + combat_suspicion * 0.3)
            pattern_reasons.extend(combat_result["flags"])

    pattern_match_score = min(1.0, pattern_match_score)

    # === PHYSICS SIMULATION (Blatant Detection) ===
    physics_result = {"violations": [], "confidence": 0.0, "is_blatant": False}
    try:
        # Run physics check on latest samples
        for sample in samples[-10:]:
            sample_dict = sample.dict() if hasattr(sample, 'dict') else sample
            check = physics_check(sample_dict)
            if check["violations"]:
                physics_result["violations"].extend(check["violations"])
                physics_result["confidence"] = max(physics_result["confidence"], check["confidence"])
                physics_result["is_blatant"] = physics_result["is_blatant"] or check["is_blatant"]

        if physics_result["is_blatant"]:
            temporal_confidence = max(temporal_confidence, physics_result["confidence"])
            pattern_reasons.append(f"physics_blatant_{physics_result['confidence']:.0%}")
            print(f"[PHYSICS] Blatant detected: {physics_result['violations']}")
    except Exception as e:
        print(f"[PHYSICS] Error: {e}")

    # === LSTM NEURAL NETWORK (Time Series) ===
    lstm_result = {"probability": 0.0, "available": False}
    try:
        sample_dicts = [s.dict() if hasattr(s, 'dict') else s for s in samples]
        lstm_result = lstm_analyze(sample_dicts)

        if lstm_result.get("available", False):
            lstm_prob = lstm_result.get("probability", 0)
            if lstm_prob > 0.6:
                temporal_confidence = max(temporal_confidence, lstm_prob * 0.7)
                pattern_reasons.append(f"lstm_{lstm_prob:.0%}")
                print(f"[LSTM] High probability: {lstm_prob:.0%}")
    except Exception as e:
        print(f"[LSTM] Error: {e}")

    # === PACKET SEQUENCE ANALYSIS ===
    packet_result = {"detected": [], "confidence": 0.0}
    try:
        sample_dicts = [s.dict() if hasattr(s, 'dict') else s for s in samples]
        packet_result = analyze_packet_patterns(sample_dicts)

        if packet_result.get("detected"):
            packet_conf = packet_result.get("confidence", 0)
            if packet_conf > 0.5:
                temporal_confidence = max(temporal_confidence, packet_conf * 0.6)
                for detection in packet_result["detected"]:
                    pattern_reasons.append(f"packet_{detection['type']}_{detection['confidence']:.0%}")
                print(f"[PACKET] Detected: {[d['type'] for d in packet_result['detected']]}")
    except Exception as e:
        print(f"[PACKET] Error: {e}")

    # === CHEAT CLIENT DETECTION ===
    client_result = {"detected": None, "confidence": 0.0, "candidates": []}
    try:
        sample_dicts = [s.dict() if hasattr(s, 'dict') else s for s in samples]
        client_result = detect_cheat_client(sample_dicts)

        if client_result.get("detected"):
            client_conf = client_result.get("confidence", 0)
            pattern_match_score = max(pattern_match_score, client_conf)
            pattern_reasons.append(f"client_{client_result['detected']}")
            print(f"[CLIENT] Detected: {client_result['detected']} ({client_conf:.0%})")
    except Exception as e:
        print(f"[CLIENT] Error: {e}")

    # === SUPERVISED MODEL PREDICTION ===
    # Use trained model from labeled data (local checks)
    supervised_result = predict_with_supervised([s.dict() for s in samples])
    supervised_probability = 0.0

    if supervised_result.get("available", False):
        supervised_probability = supervised_result.get("cheat_probability", 0.0)
        print(f"[SUPERVISED] Prediction: {supervised_probability:.2%} cheat probability")

        # If supervised model is confident, boost temporal confidence
        if supervised_probability > 0.7:
            temporal_confidence = max(temporal_confidence, supervised_probability * 0.8)
            pattern_reasons.append(f"supervised_ml_{supervised_probability:.0%}")

    # Get LSTM probability for combined score
    lstm_prob = lstm_result.get("probability", 0.0) if lstm_result.get("available", False) else 0.0
    physics_conf = physics_result.get("confidence", 0.0)
    packet_conf = packet_result.get("confidence", 0.0)
    client_conf = client_result.get("confidence", 0.0)

    # Combined ML Score (weighted average of ALL methods)
    # Weights sum to 1.0
    ml_score = (
        anomaly_score * 0.10 +          # Unsupervised anomaly (Isolation Forest)
        temporal_confidence * 0.15 +     # Pattern-based temporal analysis
        pattern_match_score * 0.10 +     # Signature matching
        supervised_probability * 0.25 +  # Supervised learning (highest weight!)
        lstm_prob * 0.15 +               # LSTM neural network
        physics_conf * 0.10 +            # Physics simulation (blatant)
        packet_conf * 0.10 +             # Packet analysis
        client_conf * 0.05               # Cheat client detection
    )

    # Boost for blatant physics violations (immediate detection)
    if physics_result.get("is_blatant", False):
        ml_score = max(ml_score, physics_conf * 0.9)
        print(f"[ML] Blatant physics boost: {ml_score:.0%}")

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
        "supervised_probability": supervised_probability,
        "ml_score": ml_score,
        "has_enough_data": True,
        "reason": reason,
        "signature_matches": signature_result.get("matched", []),
        "combat_flags": combat_result.get("flags", []) if combat_result.get("has_data") else [],
        "supervised_available": supervised_result.get("available", False),
        "model_accuracy": supervised_result.get("model_accuracy", 0.0),
        # NEW: Physics Simulation
        "physics_violations": physics_result.get("violations", []),
        "physics_confidence": physics_result.get("confidence", 0.0),
        "is_blatant": physics_result.get("is_blatant", False),
        # NEW: LSTM Neural Network
        "lstm_probability": lstm_result.get("probability", 0.0),
        "lstm_available": lstm_result.get("available", False),
        "lstm_model": lstm_result.get("model", ""),
        # NEW: Packet Analysis
        "packet_detections": packet_result.get("detected", []),
        "packet_confidence": packet_result.get("confidence", 0.0),
        # NEW: Cheat Client Detection
        "detected_client": client_result.get("detected"),
        "client_confidence": client_result.get("confidence", 0.0),
        "client_candidates": client_result.get("candidates", []),
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
        # ML Training Stats
        "ml_training": {
            "cheat_samples": len(labeled_samples["cheat"]),
            "legit_samples": len(labeled_samples["legit"]),
            "model_trained": len(supervised_models) > 0,
            "model_accuracy": model_accuracy,
            "last_training": last_training_time,
        }
    }


@app.get("/api/v2/training/stats")
async def training_stats():
    """Get ML training statistics"""
    return {
        "labeled_samples": {
            "cheat": len(labeled_samples["cheat"]),
            "legit": len(labeled_samples["legit"]),
            "total": len(labeled_samples["cheat"]) + len(labeled_samples["legit"]),
        },
        "models": {
            "trained": len(supervised_models) > 0,
            "model_names": list(supervised_models.keys()),
            "accuracy": model_accuracy,
            "last_training_time": last_training_time,
            "minutes_since_training": (time.time() - last_training_time) / 60 if last_training_time > 0 else -1,
        },
        "thresholds": {
            "min_samples_for_training": MIN_LABELED_SAMPLES,
            "retrain_interval_seconds": RETRAIN_INTERVAL,
        }
    }


@app.post("/api/v2/training/trigger")
async def trigger_training(background_tasks: BackgroundTasks):
    """Manually trigger model retraining"""
    total = len(labeled_samples["cheat"]) + len(labeled_samples["legit"])

    if total < MIN_LABELED_SAMPLES:
        return {
            "status": "error",
            "message": f"Not enough samples. Have {total}, need {MIN_LABELED_SAMPLES}",
            "cheat_samples": len(labeled_samples["cheat"]),
            "legit_samples": len(labeled_samples["legit"]),
        }

    # Train in background
    background_tasks.add_task(train_supervised_model)

    return {
        "status": "training_started",
        "cheat_samples": len(labeled_samples["cheat"]),
        "legit_samples": len(labeled_samples["legit"]),
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

    # Determine if cloud should kick/ban
    should_kick = False
    kick_reason = ""

    # Get all confidence scores
    supervised_prob = analysis.get("supervised_probability", 0)
    ml_score = analysis.get("ml_score", 0)
    temporal_conf = analysis.get("temporal_confidence", 0)
    has_data = analysis.get("has_enough_data", False)

    # Combined confidence (old method)
    combined_confidence = (
        analysis.get("anomaly_score", 0) * 0.3 +
        temporal_conf * 0.5 +
        analysis.get("pattern_match_score", 0) * 0.2
    )

    # === BAN/KICK THRESHOLDS ===
    # Priority 1: Supervised ML Model (trained on labeled data)
    if has_data and supervised_prob >= 0.90:
        should_kick = True
        kick_reason = f"ML Detection ({supervised_prob:.0%})"
        print(f"[KICK] Supervised ML: {supervised_prob:.0%}")

    # Priority 2: Combined ML Score (all methods together)
    elif has_data and ml_score >= 0.85:
        should_kick = True
        kick_reason = f"Cloud Analysis ({ml_score:.0%})"
        print(f"[KICK] ML Score: {ml_score:.0%}")

    # Priority 3: High temporal + pattern confidence (old method)
    elif (combined_confidence >= 0.90 and has_data and temporal_conf >= 0.8):
        should_kick = True
        kick_reason = "Pattern Detection"
        print(f"[KICK] Pattern: combined={combined_confidence:.0%}, temporal={temporal_conf:.0%}")

    # Log high confidence but not kicking yet
    elif supervised_prob >= 0.7 or ml_score >= 0.7:
        print(f"[WATCH] High confidence: supervised={supervised_prob:.0%}, ml_score={ml_score:.0%}")

    # Determine detected cheat type from signatures
    detected_cheat_type = ""
    signature_matches = analysis.get("signature_matches", [])
    if signature_matches:
        # Get the highest confidence match
        best_match = max(signature_matches, key=lambda x: x.get("confidence", 0))
        detected_cheat_type = best_match.get("name", "")

    # Determine detected cheat type (from client or signature)
    if not detected_cheat_type and analysis.get("detected_client"):
        detected_cheat_type = analysis.get("detected_client")

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
        watch_closely=analysis.get("temporal_confidence", 0) > 0.5 or analysis.get("supervised_probability", 0) > 0.5,
        has_enough_data=analysis.get("has_enough_data", False),
        known_cheater=player.get("known_cheater", False),
        cross_server_violations=player.get("total_violations", 0),
        should_kick=should_kick,
        kick_reason=kick_reason,
        signature_matches=signature_matches,
        combat_flags=analysis.get("combat_flags", []),
        detected_cheat_type=detected_cheat_type,
        supervised_probability=analysis.get("supervised_probability", 0),
        ml_score=analysis.get("ml_score", 0),
        supervised_available=analysis.get("supervised_available", False),
        model_accuracy=analysis.get("model_accuracy", 0),
        # NEW: Physics Simulation
        physics_violations=analysis.get("physics_violations", []),
        physics_confidence=analysis.get("physics_confidence", 0),
        is_blatant=analysis.get("is_blatant", False),
        # NEW: LSTM
        lstm_probability=analysis.get("lstm_probability", 0),
        lstm_available=analysis.get("lstm_available", False),
        # NEW: Packet Analysis
        packet_detections=analysis.get("packet_detections", []),
        packet_confidence=analysis.get("packet_confidence", 0),
        # NEW: Cheat Client
        detected_client=analysis.get("detected_client"),
        client_confidence=analysis.get("client_confidence", 0),
        client_candidates=analysis.get("client_candidates", []),
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
async def submit_training_label(data: TrainingLabel, background_tasks: BackgroundTasks):
    """Submit a training label (legit/cheat) for a player with samples for ML training"""
    player = get_or_create_player(data.player_hash)

    player["labels"].append({
        "label": data.label,
        "server_id": data.server_id,
        "playtime": data.playtime_minutes,
        "timestamp": data.timestamp,
        "check_name": data.check_name,
    })

    # === ADD SAMPLES FOR SUPERVISED LEARNING ===
    samples_added = 0
    if data.samples:
        for sample in data.samples:
            add_labeled_sample(sample, data.label, data.player_hash)
            samples_added += 1
    else:
        # If no samples provided, use player's stored samples
        stored_samples = player_samples.get(data.player_hash, [])
        # Use last 20 samples for labeling
        for sample in stored_samples[-20:]:
            add_labeled_sample(sample, data.label, data.player_hash)
            samples_added += 1

    # If marked as legit with significant playtime, increase trust
    if data.label == "legit" and data.playtime_minutes > 10:
        player["trust_score"] = min(100, player.get("trust_score", 50) + 2)

    # If marked as cheat with high confidence, decrease trust
    if data.label == "cheat" and data.confidence > 0.7:
        player["trust_score"] = max(0, player.get("trust_score", 50) - 5)

    return {
        "status": "ok",
        "samples_added": samples_added,
        "total_cheat_samples": len(labeled_samples["cheat"]),
        "total_legit_samples": len(labeled_samples["legit"]),
        "model_accuracy": model_accuracy,
    }


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


# ==================== MISSING V1 ENDPOINTS (plugin compatibility) ====================

class ViolationReport(BaseModel):
    uuid: str
    check_type: str
    severity: float = 5.0
    details: str = ""
    server_id: str = "unknown"


class TrainingData(BaseModel):
    uuid: str
    data_type: str
    features: str  # JSON string
    is_legit: bool
    server_id: str = "unknown"


class ConfidenceRequest(BaseModel):
    uuid: str
    check_type: str
    features: str  # JSON string
    server_id: str = "unknown"


class BehaviorSampleRequest(BaseModel):
    uuid: str
    data_type: str
    features: str  # JSON string
    server_id: str = "unknown"
    timestamp: int = 0
    label: Optional[str] = None


@app.post("/api/violation")
async def report_violation_v1(report: ViolationReport):
    """V1 compat: Report a violation"""
    player_hash = hashlib.sha256(report.uuid.encode()).hexdigest()[:16]
    player = get_or_create_player(player_hash)

    player["total_violations"] = player.get("total_violations", 0) + 1
    player["last_violation"] = time.time()
    player["last_check"] = report.check_type

    # Adjust trust score
    player["trust_score"] = max(0, player.get("trust_score", 50) - report.severity * 2)

    global_stats["total_violations"] += 1

    if report.check_type in ["cheat", "suspicious"]:
        labeled_samples["cheat"].append({
            "check": report.check_type,
            "details": report.details,
            "timestamp": time.time()
        })

    return {"success": True, "trust_score": player["trust_score"]}


@app.post("/api/training")
async def submit_training_data(data: TrainingData):
    """V1 compat: Submit labeled training data"""
    try:
        features = json.loads(data.features)
    except Exception:
        features = {}

    label = "legit" if data.is_legit else "cheat"
    labeled_samples[label].append({
        "data_type": data.data_type,
        "features": features,
        "timestamp": time.time()
    })

    return {"success": True, "label": label}


@app.post("/api/ml/sample")
async def submit_ml_sample(sample: BehaviorSampleRequest):
    """Submit a behavior sample for ML learning"""
    try:
        features = json.loads(sample.features)
    except Exception:
        features = {}

    player_hash = hashlib.sha256(sample.uuid.encode()).hexdigest()[:16]

    entry = {
        "data_type": sample.data_type,
        "features": features,
        "timestamp": sample.timestamp or time.time(),
        "server_id": sample.server_id
    }

    if sample.label:
        labeled_samples.get(sample.label, labeled_samples["legit"]).append(entry)
    else:
        player_samples[player_hash].append(entry)
        if len(player_samples[player_hash]) > 500:
            player_samples[player_hash] = player_samples[player_hash][-500:]

    global_stats["total_samples"] += 1

    return {"success": True, "label": sample.label}


@app.post("/api/ml/confidence")
async def get_ml_confidence(req: ConfidenceRequest):
    """Get ML confidence score for a specific check type"""
    player_hash = hashlib.sha256(req.uuid.encode()).hexdigest()[:16]
    samples = player_samples.get(player_hash, [])

    if len(samples) < MIN_SAMPLES_FOR_ANALYSIS:
        return {
            "confidence": 0.0,
            "has_enough_data": False,
            "reason": f"Need {MIN_SAMPLES_FOR_ANALYSIS} samples, have {len(samples)}"
        }

    # Use anomaly detection on recent samples
    confidence = 0.0
    try:
        features = json.loads(req.features)
        check_type = req.check_type.lower()

        # Packet-specific confidence
        if "packet" in check_type or "timer" in check_type or "blink" in check_type:
            # Analyze packet patterns from recent samples
            packet_analysis = analyze_packet_patterns([s.get("features", {}) for s in samples[-20:]])
            confidence = packet_analysis.get("confidence", 0.0)
        else:
            # Generic anomaly score
            behavior_samples_obj = []
            for s in samples[-30:]:
                f = s.get("features", {})
                if isinstance(f, dict):
                    behavior_samples_obj.append(BehaviorSample(**{k: v for k, v in f.items()
                                                                   if k in BehaviorSample.__fields__}))
            if behavior_samples_obj:
                analysis = analyze_samples(behavior_samples_obj)
                confidence = analysis.get("anomaly_score", 0.0)

    except Exception as e:
        confidence = 0.0

    return {
        "confidence": min(1.0, confidence),
        "has_enough_data": True
    }


@app.post("/api/player/check")
async def check_player_v1(request: Request):
    """V1 compat: Check a player and return trust score"""
    try:
        body = await request.json()
        uuid = body.get("uuid", "")
        check_type = body.get("check_type", "")
        severity = float(body.get("severity", 5.0))
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid request body")

    player_hash = hashlib.sha256(uuid.encode()).hexdigest()[:16]
    player = get_or_create_player(player_hash)
    trust_score = player.get("trust_score", DEFAULT_TRUST_SCORE)

    risk_level = "low" if trust_score >= 70 else "medium" if trust_score >= 40 else "high"
    should_kick = trust_score < 20 and severity > 8

    return {
        "trust_score": trust_score,
        "risk_level": risk_level,
        "should_kick": should_kick,
        "recommendation": f"Trust: {trust_score:.0f}/100"
    }


# ==================== AUTO-UPDATE SYSTEM ====================
# Allows plugins to update themselves via /gac update

PLUGIN_VERSION = "1.0.0"
PLUGIN_DIR = os.path.join(os.path.dirname(__file__), "plugins")
PLUGIN_FILE = os.path.join(PLUGIN_DIR, "gac.jar")

# Ensure plugin directory exists
os.makedirs(PLUGIN_DIR, exist_ok=True)


class PluginUpload(BaseModel):
    version: str
    changelog: str = ""


@app.get("/api/v2/update/version")
async def get_plugin_version():
    """Get current plugin version and info"""
    jar_exists = os.path.exists(PLUGIN_FILE)
    jar_size = os.path.getsize(PLUGIN_FILE) if jar_exists else 0
    jar_modified = os.path.getmtime(PLUGIN_FILE) if jar_exists else 0

    return {
        "version": PLUGIN_VERSION,
        "available": jar_exists,
        "size": jar_size,
        "modified": datetime.fromtimestamp(jar_modified).isoformat() if jar_exists else None,
        "download_url": "/api/v2/update/download"
    }


@app.get("/api/v2/update/download")
async def download_plugin():
    """Download the latest plugin JAR"""
    from fastapi.responses import FileResponse

    if not os.path.exists(PLUGIN_FILE):
        raise HTTPException(status_code=404, detail="Plugin JAR not available")

    return FileResponse(
        PLUGIN_FILE,
        media_type="application/java-archive",
        filename="gac.jar"
    )


@app.post("/api/v2/update/upload")
async def upload_plugin(
    request: Request,
    x_admin_key: str = Header(None)
):
    """
    Upload a new plugin JAR (requires admin key)

    Usage with curl:
    curl -X POST "http://your-server:17865/api/v2/update/upload" \
         -H "X-Admin-Key: your-admin-key" \
         -H "X-Plugin-Version: 1.0.1" \
         --data-binary @target/gac-1.0-SNAPSHOT.jar

    Or use the upload script: ./upload_plugin.sh
    """
    global PLUGIN_VERSION

    # Check admin key (set GAC_ADMIN_KEY env variable)
    admin_key = os.environ.get("GAC_ADMIN_KEY", "gac-admin-secret")
    if x_admin_key != admin_key:
        raise HTTPException(status_code=403, detail="Invalid admin key")

    # Get version from header
    new_version = request.headers.get("X-Plugin-Version", PLUGIN_VERSION)

    # Read JAR data
    jar_data = await request.body()
    if len(jar_data) < 1000:
        raise HTTPException(status_code=400, detail="Invalid JAR file (too small)")

    # Verify it's a valid JAR (starts with PK zip signature)
    if jar_data[:2] != b'PK':
        raise HTTPException(status_code=400, detail="Invalid JAR file (not a ZIP/JAR)")

    # Save the JAR
    with open(PLUGIN_FILE, "wb") as f:
        f.write(jar_data)

    PLUGIN_VERSION = new_version

    print(f"[UPDATE] New plugin uploaded: v{new_version} ({len(jar_data)} bytes)")

    return {
        "success": True,
        "version": new_version,
        "size": len(jar_data),
        "message": f"Plugin v{new_version} uploaded successfully"
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 17865))
    uvicorn.run(app, host="0.0.0.0", port=port)
