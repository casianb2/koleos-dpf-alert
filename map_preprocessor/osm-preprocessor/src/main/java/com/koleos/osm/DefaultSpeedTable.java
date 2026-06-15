package com.koleos.osm;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class DefaultSpeedTable {
    public static final int UNDEFINED = -1;

    private static final Map<String, Integer> RURAL_SPEEDS = new HashMap<>();
    private static final Map<String, Integer> URBAN_SPEEDS = new HashMap<>();

    static {
        //set("motorway", 130, 130);
        //set("trunk", 100, 100);
        //set("primary", 90, 50);
        //set("secondary", 90, 50);
        //set("residential", 50, 50);
        set("track", UNDEFINED, UNDEFINED);
    }

    /**
     * Returns an explicit override for the supplied highway type.
     *
     * null means no table override exists, so the caller should continue with
     * legal_default_speeds.json inference. UNDEFINED means the speed should be
     * treated as unknown/undefined and must not fall back to JSON inference.
     */
    public static Integer lookup(String countryCode, String highway, boolean urban) {
        if (highway == null || highway.isBlank()) return null;

        String key = highway.toLowerCase(Locale.ROOT);
        Map<String, Integer> speeds = urban ? URBAN_SPEEDS : RURAL_SPEEDS;

        return speeds.get(key);
    }

    private static void set(String highway, int ruralSpeed, int urbanSpeed) {
        RURAL_SPEEDS.put(highway, ruralSpeed);
        URBAN_SPEEDS.put(highway, urbanSpeed);
    }
}
