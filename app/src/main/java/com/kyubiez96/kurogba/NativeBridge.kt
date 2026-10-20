package com.kyubiez96.kurogba

import java.nio.ByteBuffer

/**
 * JNI bridge to the native libretro host (native/kurogba_host.c).
 *
 * The host dlopen()s libmgba_libretro.so and drives it under the libretro
 * single-thread contract: retro_run() only ever happens on the emulation
 * thread (via [nativeStep]), and every state operation is marshalled
 * through a native command queue executed between frames.
 */
object NativeBridge {

    init {
        System.loadLibrary("kurogba")
    }

    /** RetroPad button ids (libretro). */
    object Pad {
        const val B = 0
        const val Y = 1
        const val SELECT = 2
        const val START = 3
        const val UP = 4
        const val DOWN = 5
        const val LEFT = 6
        const val RIGHT = 7
        const val A = 8
        const val X = 9
        const val L = 10
        const val R = 11
    }

    external fun nativeInit(corePath: String, systemDir: String, saveDir: String): Boolean

    /** Loads a ROM from memory. [fallbackFile] is only used if the core demands a real path. */
    external fun nativeLoadRom(rom: ByteArray, pathHint: String, fallbackFile: String?): Boolean

    /** Processes pending commands, then runs [frames] frames. Emu thread only. */
    external fun nativeStep(frames: Int): Int

    /** Fills [out] with [frameSerial, width, height]. */
    external fun nativeGetFrameInfo(out: IntArray)

    /** Copies the latest RGB565 frame into [dst] (direct buffer). Returns the frame serial. */
    external fun nativeCopyFrame(dst: ByteBuffer): Int

    /** Drains interleaved stereo samples into [out]; returns the number of frames read. */
    external fun nativeReadAudio(out: ShortArray): Int

    external fun nativeSetButtons(mask: Int)
    external fun nativeSetFastForward(ff: Boolean)

    external fun nativeSaveState(): ByteArray?
    external fun nativeLoadState(state: ByteArray): Boolean
    external fun nativeGetSaveRam(): ByteArray?
    external fun nativeSetSaveRam(data: ByteArray): Boolean
    external fun nativeApplyCheat(code: String): Boolean
    external fun nativeClearCheats()
    external fun nativeReset()

    external fun nativeGetFps(): Double
    external fun nativeGetSampleRate(): Int

    /** Stops the core. The emulation thread must be joined before calling this. */
    external fun nativeShutdown()
}
