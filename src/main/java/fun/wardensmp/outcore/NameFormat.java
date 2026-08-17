package fun.wardensmp.outcore;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

// Ник игрока: голова + префикс LuckPerms + градиент по группе + суффикс.
// Данные LP читаются вживую, чтобы смена группы применялась без перезахода.
public class NameFormat implements Listener {

    private final HeadGlyphs heads;
    private LuckPerms lp;

    public NameFormat(HeadGlyphs heads) {
        this.heads = heads;
        RegisteredServiceProvider<LuckPerms> reg = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (reg != null) lp = reg.getProvider();
    }

    // группа с наибольшим весом — так же, как сортирует TAB
    public String[] gradFor(Player p) {
        String[] best = Palette.fallback();
        int bw = Integer.MIN_VALUE;
        User u = (lp == null) ? null : lp.getUserManager().getUser(p.getUniqueId());
        if (u != null) for (Group g : u.getInheritedGroups(u.getQueryOptions())) {
            String[] col = Palette.of(g.getName());
            if (col == null) continue;
            int w = g.getWeight().orElse(0);
            if (w > bw) { bw = w; best = col; }
        }
        return best;
    }

    private String rawPrefix(Player p) {
        if (lp == null) return null;
        User u = lp.getUserManager().getUser(p.getUniqueId());
        return u == null ? null : u.getCachedData().getMetaData().getPrefix();
    }

    public String gradName(Player p) {
        String[] gr = gradFor(p);
        // жирность наследуем из &l в префиксе группы, но только на сам ник
        String pfx = rawPrefix(p);
        boolean bold = pfx != null && pfx.contains("&l");
        return Palette.gradient(gr[0], gr[1], p.getName(), bold);
    }

    private String affix(Player p, boolean prefix) {
        if (lp == null) return "";
        User u = lp.getUserManager().getUser(p.getUniqueId());
        if (u == null) return "";
        String s = prefix ? u.getCachedData().getMetaData().getPrefix() : u.getCachedData().getMetaData().getSuffix();
        return (s == null || s.isEmpty()) ? "" : Palette.colorize(s);
    }

    public String fullName(Player p) {
        return heads.glyph(p) + affix(p, true) + gradName(p) + affix(p, false);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChat(AsyncPlayerChatEvent e) {
        String fname = fullName(e.getPlayer());
        // %2$s — сообщение; ник подставляем готовым, поэтому его % экранируем
        e.setFormat(fname.replace("%", "%%") + " " + Palette.S + "7» " + Palette.S + "f%2$s");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        heads.sendPack(e.getPlayer());
        e.setJoinMessage(Palette.S + "a+ " + Palette.S + "r" + fullName(e.getPlayer()) + Palette.S + "7 зашёл на сервер");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        e.setQuitMessage(Palette.S + "c- " + Palette.S + "r" + fullName(e.getPlayer()) + Palette.S + "7 покинул сервер");
    }
}
