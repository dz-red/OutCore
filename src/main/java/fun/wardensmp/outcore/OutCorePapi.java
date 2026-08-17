package fun.wardensmp.outcore;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

// %outcore_head%, %outcore_name%, %outcore_boldname%.
// Старый идентификатор wardenchat регистрируется вторым — на него завязан конфиг TAB.
public class OutCorePapi extends PlaceholderExpansion {

    private final String id;
    private final HeadGlyphs heads;
    private final NameFormat names;

    public OutCorePapi(String id, HeadGlyphs heads, NameFormat names) {
        this.id = id; this.heads = heads; this.names = names;
    }

    public static void registerAll(HeadGlyphs heads, NameFormat names) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        new OutCorePapi("outcore", heads, names).register();
        new OutCorePapi("wardenchat", heads, names).register();
    }

    @Override public String getIdentifier() { return id; }
    @Override public String getAuthor() { return "Daniil"; }
    @Override public String getVersion() { return "1.0"; }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer p, String params) {
        if (p == null || p.getName() == null) return "";

        // готовый §x-градиент для TAB: сам он жирный градиент не умеет, §x сбрасывает жирность
        if ("boldname".equalsIgnoreCase(params) || "name".equalsIgnoreCase(params)) {
            Player pl = p.getPlayer();
            if (pl == null) return p.getName();
            String[] gr = names.gradFor(pl);
            return Palette.gradient(gr[0], gr[1], pl.getName(), "boldname".equalsIgnoreCase(params));
        }
        if (!"head".equalsIgnoreCase(params)) return null;
        return heads.glyph(p.getName());
    }
}
