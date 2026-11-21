/* Added for AEmulator Moon Valley, 2026-11-21: VR mode on Meta Quest, the native side (src/main/cpp/vr.cpp). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.app.Activity

/**
 * The OpenXR session of VR mode. Everything is called from the one render thread of ui/VrActivity: [create] makes the
 * EGL context current there.
 */
object VrNative {
    /** false where libaemuvr.so does not load (it needs the OpenXR loader next to it) */
    val ok: Boolean = runCatching { System.loadLibrary("aemuvr") }.isSuccess

    /** the session for a guest screen of [w]x[h]; 0 if it failed (then see [lastError]) */
    @JvmStatic external fun create(activity: Activity, w: Int, h: Int): Long
    @JvmStatic external fun lastError(): String
    /** the GL_TEXTURE_EXTERNAL_OES name a SurfaceTexture for the GPU bridge's frames is made on */
    @JvmStatic external fun oesTexture(handle: Long): Int
    /** after SurfaceTexture.updateTexImage: the new frame (with its transform [m]) becomes the screen */
    @JvmStatic external fun oesFrame(handle: Long, m: FloatArray)
    /** a frame from fb0, ARGB at the guest's size, becomes the screen */
    @JvmStatic external fun upload(handle: Long, px: IntArray, w: Int, h: Int)
    /** the hint under the screen, ARGB with alpha */
    @JvmStatic external fun setHint(handle: Long, px: IntArray, w: Int, h: Int)
    /**
     * Waits for the headset and draws one frame. Returns 1 when VR is over. [out] (11): [0] focused; left hand
     * down/u/v at 1..3, right at 4..6 (u, v 0..1 from the screen's top left); [7] home, [8] back, [9] recents pressed
     * since the last frame; [10] the thumbstick's forward/back (-1..1) for scrolling.
     */
    @JvmStatic external fun frame(handle: Long, out: FloatArray): Int
    @JvmStatic external fun requestExit(handle: Long)
    @JvmStatic external fun destroy(handle: Long)

    /** a VR headset this app can go immersive on (Meta Quest; other OpenXR headsets report the VR head-tracking feature) */
    fun isHeadset(ctx: android.content.Context): Boolean {
        val m = android.os.Build.MANUFACTURER.lowercase()
        return m == "oculus" || m == "meta" || ctx.packageManager.hasSystemFeature("android.hardware.vr.headtracking")
    }
}
