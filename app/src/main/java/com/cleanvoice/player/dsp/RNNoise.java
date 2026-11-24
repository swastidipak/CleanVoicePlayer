package com.cleanvoice.player.dsp;

/** One RNNoise neural-network denoiser instance (one per audio channel). 48 kHz, 480-sample frames. */
final class RNNoise implements AutoCloseable {
    static final int FRAME = 480;
    static final int SAMPLE_RATE = 48000;

    private static boolean loaded;

    static synchronized boolean load() {
        if (!loaded) {
            try {
                System.loadLibrary("cleanvoice");
                loaded = true;
            } catch (Throwable t) {
                loaded = false;
            }
        }
        return loaded;
    }

    private long handle;

    RNNoise() {
        if (!load()) throw new IllegalStateException("Noise removal library not available");
        handle = nativeCreate();
    }

    /** Denoises {@link #FRAME} samples in place; returns voice probability 0..1. */
    float process(float[] frame) {
        return nativeProcess(handle, frame);
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native float nativeProcess(long handle, float[] frame);
}
