package com.bizzle.minicpm.core

import kotlinx.serialization.Serializable

@Serializable
data class ThemeSpec(val name: String, val primary: Long, val background: Long, val surface: Long, val onSurface: Long) {
    companion object {
        val NEON = ThemeSpec("Neon v4.20", 0xFF39FF14, 0xFF000000, 0xFF0B140B, 0xFFD6FFCC)
        val presets = listOf(
            NEON,
            ThemeSpec("Matrix AMOLED", 0xFF00FF41, 0xFF000000, 0xFF000000, 0xFF9CFFB0),
            ThemeSpec("Purple Haze", 0xFFC77DFF, 0xFF0D0614, 0xFF1A0F26, 0xFFEBD9FF),
            ThemeSpec("Ice Blue", 0xFF4CC9F0, 0xFF03101A, 0xFF0B1E2B, 0xFFD3F3FF),
            ThemeSpec("Sunset Kush", 0xFFFF8C42, 0xFF140A04, 0xFF26150A, 0xFFFFE3CC),
            ThemeSpec("Gold Leaf", 0xFFFFD60A, 0xFF0F0D02, 0xFF1F1B07, 0xFFFFF5C2),
            ThemeSpec("Hot Pink", 0xFFFF2E93, 0xFF12030A, 0xFF240A17, 0xFFFFD6EA),
        )

        private fun lum(c: Long): Double {
            fun ch(v: Long): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        fun contrast(a: Long, b: Long): Double { val l1 = lum(a); val l2 = lum(b); return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05) }

        private val hex = Regex("#([0-9A-Fa-f]{6})")
        private val keywords = mapOf(
            "green" to 0xFF39FF14, "neon" to 0xFF39FF14, "weed" to 0xFF39FF14, "kush" to 0xFF5CFF2E, "matrix" to 0xFF00FF41,
            "purple" to 0xFFC77DFF, "violet" to 0xFF9D4EDD, "blue" to 0xFF4CC9F0, "ocean" to 0xFF00B4D8, "ice" to 0xFFA2E8FF,
            "red" to 0xFFFF3B3B, "fire" to 0xFFFF5400, "orange" to 0xFFFF8C42, "sunset" to 0xFFFF7B54, "gold" to 0xFFFFD60A,
            "yellow" to 0xFFFFEA00, "pink" to 0xFFFF2E93, "cyber" to 0xFF00F5D4, "teal" to 0xFF2EC4B6, "white" to 0xFFF1F1F1,
        )

        /**
         * Builds a readable dark theme from an AI reply (or the user's own words). Takes the first #RRGGBB as the accent;
         * falls back to color keywords; guarantees accent/background contrast >= 4.5.
         */
        fun fromText(name: String, text: String): ThemeSpec? {
            val accent = hex.find(text)?.groupValues?.get(1)?.toLong(16)?.or(0xFF000000)
                ?: keywords.entries.firstOrNull { text.contains(it.key, ignoreCase = true) }?.value
                ?: return null
            val r = (accent shr 16) and 0xFF; val g = (accent shr 8) and 0xFF; val b = accent and 0xFF
            fun mix(f: Double) = 0xFF000000 or ((r * f).toLong() shl 16) or ((g * f).toLong() shl 8) or (b * f).toLong()
            val bg = mix(0.04); val surface = mix(0.11)
            var acc = accent
            if (contrast(acc, bg) < 4.5) acc = 0xFF39FF14
            val onS = 0xFF000000 or ((r + (255 - r) * 0.82).toLong() shl 16) or ((g + (255 - g) * 0.82).toLong() shl 8) or (b + (255 - b) * 0.82).toLong()
            return ThemeSpec(name.take(24).ifBlank { "AI theme" }, acc, bg, surface, onS)
        }
    }
}
