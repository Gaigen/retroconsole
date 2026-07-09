package com.retroconsole.client;

import com.retroconsole.library.GameSystem;
import net.minecraft.network.chat.Component;

/** UI translation keys under {@code retroconsole.*}. */
public final class ModTexts {

    private static final String P = "retroconsole.";

    private static final String[] HELP_SERVER = {
            "help.server.00", "help.server.01", "help.server.02", "help.server.03",
            "help.server.04", "help.server.05", "help.server.06", "help.server.07",
            "help.server.08", "help.server.09", "help.server.10", "help.server.11",
            "help.server.12", "help.server.13",
    };
    private static final String[] HELP_LOCAL = {
            "help.local.00", "help.local.01", "help.local.02", "help.local.03",
            "help.local.04", "help.local.05", "help.local.06", "help.local.07",
            "help.local.08", "help.local.09", "help.local.10", "help.local.11",
            "help.local.12", "help.local.13", "help.local.14", "help.local.15",
            "help.local.16", "help.local.17",
    };

    private ModTexts() {}

    public static Component c(String key, Object... args) {
        return Component.translatable(P + key, args);
    }

    public static String s(String key, Object... args) {
        return c(key, args).getString();
    }

    public enum PluralForm { ONE, FEW, MANY }

    public static PluralForm pluralForm(long n) {
        long mod10 = n % 10;
        long mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return PluralForm.ONE;
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return PluralForm.FEW;
        return PluralForm.MANY;
    }

    public static String gamesWord(long n) {
        return s("count.games." + pluralForm(n).name().toLowerCase());
    }

    public static String coresWord(long n) {
        return s("count.cores." + pluralForm(n).name().toLowerCase());
    }

    public static String tab(GameSystem sys) {
        if (sys == GameSystem.OTHER) return s("system.other.tab");
        return sys.tab;
    }

    public static String fullName(GameSystem sys) {
        if (sys == GameSystem.OTHER) return s("system.other.full");
        return sys.fullName;
    }

    public static String tabLabel(GameSystem sys, boolean withCount, long count) {
        if (sys == null) {
            return withCount ? s("tab.all_count", count) : s("tab.all");
        }
        if (!withCount) return tab(sys);
        return count > 0 ? tab(sys) + " (" + count + ")" : tab(sys);
    }

    public static String[] helpLines(boolean serverLibrary) {
        String[] keys = serverLibrary ? HELP_SERVER : HELP_LOCAL;
        String[] lines = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            lines[i] = s(keys[i]);
        }
        return lines;
    }
}
