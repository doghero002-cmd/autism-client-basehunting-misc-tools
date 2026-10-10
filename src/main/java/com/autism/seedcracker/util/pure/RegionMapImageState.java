package com.autism.seedcracker.util.pure;

/** Remembers an image selection before loading, including missing or unreadable files. */
public final class RegionMapImageState {

    public static final String BUILTIN_TEXTURE = "textures/gui/region_map.png";

    private String path = "";

    public boolean select(boolean useCustom, String configuredPath) {
        String requested = useCustom && configuredPath != null ? configuredPath.trim() : "";
        if (requested.equals(path)) return false;
        // ponytail: reload on selection/lifecycle changes only; use a file watcher if live reload is needed.
        path = requested;
        return true;
    }

    public String path() {
        return path;
    }

    public void reset() {
        path = "";
    }
}
