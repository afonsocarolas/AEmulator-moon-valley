/* Added for AEmulator Moon Valley, 2026-11-23: touch of the first Galaxy Tab. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * The first Galaxy Tab (GT-P1000, Android 2.3) has a 1024x600 panel mounted sideways. SurfaceFlinger turns the picture by
 * ro.sf.hwrotation=90, which is set to 0 here (see GuestVm: the UI follows the framebuffer). The touch side of the same
 * quarter turn is compiled into libandroid_servers.so (NativeInputManager, Thumb-2) and reads no property:
 *
 *     constructor:            displayOrientation = 1              (AOSP: 0)
 *     setDisplayOrientation:  displayOrientation = (rotation + 1) & 3, as  cmp r7,#3 ; it ne ; addne r5,r7,#1  (AOSP: rotation)
 *     setDisplaySize:         width and height swapped while displayOrientation is 1 or 3  (AOSP: never)
 *
 * so every touch landed a quarter turn away from the finger. All of it is put back to what AOSP does, or nothing is.
 */
internal object SidewaysTouchPatch {
    const val PATCHED = 1
    const val ALREADY = 0
    /** no such code: not this firmware */
    const val ABSENT = -1

    private class Site(val from: String, val at: Int, val old: String, val new: String, val count: Int) {
        val pattern = hex(from)
        val patched = pattern.copyOf().also { hex(new).copyInto(it, at) }
        init { check(pattern.copyOfRange(at, at + hex(old).size).contentEquals(hex(old)) && hex(old).size == hex(new).size) }
    }

    private val SITES = listOf(
        // movs r7,#1 ; add.w r0,r4,#0x34 ; str r7,[r4,#0x30]   (both constructors)
        Site("012704f134002763", 0, "0127", "0027", 2),
        // cmp r7,#3 ; it ne ; addne r5,r7,#1 ; mov r0,r6 ; str r5,[r4,#0x30]   ->  mov r5,r7 ; nop ; nop
        Site("032f18bf7d1c30462563", 0, "032f18bf7d1c", "3d4600bf00bf", 1),
        // orr r3,r2,#1 ; cbz r3 ; str r7,[r4,#0x28] ; str r5,[r4,#0x2c] ; b ; str r5,[r4,#0x28] ; str r7,[r4,#0x2c]   ->  always the last two
        Site("42f0010313b1a762e56201e0a562e762", 4, "13b1", "02e0", 1),
    )

    /** Patches [d] in place, only when every site is there exactly as often as it should be. */
    fun apply(d: ByteArray): Int {
        val found = SITES.map { find(d, it.pattern) }
        if (SITES.indices.all { found[it].size == SITES[it].count }) {
            for (i in SITES.indices) for (o in found[i]) SITES[i].patched.copyInto(d, o)
            return PATCHED
        }
        return if (SITES.all { find(d, it.patched).size == it.count }) ALREADY else ABSENT
    }

    private fun find(d: ByteArray, p: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + p.size <= d.size) {
            var k = 0
            while (k < p.size && d[i + k] == p[k]) k++
            if (k == p.size) out.add(i)
            i += 2 // Thumb code
        }
        return out
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
