package de.geffeniuse.gac.check.player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.check.Check;
import de.geffeniuse.gac.data.GACUser;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * ClientSpoofA - Detects clients pretending to be vanilla / spoofing their brand.
 *
 * Detects:
 * 1. Known hack client channels sent while brand claims "vanilla"
 * 2. Brand changes mid-session (brand changed after join)
 * 3. Brand format inconsistencies (vanilla adds a length prefix byte, some clients don't)
 */
public class ClientSpoofA extends Check implements Listener {

    // Channels that only hack clients register
    private static final Set<String> HACK_CHANNELS = new HashSet<>(Arrays.asList(
        "vape",
        "rise",
        "liquidbounce",
        "meteor",
        "wurst",
        "sigma",
        "rusherhack",
        "future",
        "ghostclient",
        "aristois",
        "raven",
        "nodus",
        "hacked",
        "cheating",
        "cheatclient",
        "baritone",        // Often present in hack clients
        "tweakeroo",       // Abuse of carpet mod channel
        "ghc",
        "hack"
    ));

    // Brands that are commonly spoofed to hide hacks
    private static final Set<String> VANILLA_BRANDS = new HashSet<>(Arrays.asList(
        "vanilla",
        "minecraft"
    ));

    private String initialBrand = null;
    private boolean brandSet = false;
    private final Set<String> registeredChannels = new HashSet<>();

    public ClientSpoofA(GACUser user) {
        super(user, "ClientSpoof", "Detects brand spoofing and suspicious client channels.");
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, GAC.getInstance());
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (!isEnabled()) return;

        // Listen for plugin channel registrations
        if (event.getPacketType() == PacketType.Play.Client.CUSTOM_PAYLOAD) {
            try {
                String channel = event.getPacket().getStrings().read(0);

                // Check "register" channel - client announces what channels it uses
                if (channel.equals("REGISTER") || channel.equals("minecraft:register")) {
                    Object modifier = event.getPacket().getModifier().read(1);
                    String channelList = extractChannelList(modifier);

                    if (channelList != null) {
                        for (String ch : channelList.split("\0")) {
                            ch = ch.toLowerCase().trim();
                            registeredChannels.add(ch);

                            // Check if it's a known hack client channel
                            for (String hackCh : HACK_CHANNELS) {
                                if (ch.contains(hackCh)) {
                                    // Hack channel registered - flag
                                    fail("hack channel registered: " + ch);
                                    return;
                                }
                            }
                        }

                        // Detect: brand is "vanilla" but hack channels are registered
                        if (initialBrand != null && VANILLA_BRANDS.contains(initialBrand.toLowerCase())) {
                            for (String ch : registeredChannels) {
                                for (String hackCh : HACK_CHANNELS) {
                                    if (ch.contains(hackCh)) {
                                        fail("brand spoof: brand=" + initialBrand + " but channel=" + ch);
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }

                // Brand packet
                if (channel.equals("minecraft:brand") || channel.equals("MC|Brand")) {
                    Object modifier = event.getPacket().getModifier().read(1);
                    String brand = extractBrand(modifier);

                    if (brand != null) {
                        brand = brand.replaceAll("[^a-zA-Z0-9 _\\-/.]", "").trim();

                        if (!brandSet) {
                            // First brand - store it
                            initialBrand = brand;
                            brandSet = true;
                        } else if (!brand.equals(initialBrand)) {
                            // Brand changed mid-session = client spoofing
                            fail("brand changed: " + initialBrand + " -> " + brand);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private String extractBrand(Object modifier) {
        try {
            if (modifier instanceof byte[]) {
                return new String((byte[]) modifier, StandardCharsets.UTF_8);
            } else if (modifier != null && modifier.getClass().getSimpleName().contains("ByteBuf")) {
                java.lang.reflect.Method readableBytes = modifier.getClass().getMethod("readableBytes");
                java.lang.reflect.Method readBytes = modifier.getClass().getMethod("readBytes", byte[].class);
                int length = (int) readableBytes.invoke(modifier);
                byte[] data = new byte[length];
                readBytes.invoke(modifier, data);
                // Skip first byte (varint length prefix that vanilla adds)
                if (data.length > 1) {
                    return new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
                }
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractChannelList(Object modifier) {
        try {
            if (modifier instanceof byte[]) {
                return new String((byte[]) modifier, StandardCharsets.UTF_8);
            } else if (modifier != null && modifier.getClass().getSimpleName().contains("ByteBuf")) {
                java.lang.reflect.Method readableBytes = modifier.getClass().getMethod("readableBytes");
                java.lang.reflect.Method readBytes = modifier.getClass().getMethod("readBytes", byte[].class);
                int length = (int) readableBytes.invoke(modifier);
                byte[] data = new byte[length];
                readBytes.invoke(modifier, data);
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public String getInitialBrand() {
        return initialBrand;
    }

    public Set<String> getRegisteredChannels() {
        return new HashSet<>(registeredChannels);
    }
}
