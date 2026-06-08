package com.koleos.osm;

public class DefaultSpeedTable {

    public static int lookup(String countryCode, String highway, boolean urban) {
        if (highway == null) return 50;

        countryCode = countryCode.toUpperCase();

        if (countryCode.equals("RO")) {
            if ("motorway".equals(highway)) return 130;
            if ("trunk".equals(highway)) return 100;
            if ("primary".equals(highway) || "secondary".equals(highway)) return urban ? 50 : 90;
            if ("residential".equals(highway)) return 50;
        }

        if (countryCode.equals("HU")) {
            if ("motorway".equals(highway)) return 130;
            if ("trunk".equals(highway)) return 110;
            if ("primary".equals(highway) || "secondary".equals(highway)) return urban ? 50 : 90;
            if ("residential".equals(highway)) return 50;
        }

        return urban ? 50 : 90;
    }
}
