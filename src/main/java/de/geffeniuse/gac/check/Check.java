package de.geffeniuse.gac.check;

import de.geffeniuse.gac.GAC;
import de.geffeniuse.gac.config.CheckConfig;
import de.geffeniuse.gac.data.GACUser;
import de.geffeniuse.gac.util.GACLogger;
import com.comphenix.protocol.events.PacketEvent;
import lombok.Getter;

import java.util.Map;

@Getter
public abstract class Check {

    protected final GACUser user;
    private final String name;
    private final String description;
    private double violationLevel;

    public Check(GACUser user, String name, String description) {
        this.user = user;
        this.name = name;
        this.description = description;
        this.violationLevel = 0;
    }

    public abstract void onPacket(PacketEvent event);

    public String getCheckId() {
        return this.getClass().getSimpleName();
    }

    public boolean isEnabled() {
        return CheckConfig.isEnabled(getCheckId());
    }

    protected void fail(String info) {
        fail(info, null);
    }

    protected void resetVL() {
        this.violationLevel = 0;
    }

    protected void fail(String info, Map<String, Object> features) {
        if (!isEnabled()) return;

        violationLevel++;
        GAC.incrementFlags();

        GACLogger.logFlag(user, name, getCheckId(), info, (int) violationLevel);

        // Notify user data (resets legit timer, BehaviorCollector tracking)
        user.onViolation(name, (int) violationLevel);

        String message = "§b§lGAC §8» §3" + user.getPlayer().getName() +
                         " §7failed §b" + name +
                         " §8(§f" + info + "§8) §7VL: §3" + (int) violationLevel;

        GAC.getInstance().getLogger().info(message);

        GAC.getInstance().getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("gac.alerts"))
                .forEach(p -> p.sendMessage(message));

        // Auto-Kick at VL 15
        if (violationLevel > 15) {
            GAC.incrementKicks();
            GACLogger.logKick(user, name, getCheckId(), "VL > 15");
            org.bukkit.Bukkit.getScheduler().runTask(GAC.getInstance(), () -> {
                user.getPlayer().kickPlayer("§b§lGAC \n\n§7Unfair Advantage detected.\n§fCheck: §b" + name);
            });
            violationLevel = 0;
        }
    }

    protected String getCheckCategory() {
        String checkId = getCheckId().toLowerCase();

        if (checkId.contains("killaura") || checkId.contains("aim") ||
            checkId.contains("reach") || checkId.contains("autoclicker") ||
            checkId.contains("critical")) return "combat";

        if (checkId.contains("speed") || checkId.contains("fly") ||
            checkId.contains("nofall") || checkId.contains("timer") ||
            checkId.contains("velocity") || checkId.contains("phase") ||
            checkId.contains("jesus") || checkId.contains("step") ||
            checkId.contains("blink") || checkId.contains("strafe")) return "movement";

        if (checkId.contains("scaffold") || checkId.contains("fastbreak") ||
            checkId.contains("tower") || checkId.contains("xray")) return "building";

        if (checkId.contains("packet") || checkId.contains("badpacket") ||
            checkId.contains("crasher") || checkId.contains("exploit")) return "packet";

        if (checkId.contains("chest") || checkId.contains("inventory") ||
            checkId.contains("client")) return "client";

        return "other";
    }
}
