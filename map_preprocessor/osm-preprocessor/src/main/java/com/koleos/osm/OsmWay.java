package com.koleos.osm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OsmWay {
    public long id;
    public List<Long> nodeIds = new ArrayList<>();
    public Map<String, String> tags = new HashMap<>();
    public List<Map<String, String>> relationTags = new ArrayList<>();
    public Integer computedMaxspeedForwardKmh;
    public Integer computedMaxspeedBackwardsKmh;
    public OsmWay(long id) {
        this.id = id;
    }
}