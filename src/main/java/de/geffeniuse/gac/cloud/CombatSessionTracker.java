package de.geffeniuse.gac.cloud;

import de.geffeniuse.gac.data.GACUser;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CombatSessionTracker - Trackt ganze Kämpfe statt einzelne Hits
 *
 * Features:
 * - Erkennt Kampf-Start und Ende
 * - Trackt Damage, Hits, Combos
 * - Berechnet Win/Loss und KDR
 * - Erkennt verdächtige Muster über ganze Kämpfe
 */
public class CombatSessionTracker {

    private final GACUser user;

    // Aktive Kämpfe gegen andere Spieler
    private final Map<UUID, CombatSession> activeSessions = new ConcurrentHashMap<>();

    // Abgeschlossene Kämpfe (für Statistik)
    private final Deque<CombatSession> completedSessions = new LinkedList<>();
    private static final int MAX_COMPLETED_SESSIONS = 50;

    // Combat timeout (5 Sekunden ohne Interaktion = Kampf vorbei)
    private static final long COMBAT_TIMEOUT = 5000;

    // Statistiken
    private int totalKills = 0;
    private int totalDeaths = 0;
    private int totalHitsDealt = 0;
    private int totalHitsTaken = 0;
    private double totalDamageDealt = 0;
    private double totalDamageTaken = 0;
    private int totalSwings = 0;
    private int longestCombo = 0;

    // Reaction Time Tracking
    private final List<Long> reactionTimes = new ArrayList<>();
    private long lastDamageTakenTime = 0;

    public CombatSessionTracker(GACUser user) {
        this.user = user;
    }

    // ==================== EVENT RECORDING ====================

    /**
     * Aufgerufen wenn Spieler jemanden schlägt
     */
    public void recordHitDealt(Player target, double damage, double distance, double aimAngle) {
        UUID targetId = target.getUniqueId();
        CombatSession session = getOrCreateSession(targetId, target.getName());

        session.hitsDealt++;
        session.damageDealt += damage;
        session.lastHitDealtTime = System.currentTimeMillis();
        session.hitDistances.add(distance);
        session.aimAngles.add(aimAngle);

        // Combo tracking
        long timeSinceLastHit = System.currentTimeMillis() - session.lastComboHitTime;
        if (timeSinceLastHit < 800) { // Innerhalb 800ms = Combo
            session.currentCombo++;
            if (session.currentCombo > session.longestCombo) {
                session.longestCombo = session.currentCombo;
            }
        } else {
            session.currentCombo = 1;
        }
        session.lastComboHitTime = System.currentTimeMillis();

        // Hit timings
        if (session.lastHitTiming > 0) {
            session.hitTimings.add(System.currentTimeMillis() - session.lastHitTiming);
        }
        session.lastHitTiming = System.currentTimeMillis();

        // Global stats
        totalHitsDealt++;
        totalDamageDealt += damage;
        if (session.longestCombo > longestCombo) {
            longestCombo = session.longestCombo;
        }
    }

    /**
     * Aufgerufen wenn Spieler Schaden nimmt
     */
    public void recordHitTaken(Player attacker, double damage) {
        if (attacker == null) return;

        UUID attackerId = attacker.getUniqueId();
        CombatSession session = getOrCreateSession(attackerId, attacker.getName());

        session.hitsTaken++;
        session.damageTaken += damage;
        session.lastHitTakenTime = System.currentTimeMillis();

        // Reaction time (Zeit von Damage bis zur nächsten Aktion)
        lastDamageTakenTime = System.currentTimeMillis();

        // Combo reset
        session.currentCombo = 0;

        // Global stats
        totalHitsTaken++;
        totalDamageTaken += damage;
    }

    /**
     * Aufgerufen bei jedem Swing (auch Misses)
     */
    public void recordSwing() {
        totalSwings++;
    }

    /**
     * Aufgerufen wenn Spieler eine Aktion nach Damage macht
     */
    public void recordReaction() {
        if (lastDamageTakenTime > 0) {
            long reactionTime = System.currentTimeMillis() - lastDamageTakenTime;
            if (reactionTime < 1000) { // Nur wenn innerhalb 1 Sekunde
                reactionTimes.add(reactionTime);
                if (reactionTimes.size() > 100) {
                    reactionTimes.remove(0);
                }
            }
            lastDamageTakenTime = 0;
        }
    }

    /**
     * Aufgerufen wenn jemand stirbt
     */
    public void recordKill(Player victim) {
        UUID victimId = victim.getUniqueId();
        CombatSession session = activeSessions.get(victimId);

        if (session != null) {
            session.won = true;
            session.endTime = System.currentTimeMillis();
            completeSession(session);
            activeSessions.remove(victimId);
        }

        totalKills++;
    }

    /**
     * Aufgerufen wenn der Spieler selbst stirbt
     */
    public void recordDeath(Player killer) {
        if (killer != null) {
            UUID killerId = killer.getUniqueId();
            CombatSession session = activeSessions.get(killerId);

            if (session != null) {
                session.won = false;
                session.endTime = System.currentTimeMillis();
                completeSession(session);
                activeSessions.remove(killerId);
            }
        }

        // Alle anderen Sessions als Verlust werten
        for (CombatSession session : activeSessions.values()) {
            session.won = false;
            session.endTime = System.currentTimeMillis();
            completeSession(session);
        }
        activeSessions.clear();

        totalDeaths++;
    }

    // ==================== SESSION MANAGEMENT ====================

    private CombatSession getOrCreateSession(UUID opponentId, String opponentName) {
        return activeSessions.computeIfAbsent(opponentId, id -> {
            CombatSession session = new CombatSession();
            session.opponentId = id;
            session.opponentName = opponentName;
            session.startTime = System.currentTimeMillis();
            return session;
        });
    }

    private void completeSession(CombatSession session) {
        completedSessions.addLast(session);
        while (completedSessions.size() > MAX_COMPLETED_SESSIONS) {
            completedSessions.removeFirst();
        }
    }

    /**
     * Tick - Prüft auf abgelaufene Sessions
     */
    public void tick() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, CombatSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            CombatSession session = it.next().getValue();

            // Session timeout
            long lastActivity = Math.max(session.lastHitDealtTime, session.lastHitTakenTime);
            if (now - lastActivity > COMBAT_TIMEOUT) {
                session.endTime = now;
                // Wer hatte mehr Damage? Der "gewinnt"
                session.won = session.damageDealt > session.damageTaken;
                completeSession(session);
                it.remove();
            }
        }
    }

    // ==================== STATISTICS ====================

    /**
     * Hole Combat-Daten für BehaviorSample
     */
    public CombatStats getStats() {
        CombatStats stats = new CombatStats();

        stats.activeCombatSessions = activeSessions.size();
        stats.combatKDR = totalDeaths > 0 ? (double) totalKills / totalDeaths : totalKills;
        stats.hitAccuracy = totalSwings > 0 ? (double) totalHitsDealt / totalSwings : 0;
        stats.longestCombo = longestCombo;

        // Average damage
        stats.avgDamageDealt = totalHitsDealt > 0 ? totalDamageDealt / totalHitsDealt : 0;
        stats.avgDamageTaken = totalHitsTaken > 0 ? totalDamageTaken / totalHitsTaken : 0;

        // Win rate from completed sessions
        if (!completedSessions.isEmpty()) {
            long wins = completedSessions.stream().filter(s -> s.won).count();
            stats.combatWinRate = (double) wins / completedSessions.size();
        }

        // Average combat duration
        if (!completedSessions.isEmpty()) {
            double totalDuration = completedSessions.stream()
                .mapToLong(s -> s.endTime - s.startTime)
                .average()
                .orElse(0);
            stats.avgCombatDuration = totalDuration;
        }

        // Reaction time
        if (!reactionTimes.isEmpty()) {
            stats.reactionTime = reactionTimes.stream()
                .mapToLong(l -> l)
                .average()
                .orElse(0);
        }

        return stats;
    }

    /**
     * Hole verdächtige Patterns aus Kämpfen
     */
    public CombatSuspicion analyzeSuspicion() {
        CombatSuspicion suspicion = new CombatSuspicion();

        if (completedSessions.size() < 3) {
            suspicion.hasEnoughData = false;
            return suspicion;
        }
        suspicion.hasEnoughData = true;

        // === Win Rate Check ===
        long wins = completedSessions.stream().filter(s -> s.won).count();
        double winRate = (double) wins / completedSessions.size();
        if (winRate > 0.95 && completedSessions.size() >= 10) {
            suspicion.suspiciousWinRate = true;
            suspicion.winRateScore = 0.8;
        } else if (winRate > 0.85 && completedSessions.size() >= 5) {
            suspicion.winRateScore = 0.4;
        }

        // === Hit Accuracy Check ===
        double accuracy = totalSwings > 0 ? (double) totalHitsDealt / totalSwings : 0;
        if (accuracy > 0.95) {
            suspicion.suspiciousAccuracy = true;
            suspicion.accuracyScore = 0.7;
        } else if (accuracy > 0.85) {
            suspicion.accuracyScore = 0.3;
        }

        // === Combo Check ===
        int maxCombo = completedSessions.stream()
            .mapToInt(s -> s.longestCombo)
            .max()
            .orElse(0);
        if (maxCombo >= 15) {
            suspicion.suspiciousCombos = true;
            suspicion.comboScore = 0.6;
        } else if (maxCombo >= 10) {
            suspicion.comboScore = 0.3;
        }

        // === Hit Timing Consistency ===
        List<Long> allTimings = new ArrayList<>();
        for (CombatSession session : completedSessions) {
            allTimings.addAll(session.hitTimings);
        }
        if (allTimings.size() >= 20) {
            double mean = allTimings.stream().mapToLong(l -> l).average().orElse(0);
            double variance = allTimings.stream()
                .mapToDouble(l -> Math.pow(l - mean, 2))
                .average()
                .orElse(0);
            double stdDev = Math.sqrt(variance);

            // Sehr niedrige Varianz = verdächtig (Bot-like)
            if (stdDev < 15 && mean > 0) {
                suspicion.suspiciousTimingConsistency = true;
                suspicion.timingScore = 0.7;
            } else if (stdDev < 30 && mean > 0) {
                suspicion.timingScore = 0.3;
            }
        }

        // === Aim Angle Consistency ===
        List<Double> allAngles = new ArrayList<>();
        for (CombatSession session : completedSessions) {
            allAngles.addAll(session.aimAngles);
        }
        if (allAngles.size() >= 20) {
            double mean = allAngles.stream().mapToDouble(d -> d).average().orElse(0);
            double variance = allAngles.stream()
                .mapToDouble(d -> Math.pow(d - mean, 2))
                .average()
                .orElse(0);

            // Sehr niedrige Varianz = immer perfekter Winkel = Aimbot
            if (variance < 5 && mean < 10) {
                suspicion.suspiciousAimConsistency = true;
                suspicion.aimScore = 0.8;
            } else if (variance < 15 && mean < 15) {
                suspicion.aimScore = 0.4;
            }
        }

        // === Reaction Time Check ===
        if (reactionTimes.size() >= 10) {
            double avgReaction = reactionTimes.stream().mapToLong(l -> l).average().orElse(0);
            if (avgReaction < 80) { // Unter 80ms = unmenschlich
                suspicion.suspiciousReactionTime = true;
                suspicion.reactionScore = 0.9;
            } else if (avgReaction < 120) {
                suspicion.reactionScore = 0.4;
            }
        }

        // Combined Score
        suspicion.combinedScore = (
            suspicion.winRateScore * 0.15 +
            suspicion.accuracyScore * 0.2 +
            suspicion.comboScore * 0.15 +
            suspicion.timingScore * 0.25 +
            suspicion.aimScore * 0.15 +
            suspicion.reactionScore * 0.1
        );

        return suspicion;
    }

    // ==================== INNER CLASSES ====================

    public static class CombatSession {
        public UUID opponentId;
        public String opponentName;
        public long startTime;
        public long endTime;
        public boolean won;

        public int hitsDealt = 0;
        public int hitsTaken = 0;
        public double damageDealt = 0;
        public double damageTaken = 0;

        public int currentCombo = 0;
        public int longestCombo = 0;
        public long lastComboHitTime = 0;

        public long lastHitDealtTime = 0;
        public long lastHitTakenTime = 0;
        public long lastHitTiming = 0;

        public List<Long> hitTimings = new ArrayList<>();
        public List<Double> hitDistances = new ArrayList<>();
        public List<Double> aimAngles = new ArrayList<>();
    }

    public static class CombatStats {
        public int activeCombatSessions;
        public double combatWinRate;
        public double avgDamageDealt;
        public double avgDamageTaken;
        public double combatKDR;
        public double avgCombatDuration;
        public double reactionTime;
        public int longestCombo;
        public double hitAccuracy;
    }

    public static class CombatSuspicion {
        public boolean hasEnoughData = false;

        public boolean suspiciousWinRate = false;
        public boolean suspiciousAccuracy = false;
        public boolean suspiciousCombos = false;
        public boolean suspiciousTimingConsistency = false;
        public boolean suspiciousAimConsistency = false;
        public boolean suspiciousReactionTime = false;

        public double winRateScore = 0;
        public double accuracyScore = 0;
        public double comboScore = 0;
        public double timingScore = 0;
        public double aimScore = 0;
        public double reactionScore = 0;

        public double combinedScore = 0;
    }
}
