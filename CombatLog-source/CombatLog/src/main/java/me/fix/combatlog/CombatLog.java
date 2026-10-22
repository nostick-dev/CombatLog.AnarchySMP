package me.fix.combatlog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CombatLog extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final String P = "COMBAT-MANAGER.";

    // لاعب -> وقت انتهاء الكومبات (ms)
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Set<UUID> warned = new HashSet<>();
    private final Map<UUID, UUID> crystalOwner = new HashMap<>();

    private Location anchorLoc;
    private UUID anchorOwner;
    private long anchorTime;

    // settings
    private boolean enabled, crystal, pearl, anchor, wind, spear, tagAttacker;
    private boolean blockItems, stasis, stasisWorld, blockTp, blockPortals;
    private int cooldown, safeSeconds;
    private double stasisMax;
    private String actionBar, blockMessage, stasisMsg, startMsg, endMsg, safeMsg, quitMsg;
    private final Set<String> blockedCmds = new HashSet<>();
    private final Set<String> blockedItems = new HashSet<>();
    private final Set<String> excluded = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        enabled = c.getBoolean(P + "ENABLED", true);
        cooldown = c.getInt(P + "COOLDOWN", 60);
        actionBar = c.getString(P + "ACTION-BAR", "&fCombat: &#00A4FC{time}s");
        crystal = c.getBoolean(P + "ENDER-CRYSTAL", true);
        pearl = c.getBoolean(P + "ENDER-PEARL", true);
        anchor = c.getBoolean(P + "RESPAWN-ANCHOR", true);
        wind = c.getBoolean(P + "WIND-CHARGE", true);
        spear = c.getBoolean(P + "SPEAR", true);
        tagAttacker = c.getBoolean(P + "TAG-ATTACKER", false);
        blockItems = c.getBoolean(P + "BLOCK-INTERACTIONS.ENABLED", false);
        stasis = c.getBoolean(P + "ANTI-STASIS-CHAMBER.ENABLED", true);
        stasisMax = c.getDouble(P + "ANTI-STASIS-CHAMBER.MAX-DISTANCE", 500);
        stasisWorld = c.getBoolean(P + "ANTI-STASIS-CHAMBER.PREVENT-WORLD-CHANGE", true);
        blockTp = c.getBoolean(P + "BLOCK-TELEPORT", true);
        blockPortals = c.getBoolean(P + "BLOCK-PORTALS", true);
        safeSeconds = c.getInt(P + "SAFE-LOGOUT-SECONDS", 5);
        blockMessage = c.getString(P + "BLOCK-MESSAGE", "&cYou can't use this command in your current status.");
        stasisMsg = c.getString(P + "STASIS-MESSAGE", "&cThis teleport is blocked while in combat.");
        startMsg = c.getString(P + "START-MESSAGE", "");
        endMsg = c.getString(P + "END-MESSAGE", "");
        safeMsg = c.getString(P + "SAFE-LOGOUT-MESSAGE", "");
        quitMsg = c.getString(P + "QUIT-BROADCAST", "");

        blockedCmds.clear();
        for (String s : c.getStringList(P + "BLOCK-COMMANDS"))
            blockedCmds.add(s.trim().toLowerCase().replaceFirst("^/", ""));
        blockedItems.clear();
        for (String s : c.getStringList(P + "BLOCK-INTERACTIONS.ITEMS"))
            blockedItems.add(s.trim().toUpperCase());
        excluded.clear();
        excluded.addAll(c.getStringList(P + "EXCLUDED-WORLDS"));
    }

    // ---------- helpers ----------

    private boolean active(Player p) {
        return enabled
                && !excluded.contains(p.getWorld().getName())
                && !p.hasPermission("combatlog.bypass");
    }

    private boolean inCombat(Player p) {
        return combat.containsKey(p.getUniqueId());
    }

    private void msg(Player p, String s) {
        if (s != null && !s.isEmpty()) p.sendMessage(LEGACY.deserialize(s));
    }

    private void showBar(Player p, int seconds) {
        p.sendActionBar(LEGACY.deserialize(actionBar.replace("{time}", String.valueOf(seconds))));
    }

    private int remaining(long end) {
        return (int) Math.ceil((end - System.currentTimeMillis()) / 1000.0);
    }

    private void tag(Player p) {
        if (!active(p)) return;
        boolean fresh = combat.put(p.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L) == null;
        warned.remove(p.getUniqueId()); // ضرب جديد = تنلغي حالة الخروج الآمن
        if (fresh) msg(p, startMsg);
        showBar(p, cooldown);
    }

    private void endCombat(Player p, boolean notify) {
        combat.remove(p.getUniqueId());
        warned.remove(p.getUniqueId());
        p.sendActionBar(Component.empty());
        if (notify) msg(p, endMsg);
    }

    private Player playerSource(Entity d) {
        if (d instanceof Player pl) return pl;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player ps) return ps;
        return null;
    }

    private boolean isSpear(Player p) {
        return p.getInventory().getItemInMainHand().getType().name().endsWith("_SPEAR");
    }

    // ---------- العداد (كل ثانية) ----------

    private void tick() {
        for (UUID id : new ArrayList<>(combat.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Long end = combat.get(id);
            if (p == null || !p.isOnline() || end == null) {
                combat.remove(id);
                warned.remove(id);
                continue;
            }
            int rem = remaining(end);
            if (rem <= 0) {
                endCombat(p, true);
                continue;
            }
            showBar(p, rem);
            // قبل النهاية بـ SAFE-LOGOUT-SECONDS: تنبيه الخروج الآمن
            if (rem <= safeSeconds && warned.add(id)) msg(p, safeMsg);
        }
    }

    // ---------- تفعيل الكومبات: لاعب ضد لاعب فقط ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!enabled) return;
        Entity ve = e.getEntity();

        // تتبع من ضرب كرستالة (عشان لو انفجرت وضرت لاعب)
        if (ve instanceof EnderCrystal && e instanceof EntityDamageByEntityEvent ce) {
            Player src = playerSource(ce.getDamager());
            if (src != null) {
                UUID cid = ve.getUniqueId();
                crystalOwner.put(cid, src.getUniqueId());
                Bukkit.getScheduler().runTaskLater(this, () -> crystalOwner.remove(cid), 40L);
            }
            return;
        }

        if (!(ve instanceof Player victim)) return;
        if (!active(victim)) return;

        Player attacker = null;

        if (e instanceof EntityDamageByEntityEvent ee) {
            Entity d = ee.getDamager();
            if (d instanceof Player pm) {
                if (!spear && isSpear(pm)) return;
                attacker = pm;
            } else if (d instanceof Projectile proj) {
                if (!(proj.getShooter() instanceof Player ps)) return; // موب/ديسبنسر = ما يصير كومبات
                String t = proj.getType().name();
                boolean special = t.equals("ENDER_PEARL") || t.equals("WIND_CHARGE");
                if (t.equals("ENDER_PEARL") && !pearl) return;
                if (t.equals("WIND_CHARGE") && !wind) return;
                if (!special && ee.getFinalDamage() <= 0) return; // ثلج/بيض بدون ضرر
                attacker = ps;
            } else if (d instanceof EnderCrystal) {
                if (!crystal) return;
                UUID owner = crystalOwner.get(d.getUniqueId());
                if (owner == null) return; // كرستالة انفجرت بدون لاعب
                attacker = Bukkit.getPlayer(owner);
            }
        } else if (e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            if (!anchor || anchorOwner == null || anchorLoc == null) return;
            if (System.currentTimeMillis() - anchorTime > 2000) return;
            if (!anchorLoc.getWorld().equals(victim.getWorld())) return;
            if (anchorLoc.distance(victim.getLocation()) > 12) return;
            attacker = Bukkit.getPlayer(anchorOwner);
        }

        if (attacker == null || attacker.equals(victim)) return;
        tag(victim);
        if (tagAttacker) tag(attacker);
    }

    // تتبع من فجّر ريسباون أنكر
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnchor(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && b != null
                && b.getType() == Material.RESPAWN_ANCHOR
                && !b.getWorld().isRespawnAnchorWorks()) {
            anchorLoc = b.getLocation();
            anchorOwner = e.getPlayer().getUniqueId();
            anchorTime = System.currentTimeMillis();
        }
    }

    // ---------- القيود أثناء الكومبات ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!inCombat(p) || !active(p)) return;
        String m = e.getMessage();
        if (m.length() < 2) return;
        String label = m.substring(1).split(" ", 2)[0].toLowerCase();
        int i = label.lastIndexOf(':'); // essentials:home -> home
        if (i >= 0) label = label.substring(i + 1);
        if (isBlocked(label)) {
            e.setCancelled(true);
            msg(p, blockMessage);
        }
    }

    private boolean isBlocked(String label) {
        if (blockedCmds.contains(label)) return true;
        Command c = Bukkit.getCommandMap().getCommand(label); // يغطي الاختصارات (aliases)
        if (c == null) return false;
        if (blockedCmds.contains(c.getName().toLowerCase())) return true;
        for (String a : c.getAliases()) if (blockedCmds.contains(a.toLowerCase())) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent e) {
        if (!blockItems) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!inCombat(p) || !active(p)) return;
        ItemStack it = e.getItem();
        if (it == null || !blockedItems.contains(it.getType().name())) return;
        e.setCancelled(true);
        msg(p, blockMessage);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!inCombat(p) || !active(p)) return;
        Location to = e.getTo();
        if (to == null) return;
        TeleportCause cause = e.getCause();

        if (cause == TeleportCause.ENDER_PEARL) {
            if (!stasis) return;
            Location from = e.getFrom();
            boolean diffWorld = !from.getWorld().equals(to.getWorld());
            boolean far = !diffWorld && from.distance(to) > stasisMax;
            if ((diffWorld && stasisWorld) || far) {
                e.setCancelled(true);
                msg(p, stasisMsg);
            }
            return;
        }
        if (blockTp && (cause == TeleportCause.COMMAND || cause == TeleportCause.PLUGIN)) {
            e.setCancelled(true);
            msg(p, blockMessage);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        Player p = e.getPlayer();
        if (!blockPortals || !inCombat(p) || !active(p)) return;
        e.setCancelled(true);
        msg(p, blockMessage);
    }

    // ---------- موت / خروج ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        endCombat(e.getEntity(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Long end = combat.remove(p.getUniqueId());
        warned.remove(p.getUniqueId());
        if (end == null || !enabled || Bukkit.isStopping() || p.isDead()) return;
        if (!active(p)) return;
        if (remaining(end) <= safeSeconds) return; // خروج آمن: ما يخسر شي
        if (!quitMsg.isEmpty())
            Bukkit.broadcast(LEGACY.deserialize(quitMsg.replace("{player}", p.getName())));
        p.setHealth(0.0); // يموت وتنزل أغراضه
    }

    // ---------- /combatlog reload ----------

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) {
            loadSettings();
            s.sendMessage("CombatLog config reloaded.");
        } else {
            s.sendMessage("/combatlog reload");
        }
        return true;
    }
}
