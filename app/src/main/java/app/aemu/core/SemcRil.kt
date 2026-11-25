/* Added for AEmulator Moon Valley, 2026-11-25: the RIL parcels of Sony Ericsson's 2.3 (Xperia Play). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Sony Ericsson's 2011 phones (Xperia Play R800i, 2.3.4) have Qualcomm's "unified ICC" RIL.java instead of AOSP's 2.3 one:
 *  - radio states are only OFF 0, UNAVAILABLE 1 and ON 2; "SIM ready" is worked out from the card status (RIL.IccHandler).
 *    AOSP's SIM_READY (4) is an exception: "Unrecognized RIL_RadioState: 4", telephony reconnects for ever.
 *  - the card status has an extra int (the slot) in front, and lists of GSM and CDMA application indices instead of one each;
 *  - a SIM_IO request has the slot and the application's AID in front.
 * Without a SIM state the setup wizard (SetupWizardEntry, the home screen until it is done) shows nothing but its background.
 */
internal object SemcRil {
    const val RADIO_ON = 2
    private val MARK = "Lcom/android/internal/telephony/RIL\$IccHandler;".toByteArray()

    /** A Sony Ericsson 2.3 whose framework has that RIL. */
    fun uses(root: File, api: Int): Boolean {
        if (api !in 9..10) return false
        val props = runCatching { PropArea.parseProps(File(root, "system/build.prop").readText()) }.getOrNull() ?: return false
        if (props["ro.product.brand"]?.trim().equals("SEMC", true).not() &&
            props["ro.product.manufacturer"]?.trim().equals("Sony Ericsson", true).not()) return false
        val jar = File(root, "system/framework/framework.jar")
        val dex = runCatching { ZipFile(jar).use { z -> z.getEntry("classes.dex")?.let { e -> z.getInputStream(e).use { it.readBytes() } } } }.getOrNull()
            ?: runCatching { File(root, "system/framework/framework.odex").readBytes() }.getOrNull() ?: return false
        return contains(dex, MARK)
    }

    private fun contains(d: ByteArray, p: ByteArray): Boolean {
        var i = 0
        while (i + p.size <= d.size) {
            var k = 0
            while (k < p.size && d[i + k] == p[k]) k++
            if (k == p.size) return true
            i++
        }
        return false
    }

    /** RIL_REQUEST_GET_SIM_STATUS: one ready SIM application, or an empty slot. */
    fun simStatus(present: Boolean): ByteArray {
        val v = if (present) intArrayOf(
            0,              // slot
            1, 0,           // card present, universal PIN unknown
            1, 0,           // GSM/UMTS applications: 0
            0,              // no CDMA application
            1,              // applications
            1, 5, 0,        // SIM, ready, no perso substate
            -1, -1,         // aid, label: null
            0, 0, 0,        // pin1 replaced, pin1, pin2
        ) else intArrayOf(0, 0, 0, 0, 0, 0)   // slot, card absent, PIN unknown, no GSM, no CDMA, no applications
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putInt(it) }
        return b.array()
    }

    /** The arguments of a SIM_IO request without the slot and the AID; the request as it is when they do not fit. */
    fun simIo(args: ByteBuffer): ByteBuffer {
        val b = args.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 8) return args
        b.int
        val length = b.int
        if (length == -1) return b
        val padded = if (length in 0..128) ((length + 1) * 2 + 3) and -4 else return args
        if (b.remaining() < padded) return args
        b.position(b.position() + padded)
        return b
    }
}
