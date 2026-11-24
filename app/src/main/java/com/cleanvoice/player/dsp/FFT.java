package com.cleanvoice.player.dsp;

/** Small in-place radix-2 complex FFT (size must be a power of two). */
final class FFT {
    private final int n;
    private final int[] rev;
    private final float[] cos;
    private final float[] sin;

    FFT(int n) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("FFT size must be power of 2");
        this.n = n;
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        cos = new float[n / 2];
        sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2.0 * Math.PI * i / n;
            cos[i] = (float) Math.cos(a);
            sin[i] = (float) Math.sin(a);
        }
    }

    /** Forward transform when inverse == false. Inverse is NOT scaled by 1/n. */
    void transform(float[] re, float[] im, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        float sign = inverse ? -1f : 1f;
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1;
            int step = n / size;
            for (int start = 0; start < n; start += size) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step];
                    float wi = sign * sin[k * step];
                    int a = start + k;
                    int b = a + half;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }
}
