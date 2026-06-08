package com.koleos.osm;

import java.util.Objects;

public class GridCellKey {
    public int ix;
    public int iy;

    public GridCellKey(int ix, int iy) {
        this.ix = ix;
        this.iy = iy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GridCellKey)) return false;
        GridCellKey that = (GridCellKey) o;
        return ix == that.ix && iy == that.iy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ix, iy);
    }
}
