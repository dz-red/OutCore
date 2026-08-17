package fun.wardensmp.outcore;

import org.bukkit.ChatColor;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Цвета групп и рендер градиента в legacy §x-hex. Adventure на Arclight нет,
// поэтому красим посимвольно вручную — так работает на любом Spigot 1.16+.
public final class Palette {

    public static final char S = '§';
    public static final Pattern HEX_AMP = Pattern.compile("&#([0-9A-Fa-f]{6})");
    public static final Pattern HEX_RAW = Pattern.compile("&#[0-9A-Fa-f]{6}");

    private static final Map<String, String[]> PAL = new HashMap<>();

    static {
        PAL.put("owner",   new String[]{"#FFD700", "#FF8C00"});
        PAL.put("admin",   new String[]{"#FF4D4D", "#FF9FB6"});
        PAL.put("mod",     new String[]{"#2ECC71", "#B6FF7A"});
        PAL.put("sponsor", new String[]{"#B48CFF", "#FF6FD8"});
        PAL.put("ivent",   new String[]{"#3FE0FF", "#4C7BFF"});
        PAL.put("mail",    new String[]{"#F5B942", "#FFE58A"});
        PAL.put("media",   new String[]{"#FF3B3B", "#A80000"});
        PAL.put("default", new String[]{"#AEB6BF", "#ECF0F1"});
    }

    private Palette() {}

    public static String[] of(String group) { return PAL.get(group); }
    public static String[] fallback() { return PAL.get("default"); }

    // &#RRGGBB -> §x§R§R§G§G§B§B, плюс обычные &-коды
    public static String colorize(String s) {
        if (s == null || s.isEmpty()) return "";
        Matcher m = HEX_AMP.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(hexSeq(m.group(1))));
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static String hexSeq(String hex6) {
        StringBuilder sb = new StringBuilder().append(S).append('x');
        for (int i = 0; i < 6; i++) sb.append(S).append(hex6.charAt(i));
        return sb.toString();
    }

    // плавный переход по символам: §x-hex перед каждым (+ §l если жирный)
    public static String gradient(String startHex, String endHex, String text, boolean bold) {
        int[] a = rgb(startHex), b = rgb(endHex);
        int n = text.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double t = (n <= 1) ? 0.0 : (double) i / (n - 1);
            int r = (int) Math.round(a[0] + (b[0] - a[0]) * t);
            int g = (int) Math.round(a[1] + (b[1] - a[1]) * t);
            int bl = (int) Math.round(a[2] + (b[2] - a[2]) * t);
            sb.append(hexSeq(String.format("%02x%02x%02x", r, g, bl)));
            if (bold) sb.append(S).append('l');
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    private static int[] rgb(String hex) {
        String h = hex.replace("#", "");
        return new int[]{Integer.parseInt(h.substring(0, 2), 16),
                         Integer.parseInt(h.substring(2, 4), 16),
                         Integer.parseInt(h.substring(4, 6), 16)};
    }

    public static byte[] hexToBytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
