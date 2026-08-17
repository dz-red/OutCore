package fun.wardensmp.outcore;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Могила: одиночный сундук на месте смерти, виртуальное хранилище на 54 слота,
// приватная (только владелец), с таймером жизни и голограммой обратного отсчёта.
public class Graves implements Listener {

    // Одна могила = одна запись. Раньше это лежало четырьмя параллельными картами
    // с общим ключом, и любая правка требовала не забыть все четыре.
    private static final class Grave {
        List<ItemStack> items;
        UUID owner;
        long born;
        String ownerName;

        Grave(List<ItemStack> items, UUID owner, long born, String ownerName) {
            this.items = items;
            this.owner = owner;
            this.born = born;
            this.ownerName = ownerName;
        }
    }

    private final JavaPlugin plugin;
    private final NameFormat names;

    private final Map<String, Grave> graves = new HashMap<>();
    private final Map<UUID, Location> deathLoc = new HashMap<>();
    // Голограммы держим отдельно: это живые сущности, а не сохраняемое состояние.
    private final Map<String, UUID> holo = new HashMap<>();
    private final File file;

    private long ttlMs;
    private boolean holoOn;
    private double holoUp;
    private int tickSec;

    public Graves(JavaPlugin plugin, NameFormat names) {
        this.plugin = plugin;
        this.names = names;
        this.file = new File(plugin.getDataFolder(), "graves.yml");
        loadSettings();
        load();
    }

    public int size() { return graves.size(); }

    public long ttlSeconds() { return ttlMs / 1000; }

    private void loadSettings() {
        var c = plugin.getConfig();
        c.addDefault("graves.ttl-seconds", 1200);
        c.addDefault("graves.hologram", true);
        c.addDefault("graves.hologram-height", 1.2);
        c.addDefault("graves.tick-seconds", 1);
        c.options().copyDefaults(true);
        plugin.saveConfig();
        ttlMs = Math.max(5, c.getLong("graves.ttl-seconds", 1200)) * 1000L;
        holoOn = c.getBoolean("graves.hologram", true);
        holoUp = c.getDouble("graves.hologram-height", 1.2);
        tickSec = Math.max(1, c.getInt("graves.tick-seconds", 1));
    }

    public void startTicker() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, tickSec * 20L);
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(holo.values())) removeHoloById(id);
        holo.clear();
    }

    // ---------- смерть ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Location loc = v.getLocation();
        deathLoc.put(v.getUniqueId(), loc);

        // сперва буккит-дроп; пусто (Arclight уводит его мимо) — берём инвентарь напрямую
        List<ItemStack> loot = new ArrayList<>();
        for (ItemStack it : e.getDrops()) if (it != null && !it.getType().isAir()) loot.add(it);
        if (loot.isEmpty()) {
            for (ItemStack it : v.getInventory().getContents())
                if (it != null && !it.getType().isAir()) loot.add(it.clone());
            v.getInventory().clear();
        }
        e.getDrops().clear(); // на землю не сыпем

        // голова убитого падает отдельно — трофей, доступен любому
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(v);
            sm.setDisplayName(Palette.S + "cГолова " + v.getName());
            head.setItemMeta(sm);
        }
        v.getWorld().dropItemNaturally(loc, head);

        if (!loot.isEmpty()) {
            Block b = loc.getBlock();
            if (b.getY() <= b.getWorld().getMinHeight()) b = b.getRelative(0, 1, 0); // из бездны сундук не поставить
            b.setType(Material.CHEST, false);
            graves.put(key(b.getLocation()),
                    new Grave(new ArrayList<>(loot), v.getUniqueId(), System.currentTimeMillis(), v.getName()));
            save();
        }

        // DeathMessages формирует текст, мы после него красим ники
        String msg = e.getDeathMessage();
        if (msg == null || msg.isEmpty()) return;
        msg = Palette.HEX_RAW.matcher(msg).replaceAll(""); // протёкшие сырые &#RRGGBB
        // в градиент-нике символы разбиты §x, поэтому замена киллера не заденет жертву
        msg = msg.replace(v.getName(), names.gradName(v));
        Player k = v.getKiller();
        if (k != null && !k.getName().equals(v.getName())) msg = msg.replace(k.getName(), names.gradName(k));
        e.setDeathMessage(msg);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Location d = deathLoc.remove(e.getPlayer().getUniqueId());
        if (d == null) return;
        Player p = e.getPlayer();
        String c = Palette.S + "eТочка смерти: " + Palette.S + "fx: " + d.getBlockX()
                + " y: " + d.getBlockY() + " z: " + d.getBlockZ();
        p.sendMessage(c);

        // компас смотрит на могилу через лодстоун-таргет, самого лодстоуна нет
        ItemStack compass = new ItemStack(Material.COMPASS);
        if (compass.getItemMeta() instanceof CompassMeta cm) {
            cm.setLodestone(d);
            cm.setLodestoneTracked(false);
            cm.setDisplayName(Palette.S + "e" + Palette.S + "lМогила");
            cm.setLore(List.of(Palette.S + "7Точка смерти: " + Palette.S + "fx: " + d.getBlockX()
                    + " y: " + d.getBlockY() + " z: " + d.getBlockZ()));
            compass.setItemMeta(cm);
        }
        p.getInventory().addItem(compass);

        // через секунду — ещё раз в экшн-бар: сразу после респавна её затирает вход в мир
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(c)), 20L);
    }

    // ---------- хранилище ----------

    private static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private static Location keyToLoc(String key) {
        String[] p = key.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void load() {
        graves.clear();
        if (file == null || !file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();
        for (String k : y.getKeys(false)) {
            ConfigurationSection sec = y.getConfigurationSection(k);
            if (sec == null) continue;
            List<?> raw = sec.getList("items");
            if (raw == null) continue;
            List<ItemStack> items = new ArrayList<>();
            for (Object o : raw) if (o instanceof ItemStack is) items.add(is);
            if (items.isEmpty()) continue;

            UUID owner = null;
            String os = sec.getString("owner", "");
            if (os != null && !os.isEmpty()) {
                try { owner = UUID.fromString(os); } catch (Exception ignored) {} // битый uuid — могила станет общей
            }
            String nm = sec.getString("name", "");
            if (nm != null && nm.isEmpty()) nm = null;
            // записям без метки времени ставим «сейчас», иначе они умрут сразу после обновления
            graves.put(k, new Grave(items, owner, sec.getLong("born", now), nm));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Grave> en : graves.entrySet()) {
            String k = en.getKey();
            Grave g = en.getValue();
            y.set(k + ".items", g.items);
            y.set(k + ".owner", g.owner == null ? "" : g.owner.toString());
            y.set(k + ".born", g.born);
            if (g.ownerName != null) y.set(k + ".name", g.ownerName);
        }
        try {
            y.save(file);
        } catch (Exception ex) {
            plugin.getLogger().warning("graves save: " + ex);
        }
    }

    // ---------- таймер и голограмма ----------

    // Один общий тик на все могилы, голограммы трогаем только в прогруженных чанках —
    // поэтому ТПС не страдает даже когда могил сотня.
    private void tick() {
        if (graves.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (Map.Entry<String, Grave> en : new ArrayList<>(graves.entrySet())) {
            long left = ttlMs - (now - en.getValue().born);
            if (left <= 0) {
                expire(en.getKey());
                dirty = true;
                continue;
            }
            if (holoOn) updateHolo(en.getKey(), left);
        }
        if (dirty) save();
    }

    // протухла: сносим сундук, голограмму и запись — вещи пропадают вместе с ней
    private void expire(String k) {
        removeHolo(k);
        breakChest(k, true);
        graves.remove(k);
    }

    // requireLoaded: по таймеру в выгруженный чанк не лезем — обращение к блоку
    // затянуло бы чанк обратно в память. При закрытии сундука игроком чанк заведомо на месте.
    private void breakChest(String k, boolean requireLoaded) {
        Location l = keyToLoc(k);
        if (l == null || l.getWorld() == null) return;
        if (requireLoaded && !l.getChunk().isLoaded()) return;
        Block b = l.getBlock();
        if (b.getType() == Material.CHEST) b.setType(Material.AIR, false);
    }

    private void updateHolo(String k, long leftMs) {
        Location l = keyToLoc(k);
        if (l == null || l.getWorld() == null || !l.getChunk().isLoaded()) return;
        long sec = Math.max(0, leftMs / 1000);
        Grave g = graves.get(k);
        String who = g == null || g.ownerName == null ? "" : g.ownerName;
        String text = Palette.S + "c☠ " + Palette.S + "fМогила" + (who.isEmpty() ? "" : " " + who)
                + "\n" + (sec <= 60 ? Palette.S + "c" : Palette.S + "e") + String.format("%d:%02d", sec / 60, sec % 60);

        Entity ent = null;
        UUID id = holo.get(k);
        if (id != null) ent = Bukkit.getEntity(id);
        if (ent == null || ent.isDead()) {
            Location at = l.clone().add(0.5, holoUp, 0.5);
            TextDisplay td = l.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setSeeThrough(false);
                d.setShadowed(true);
                d.setPersistent(false);            // не сохраняется в мир — не останется висяков после падения сервера
                d.addScoreboardTag("wardengrave"); // метка для чистки осиротевших
            });
            holo.put(k, td.getUniqueId());
            ent = td;
        }
        if (ent instanceof TextDisplay td) td.setText(text);
    }

    private void removeHolo(String k) { removeHoloById(holo.remove(k)); }

    private void removeHoloById(UUID id) {
        if (id == null) return;
        Entity e = Bukkit.getEntity(id);
        if (e != null && !e.isDead()) e.remove();
    }

    // ---------- доступ к сундуку ----------

    private static final class Holder implements InventoryHolder {
        final String key;
        Inventory inv;

        Holder(String key) { this.key = key; }

        @Override public Inventory getInventory() { return inv; }
    }

    @EventHandler
    public void onOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.CHEST) return;
        String gk = key(b.getLocation());
        Grave g = graves.get(gk);
        if (g == null) return; // обычный сундук — не трогаем
        e.setCancelled(true);
        if (g.owner != null && !g.owner.equals(e.getPlayer().getUniqueId())) {
            e.getPlayer().sendMessage(Palette.S + "c☠ Это не твоя могила — залутать может только владелец.");
            return;
        }
        // виртуальный инвентарь: сам сундук пустой, вещи живут в записи
        Holder h = new Holder(gk);
        Inventory inv = Bukkit.createInventory(h, 54, Palette.S + "c☠ Могила");
        h.inv = inv;
        for (ItemStack it : g.items) if (it != null) inv.addItem(it.clone());
        e.getPlayer().openInventory(inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack it : e.getInventory().getContents())
            if (it != null && !it.getType().isAir()) left.add(it);
        if (left.isEmpty()) {
            graves.remove(h.key);
            removeHolo(h.key);
            breakChest(h.key, false);
        } else {
            // Могила могла протухнуть, пока сундук был открыт. Вещи в этом случае
            // всё равно возвращаем — но уже безымянной могилой, как было и раньше.
            Grave g = graves.computeIfAbsent(h.key, x -> new Grave(new ArrayList<>(), null, System.currentTimeMillis(), null));
            g.items = left;
        }
        save();
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.CHEST) return;
        String gk = key(e.getBlock().getLocation());
        Grave g = graves.get(gk);
        if (g == null) return;
        if (g.owner != null && !g.owner.equals(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Palette.S + "c☠ Это не твоя могила — сломать может только владелец.");
            return;
        }
        graves.remove(gk);
        removeHolo(gk);
        save();
        for (ItemStack it : g.items)
            if (it != null) e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), it);
    }
}
