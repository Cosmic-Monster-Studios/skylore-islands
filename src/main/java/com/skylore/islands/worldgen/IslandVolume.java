package com.skylore.islands.worldgen;

import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction.FastNoise;

/**
 * 3D island stone density.
 */
public final class IslandVolume {

    private static final ThreadLocal<ColumnDensityCache> COLUMN_DENSITY_CACHE = ThreadLocal.withInitial(ColumnDensityCache::new);

    /** Shared read-only result for columns with no island; never mutated after construction. */
    private static final ColumnDensity INACTIVE_COLUMN = new ColumnDensity();

    /** Hanging spires under large islands: noise above this threshold grows a spire. */
    private static final double SPIRE_THRESHOLD = 0.30;
    private static final double SPIRE_MAX_DEPTH = 42.0;

    private IslandVolume() {
    }

    public static double compute(int x, int y, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        if (y < minAlt - IslandLayout.Y_PAD_BELOW || y > maxAlt + IslandLayout.Y_PAD_ABOVE) {
            return -1.0;
        }

        int density = SkyloreConfig.islandDensity();
        long worldSeed = CellularIslandDensityFunction.worldSeed();
        long layoutSalt = CellularIslandDensityFunction.layoutSalt();

        ColumnDensity column = COLUMN_DENSITY_CACHE.get().get(x, z, cellSize, minAlt, maxAlt, minRad, maxRad, density, worldSeed, layoutSalt);
        if (!column.active) {
            return -1.0;
        }
        // Interpolation samples each corner once, so a per-sample cache would never hit.
        return evaluateColumnDensity(column, x, y, z);
    }

    public static double calculateIslandDensity(int x, int y, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return evaluateColumnDensity(columnDensityAt(x, z, cellSize, minAlt, maxAlt, minRad, maxRad), x, y, z);
    }

    public static ColumnDensity columnDensityAt(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return COLUMN_DENSITY_CACHE.get().get(x, z, cellSize, minAlt, maxAlt, minRad, maxRad,
                SkyloreConfig.islandDensity(), CellularIslandDensityFunction.worldSeed(), CellularIslandDensityFunction.layoutSalt());
    }

    public static ColumnDensity buildColumnDensity(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        IslandLayout.WarpedPoint warp = IslandLayout.warpedPoint(x, z, cellSize);
        double wx = warp.wx;
        double wz = warp.wz;
        int nearbyCount = IslandLayout.nearbyLayoutsForColumn(x, z, warp.cellX, warp.cellZ, cellSize, minAlt, maxAlt, minRad, maxRad);
        CellularIslandDensityFunction.IslandLayout[] nearby = IslandLayout.nearbyLayouts();
        boolean onFootprint = IslandLayout.columnOnFootprintCached(x, z, wx, wz, nearby, nearbyCount);

        ColumnDensity column = new ColumnDensity();
        column.onFootprint = onFootprint;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int i = 0; i < nearbyCount; i++) {
            CellularIslandDensityFunction.IslandLayout layout = nearby[i];
            double dx = wx - layout.centerX;
            double dz = wz - layout.centerZ;
            double distSq = dx * dx + dz * dz;
            double skipR = IslandLayout.layoutSkipRadius(layout);
            if (distSq > skipR * skipR) {
                continue;
            }

            ColumnContrib contrib = new ColumnContrib();
            contrib.altitude = layout.altitude;
            contrib.archetype = layout.archetype;
            contrib.minY = layout.altitude - IslandLayout.Y_PAD_BELOW;
            contrib.maxY = layout.altitude + IslandLayout.Y_PAD_ABOVE;
            minY = Math.min(minY, contrib.minY);
            maxY = Math.max(maxY, contrib.maxY);

            if (IslandLayout.hasSatellites(layout)) {
                double inner = layout.baseRadius * 1.10;
                if (distSq >= inner * inner) {
                    fillSatellites(contrib, layout, wx, wz);
                }
            }
            if (layout.islets.length > 0) {
                double inner = layout.baseRadius * 0.50;
                if (distSq >= inner * inner) {
                    fillIslets(contrib, layout, wx, wz);
                }
            }

            double dist = Math.sqrt(distSq);
            if (dist >= layout.baseRadius * IslandLayout.MAX_SHAPE_SCALE) {
                // Outside any shape: only the satellites and islets above can contribute.
                if (contrib.satCount > 0 && column.contribCount < column.contribs.length) {
                    column.contribs[column.contribCount++] = contrib;
                }
                continue;
            }
            double angle = Math.atan2(dz, dx);
            long cellHash = layout.cellHash;
            double phase1 = ((cellHash >> 16) & 0xFF) * 0.025;
            double phase2 = ((cellHash >> 24) & 0xFF) * 0.025;
            double effectiveRadius = layout.baseRadius * getShapeMod(layout.archetype, angle, phase1, phase2);

            if (hasHangingShelf(layout) && dist < effectiveRadius * 0.50) {
                contrib.shelfHoriz = true;
                contrib.shelfAlt = layout.altitude - 42.0;
                contrib.shelfSmooth = Math.cos((dist / Math.max(1.0, effectiveRadius * 0.50)) * (Math.PI / 2.0));
                contrib.shelfGap = true;
            }

            if (dist < effectiveRadius) {
                contrib.onBody = true;
                double normDist = dist / effectiveRadius;
                contrib.normDist = normDist;
                contrib.smoothHoriz = Math.cos(normDist * (Math.PI / 2.0));
                fillBodyThickness(contrib, layout, wx, wz, x, z, angle, phase1, normDist);
                contrib.archColumn = isArchColumn(layout, dx, dz, effectiveRadius);
                contrib.bowlRim4 = layout.archetype == 4 && normDist >= 0.68 && normDist <= 0.90;
                contrib.bowlDeep4 = layout.archetype == 4 && normDist < 0.72;
                contrib.bowlRim1 = layout.archetype == 1 && normDist >= 0.42 && normDist <= 0.58;
            }

            if (contrib.onBody || contrib.satCount > 0 || contrib.shelfHoriz) {
                if (column.contribCount < column.contribs.length) {
                    column.contribs[column.contribCount++] = contrib;
                }
            }
        }

        if (!onFootprint) {
            fillBridges(column, nearby, nearbyCount, wx, wz);
            for (int i = 0; i < column.bridgeCount; i++) {
                minY = Math.min(minY, column.bridges[i].botY);
                maxY = Math.max(maxY, column.bridges[i].topY + 4);
            }
        }

        column.active = onFootprint || column.contribCount > 0 || column.bridgeCount > 0;
        if (!column.active) {
            return INACTIVE_COLUMN;
        }
        column.minY = minY;
        column.maxY = maxY;
        return column;
    }

    public static void fillBodyThickness(ColumnContrib contrib, CellularIslandDensityFunction.IslandLayout layout, double wx, double wz,
            int x, int z, double angle, double phase1, double normDist) {
        int archetype = layout.archetype;
        boolean isMega = layout.mega;
        long cellHash = layout.cellHash;
        double smoothHoriz = contrib.smoothHoriz;
        double islandHeight = isMega ? 80.0 : (archetype == 4 ? 48.0 : 34.0 + ((cellHash >> 3) & 15));
        double islandDepth = isMega ? 160.0 : (archetype == 4 ? 110.0 : 95.0 + ((cellHash >> 7) & 31));
        double macroNoise = surfaceNoise2D(x, z);
        double topSurfaceNoise = FastNoise.noise2D(x * 0.035, z * 0.035, 401) * 6.5;
        double topThickness;

        if (archetype == 4) {
            if (normDist < 0.70) {
                double seabedRatio = normDist / 0.70;
                double basinDepth = 26.0 * (1.0 - Math.pow(seabedRatio, 1.5));
                topThickness = -4.0 - basinDepth + (topSurfaceNoise + macroNoise * 0.3) * smoothHoriz;
            } else if (normDist < 0.88) {
                double beachRatio = (normDist - 0.70) / 0.18;
                double coastalHeight = -4.0 + 20.0 * Math.sin(beachRatio * (Math.PI / 2.0));
                topThickness = coastalHeight + (topSurfaceNoise + macroNoise * 0.3) * smoothHoriz;
            } else {
                double cliffRatio = (normDist - 0.88) / 0.12;
                double cliffHeight = 16.0 * Math.cos(cliffRatio * (Math.PI / 2.0));
                topThickness = cliffHeight + (topSurfaceNoise + macroNoise * 0.3) * smoothHoriz;
            }
        } else if (archetype == 1) {
            if (normDist < 0.45) {
                double lakeRatio = normDist / 0.45;
                double lakeDepth = 16.0 * (1.0 - Math.pow(lakeRatio, 1.6));
                topThickness = -4.0 - lakeDepth + (topSurfaceNoise + macroNoise * 0.3) * smoothHoriz;
            } else if (normDist < 0.78) {
                double mountainRatio = (normDist - 0.45) / 0.33;
                double mountainHeight = -4.0 + 36.0 * Math.sin(mountainRatio * (Math.PI / 2.0));
                topThickness = mountainHeight + (topSurfaceNoise + macroNoise * 0.3) * smoothHoriz;
            } else {
                double outerRatio = (normDist - 0.78) / 0.22;
                double outerHeight = 32.0 * Math.cos(outerRatio * (Math.PI / 2.0));
                topThickness = outerHeight + (topSurfaceNoise + macroNoise * 0.3) * smoothHoriz;
            }
        } else if (archetype == 5) {
            double swampNoise = FastNoise.noise2D(wx * 0.04, wz * 0.04, 801);
            double baseTop = (islandHeight * 0.6) * Math.pow(smoothHoriz, 0.7) + (topSurfaceNoise + macroNoise * 0.4) * smoothHoriz;
            if (normDist < 0.60 && swampNoise < -0.15) {
                topThickness = baseTop - 3.0 * (-0.15 - swampNoise) * (1.0 - normDist / 0.60);
            } else {
                topThickness = baseTop;
            }
        } else {
            double baseTop = islandHeight * Math.pow(smoothHoriz, 0.7) + (topSurfaceNoise + macroNoise * 0.5) * smoothHoriz;
            if (archetype == 2 && hasRidge(layout)) {
                baseTop *= 1.0 + 0.55 * Math.abs(Math.sin(angle - phase1));
            }
            if (archetype == 0 && hasTwinPeak(layout)) {
                baseTop *= 1.0 + 0.40 * Math.abs(Math.sin(2.0 * angle + phase1));
            }
            if (archetype == 2 && hasNetherSpine(layout)) {
                baseTop *= 1.0 + 0.70 * Math.abs(Math.sin(5.0 * angle + phase1));
            }
            if (archetype == 3 && !isMega) {
                double step = hasStrongTerrace(layout) ? 4.0 : 7.0;
                baseTop = Math.floor(Math.max(0.0, baseTop) / step) * step;
            }
            if (hasAshPlateau(layout)) {
                baseTop *= 0.55;
            }
            boolean hasRiver = ((cellHash >> 9) & 3) == 0 && layout.baseRadius >= 75;
            if (hasRiver && normDist < 0.55) {
                double riverNoise = Math.abs(FastNoise.noise2D(wx * 0.012 + 500, wz * 0.012 + 500, 701));
                if (riverNoise < 0.07) {
                    topThickness = baseTop - 4.5 * (1.0 - riverNoise / 0.07) * (1.0 - normDist / 0.55);
                } else {
                    topThickness = baseTop;
                }
            } else {
                topThickness = baseTop;
            }
        }

        contrib.topThickness = topThickness;
        contrib.bottomBase = islandDepth * Math.pow(smoothHoriz, 1.35) + macroNoise * smoothHoriz;
        if (IslandLayout.isLargeLand(layout)) {
            contrib.bottomBase += spireDepth(x, z, smoothHoriz, contrib.bottomBase);
        }
        contrib.maxBottom = contrib.bottomBase + 5.5 * smoothHoriz;
    }

    public static void fillSatellites(ColumnContrib contrib, CellularIslandDensityFunction.IslandLayout layout, double wx, double wz) {
        double[] sats = layout.satellites;
        for (int o = 0; o < sats.length; o += IslandLayout.SATELLITE_STRIDE) {
            double rad = sats[o + 2];
            double sdx = wx - sats[o];
            double sdz = wz - sats[o + 1];
            double distSq = sdx * sdx + sdz * sdz;
            if (distSq >= rad * rad) {
                continue;
            }
            double dist = Math.sqrt(distSq);
            ColumnSat sat = new ColumnSat();
            sat.smooth = Math.cos((dist / rad) * (Math.PI / 2.0));
            sat.top = 10.0 * Math.pow(sat.smooth, 0.7);
            sat.bot = 22.0 * Math.pow(sat.smooth, 1.2);
            contrib.sats[contrib.satCount++] = sat;
        }
    }

    /** Extra underside depth for a stalactite spire at this column; 0 for most columns. */
    public static double spireDepth(int x, int z, double smoothHoriz, double bottomBase) {
        double n = FastNoise.noise2D(x * 0.07, z * 0.07, 901);
        if (n <= SPIRE_THRESHOLD) {
            return 0.0;
        }
        double t = Math.min(1.0, (n - SPIRE_THRESHOLD) / 0.35);
        double depth = SPIRE_MAX_DEPTH * t * t * Math.sqrt(smoothHoriz);
        // Stay inside the Y_PAD_BELOW band, including maxBottom's +5.5 detail slack.
        return Math.max(0.0, Math.min(depth, IslandLayout.Y_PAD_BELOW - 12.0 - bottomBase));
    }

    /** Small rock islets: low mound on top, a hanging cone below. */
    public static void fillIslets(ColumnContrib contrib, CellularIslandDensityFunction.IslandLayout layout, double wx, double wz) {
        double[] islets = layout.islets;
        for (int o = 0; o < islets.length && contrib.satCount < contrib.sats.length; o += IslandLayout.ISLET_STRIDE) {
            double dx = wx - islets[o];
            double dz = wz - islets[o + 1];
            double rad = islets[o + 2];
            double distSq = dx * dx + dz * dz;
            if (distSq >= rad * rad) {
                continue;
            }
            ColumnSat sat = new ColumnSat();
            sat.smooth = Math.cos((Math.sqrt(distSq) / rad) * (Math.PI / 2.0));
            sat.top = 5.0 * Math.pow(sat.smooth, 0.7);
            sat.bot = rad * 1.6 * Math.pow(sat.smooth, 1.2);
            sat.yOffset = islets[o + 3];
            contrib.sats[contrib.satCount++] = sat;
        }
    }

    public static void fillBridges(ColumnDensity column, CellularIslandDensityFunction.IslandLayout[] nearby, int n, double wx, double wz) {
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                CellularIslandDensityFunction.IslandLayout a = nearby[i];
                CellularIslandDensityFunction.IslandLayout b = nearby[j];
                if (!IslandLayout.isLandBridge(a, b)) {
                    continue;
                }
                double dist = IslandLayout.distanceToSegment(wx, wz, a.centerX, a.centerZ, b.centerX, b.centerZ);
                if (dist > IslandLayout.LAND_BRIDGE_HALF_WIDTH) {
                    continue;
                }
                ColumnBridge bridge = new ColumnBridge();
                bridge.horiz = 1.0 - dist / IslandLayout.LAND_BRIDGE_HALF_WIDTH;
                bridge.topY = Math.min(a.altitude, b.altitude) + 6;
                bridge.botY = bridge.topY - 28;
                if (column.bridgeCount < column.bridges.length) {
                    column.bridges[column.bridgeCount++] = bridge;
                }
            }
        }
    }

    public static boolean isArchColumn(CellularIslandDensityFunction.IslandLayout layout, double dx, double dz, double effectiveRadius) {
        if (!hasThroughArch(layout) || islandHasRiver(layout)) {
            return false;
        }
        double heading = ((layout.cellHash >> 20) & 255) * (Math.PI * 2.0 / 256.0);
        double hx = Math.cos(heading);
        double hz = Math.sin(heading);
        double along = Math.abs(dx * hx + dz * hz);
        double perp = Math.abs(dx * (-hz) + dz * hx);
        return perp <= 9.0 && along >= 0.52 * effectiveRadius && along <= 0.82 * effectiveRadius;
    }

    /**
     * Highest Y where this column's density can be positive, or Integer.MIN_VALUE if nowhere.
     * Mirrors the upper gates in {@link #evaluateColumnDensity}; above it every sample is -1.
     */
    public static int solidTopY(int x, int z) {
        ColumnDensity column = columnDensityAt(x, z, SkyloreConfig.cellSize(), SkyloreConfig.minIslandAltitude(),
                SkyloreConfig.maxIslandAltitude(), SkyloreConfig.minIslandRadius(), SkyloreConfig.maxIslandRadius());
        if (!column.active) {
            return Integer.MIN_VALUE;
        }
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < column.contribCount; i++) {
            ColumnContrib c = column.contribs[i];
            double contribTop = Double.NEGATIVE_INFINITY;
            for (int s = 0; s < c.satCount; s++) {
                contribTop = Math.max(contribTop, c.altitude + c.sats[s].yOffset + c.sats[s].top);
            }
            if (c.shelfHoriz) {
                contribTop = Math.max(contribTop, c.shelfAlt + 6.0);
            }
            if (c.onBody) {
                contribTop = Math.max(contribTop, c.altitude + Math.max(c.topThickness, 4.0));
            }
            top = Math.max(top, Math.min(contribTop, c.maxY));
        }
        for (int i = 0; i < column.bridgeCount; i++) {
            top = Math.max(top, column.bridges[i].topY + 4);
        }
        if (top == Double.NEGATIVE_INFINITY) {
            return Integer.MIN_VALUE;
        }
        return Math.min((int) Math.ceil(top) + 1, column.maxY);
    }

    public static double evaluateColumnDensity(ColumnDensity column, int x, int y, int z) {
        if (!column.active || y < column.minY || y > column.maxY) {
            return -1.0;
        }
        double bestDensity = -1.0;
        for (int i = 0; i < column.contribCount; i++) {
            ColumnContrib c = column.contribs[i];
            if (y < c.minY || y > c.maxY) {
                continue;
            }
            double dy = y - c.altitude;
            for (int s = 0; s < c.satCount; s++) {
                ColumnSat sat = c.sats[s];
                double sdy = dy - sat.yOffset;
                if (sdy < -sat.bot || sdy > sat.top) {
                    continue;
                }
                double vert = sdy > 0.0 ? 1.0 - sdy / Math.max(1.0, sat.top) : 1.0 - (-sdy) / Math.max(1.0, sat.bot);
                bestDensity = Math.max(bestDensity, sat.smooth * vert + FastNoise.noise3D(x * 0.05, y * 0.05, z * 0.05, 521) * 0.10);
            }
            if (c.shelfHoriz) {
                double shelfDy = y - c.shelfAlt;
                if (shelfDy >= -14.0 && shelfDy <= 6.0) {
                    double vert = shelfDy > 0.0 ? 1.0 - shelfDy / 6.0 : 1.0 - (-shelfDy) / 14.0;
                    bestDensity = Math.max(bestDensity, c.shelfSmooth * vert + FastNoise.noise3D(x * 0.04, y * 0.04, z * 0.04, 511) * 0.10);
                }
            }
            if (c.onBody) {
                boolean skipBody = (c.archColumn && Math.abs(dy) <= 8.0)
                        || (c.shelfGap && dy <= -18.0 && dy >= -36.0);
                if (!skipBody && dy <= c.topThickness && dy >= -c.maxBottom) {
                    double detailNoise = FastNoise.noise3D(x * 0.06, y * 0.06, z * 0.06, 301) * 5.5;
                    double bottomThickness = c.bottomBase + detailNoise * c.smoothHoriz;
                    if (dy >= -bottomThickness && dy <= c.topThickness) {
                        double vertFactor = dy > 0.0
                                ? 1.0 - (dy / Math.max(1.0, c.topThickness))
                                : 1.0 - (-dy / Math.max(1.0, bottomThickness));
                        bestDensity = Math.max(bestDensity, c.smoothHoriz * vertFactor
                                + FastNoise.noise3D(x * 0.04, y * 0.04, z * 0.04, 501) * 0.14);
                    }
                }
                if (c.bowlRim4 && dy >= -32 && dy <= 4) {
                    bestDensity = Math.max(bestDensity, 0.28);
                }
                if (c.bowlDeep4 && dy >= -32 && dy <= -22) {
                    bestDensity = Math.max(bestDensity, 0.28);
                }
                if (c.bowlRim1 && dy >= -24 && dy <= 4) {
                    bestDensity = Math.max(bestDensity, 0.28);
                }
            }
        }
        for (int i = 0; i < column.bridgeCount; i++) {
            ColumnBridge bridge = column.bridges[i];
            if (y < bridge.botY || y > bridge.topY + 4) {
                continue;
            }
            double vert = y > bridge.topY ? 1.0 - (y - bridge.topY) / 4.0 : 1.0 - (bridge.topY - y) / 28.0;
            bestDensity = Math.max(bestDensity, bridge.horiz * Math.max(0.0, vert) * 0.55);
        }
        return bestDensity;
    }

    public static double getShapeMod(int archetype, double angle, double phase1, double phase2) {
        switch (archetype) {
            case 0:
                return 1.0 + 0.30 * Math.sin(3.0 * angle + phase1) + 0.18 * Math.cos(5.0 * angle + phase2) + 0.10 * Math.sin(2.0 * angle + phase1);
            case 1:
                return 1.0 + 0.18 * Math.sin(4.0 * angle + phase1) + 0.12 * Math.cos(2.0 * angle + phase2);
            case 2:
                return 0.92 + 0.35 * Math.abs(Math.sin(angle + phase1)) + 0.20 * Math.cos(3.0 * angle + phase2);
            case 3:
                return 1.0 + 0.22 * Math.sin(2.0 * angle + phase1) + 0.15 * Math.cos(4.0 * angle + phase2);
            case 5:
                return 1.0 + 0.25 * Math.sin(3.0 * angle + phase1) + 0.20 * Math.cos(2.0 * angle + phase2);
            default:
                return 1.02 + 0.12 * Math.sin(3.0 * angle + phase1) + 0.08 * Math.cos(4.0 * angle + phase2);
        }
    }

    public static boolean isInsideIslandStone(int x, int y, int z) {
        return calculateIslandDensity(
                x, y, z,
                SkyloreConfig.cellSize(),
                SkyloreConfig.minIslandAltitude(),
                SkyloreConfig.maxIslandAltitude(),
                SkyloreConfig.minIslandRadius(),
                SkyloreConfig.maxIslandRadius()) > 0.12;
    }

    public static boolean allowsStructureBlock(int x, int y, int z) {
        return IslandLayout.hasIslandColumn(x, z) || isInsideIslandStone(x, y, z);
    }

    public static boolean hasRidge(CellularIslandDensityFunction.IslandLayout layout) {
        return layout.archetype == 2 && !layout.mega && ((layout.cellHash >> 11) & 3) == 0;
    }

    public static boolean hasThroughArch(CellularIslandDensityFunction.IslandLayout layout) {
        if (!IslandLayout.isLargeLand(layout)) {
            return false;
        }
        return Math.floorMod(layout.cellHash >> 13, 7) == 0
                || Math.floorMod(layout.cellHash >> 28, 15) == 0;
    }

    public static boolean hasHangingShelf(CellularIslandDensityFunction.IslandLayout layout) {
        if (!IslandLayout.isLargeLand(layout)) {
            return false;
        }
        return Math.floorMod(layout.cellHash >> 15, 8) == 0
                || Math.floorMod(layout.cellHash >> 27, 15) == 0;
    }

    public static boolean hasTwinPeak(CellularIslandDensityFunction.IslandLayout layout) {
        return layout.archetype == 0 && IslandLayout.isLargeLand(layout)
                && ((layout.cellHash >> 25) & 7) == 0;
    }

    public static boolean hasStrongTerrace(CellularIslandDensityFunction.IslandLayout layout) {
        return layout.archetype == 3 && !layout.mega && ((layout.cellHash >> 26) & 3) == 0;
    }

    public static boolean hasNetherSpine(CellularIslandDensityFunction.IslandLayout layout) {
        return CellularIslandDensityFunction.usingNetherLayout()
                && layout.archetype == 2
                && ((layout.cellHash >> 25) & 3) == 0;
    }

    public static boolean hasAshPlateau(CellularIslandDensityFunction.IslandLayout layout) {
        return CellularIslandDensityFunction.usingNetherLayout()
                && layout.archetype == 3
                && ((layout.cellHash >> 26) & 1) == 0;
    }

    public static boolean islandHasRiver(CellularIslandDensityFunction.IslandLayout layout) {
        return ((layout.cellHash >> 9) & 3) == 0 && layout.baseRadius >= 75;
    }

    public static double surfaceNoise2D(int x, int z) {
        return FastNoise.noise2D(x * 0.015, z * 0.015, 201) * 20.0;
    }

    static final class ColumnDensityCache {
        final LongKeyedCache<ColumnDensity> cache = new LongKeyedCache<>(4096);

        ColumnDensity get(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad,
                int density, long worldSeed, long layoutSalt) {
            cache.validate(LongKeyedCache.stamp(worldSeed, layoutSalt, cellSize, minAlt, maxAlt, minRad, maxRad, density));
            long key = IslandLayout.packedXZ(x, z);
            ColumnDensity column = cache.get(key);
            if (column == null) {
                column = buildColumnDensity(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
                cache.put(key, column);
            }
            return column;
        }
    }

    static final class ColumnDensity {
        boolean active;
        boolean onFootprint;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int contribCount;
        final ColumnContrib[] contribs = new ColumnContrib[9];
        int bridgeCount;
        final ColumnBridge[] bridges = new ColumnBridge[8];
    }

    static final class ColumnContrib {
        int altitude;
        int archetype;
        int minY;
        int maxY;
        boolean onBody;
        boolean archColumn;
        boolean shelfHoriz;
        boolean shelfGap;
        boolean bowlRim4;
        boolean bowlDeep4;
        boolean bowlRim1;
        double normDist;
        double smoothHoriz;
        double topThickness;
        double bottomBase;
        double maxBottom;
        double shelfAlt;
        double shelfSmooth;
        int satCount;
        /** Up to 3 satellites first, then islets fill the remaining slots. */
        final ColumnSat[] sats = new ColumnSat[6];
    }

    static final class ColumnSat {
        double smooth;
        double top;
        double bot;
        /** Vertical offset from the island altitude; 0 for satellites, varied for islets. */
        double yOffset;
    }

    static final class ColumnBridge {
        double horiz;
        int topY;
        int botY;
    }
}
