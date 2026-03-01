package de.geffeniuse.gac.check.combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;

import java.util.HashSet;
import java.util.Set;

/**
 * KillauraE - Multi-Aura Detection
 * Detects when players attack too many different targets in a short time.
 * Humans typically focus on one target, aura clients switch rapidly.
 */
public class KillauraE extends Check {

    private final Set<Integer> recentTargets = new HashSet<>();
    private long windowStart = 0;
    private int switchCount = 0;
    private int lastTargetId = -1;
    private int flagCount = 0; // Heuristics

    // Thresholds - increased for real PvP scenarios
    private static final long WINDOW_MS = 3000; // 3 second window
    private static final int MAX_UNIQUE_TARGETS = 6; // Max 6 different targets in 3 seconds
    private static final int MAX_SWITCHES = 10; // Max 10 target switches in 3 seconds
    private static final int FLAG_THRESHOLD = 3; // Need 3 violations to actually flag

    public KillauraE(GACUser user) {
        super(user, "Killaura (MultiAura)", "Detects rapid target switching.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) return;

        EnumWrappers.EntityUseAction action;
        try {
            action = event.getPacket().getEnumEntityUseActions().read(0).getAction();
        } catch (Exception e) {
            return;
        }

        if (action != EnumWrappers.EntityUseAction.ATTACK) return;

        int targetId = event.getPacket().getIntegers().read(0);
        long now = System.currentTimeMillis();

        // Reset window if expired
        if (now - windowStart > WINDOW_MS) {
            recentTargets.clear();
            switchCount = 0;
            windowStart = now;
        }

        // Track target switch
        if (lastTargetId != -1 && lastTargetId != targetId) {
            switchCount++;
        }
        lastTargetId = targetId;

        // Add to unique targets
        recentTargets.add(targetId);

        // Check for too many unique targets
        if (recentTargets.size() > MAX_UNIQUE_TARGETS) {
            flagCount++;
            if (flagCount >= FLAG_THRESHOLD) {
                fail("targets=" + recentTargets.size() + " in " + (now - windowStart) + "ms x" + flagCount);
                flagCount = 0;
            }
            recentTargets.clear();
            switchCount = 0;
            windowStart = now;
            return;
        }

        // Check for too many switches
        if (switchCount > MAX_SWITCHES) {
            flagCount++;
            if (flagCount >= FLAG_THRESHOLD) {
                fail("switches=" + switchCount + " in " + (now - windowStart) + "ms x" + flagCount);
                flagCount = 0;
            }
            recentTargets.clear();
            switchCount = 0;
            windowStart = now;
        }
    }
}
