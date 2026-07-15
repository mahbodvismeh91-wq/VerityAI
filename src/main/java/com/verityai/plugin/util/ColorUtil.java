package com.verityai.plugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class ColorUtil {

    private ColorUtil() {}

    public static Component color(String raw) {
        if (raw == null) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }
}
