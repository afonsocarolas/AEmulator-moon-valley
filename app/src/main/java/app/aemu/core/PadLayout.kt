/* Added for AEmulator Moon Valley, 2026-11-27: the on-screen controllers' own layout. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.Context

/**
 * How a person arranged an on-screen controller (the Xperia Play's gamepad, the OUYA's controller): each control moved
 * by [Item.dx], [Item.dy] (dp, from where the controller puts it) and sized by [Item.scale]; [opacity] for all of it.
 * Kept per machine and per controller as one line of text.
 */
data class PadLayout(val opacity: Float = 1f, val items: Map<String, Item> = emptyMap()) {
    data class Item(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f)

    fun item(id: String): Item = items[id] ?: Item()
    fun with(id: String, item: Item): PadLayout = copy(items = items + (id to item))

    /** "opacity=0.8;dpad=12.0,-30.5,1.2;..." */
    fun encode(): String = buildString {
        append("opacity=").append(opacity)
        for ((id, it) in items) if (it != Item()) append(';').append(id).append('=').append(it.dx).append(',').append(it.dy).append(',').append(it.scale)
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
        const val MIN_OPACITY = 0.15f

        fun decode(s: String?): PadLayout {
            if (s.isNullOrBlank()) return PadLayout()
            var opacity = 1f
            val items = LinkedHashMap<String, Item>()
            for (part in s.split(';')) {
                val id = part.substringBefore('=', "").trim(); val v = part.substringAfter('=', "")
                if (id.isEmpty()) continue
                if (id == "opacity") { v.toFloatOrNull()?.let { opacity = it.coerceIn(MIN_OPACITY, 1f) }; continue }
                val n = v.split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (n.size == 3 && n.all { it.isFinite() }) items[id] = Item(n[0], n[1], n[2].coerceIn(MIN_SCALE, MAX_SCALE))
            }
            return PadLayout(opacity, items)
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("pad_layouts", Context.MODE_PRIVATE)
        fun load(ctx: Context, machine: String, pad: String): PadLayout = decode(prefs(ctx).getString("$machine/$pad", null))
        fun save(ctx: Context, machine: String, pad: String, layout: PadLayout) {
            prefs(ctx).edit().apply { if (layout == PadLayout()) remove("$machine/$pad") else putString("$machine/$pad", layout.encode()) }.apply()
        }
    }
}
