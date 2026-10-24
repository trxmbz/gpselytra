package com.elytragps.util;

/**
 * Путевая точка. {@code y} может быть null — тогда высота не важна (летим на крейсерской высоте).
 */
public record Point(double x, Double y, double z) {
    public boolean hasY() {
        return y != null;
    }

    @Override
    public String toString() {
        return hasY()
            ? String.format("%.0f %.0f %.0f", x, y, z)
            : String.format("%.0f ~ %.0f", x, z);
    }
}
