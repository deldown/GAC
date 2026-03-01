package de.geffeniuse.gac.cloud;

import java.util.ArrayList;
import java.util.List;

/**
 * BehaviorSample - Ein einzelnes Verhaltens-Sample über einen kurzen Zeitraum
 *
 * Wird alle paar Sekunden erfasst und enthält statistische Features
 * sowie Rohsequenzen für LSTM/Autoencoder Analyse in der Cloud.
 */
public class BehaviorSample {

    // Timestamps
    public final long timestamp;
    public final long sessionDuration; // Wie lange Spieler schon online

    // === COMBAT FEATURES ===
    public double avgHitDistance;       // Durchschnittliche Hit-Distanz
    public double maxHitDistance;       // Maximale Hit-Distanz
    public double hitDistanceVariance;  // Varianz der Hit-Distanzen
    public int hitCount;                // Anzahl Hits im Sample
    public double avgHitTiming;         // Durchschnittliche Zeit zwischen Hits (ms)
    public double hitTimingVariance;    // Varianz des Hit-Timings
    public int cps;                     // Clicks per Second
    public double cpsVariance;          // CPS Varianz über Zeit

    // === MOVEMENT FEATURES ===
    public double avgSpeed;             // Durchschnittliche XZ-Geschwindigkeit
    public double maxSpeed;             // Maximale Geschwindigkeit
    public double speedVariance;        // Geschwindigkeits-Varianz
    public double avgDeltaY;            // Durchschnittliche vertikale Bewegung
    public double maxDeltaY;            // Maximaler Y-Delta
    public int airTicks;                // Ticks in der Luft
    public int groundTicks;             // Ticks am Boden
    public double airGroundRatio;       // Verhältnis Luft/Boden

    // === ROTATION FEATURES ===
    public double avgYawChange;         // Durchschnittliche Yaw-Änderung
    public double avgPitchChange;       // Durchschnittliche Pitch-Änderung
    public double yawVariance;          // Yaw-Varianz (niedrig = Aimbot)
    public double pitchVariance;        // Pitch-Varianz
    public double rotationSmoothness;   // Wie "smooth" sind Rotationen (0-1)
    public int snapCount;               // Anzahl plötzlicher Snaps (>30 Grad)

    // === ADVANCED AIM FEATURES ===
    public double aimAcceleration;      // Wie schnell ändert sich die Aim-Geschwindigkeit
    public double aimJitter;            // Kleine Mikro-Bewegungen im Aim
    public double targetSwitchSpeed;    // Durchschnittliche Zeit für Target-Wechsel (ms)
    public int targetSwitchCount;       // Anzahl Target-Wechsel
    public double avgAimAngle;          // Durchschnittlicher Winkel zum Ziel
    public double aimAngleVariance;     // Varianz des Aim-Winkels

    // === STRAFE/MOVEMENT PATTERNS ===
    public int strafeCount;             // Anzahl A-D Wechsel
    public double strafeFrequency;      // Strafe-Frequenz (Hz)
    public double strafeConsistency;    // Wie konstant sind die Strafes (0-1)
    public double wTapCount;            // Anzahl W-Tap Patterns
    public double sprintResetCount;     // Anzahl Sprint-Resets

    // === CLICK PATTERN FEATURES ===
    public double avgClickInterval;     // Durchschnittliche Zeit zwischen Clicks
    public double clickIntervalVariance; // Varianz der Click-Intervalle
    public double clickEntropy;         // Entropie der Click-Pattern (niedrig = Bot)
    public int doubleClickCount;        // Anzahl Double-Clicks (<50ms)

    // === VELOCITY/KNOCKBACK FEATURES ===
    public double avgKnockbackTaken;    // Durchschnittlich genommenes Knockback
    public double knockbackVariance;    // Knockback-Varianz
    public double knockbackComplianceRate; // Wie oft wird Knockback korrekt genommen (0-1)

    // === PACKET FEATURES ===
    public int pps;                     // Packets per Second
    public double packetTimingVariance; // Varianz der Packet-Timings
    public int duplicatePackets;        // Anzahl Duplicate-Packets
    public int invalidPackets;          // Anzahl invalider Packets

    // === BLOCK/SCAFFOLD FEATURES ===
    public int blocksPlaced;            // Blocks platziert im Zeitraum
    public double avgPlaceSpeed;        // Durchschnittliche Platzier-Geschwindigkeit
    public double placeAngleVariance;   // Varianz der Platzier-Winkel
    public int impossiblePlacements;    // Anzahl "unmöglicher" Platzierungen

    // === RAW SEQUENCES (für LSTM/Autoencoder) ===
    public List<Double> speedSequence = new ArrayList<>();
    public List<Double> verticalSequence = new ArrayList<>();
    public List<Float> yawSequence = new ArrayList<>();
    public List<Float> pitchSequence = new ArrayList<>();
    public List<Long> hitTimingSequence = new ArrayList<>();
    public List<Double> hitDistanceSequence = new ArrayList<>();
    public List<Long> clickIntervalSequence = new ArrayList<>();
    public List<Double> aimAngleSequence = new ArrayList<>();
    public List<Long> targetSwitchTimeSequence = new ArrayList<>();

    // === CONTEXT ===
    public int ping;
    public double serverTps;
    public boolean sprinting;
    public boolean sneaking;
    public boolean flying;
    public boolean gliding;
    public boolean inWater;
    public boolean inLava;
    public String gamemode;

    // === LOCAL CHECK DATA ===
    public int localViolations;         // Lokale Check-Violations in diesem Sample
    public String violationTypes;       // Welche Checks haben ausgelöst

    // === COMBAT SESSION DATA ===
    public int activeCombatSessions;    // Anzahl aktive Kämpfe
    public double combatWinRate;        // Win-Rate in aktuellen Kämpfen
    public double avgDamageDealt;       // Durchschnittlicher Schaden pro Hit
    public double avgDamageTaken;       // Durchschnittlicher Schaden erhalten
    public double combatKDR;            // Kill/Death Ratio in Session
    public double avgCombatDuration;    // Durchschnittliche Kampf-Dauer (ms)
    public double reactionTime;         // Durchschnittliche Reaktionszeit (ms)
    public int comboCount;              // Längste Combo (Hits ohne unterbrochen zu werden)
    public double hitAccuracy;          // Hit Accuracy (Hits / Swings)

    public BehaviorSample(long timestamp, long sessionDuration) {
        this.timestamp = timestamp;
        this.sessionDuration = sessionDuration;
    }

    /**
     * Berechne abgeleitete Features
     */
    public void calculateDerivedFeatures() {
        // Air/Ground Ratio
        if (groundTicks > 0) {
            airGroundRatio = (double) airTicks / groundTicks;
        } else {
            airGroundRatio = airTicks > 0 ? 100.0 : 0.0;
        }

        // Rotation Smoothness (niedrige Varianz + keine Snaps = smooth)
        if (yawVariance + pitchVariance > 0) {
            rotationSmoothness = 1.0 / (1.0 + (yawVariance + pitchVariance) / 100.0 + snapCount * 0.1);
        } else {
            rotationSmoothness = 1.0;
        }
    }
}
