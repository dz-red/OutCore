package fun.wardensmp.outcore;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * OutCore — визуальный движок сервера: градиенты ников, лица игроков в чате,
 * приватные могилы с таймером. Spigot-версия под Arclight, без Adventure:
 * градиенты рендерятся вручную как legacy §x-hex, поэтому MiniMessage не нужен.
 */
public class OutCore extends JavaPlugin {

    private HeadGlyphs heads;
    private NameFormat names;
    private Graves graves;

    @Override public void onEnable() {
        migrateFromWardenChat();
        saveDefaultConfig();

        heads = new HeadGlyphs(this);
        names = new NameFormat(heads);
        graves = new Graves(this, names);

        Bukkit.getPluginManager().registerEvents(names, this);
        Bukkit.getPluginManager().registerEvents(graves, this);
        OutCorePapi.registerAll(heads, names);
        graves.startTicker();

        getLogger().info("OutCore enabled (голов: " + heads.size() + ", могил: " + graves.size()
            + ", живут " + graves.ttlSeconds() + " сек)");
    }

    @Override public void onDisable() {
        if (graves != null) graves.shutdown();
        if (heads != null) heads.savePackSent();
    }

    // Плагин переименован из WardenChat, а вместе с именем сменилась и папка данных.
    // Переносим старую при первом запуске, иначе потеряются могилы с вещами игроков.
    private void migrateFromWardenChat() {
        File now = getDataFolder();
        File old = new File(now.getParentFile(), "WardenChat");
        if (!old.isDirectory() || new File(now, "graves.yml").isFile()) return;
        if (!now.isDirectory() && !now.mkdirs()) return;

        int moved = 0;
        for (String name : new String[]{"config.yml", "graves.yml", "heads.yml", "pack_sent.yml"}) {
            File src = new File(old, name);
            if (!src.isFile()) continue;
            try {
                Files.copy(src.toPath(), new File(now, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved++;
            } catch (Exception e) {
                getLogger().warning("не перенёс " + name + " из WardenChat: " + e);
            }
        }
        if (moved > 0) getLogger().info("Перенесено файлов из plugins/WardenChat: " + moved);
    }
}
