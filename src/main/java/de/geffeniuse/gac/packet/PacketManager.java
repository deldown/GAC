package de.geffeniuse.gac.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.data.GACUser;

public class PacketManager {

    private final GAC plugin;
    private final ProtocolManager protocolManager;

    public PacketManager(GAC plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();

        registerPacketListeners();
    }
    
    public static void init() {
        // Legacy stub
    }

    private void registerPacketListeners() {
        // CLIENT SIDE (Inbound)
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Client.USE_ENTITY,
                PacketType.Play.Client.ARM_ANIMATION,
                PacketType.Play.Client.FLYING,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK,
                PacketType.Play.Client.LOOK,
                PacketType.Play.Client.ENTITY_ACTION,
                PacketType.Play.Client.BLOCK_DIG,
                PacketType.Play.Client.BLOCK_PLACE,
                PacketType.Play.Client.USE_ITEM,
                PacketType.Play.Client.HELD_ITEM_SLOT,
                PacketType.Play.Client.CUSTOM_PAYLOAD,
                PacketType.Play.Client.WINDOW_CLICK,
                PacketType.Play.Client.CLOSE_WINDOW,
                PacketType.Play.Client.UPDATE_SIGN,
                PacketType.Play.Client.TAB_COMPLETE,
                PacketType.Play.Client.CHAT,
                PacketType.Play.Client.KEEP_ALIVE,
                PacketType.Play.Client.VEHICLE_MOVE,
                PacketType.Play.Client.STEER_VEHICLE,
                PacketType.Play.Client.PONG) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    if (event.getPlayer() == null) return;

                    GACUser user = GAC.getInstance().getUser(event.getPlayer().getUniqueId());
                    if (user != null) {
                        // Handle Brand
                        if (event.getPacketType() == PacketType.Play.Client.CUSTOM_PAYLOAD) {
                            try {
                                String channel = event.getPacket().getStrings().read(0);
                                if (channel.equals("minecraft:brand") || channel.equals("MC|Brand")) {
                                    byte[] data = null;
                                    Object modifier = event.getPacket().getModifier().read(1);

                                    // ProtocolLib handling of ByteBuf vs byte[]
                                    if (modifier instanceof byte[]) {
                                        data = (byte[]) modifier;
                                    } else if (modifier.getClass().getSimpleName().contains("ByteBuf")) {
                                        // Reflection for Netty ByteBuf to avoid direct dependency
                                        java.lang.reflect.Method readableBytes = modifier.getClass().getMethod("readableBytes");
                                        java.lang.reflect.Method readBytes = modifier.getClass().getMethod("readBytes", byte[].class);
                                        int length = (int) readableBytes.invoke(modifier);
                                        data = new byte[length];
                                        readBytes.invoke(modifier, data);
                                    }

                                    if (data != null) {
                                        String brand = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                                        brand = brand.replaceAll("[^a-zA-Z0-9 _-]", "");
                                        user.setClientBrand(brand);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        user.handlePacket(event);
                    }
                } catch (Exception e) {
                    // Log errors for debugging
                    plugin.getLogger().warning("[PKT ERROR] " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        
        // SERVER SIDE (Outbound) - Velocity & Window Tracking
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.ENTITY_VELOCITY,
                PacketType.Play.Server.OPEN_WINDOW) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    if (event.getPlayer() == null) return;

                    GACUser user = GAC.getInstance().getUser(event.getPlayer().getUniqueId());
                    if (user == null) return;

                    if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
                        int entityId = event.getPacket().getIntegers().read(0);
                        if (entityId == event.getPlayer().getEntityId()) {
                            user.handlePacket(event);
                        }
                    } else {
                        user.handlePacket(event);
                    }
                } catch (Exception ignored) {
                    // Suppress ALL errors
                }
            }
        });
    }
}
