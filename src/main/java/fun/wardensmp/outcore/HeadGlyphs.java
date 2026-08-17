package fun.wardensmp.outcore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Лица игроков в чате: битмап-шрифт в ресурс-паке, heads.yml пишет gen_heads.py,
// тут только чтение и раздача пака.
public class HeadGlyphs {

    private final JavaPlugin plugin;
    private final Map<String, String> chars = new HashMap<>();
    private final Map<UUID, String> packSent = new HashMap<>();
    private final File headsFile, packSentFile;

    private boolean enabled;
    private String packUrl, packSha1;
    private long mtime, lastCheck;

    public HeadGlyphs(JavaPlugin plugin) {
        this.plugin = plugin;
        headsFile = new File(plugin.getDataFolder(), "heads.yml");
        packSentFile = new File(plugin.getDataFolder(), "pack_sent.yml");
        loadPackSent();
        load();
    }

    public int size() { return chars.size(); }
    public boolean enabled() { return enabled; }

    public void load() {
        chars.clear();
        enabled = false;
        if (!headsFile.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(headsFile);
        enabled = y.getBoolean("enabled", true);
        packUrl = y.getString("url");
        packSha1 = y.getString("sha1");
        ConfigurationSection cs = y.getConfigurationSection("chars");
        if (cs != null) for (String k : cs.getKeys(false)) chars.put(k.toLowerCase(), cs.getString(k));
        mtime = headsFile.lastModified();
    }

    // пак пересобирается ботом — подхватываем новый heads.yml без рестарта, проверка раз в минуту
    private void maybeReload() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 60_000L) return;
        lastCheck = now;
        if (headsFile.lastModified() != mtime) load();
    }

    // белый цвет — чтобы битмап рендерился в оригинальных цветах пнг
    public String glyph(String name) {
        maybeReload();
        if (!enabled) return "";
        String ch = chars.get(name.toLowerCase());
        return ch == null ? "" : Palette.S + "f" + ch + " ";
    }

    public String glyph(Player p) { return glyph(p.getName()); }

    // Пак пушим только если у игрока ещё нет текущей версии: повторное применение
    // рушит стейт TACZ, и стрельба пропадает до самой смерти.
    public void sendPack(Player p) {
        if (!enabled || packUrl == null || packSha1 == null) return;
        try {
            p.setResourcePack(packUrl, Palette.hexToBytes(packSha1),
                "OUTBREAK — ресурспак сервера (лого + головы)", true);
        } catch (Throwable t) {
            plugin.getLogger().warning("не смог отправить heads-пак: " + t);
        }
    }

    private void loadPackSent() {
        packSent.clear();
        if (!packSentFile.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(packSentFile);
        for (String k : y.getKeys(false)) {
            try { packSent.put(UUID.fromString(k), y.getString(k)); } catch (Exception ignored) {}
        }
    }

    public void savePackSent() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, String> en : packSent.entrySet()) y.set(en.getKey().toString(), en.getValue());
        try { y.save(packSentFile); } catch (Exception e) { plugin.getLogger().warning("pack_sent save: " + e); }
    }
}
