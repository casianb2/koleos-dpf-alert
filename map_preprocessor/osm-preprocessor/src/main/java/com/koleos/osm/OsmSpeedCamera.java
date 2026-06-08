package com.koleos.osm;

import java.util.*;

public class OsmSpeedCamera {
    public long id;
    public double lat;
    public double lon;
    public Map<String, String> tags = new HashMap<>();
    public List<Map<String, String>> relationTags = new ArrayList<>();
    public Set<Long> relatedWayIds = new HashSet<>();
    public Set<Long> fromWayIds = new HashSet<>();
    public Set<Long> toWayIds = new HashSet<>();

    public OsmSpeedCamera(long id) {
        this.id = id;
        this.lat = Double.NaN;
        this.lon = Double.NaN;
    }

    public boolean hasLocation() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    public String primaryEnforcementType() {
        for (Map<String, String> relTags : relationTags) {
            String enforcement = relTags.get("enforcement");
            if (enforcement != null && !enforcement.isBlank()) {
                return enforcement;
            }
        }

        String[] keys = {
                "enforcement",
                "enforcement:type",
                "camera:enforcement",
                "speed_camera:enforcement"
        };

        for (String key : keys) {
            String value = tags.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }
}
