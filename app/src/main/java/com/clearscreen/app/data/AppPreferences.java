package com.clearscreen.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public class AppPreferences {
    private static final String PREF_NAME = "clear_screen_prefs";
    private static final String KEY_WHITELIST = "whitelist_packages";
    private static final String KEY_SHIELD_ENABLED = "touch_shield_enabled";

    private final SharedPreferences prefs;

    private static AppPreferences instance;

    public static synchronized AppPreferences getInstance(Context context) {
        if (instance == null) {
            instance = new AppPreferences(context.getApplicationContext());
        }
        return instance;
    }

    private AppPreferences(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isTouchShieldEnabled() {
        return prefs.getBoolean(KEY_SHIELD_ENABLED, true);
    }

    public void setTouchShieldEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHIELD_ENABLED, enabled).apply();
    }

    public boolean isPackageWhitelisted(String packageName) {
        Set<String> set = prefs.getStringSet(KEY_WHITELIST, new HashSet<>());
        return set.contains(packageName);
    }

    public void addWhitelistPackage(String packageName) {
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_WHITELIST, new HashSet<>()));
        set.add(packageName);
        prefs.edit().putStringSet(KEY_WHITELIST, set).apply();
    }

    public void removeWhitelistPackage(String packageName) {
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_WHITELIST, new HashSet<>()));
        set.remove(packageName);
        prefs.edit().putStringSet(KEY_WHITELIST, set).apply();
    }
}
