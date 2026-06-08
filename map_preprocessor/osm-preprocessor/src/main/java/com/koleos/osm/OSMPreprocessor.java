package com.koleos.osm;

import crosby.binary.osmosis.OsmosisReader;
import org.openstreetmap.osmosis.core.container.v0_6.*;
import org.openstreetmap.osmosis.core.domain.v0_6.*;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import java.io.*;
import java.util.*;

public class OSMPreprocessor {
    private static final double CELL_SIZE = 0.5;
    private static final double SPEED_CAMERA_TILE_RADIUS_METERS = 3000.0;

    
    /*
    CELL_SIZE is geographical size of each grid tile your OSMPreprocessor produces 
    CELL_SIZE is measured in degrees of latitude/longitude, not meters.
    CELL_SIZE = 0.01 degrees. This defines the width and height of each grid cell.
    How big is 0.01 degrees in real distance?
    Latitude
    1 degree latitude ≈ 111.32 km
    So 0.01° latitude ≈ 1.113 km

    Longitude
    Longitude depends on where you are (because Earth is round).
    In Romania (around 45° latitude):
    1 degree longitude ≈ 78.7 km
    So 0.01° longitude ≈ 0.787 km

    So each grid tile is roughly:
    ~1.1 km tall
    ~0.8 km wide

    How the tile index is computed:
    int ix = (int) Math.floor((lon + 180.0) / CELL_SIZE);
    int iy = (int) Math.floor((lat + 90.0) / CELL_SIZE);
    This converts GPS coordinates into a grid index.

    Example:
    lat = 45.75
    lon = 21.23

    ix = floor((21.23 + 180) / 0.01) = floor(20123 / 0.01) = 20123
    iy = floor((45.75 + 90) / 0.01) = floor(135.75 / 0.01) = 13575

    So the tile file is:
    grid_RO_20123_13575.osm
    The Android app loads exactly that file.

    If you want different tile sizes
    You can change CELL_SIZE:

    CELL_SIZE	Approx size	Use case
    0.001	    ~100 m	Very precise, many files
    0.005	    ~500 m	Good for cities
    0.01	    ~1 km	Balanced (recommended)
    0.02	    ~2 km	Faster preprocessing, slower Android lookup
    */

    private final Map<Long, OsmWay> ways = new HashMap<>();
    private final Set<Long> neededNodeIds = new HashSet<>();
    private final Map<Long, OsmNode> nodes = new HashMap<>();
    private final Map<Long, OsmSpeedCamera> speedCameras = new HashMap<>();

    private LegalDefaultSpeedEngine speedEngine;
    private boolean requireCameraMaxspeedTag = false;

    public static void main(String[] args) throws Exception {
    boolean requireCameraMaxspeedTag = Arrays.asList(args).contains("-maxspeed");

    List<String> positional = new ArrayList<>();
    for (String arg : args) {
        if (!"-maxspeed".equals(arg)) {
            positional.add(arg);
        }
    }

    if (positional.size() < 4) {
        System.out.println("Usage: java -jar OSMPreprocessor.jar [-maxspeed] <countryCode> <input.pbf> <legal_default_speeds.json> <outputDir>");
        return;
    }

    String countryCode = positional.get(0).toUpperCase(Locale.ROOT);
    File input = new File(positional.get(1));
    File speedJson = new File(positional.get(2));
    File outputDir = new File(positional.get(3));

    if (!outputDir.exists()) outputDir.mkdirs();

    OSMPreprocessor preprocessor = new OSMPreprocessor();
    preprocessor.requireCameraMaxspeedTag = requireCameraMaxspeedTag;
    preprocessor.process(countryCode, input, speedJson, outputDir);
}

    public void process(String countryCode, File inputPbf, File speedJson, File outputDir) throws Exception {
        speedEngine = new LegalDefaultSpeedEngine(speedJson);

        System.out.println("Reading ways...");
        readWays(inputPbf);

        System.out.println("Ways: " + ways.size());
        System.out.println("Needed nodes: " + neededNodeIds.size());

        System.out.println("Reading road-route and enforcement relations...");
        readRelations(inputPbf);

        System.out.println("Reading nodes...");
        readNodes(inputPbf);

        System.out.println("Nodes loaded: " + nodes.size());
        System.out.println("Speed cameras loaded: " + speedCameras.size());

        System.out.println("Computing maxspeed...");
        for (OsmWay w : ways.values()) {
            computeDirectionalMaxspeeds(countryCode, w);
        }

        System.out.println("Assigning to grid...");
        GridIndex grid = assignToGrid();

        System.out.println("Grid cells: " + grid.cellCount());
        System.out.println("Writing XML tiles...");

        writeTiles(countryCode, outputDir, grid);

        System.out.println("Done.");
    }

    private void readWays(File input) throws Exception {
        OsmosisReader reader = new OsmosisReader(new FileInputStream(input));

        reader.setSink(new Sink() {
            @Override
            public void process(EntityContainer ec) {
                if (!(ec instanceof WayContainer)) return;

                Way w = ((WayContainer) ec).getEntity();
                OsmWay ow = new OsmWay(w.getId());

                for (WayNode wn : w.getWayNodes()) {
                    ow.nodeIds.add(wn.getNodeId());
                }

                for (Tag t : w.getTags()) {
                    ow.tags.put(t.getKey(), t.getValue());
                }

                if (ow.tags.containsKey("highway")) {
                    ways.put(ow.id, ow);
                    neededNodeIds.addAll(ow.nodeIds);
                }
            }

            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {}
            @Override public void close() {}
        });

        reader.run();
    }

    private void readRelations(File input) throws Exception {
        OsmosisReader reader = new OsmosisReader(new FileInputStream(input));

        reader.setSink(new Sink() {
            @Override
            public void process(EntityContainer ec) {
                if (!(ec instanceof RelationContainer)) return;

                Relation r = ((RelationContainer) ec).getEntity();

                Map<String, String> relationTags = new HashMap<>();
                for (Tag t : r.getTags()) {
                    relationTags.put(t.getKey(), t.getValue());
                }

                if ("route".equals(relationTags.get("type")) && "road".equals(relationTags.get("route"))) {
                    for (RelationMember m : r.getMembers()) {
                        if (m.getMemberType() == EntityType.Way && ways.containsKey(m.getMemberId())) {
                            ways.get(m.getMemberId()).relationTags.add(relationTags);
                        }
                    }

                    return;
                }

                if (!"enforcement".equals(relationTags.get("type"))) return;

                String enforcement = relationTags.get("enforcement");
                if (
                        !"maxspeed".equals(enforcement) &&
                        !"average_speed".equals(enforcement) &&
                        !"traffic_signals".equals(enforcement)
                ) {
                    return;
                }

                for (RelationMember m : r.getMembers()) {
                    if (m.getMemberType() == EntityType.Node && "device".equals(m.getMemberRole())) {
                        OsmSpeedCamera camera = speedCameras.computeIfAbsent(
                                m.getMemberId(),
                                OsmSpeedCamera::new
                        );

                        camera.relationTags.add(relationTags);
                    }

                    if (m.getMemberType() == EntityType.Way) {
                        if ("from".equals(m.getMemberRole())) {
                            for (RelationMember device : r.getMembers()) {
                                if (device.getMemberType() == EntityType.Node && "device".equals(device.getMemberRole())) {
                                    speedCameras.computeIfAbsent(device.getMemberId(), OsmSpeedCamera::new)
                                            .fromWayIds.add(m.getMemberId());
                                }
                            }
                        } else if ("to".equals(m.getMemberRole())) {
                            for (RelationMember device : r.getMembers()) {
                                if (device.getMemberType() == EntityType.Node && "device".equals(device.getMemberRole())) {
                                    speedCameras.computeIfAbsent(device.getMemberId(), OsmSpeedCamera::new)
                                            .toWayIds.add(m.getMemberId());
                                }
                            }
                        }
                    }
                }
            }

            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {}
            @Override public void close() {}
        });

        reader.run();
    }

    private void readNodes(File input) throws Exception {
        OsmosisReader reader = new OsmosisReader(new FileInputStream(input));

        reader.setSink(new Sink() {
            @Override
            public void process(EntityContainer ec) {
                if (!(ec instanceof NodeContainer)) return;

                Node n = ((NodeContainer) ec).getEntity();

                Map<String, String> nodeTags = new HashMap<>();
                for (Tag t : n.getTags()) {
                    nodeTags.put(t.getKey(), t.getValue());
                }

                boolean isSpeedCamera = "speed_camera".equals(nodeTags.get("highway"));

                if (neededNodeIds.contains(n.getId())) {
                    nodes.put(n.getId(), new OsmNode(n.getId(), n.getLatitude(), n.getLongitude()));
                }

                if (isSpeedCamera || speedCameras.containsKey(n.getId())) {
                    OsmSpeedCamera camera = speedCameras.computeIfAbsent(
                            n.getId(),
                            OsmSpeedCamera::new
                    );

                    camera.lat = n.getLatitude();
                    camera.lon = n.getLongitude();
                    camera.tags.putAll(nodeTags);
                }
            }

            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {}
            @Override public void close() {}
        });

        reader.run();
    }

    private void computeDirectionalMaxspeeds(String country, OsmWay way) {
        Integer defaultSpeed = computeBaseMaxspeed(country, way);

        if (defaultSpeed == null || defaultSpeed < 0) {
            way.computedMaxspeedForwardKmh = -1;
            way.computedMaxspeedBackwardsKmh = -1;
            return;
        }

        Integer forwardSpeed = LegalDefaultSpeedEngine.parseMaxspeedKmh(
                way.tags.get("maxspeed:forward")
        );

        Integer backwardSpeed = LegalDefaultSpeedEngine.parseMaxspeedKmh(
                way.tags.get("maxspeed:backward")
        );

        way.computedMaxspeedForwardKmh =
                forwardSpeed != null ? forwardSpeed : defaultSpeed;

        way.computedMaxspeedBackwardsKmh =
                backwardSpeed != null ? backwardSpeed : defaultSpeed;
    }

    private Integer computeBaseMaxspeed(String country, OsmWay way) {
        Integer explicit = LegalDefaultSpeedEngine.parseMaxspeedKmh(
                way.tags.get("maxspeed")
        );

        if (explicit != null) return explicit;

        String highway = way.tags.get("highway");
        if (highway == null) return -1;

        if (
                highway.equals("construction") ||
                highway.equals("proposed") ||
                highway.equals("planned") ||
                highway.equals("abandoned") ||
                highway.equals("razed") ||
                highway.equals("dismantled")
        ) {
            return -1;
        }

        if (way.tags.containsKey("construction") || way.tags.containsKey("proposed")) {
            return -1;
        }

        if (
                highway.equals("footway") ||
                highway.equals("cycleway") ||
                highway.equals("path") ||
                highway.equals("steps") ||
                highway.equals("bridleway") ||
                highway.equals("corridor") ||
                highway.equals("platform")
        ) {
            return -1;
        }

        if (highway.equals("service")) {
            String service = way.tags.get("service");

            if (
                    "driveway".equals(service) ||
                    "parking_aisle".equals(service) ||
                    "alley".equals(service) ||
                    "drive-through".equals(service)
            ) {
                return 30;
            }

            return 50;
        }

        Integer inferred = speedEngine.computeMaxspeedKmh(
                country,
                way.tags,
                way.relationTags
        );

        if (inferred == null) return -1;

        return inferred;
    }

    private GridIndex assignToGrid() {
        GridIndex grid = new GridIndex();

        for (OsmWay w : ways.values()) {
            for (int i = 0; i < w.nodeIds.size() - 1; i++) {
                OsmNode a = nodes.get(w.nodeIds.get(i));
                OsmNode b = nodes.get(w.nodeIds.get(i + 1));
                if (a == null || b == null) continue;

                double lat = (a.lat + b.lat) / 2.0;
                double lon = (a.lon + b.lon) / 2.0;

                GridCellKey cell = cellFor(lat, lon);
                grid.cellToWayIds.computeIfAbsent(cell, k -> new HashSet<>()).add(w.id);
            }
        }

        for (OsmSpeedCamera camera : speedCameras.values()) {
            if (!camera.hasLocation()) continue;
            if (requireCameraMaxspeedTag && !hasNumericMaxspeedTag(camera)) continue;
            camera.relatedWayIds.addAll(findWaysContainingNode(camera.id));
            camera.relatedWayIds.addAll(camera.fromWayIds);
            camera.relatedWayIds.addAll(camera.toWayIds);

            for (GridCellKey cell : cellsAround(camera.lat, camera.lon, SPEED_CAMERA_TILE_RADIUS_METERS)) {
                grid.cellToCameraIds.computeIfAbsent(cell, k -> new HashSet<>()).add(camera.id);
            }
        }

        return grid;
    }

    private boolean hasNumericMaxspeedTag(OsmSpeedCamera camera) {
        String maxspeed = camera.tags.get("maxspeed");
        return maxspeed != null && maxspeed.trim().matches("\\d+");
    }

    private Set<Long> findWaysContainingNode(long nodeId) {
        Set<Long> result = new HashSet<>();

        for (OsmWay way : ways.values()) {
            if (way.nodeIds.contains(nodeId)) {
                result.add(way.id);
            }
        }

        return result;
    }

    private List<GridCellKey> cellsAround(double lat, double lon, double radiusMeters) {
        List<GridCellKey> result = new ArrayList<>();

        double latDelta = radiusMeters / 111_320.0;
        double cosLat = Math.cos(Math.toRadians(lat));
        double lonMeters = Math.max(1.0, 111_320.0 * Math.abs(cosLat));
        double lonDelta = radiusMeters / lonMeters;

        GridCellKey min = cellFor(lat - latDelta, lon - lonDelta);
        GridCellKey max = cellFor(lat + latDelta, lon + lonDelta);

        for (int ix = min.ix; ix <= max.ix; ix++) {
            for (int iy = min.iy; iy <= max.iy; iy++) {
                result.add(new GridCellKey(ix, iy));
            }
        }

        return result;
    }

    private GridCellKey cellFor(double lat, double lon) {
        int ix = (int) Math.floor((lon + 180) / CELL_SIZE);
        int iy = (int) Math.floor((lat + 90) / CELL_SIZE);
        return new GridCellKey(ix, iy);
    }

    private void writeTiles(String country, File outputDir, GridIndex grid) throws Exception {
        Map<GridCellKey, Set<Long>> cellToWayIds = grid.cellToWayIds;
        Set<GridCellKey> allCells = new HashSet<>();
        allCells.addAll(grid.cellToWayIds.keySet());
        allCells.addAll(grid.cellToCameraIds.keySet());

        int total = allCells.size();
        int index = 0;

        for (GridCellKey cell : allCells) {
            index++;

            Set<Long> wayIds = cellToWayIds.getOrDefault(cell, Collections.emptySet());
            Set<Long> cameraIds = grid.cellToCameraIds.getOrDefault(cell, Collections.emptySet());

            Set<Long> nodeIds = new HashSet<>();
            for (Long wid : wayIds) {
                nodeIds.addAll(ways.get(wid).nodeIds);
            }

            File out = new File(outputDir, "grid_" + country + "_" + cell.ix + "_" + cell.iy + ".osm");

            try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
                pw.println("<?xml version='1.0' encoding='UTF-8'?>");
                pw.println("<osm version=\"0.6\" generator=\"OSMPreprocessor\">");

                for (Long nid : nodeIds) {
                    OsmNode n = nodes.get(nid);
                    if (n != null && !cameraIds.contains(n.id)) {
                        pw.printf(Locale.US, "<node id=\"%d\" lat=\"%.7f\" lon=\"%.7f\" />%n", n.id, n.lat, n.lon);
                    }
                }

                for (Long cameraId : cameraIds) {
                    OsmSpeedCamera camera = speedCameras.get(cameraId);
                    if (camera == null || !camera.hasLocation()) continue;

                    pw.printf(Locale.US, "<node id=\"%d\" lat=\"%.7f\" lon=\"%.7f\">%n", camera.id, camera.lat, camera.lon);

                    for (Map.Entry<String, String> t : camera.tags.entrySet()) {
                        pw.printf("  <tag k=\"%s\" v=\"%s\" />%n", escape(t.getKey()), escape(t.getValue()));
                    }

                    if (!camera.relatedWayIds.isEmpty()) {
                        pw.printf("  <tag k=\"speed_camera:relatedWayIds\" v=\"%s\" />%n", escape(joinLongs(camera.relatedWayIds)));
                    }

                    if (!camera.fromWayIds.isEmpty()) {
                        pw.printf("  <tag k=\"speed_camera:fromWayIds\" v=\"%s\" />%n", escape(joinLongs(camera.fromWayIds)));
                    }

                    if (!camera.toWayIds.isEmpty()) {
                        pw.printf("  <tag k=\"speed_camera:toWayIds\" v=\"%s\" />%n", escape(joinLongs(camera.toWayIds)));
                    }

                    pw.println("</node>");
                }

                for (Long wid : wayIds) {
                    OsmWay w = ways.get(wid);

                    pw.printf("<way id=\"%d\">%n", w.id);

                    for (Long nid : w.nodeIds) {
                        pw.printf("  <nd ref=\"%d\" />%n", nid);
                    }

                    for (Map.Entry<String, String> t : w.tags.entrySet()) {
                        pw.printf("  <tag k=\"%s\" v=\"%s\" />%n", escape(t.getKey()), escape(t.getValue()));
                    }

                    if (w.computedMaxspeedForwardKmh != null) {
                        pw.printf(
                                "  <tag k=\"maxspeed:computedForward\" v=\"%d\" />%n",
                                w.computedMaxspeedForwardKmh
                        );
                    }

                    if (w.computedMaxspeedBackwardsKmh != null) {
                        pw.printf(
                                "  <tag k=\"maxspeed:computedBackwards\" v=\"%d\" />%n",
                                w.computedMaxspeedBackwardsKmh
                        );
                    }

                    pw.println("</way>");
                }

                pw.println("</osm>");
            }

            double pct = (index * 100.0) / total;
            int bars = (int) (pct / 2);

            String bar = "[" +
                    "█".repeat(Math.max(0, bars)) +
                    "░".repeat(Math.max(0, 50 - bars)) +
                    "]";

            System.out.printf("\rWriting tiles %s %.1f%% (%d / %d)", bar, pct, index, total);
        }

        System.out.println("\nTile generation complete.");
    }

    private String joinLongs(Collection<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        StringBuilder sb = new StringBuilder();
        for (Long value : sorted) {
            if (sb.length() > 0) sb.append(",");
            sb.append(value);
        }

        return sb.toString();
    }

    private static class GridIndex {
        final Map<GridCellKey, Set<Long>> cellToWayIds = new HashMap<>();
        final Map<GridCellKey, Set<Long>> cellToCameraIds = new HashMap<>();

        int cellCount() {
            Set<GridCellKey> allCells = new HashSet<>();
            allCells.addAll(cellToWayIds.keySet());
            allCells.addAll(cellToCameraIds.keySet());
            return allCells.size();
        }
    }

    private String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}

/*
.\gradlew clean jar
java -jar build/libs/OSMPreprocessor.jar RO ../raw_maps/romania-260531.osm.pbf assets/legal_default_speeds.json ../output_osm_preprocessor/romania
java -jar build/libs/OSMPreprocessor.jar -maxspeed RO ../raw_maps/romania-260531.osm.pbf assets/legal_default_speeds.json ../output_osm_preprocessor/romania
*/