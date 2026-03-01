package de.geffeniuse.gac.cloud;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * BehaviorCollector - Sammelt Verhaltensdaten über mehrere Minuten
 *
 * Polar-Philosophie:
 * - Sammelt kontinuierlich strukturierte Daten
 * - Nicht tickweise, sondern gebündelt (alle 5 Sekunden ein Sample)
 * - Aggregiert zu Zeitreihen für Cloud-Analyse
 * - Sendet asynchron an Cloud wenn genug Daten vorhanden
 */
public class BehaviorCollector {

    private final GACUser user;

    // === SAMPLE BUFFER ===
    // Speichert 5-15 Minuten an Samples (60-180 Samples bei 5s Intervall)
    private final Deque<BehaviorSample> sampleBuffer = new ConcurrentLinkedDeque<>();
    private static final int MAX_SAMPLES = 180; // 15 Minuten
    private static final int MIN_SAMPLES_FOR_UPLOAD = 12; // 1 Minute Minimum

    // === CURRENT SAMPLE ACCUMULATORS ===
    private long sampleStartTime;
    private long joinTime;

    // Hit Tracking
    private final List<Double> currentHitDistances = new ArrayList<>();
    private final List<Long> currentHitTimings = new ArrayList<>();
    private long lastHitTime = 0;
    private int currentHits = 0;

    // Movement Tracking
    private final List<Double> currentSpeeds = new ArrayList<>();
    private final List<Double> currentDeltaY = new ArrayList<>();
    private int currentAirTicks = 0;
    private int currentGroundTicks = 0;

    // Rotation Tracking
    private final List<Float> currentYawChanges = new ArrayList<>();
    private final List<Float> currentPitchChanges = new ArrayList<>();
    private int currentSnapCount = 0;

    // Velocity/Knockback Tracking
    private final List<Double> currentKnockbacks = new ArrayList<>();
    private int knockbackExpected = 0;
    private int knockbackReceived = 0;

    // Packet Tracking
    private int currentPackets = 0;
    private final List<Long> currentPacketTimings = new ArrayList<>();
    private long lastPacketTime = 0;
    private int currentDuplicates = 0;
    private int currentInvalid = 0;

    // Block Tracking
    private int currentBlocksPlaced = 0;
    private final List<Double> currentPlaceAngles = new ArrayList<>();
    private int currentImpossiblePlacements = 0;

    // CPS Tracking
    private final List<Integer> cpsHistory = new ArrayList<>();
    private int currentClicks = 0;
    private long lastCpsUpdate = 0;

    // Violation Tracking
    private int currentViolations = 0;
    private final Set<String> currentViolationTypes = new HashSet<>();

    // === NEW: Advanced Aim Tracking ===
    private final List<Double> currentAimAccelerations = new ArrayList<>();
    private float lastYawChange = 0;
    private float lastPitchChange = 0;
    private final List<Double> currentAimJitter = new ArrayList<>();
    private final List<Double> currentAimAngles = new ArrayList<>();

    // === NEW: Target Switch Tracking ===
    private UUID lastTargetId = null;
    private long lastTargetSwitchTime = 0;
    private final List<Long> currentTargetSwitchTimes = new ArrayList<>();
    private int currentTargetSwitches = 0;

    // === NEW: Strafe Pattern Tracking ===
    private int currentStrafeCount = 0;
    private long lastStrafeTime = 0;
    private final List<Long> currentStrafeTimings = new ArrayList<>();
    private boolean lastStrafeLeft = false;
    private int currentWTaps = 0;
    private int currentSprintResets = 0;
    private boolean wasSprinting = false;

    // === NEW: Click Pattern Tracking ===
    private final List<Long> currentClickIntervals = new ArrayList<>();
    private long lastClickTime = 0;
    private int currentDoubleClicks = 0;

    // === NEW: Combat Session Tracker ===
    private final CombatSessionTracker combatTracker;

    // Upload Tracking
    private long lastUploadTime = 0;
    private static final long UPLOAD_INTERVAL = 60000; // Upload alle 60 Sekunden
    private static final long SAMPLE_INTERVAL = 5000;  // Sample alle 5 Sekunden

    public BehaviorCollector(GACUser user) {
        this.user = user;
        this.joinTime = System.currentTimeMillis();
        this.sampleStartTime = System.currentTimeMillis();
        this.combatTracker = new CombatSessionTracker(user);
    }

    // ==================== DATA RECORDING ====================

    /**
     * Aufgerufen bei jedem Hit (einfache Version für Rückwärtskompatibilität)
     */
    public void recordHit(double distance) {
        recordHit(distance, null, 0, 0);
    }

    /**
     * Aufgerufen bei jedem Hit (erweiterte Version)
     */
    public void recordHit(double distance, org.bukkit.entity.Player target, double damage, double aimAngle) {
        currentHitDistances.add(distance);
        currentHits++;

        long now = System.currentTimeMillis();
        if (lastHitTime > 0) {
            currentHitTimings.add(now - lastHitTime);
        }
        lastHitTime = now;

        // Aim angle tracking
        currentAimAngles.add(aimAngle);

        // Target switch tracking
        if (target != null) {
            UUID targetId = target.getUniqueId();
            if (lastTargetId != null && !lastTargetId.equals(targetId)) {
                // Target gewechselt
                currentTargetSwitches++;
                if (lastTargetSwitchTime > 0) {
                    currentTargetSwitchTimes.add(now - lastTargetSwitchTime);
                }
                lastTargetSwitchTime = now;
            }
            lastTargetId = targetId;

            // Combat session tracking
            combatTracker.recordHitDealt(target, damage, distance, aimAngle);
        }
    }

    /**
     * Aufgerufen bei jeder Bewegung
     */
    public void recordMovement(double deltaXZ, double deltaY, boolean onGround) {
        currentSpeeds.add(deltaXZ);
        currentDeltaY.add(deltaY);

        if (onGround) {
            currentGroundTicks++;
        } else {
            currentAirTicks++;
        }
    }

    /**
     * Aufgerufen bei jeder Rotation
     */
    public void recordRotation(float deltaYaw, float deltaPitch) {
        currentYawChanges.add(deltaYaw);
        currentPitchChanges.add(deltaPitch);

        // Snap Detection (plötzliche große Bewegung)
        if (deltaYaw > 30 || deltaPitch > 20) {
            currentSnapCount++;
        }

        // === NEW: Aim Acceleration ===
        // Wie schnell ändert sich die Geschwindigkeit der Aim-Bewegung
        double yawAccel = Math.abs(deltaYaw - lastYawChange);
        double pitchAccel = Math.abs(deltaPitch - lastPitchChange);
        currentAimAccelerations.add(yawAccel + pitchAccel);
        lastYawChange = deltaYaw;
        lastPitchChange = deltaPitch;

        // === NEW: Aim Jitter ===
        // Kleine Mikro-Bewegungen (unter 2 Grad aber über 0.1)
        double totalDelta = Math.abs(deltaYaw) + Math.abs(deltaPitch);
        if (totalDelta > 0.1 && totalDelta < 2.0) {
            currentAimJitter.add(totalDelta);
        }
    }

    /**
     * Aufgerufen wenn Server Velocity sendet
     */
    public void recordVelocitySent(double strength) {
        knockbackExpected++;
    }

    /**
     * Aufgerufen wenn Spieler Knockback nimmt (gemessen an Bewegung)
     */
    public void recordVelocityTaken(double actualKnockback, double expectedKnockback) {
        knockbackReceived++;
        if (expectedKnockback > 0) {
            currentKnockbacks.add(actualKnockback / expectedKnockback);
        }
    }

    /**
     * Aufgerufen bei jedem Packet
     */
    public void recordPacket(boolean isDuplicate, boolean isInvalid) {
        currentPackets++;
        long now = System.currentTimeMillis();
        if (lastPacketTime > 0) {
            currentPacketTimings.add(now - lastPacketTime);
        }
        lastPacketTime = now;

        if (isDuplicate) currentDuplicates++;
        if (isInvalid) currentInvalid++;
    }

    /**
     * Aufgerufen bei jedem Klick
     */
    public void recordClick() {
        currentClicks++;
        long now = System.currentTimeMillis();

        // CPS Update jede Sekunde
        if (now - lastCpsUpdate >= 1000) {
            cpsHistory.add(currentClicks);
            if (cpsHistory.size() > 10) cpsHistory.remove(0);
            currentClicks = 0;
            lastCpsUpdate = now;
        }

        // === NEW: Click Interval Tracking ===
        if (lastClickTime > 0) {
            long interval = now - lastClickTime;
            currentClickIntervals.add(interval);

            // Double-Click Detection (unter 50ms)
            if (interval < 50) {
                currentDoubleClicks++;
            }
        }
        lastClickTime = now;

        // Combat reaction tracking
        combatTracker.recordReaction();
    }

    /**
     * Aufgerufen bei Strafe-Bewegung (A oder D gedrückt)
     */
    public void recordStrafe(boolean isLeft) {
        long now = System.currentTimeMillis();

        // Nur wenn Richtung wechselt
        if (lastStrafeLeft != isLeft) {
            currentStrafeCount++;

            if (lastStrafeTime > 0) {
                currentStrafeTimings.add(now - lastStrafeTime);
            }
            lastStrafeTime = now;
            lastStrafeLeft = isLeft;
        }
    }

    /**
     * Aufgerufen bei Sprint-Änderung
     */
    public void recordSprint(boolean isSprinting) {
        if (wasSprinting && !isSprinting) {
            currentSprintResets++;

            // W-Tap detection (Sprint reset während Bewegung)
            Player player = user.getPlayer();
            if (player != null && player.getVelocity().lengthSquared() > 0.01) {
                currentWTaps++;
            }
        }
        wasSprinting = isSprinting;
    }

    /**
     * Aufgerufen wenn Spieler Schaden nimmt
     */
    public void recordDamageTaken(org.bukkit.entity.Player attacker, double damage) {
        if (attacker != null) {
            combatTracker.recordHitTaken(attacker, damage);
        }
    }

    /**
     * Aufgerufen wenn Spieler jemanden tötet
     */
    public void recordKill(org.bukkit.entity.Player victim) {
        combatTracker.recordKill(victim);
    }

    /**
     * Aufgerufen wenn Spieler stirbt
     */
    public void recordDeath(org.bukkit.entity.Player killer) {
        combatTracker.recordDeath(killer);
    }

    /**
     * Aufgerufen bei jedem Swing (auch Miss)
     */
    public void recordSwing() {
        combatTracker.recordSwing();
    }

    /**
     * Aufgerufen bei Block-Platzierung
     */
    public void recordBlockPlace(double angle, boolean impossible) {
        currentBlocksPlaced++;
        currentPlaceAngles.add(angle);
        if (impossible) currentImpossiblePlacements++;
    }

    /**
     * Aufgerufen wenn ein lokaler Check auslöst
     */
    public void recordViolation(String checkName) {
        currentViolations++;
        currentViolationTypes.add(checkName);
    }

    // ==================== SAMPLE CREATION ====================

    /**
     * Tick - Aufgerufen jede Sekunde
     * Erstellt periodisch Samples und triggert Uploads
     */
    public void tick() {
        long now = System.currentTimeMillis();

        // Combat session tick
        combatTracker.tick();

        // Sample erstellen alle 5 Sekunden
        if (now - sampleStartTime >= SAMPLE_INTERVAL) {
            createSample();
            sampleStartTime = now;
        }

        // Upload alle 60 Sekunden (wenn genug Samples)
        if (now - lastUploadTime >= UPLOAD_INTERVAL && sampleBuffer.size() >= MIN_SAMPLES_FOR_UPLOAD) {
            triggerUpload();
            lastUploadTime = now;
        }
    }

    /**
     * Erstellt ein Sample aus den aktuellen Akkumulatoren
     */
    private void createSample() {
        Player player = user.getPlayer();
        if (player == null || !player.isOnline()) return;

        long now = System.currentTimeMillis();
        BehaviorSample sample = new BehaviorSample(now, now - joinTime);

        // === COMBAT ===
        sample.hitCount = currentHits;
        sample.avgHitDistance = average(currentHitDistances);
        sample.maxHitDistance = max(currentHitDistances);
        sample.hitDistanceVariance = variance(currentHitDistances);
        sample.avgHitTiming = averageLong(currentHitTimings);
        sample.hitTimingVariance = varianceLong(currentHitTimings);
        sample.hitTimingSequence = new ArrayList<>(currentHitTimings);
        sample.hitDistanceSequence = new ArrayList<>(currentHitDistances);

        // CPS
        sample.cps = cpsHistory.isEmpty() ? 0 : cpsHistory.get(cpsHistory.size() - 1);
        sample.cpsVariance = varianceInt(cpsHistory);

        // === MOVEMENT ===
        sample.avgSpeed = average(currentSpeeds);
        sample.maxSpeed = max(currentSpeeds);
        sample.speedVariance = variance(currentSpeeds);
        sample.avgDeltaY = average(currentDeltaY);
        sample.maxDeltaY = max(currentDeltaY);
        sample.airTicks = currentAirTicks;
        sample.groundTicks = currentGroundTicks;
        sample.speedSequence = new ArrayList<>(currentSpeeds);
        sample.verticalSequence = new ArrayList<>(currentDeltaY);

        // === ROTATION ===
        sample.avgYawChange = averageFloat(currentYawChanges);
        sample.avgPitchChange = averageFloat(currentPitchChanges);
        sample.yawVariance = varianceFloat(currentYawChanges);
        sample.pitchVariance = varianceFloat(currentPitchChanges);
        sample.snapCount = currentSnapCount;
        sample.yawSequence = new ArrayList<>(currentYawChanges);
        sample.pitchSequence = new ArrayList<>(currentPitchChanges);

        // === VELOCITY ===
        sample.avgKnockbackTaken = average(currentKnockbacks);
        sample.knockbackVariance = variance(currentKnockbacks);
        sample.knockbackComplianceRate = knockbackExpected > 0 ?
            (double) knockbackReceived / knockbackExpected : 1.0;

        // === PACKETS ===
        sample.pps = currentPackets;
        sample.packetTimingVariance = varianceLong(currentPacketTimings);
        sample.duplicatePackets = currentDuplicates;
        sample.invalidPackets = currentInvalid;

        // === BLOCKS ===
        sample.blocksPlaced = currentBlocksPlaced;
        sample.avgPlaceSpeed = currentBlocksPlaced / 5.0; // Blocks pro Sekunde
        sample.placeAngleVariance = variance(currentPlaceAngles);
        sample.impossiblePlacements = currentImpossiblePlacements;

        // === CONTEXT ===
        sample.ping = player.getPing();
        sample.serverTps = GAC.getTPS();
        sample.sprinting = player.isSprinting();
        sample.sneaking = player.isSneaking();
        sample.flying = player.isFlying();
        sample.gliding = player.isGliding();
        sample.inWater = player.isInWater();
        sample.inLava = player.getLocation().getBlock().getType().name().contains("LAVA");
        sample.gamemode = player.getGameMode().name();

        // === VIOLATIONS ===
        sample.localViolations = currentViolations;
        sample.violationTypes = String.join(",", currentViolationTypes);

        // === NEW: ADVANCED AIM FEATURES ===
        sample.aimAcceleration = average(currentAimAccelerations);
        sample.aimJitter = average(currentAimJitter);
        sample.targetSwitchCount = currentTargetSwitches;
        sample.targetSwitchSpeed = averageLong(currentTargetSwitchTimes);
        sample.avgAimAngle = average(currentAimAngles);
        sample.aimAngleVariance = variance(currentAimAngles);
        sample.aimAngleSequence = new ArrayList<>(currentAimAngles);
        sample.targetSwitchTimeSequence = new ArrayList<>(currentTargetSwitchTimes);

        // === NEW: STRAFE/MOVEMENT PATTERNS ===
        sample.strafeCount = currentStrafeCount;
        if (!currentStrafeTimings.isEmpty()) {
            sample.strafeFrequency = 1000.0 / averageLong(currentStrafeTimings); // Hz
            double strafeVar = varianceLong(currentStrafeTimings);
            double strafeMean = averageLong(currentStrafeTimings);
            sample.strafeConsistency = strafeMean > 0 ? 1.0 / (1.0 + strafeVar / (strafeMean * strafeMean)) : 0;
        }
        sample.wTapCount = currentWTaps;
        sample.sprintResetCount = currentSprintResets;

        // === NEW: CLICK PATTERN FEATURES ===
        sample.avgClickInterval = averageLong(currentClickIntervals);
        sample.clickIntervalVariance = varianceLong(currentClickIntervals);
        sample.doubleClickCount = currentDoubleClicks;
        sample.clickIntervalSequence = new ArrayList<>(currentClickIntervals);

        // Click Entropy (Shannon Entropy)
        if (currentClickIntervals.size() >= 5) {
            sample.clickEntropy = calculateEntropy(currentClickIntervals);
        }

        // === NEW: COMBAT SESSION DATA ===
        CombatSessionTracker.CombatStats combatStats = combatTracker.getStats();
        sample.activeCombatSessions = combatStats.activeCombatSessions;
        sample.combatWinRate = combatStats.combatWinRate;
        sample.avgDamageDealt = combatStats.avgDamageDealt;
        sample.avgDamageTaken = combatStats.avgDamageTaken;
        sample.combatKDR = combatStats.combatKDR;
        sample.avgCombatDuration = combatStats.avgCombatDuration;
        sample.reactionTime = combatStats.reactionTime;
        sample.comboCount = combatStats.longestCombo;
        sample.hitAccuracy = combatStats.hitAccuracy;

        // Derived Features berechnen
        sample.calculateDerivedFeatures();

        // Sample speichern
        sampleBuffer.addLast(sample);
        while (sampleBuffer.size() > MAX_SAMPLES) {
            sampleBuffer.removeFirst();
        }

        // Akkumulatoren resetten
        resetAccumulators();
    }

    private void resetAccumulators() {
        currentHitDistances.clear();
        currentHitTimings.clear();
        currentHits = 0;
        currentSpeeds.clear();
        currentDeltaY.clear();
        currentAirTicks = 0;
        currentGroundTicks = 0;
        currentYawChanges.clear();
        currentPitchChanges.clear();
        currentSnapCount = 0;
        currentKnockbacks.clear();
        knockbackExpected = 0;
        knockbackReceived = 0;
        currentPackets = 0;
        currentPacketTimings.clear();
        currentDuplicates = 0;
        currentInvalid = 0;
        currentBlocksPlaced = 0;
        currentPlaceAngles.clear();
        currentImpossiblePlacements = 0;
        currentViolations = 0;
        currentViolationTypes.clear();

        // === NEW: Reset new tracking variables ===
        currentAimAccelerations.clear();
        currentAimJitter.clear();
        currentAimAngles.clear();
        currentTargetSwitchTimes.clear();
        currentTargetSwitches = 0;
        currentStrafeCount = 0;
        currentStrafeTimings.clear();
        currentWTaps = 0;
        currentSprintResets = 0;
        currentClickIntervals.clear();
        currentDoubleClicks = 0;
    }

    // ==================== UPLOAD ====================

    private void triggerUpload() {
        // Cloud upload removed - samples are analyzed locally by LocalMLAnalyzer
        // LocalMLAnalyzer reads from sampleBuffer via getRecentSamples()
    }

    /**
     * Getter für aktuelle Sample-Anzahl (für Status)
     */
    public int getSampleCount() {
        return sampleBuffer.size();
    }

    /**
     * Getter für Session-Dauer
     */
    public long getSessionDuration() {
        return System.currentTimeMillis() - joinTime;
    }

    /**
     * Hole die letzten N Samples für Training/Labeling
     */
    public List<BehaviorSample> getRecentSamples(int count) {
        List<BehaviorSample> recent = new ArrayList<>();
        int skip = Math.max(0, sampleBuffer.size() - count);
        int i = 0;
        for (BehaviorSample sample : sampleBuffer) {
            if (i >= skip) {
                recent.add(sample);
            }
            i++;
        }
        return recent;
    }

    /**
     * Konvertiere Samples zu JSON für Cloud-Upload
     */
    public com.google.gson.JsonArray getSamplesAsJson(int count) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        com.google.gson.Gson gson = new com.google.gson.Gson();

        for (BehaviorSample sample : getRecentSamples(count)) {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("timestamp", sample.timestamp);
            obj.addProperty("sessionDuration", sample.sessionDuration);
            obj.addProperty("avgHitDistance", sample.avgHitDistance);
            obj.addProperty("maxHitDistance", sample.maxHitDistance);
            obj.addProperty("hitDistanceVariance", sample.hitDistanceVariance);
            obj.addProperty("hitCount", sample.hitCount);
            obj.addProperty("avgHitTiming", sample.avgHitTiming);
            obj.addProperty("hitTimingVariance", sample.hitTimingVariance);
            obj.addProperty("cps", sample.cps);
            obj.addProperty("cpsVariance", sample.cpsVariance);
            obj.addProperty("avgSpeed", sample.avgSpeed);
            obj.addProperty("maxSpeed", sample.maxSpeed);
            obj.addProperty("speedVariance", sample.speedVariance);
            obj.addProperty("avgDeltaY", sample.avgDeltaY);
            obj.addProperty("maxDeltaY", sample.maxDeltaY);
            obj.addProperty("airTicks", sample.airTicks);
            obj.addProperty("groundTicks", sample.groundTicks);
            obj.addProperty("airGroundRatio", sample.airGroundRatio);
            obj.addProperty("avgYawChange", sample.avgYawChange);
            obj.addProperty("avgPitchChange", sample.avgPitchChange);
            obj.addProperty("yawVariance", sample.yawVariance);
            obj.addProperty("pitchVariance", sample.pitchVariance);
            obj.addProperty("rotationSmoothness", sample.rotationSmoothness);
            obj.addProperty("snapCount", sample.snapCount);
            obj.addProperty("aimAcceleration", sample.aimAcceleration);
            obj.addProperty("aimJitter", sample.aimJitter);
            obj.addProperty("targetSwitchSpeed", sample.targetSwitchSpeed);
            obj.addProperty("targetSwitchCount", sample.targetSwitchCount);
            obj.addProperty("avgAimAngle", sample.avgAimAngle);
            obj.addProperty("aimAngleVariance", sample.aimAngleVariance);
            obj.addProperty("strafeCount", sample.strafeCount);
            obj.addProperty("strafeFrequency", sample.strafeFrequency);
            obj.addProperty("strafeConsistency", sample.strafeConsistency);
            obj.addProperty("wTapCount", sample.wTapCount);
            obj.addProperty("sprintResetCount", sample.sprintResetCount);
            obj.addProperty("avgClickInterval", sample.avgClickInterval);
            obj.addProperty("clickIntervalVariance", sample.clickIntervalVariance);
            obj.addProperty("clickEntropy", sample.clickEntropy);
            obj.addProperty("doubleClickCount", sample.doubleClickCount);
            obj.addProperty("avgKnockbackTaken", sample.avgKnockbackTaken);
            obj.addProperty("knockbackVariance", sample.knockbackVariance);
            obj.addProperty("knockbackComplianceRate", sample.knockbackComplianceRate);
            obj.addProperty("pps", sample.pps);
            obj.addProperty("packetTimingVariance", sample.packetTimingVariance);
            obj.addProperty("duplicatePackets", sample.duplicatePackets);
            obj.addProperty("invalidPackets", sample.invalidPackets);
            obj.addProperty("blocksPlaced", sample.blocksPlaced);
            obj.addProperty("avgPlaceSpeed", sample.avgPlaceSpeed);
            obj.addProperty("placeAngleVariance", sample.placeAngleVariance);
            obj.addProperty("impossiblePlacements", sample.impossiblePlacements);
            obj.addProperty("ping", sample.ping);
            obj.addProperty("serverTps", sample.serverTps);
            obj.addProperty("activeCombatSessions", sample.activeCombatSessions);
            obj.addProperty("combatWinRate", sample.combatWinRate);
            obj.addProperty("avgDamageDealt", sample.avgDamageDealt);
            obj.addProperty("avgDamageTaken", sample.avgDamageTaken);
            obj.addProperty("combatKDR", sample.combatKDR);
            obj.addProperty("avgCombatDuration", sample.avgCombatDuration);
            obj.addProperty("reactionTime", sample.reactionTime);
            obj.addProperty("comboCount", sample.comboCount);
            obj.addProperty("hitAccuracy", sample.hitAccuracy);
            array.add(obj);
        }
        return array;
    }

    // ==================== HELPER METHODS ====================

    private double average(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private double max(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToDouble(d -> d).max().orElse(0.0);
    }

    private double variance(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double mean = average(values);
        return values.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0.0);
    }

    private double averageLong(List<Long> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToLong(l -> l).average().orElse(0.0);
    }

    private double varianceLong(List<Long> values) {
        if (values.size() < 2) return 0.0;
        double mean = averageLong(values);
        return values.stream().mapToDouble(l -> Math.pow(l - mean, 2)).average().orElse(0.0);
    }

    private float averageFloat(List<Float> values) {
        if (values.isEmpty()) return 0.0f;
        return (float) values.stream().mapToDouble(f -> f).average().orElse(0.0);
    }

    private double varianceFloat(List<Float> values) {
        if (values.size() < 2) return 0.0;
        double mean = averageFloat(values);
        return values.stream().mapToDouble(f -> Math.pow(f - mean, 2)).average().orElse(0.0);
    }

    private double varianceInt(List<Integer> values) {
        if (values.size() < 2) return 0.0;
        double mean = values.stream().mapToInt(i -> i).average().orElse(0.0);
        return values.stream().mapToDouble(i -> Math.pow(i - mean, 2)).average().orElse(0.0);
    }

    /**
     * Berechne Shannon-Entropie für Click-Intervalle
     * Niedrige Entropie = sehr gleichmäßig = verdächtig (Bot)
     * Hohe Entropie = zufällig = normal
     */
    private double calculateEntropy(List<Long> intervals) {
        if (intervals.size() < 5) return 1.0;

        // Bin die Intervalle in Kategorien (0-50ms, 50-100ms, 100-150ms, etc.)
        Map<Integer, Integer> bins = new HashMap<>();
        for (Long interval : intervals) {
            int bin = (int) (interval / 50); // 50ms Bins
            bins.merge(bin, 1, Integer::sum);
        }

        // Shannon Entropie berechnen
        double entropy = 0.0;
        int total = intervals.size();
        for (int count : bins.values()) {
            if (count > 0) {
                double p = (double) count / total;
                entropy -= p * Math.log(p) / Math.log(2);
            }
        }

        // Normalisieren (max Entropie = log2(n))
        double maxEntropy = Math.log(bins.size()) / Math.log(2);
        return maxEntropy > 0 ? entropy / maxEntropy : 1.0;
    }

    /**
     * Hole Combat Session Tracker für externe Nutzung
     */
    public CombatSessionTracker getCombatTracker() {
        return combatTracker;
    }

    /**
     * Hole Combat Suspicion Score
     */
    public CombatSessionTracker.CombatSuspicion getCombatSuspicion() {
        return combatTracker.analyzeSuspicion();
    }
}
