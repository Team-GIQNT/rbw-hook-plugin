package dev.giqnt.rbw.hook.adapter;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

public record MapInfo(
        String category,
        String name,
        @Nullable Integer buildHeight
) {
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        MapInfo mapInfo = (MapInfo) obj;
        return Objects.equals(category, mapInfo.category) && Objects.equals(name, mapInfo.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, name);
    }
}
