package com.koleos.osm;

public class OsmNode {
    public long id;
    public double lat;
    public double lon;

    public OsmNode(long id, double lat, double lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
    }
}
