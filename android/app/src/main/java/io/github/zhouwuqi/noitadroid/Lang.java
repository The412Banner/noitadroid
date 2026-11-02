package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * The language of the app's own screens (the first-start screen, the in-game menu): English unless the user picks
 * Chinese in either, whatever the system's language. The game has its own language setting.
 */
final class Lang {
    static final String EN = "en", ZH = "zh";

    private Lang() {}

    static String get(Context c) {
        return c.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("lang", EN);
    }

    static void set(Context c, String lang) {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("lang", lang).apply();
    }

    static String other(Context c) {
        return ZH.equals(get(c)) ? EN : ZH;
    }

    /** A context whose strings are in the chosen language (res/values is English, res/values-zh Chinese). */
    static Context wrap(Context base) {
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(ZH.equals(get(base)) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH);
        return base.createConfigurationContext(conf);
    }
}
