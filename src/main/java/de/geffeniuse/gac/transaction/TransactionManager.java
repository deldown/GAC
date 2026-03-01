package de.geffeniuse.gac.transaction;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TransactionManager - Ping/Latency tracking using Transaction packets
 *
 * How it works:
 * 1. Server sends PING packet with unique ID
 * 2. Client responds with PONG packet with same ID
 * 3. We measure time between send and receive = true latency
 *
 * This is more accurate than player.getPing() because:
 * - It's measured in real-time, not averaged
 * - We know EXACTLY when client received our data
 * - We can sync server/client state
 *
 * Used by Polar, GrimAC, and other top anticheats.
 */
public class TransactionManager {

    private final GACUser user;

    // Transaction tracking
    private final Map<Integer, Long> pendingTransactions = new ConcurrentHashMap<>();
    private final Queue<TransactionData> transactionHistory = new ConcurrentLinkedQueue<>();
    private static final int HISTORY_SIZE = 20;

    // ID counter (use negative to avoid conflicts with vanilla)
    private final AtomicInteger nextId = new AtomicInteger(-1);

    // Latency data
    private final AtomicLong lastTransactionTime = new AtomicLong(0);
    private final AtomicLong confirmedLatency = new AtomicLong(0);
    private volatile long averageLatency = 0;
    private volatile long minLatency = Long.MAX_VALUE;
    private volatile long maxLatency = 0;

    // State sync
    private volatile boolean awaitingTransaction = false;
    private volatile long lastSentTime = 0;

    public TransactionManager(GACUser user) {
        this.user = user;
    }

    /**
     * Send a transaction packet to the client
     * @return the transaction ID
     */
    public int sendTransaction() {
        Player player = user.getPlayer();
        if (player == null || !player.isOnline()) return 0;

        int id = nextId.getAndDecrement();
        if (id < Short.MIN_VALUE) {
            nextId.set(-1);
            id = -1;
        }

        long now = System.currentTimeMillis();
        pendingTransactions.put(id, now);
        lastSentTime = now;
        awaitingTransaction = true;

        try {
            // Send PING packet (1.17+)
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.PING);
            packet.getIntegers().write(0, id);
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Exception e) {
            pendingTransactions.remove(id);
            return 0;
        }

        return id;
    }

    /**
     * Handle incoming transaction response from client
     */
    public void onTransactionReceive(int id) {
        Long sentTime = pendingTransactions.remove(id);
        if (sentTime == null) return;

        long now = System.currentTimeMillis();
        long latency = now - sentTime;

        // Update latency stats
        confirmedLatency.set(latency);
        lastTransactionTime.set(now);
        awaitingTransaction = false;

        // Track min/max
        if (latency < minLatency) minLatency = latency;
        if (latency > maxLatency) maxLatency = latency;

        // Add to history
        transactionHistory.offer(new TransactionData(id, sentTime, now, latency));
        while (transactionHistory.size() > HISTORY_SIZE) {
            transactionHistory.poll();
        }

        // Calculate average
        long sum = 0;
        int count = 0;
        for (TransactionData data : transactionHistory) {
            sum += data.latency;
            count++;
        }
        if (count > 0) {
            averageLatency = sum / count;
        }
    }

    /**
     * Get the confirmed latency (from last transaction)
     */
    public long getConfirmedLatency() {
        return confirmedLatency.get();
    }

    /**
     * Get average latency over recent transactions
     */
    public long getAverageLatency() {
        return averageLatency;
    }

    /**
     * Get time since last transaction was confirmed
     */
    public long getTimeSinceLastTransaction() {
        long last = lastTransactionTime.get();
        if (last == 0) return Long.MAX_VALUE;
        return System.currentTimeMillis() - last;
    }

    /**
     * Check if client is responding to transactions
     * (If not, might be a bot or lagging badly)
     */
    public boolean isResponsive() {
        if (!awaitingTransaction) return true;

        // If we've been waiting more than 5 seconds, not responsive
        return System.currentTimeMillis() - lastSentTime < 5000;
    }

    /**
     * Check if a timestamp is "confirmed" by the client
     * (The client has acknowledged receiving data from this time)
     */
    public boolean isTimeConfirmed(long timestamp) {
        // Find the earliest transaction after this timestamp
        for (TransactionData data : transactionHistory) {
            if (data.sentTime >= timestamp && data.receiveTime > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get latency-compensated time
     * (What time the client is "seeing" right now)
     */
    public long getClientTime() {
        return System.currentTimeMillis() - averageLatency;
    }

    /**
     * Start periodic transaction sending
     */
    public void startTracking() {
        Bukkit.getScheduler().runTaskTimer(GAC.getInstance(), () -> {
            Player player = user.getPlayer();
            if (player != null && player.isOnline()) {
                sendTransaction();
            }
        }, 20L, 20L); // Every second
    }

    // Data class
    private static class TransactionData {
        final int id;
        final long sentTime;
        final long receiveTime;
        final long latency;

        TransactionData(int id, long sentTime, long receiveTime, long latency) {
            this.id = id;
            this.sentTime = sentTime;
            this.receiveTime = receiveTime;
            this.latency = latency;
        }
    }
}
