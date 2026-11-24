package com.cleanvoice.player.dsp;

import java.io.*;
import java.nio.*;
import java.util.Random;

/**
 * Offline test bench for the enhancement chain (runs on a PC/CI, same Java + native code as the app).
 * Usage: java -Djava.library.path=<dir with libcleanvoice> -cp out com.cleanvoice.player.dsp.VoiceTest speech.wav babble1.wav babble2.wav [outdir]
 */
public class VoiceTest {
    static int SR;
    static int lastDetections;

    public static void main(String[] a) throws Exception {
        float[] speechRaw = readWav(a[0]);
        float[] bab1 = readWav(a[1]), bab2 = readWav(a[2]);
        String outDir = a.length > 3 ? a[3] : null;
        int n = speechRaw.length + 2 * SR;
        float[] clean = new float[n];
        for (int i = 0; i < speechRaw.length; i++) clean[i + SR] = speechRaw[i] * 0.5f / peak(speechRaw);
        Random r = new Random(7);

        float[] hiss = new float[n], claps = new float[n], babble = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            lp = 0.97f * lp + 0.03f * (float) r.nextGaussian();
            hiss[i] = (float) (0.025 * r.nextGaussian() + 0.025 * Math.sin(2 * Math.PI * 50 * t) + 0.01 * Math.sin(2 * Math.PI * 150 * t) + 0.12 * lp);
            babble[i] = 0.18f * (bab1[i % bab1.length] + bab2[(i + SR) % bab2.length]) / 2f;
        }
        for (int i = (int) (0.3 * SR); i < n; i += (int) ((0.45 + 0.5 * r.nextDouble()) * SR)) {
            float amp = 0.5f + 0.4f * r.nextFloat();
            for (int k = 0; k < SR * 0.06 && i + k < n; k++)
                claps[i + k] += (float) (amp * r.nextGaussian() * Math.exp(-k / (SR * 0.008)));
        }
        float[] tiny = new float[n]; for (int i = 0; i < n; i++) tiny[i] = (float) (0.0005 * r.nextGaussian());
        String[] names = {"steady hiss+hum+fan", "claps", "background talkers", "everything mixed", "clean speech (no noise)"};
        float[][] noises = {hiss, claps, babble, sum(hiss, claps, babble), tiny};

        boolean neural = new AudioProcessor(SR, 1).isNeural();
        System.out.println("Neural denoiser loaded: " + neural + "   input rate " + SR);
        float[] ref48 = resampleRef(clean);

        for (int s = 0; s < names.length; s++) {
            float[] noisy = new float[n];
            for (int i = 0; i < n; i++) noisy[i] = clean[i] + noises[s][i];
            float[] noisy48 = resampleRef(noisy);
            System.out.printf("%n== %s ==   (input voice/noise %.1f dB)%n", names[s], 10 * Math.log10(pow(clean) / pow(noises[s])));
            for (float amt : new float[]{0f, 0.5f, 0.8f, 1f}) {
                for (float clar : amt == 1f ? new float[]{0f, 0.4f} : new float[]{0f}) {
                    float[] y = run(noisy, amt, clar, 1f);
                    report(String.format("NR %3.0f%% clarity %2.0f%%", amt * 100, clar * 100), ref48, noisy48, y);
                    if (outDir != null && s < 4 && (amt == 0f || (amt == 1f && clar > 0))) {
                        writeWav(outDir + "/" + (s + 1) + "_" + (amt == 0 ? "before" : "after") + ".wav", y, 48000);
                    }
                }
            }
        }
        // Loudness / safety check: max settings + 5x volume never clips
        float[] noisy = new float[n];
        for (int i = 0; i < n; i++) noisy[i] = clean[i] + noises[3][i];
        float[] y = run(noisy, 1f, 1f, 5f);
        float pk = 0; for (float v : y) pk = Math.max(pk, Math.abs(v));
        System.out.printf("%nMax settings, 5x volume: output peak %.3f (must be <= 0.944)%n", pk);
    }

    static float[] run(float[] in, float amt, float clar, float vol) {
        AudioProcessor p = new AudioProcessor(SR, 1);
        p.setNoiseReduction(amt); p.setVoiceClarity(clar); p.setVolumeGain(vol);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FloatArr acc = new FloatArr();
        for (int off = 0; off < in.length; off += 4096) {
            int len = Math.min(4096, in.length - off);
            float[] x = new float[len]; System.arraycopy(in, off, x, 0, len);
            int m = p.process(x, len); acc.add(p.output(), m);
        }
        int m = p.flush(); acc.add(p.output(), m);
        lastDetections = p.clapDetections();
        int lat = p.latencyFrames();
        float[] r = new float[acc.n - lat];
        System.arraycopy(acc.a, lat, r, 0, r.length);
        p.release();
        return r;
    }

    static void report(String label, float[] ref, float[] noisy, float[] y) {
        // best lag check (should be 0 if latency is right)
        int bestLag = 0; double best = -1;
        for (int lag = -800; lag <= 800; lag += 1) {
            double c = 0; for (int i = 48000; i < 48000 * 4; i += 3) { int j = i + lag; if (j >= 0 && j < y.length) c += ref[i] * y[j]; }
            if (c > best) { best = c; bestLag = lag; }
        }
        // envelope of reference to find pauses
        int len = Math.min(ref.length, y.length - Math.max(0, bestLag));
        double[] env = new double[len]; double e = 0;
        for (int i = 0; i < len; i++) { e = Math.max(Math.abs(ref[i]), e * 0.9995); env[i] = e; }
        double pauseIn = 0, pauseOut = 0, sr = 0, sy = 0, yy = 0, spIn = 0;
        for (int i = 4800; i < len; i++) {
            float yi = y[i + bestLag];
            if (env[i] < 1e-3) { double nz = noisy[i] - ref[i]; pauseIn += nz * nz; pauseOut += yi * yi; }
            else { sr += ref[i] * ref[i]; sy += ref[i] * yi; yy += yi * yi; spIn += noisy[i] * noisy[i]; }
        }
        double nrDb = 10 * Math.log10((yy / pauseOut) / (spIn / pauseIn)); // gain-independent
        double g = sy / yy; // best scale
        double err = sr - 2 * g * sy + g * g * yy;
        System.out.printf("  %-22s noise removed %5.1f dB   voice quality (SI-SNR) %5.1f dB   claps detected %3d   lag %d%n",
                label, nrDb, 10 * Math.log10(sr / Math.max(err, 1e-12)), lastDetections, bestLag);
    }

    static float[] resampleRef(float[] x) {
        Resampler rs = new Resampler(SR, 48000, 1);
        FloatArr acc = new FloatArr();
        int m = rs.process(x, x.length); acc.add(rs.output(), m);
        float[] z = new float[64]; m = rs.process(z, 64); acc.add(rs.output(), m);
        int lat = (int) Math.round(Resampler.latencyInputFrames() * 48000.0 / SR);
        float[] r = new float[acc.n - lat]; System.arraycopy(acc.a, lat, r, 0, r.length); return r;
    }

    static class FloatArr { float[] a = new float[1 << 16]; int n;
        void add(float[] b, int frames) { while (n + frames > a.length) a = java.util.Arrays.copyOf(a, a.length * 2); System.arraycopy(b, 0, a, n, frames); n += frames; } }
    static float[] sum(float[]... xs) { float[] r = new float[xs[0].length]; for (float[] x : xs) for (int i = 0; i < r.length; i++) r[i] += x[i]; return r; }
    static double pow(float[] x) { double s = 0; for (float v : x) s += v * v; return s; }
    static float peak(float[] x) { float p = 0; for (float v : x) p = Math.max(p, Math.abs(v)); return p; }

    static float[] readWav(String f) throws IOException {
        byte[] b = java.nio.file.Files.readAllBytes(new File(f).toPath());
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        SR = bb.getInt(24);
        int data = 12; while (!(b[data] == 'd' && b[data + 1] == 'a' && b[data + 2] == 't' && b[data + 3] == 'a')) data += 8 + bb.getInt(data + 4);
        int len = bb.getInt(data + 4) / 2; float[] r = new float[len];
        for (int i = 0; i < len; i++) r[i] = bb.getShort(data + 8 + 2 * i) / 32768f;
        return r;
    }
    static void writeWav(String f, float[] x, int sr) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(44 + x.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(36 + x.length * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
          .putInt(sr).putInt(sr * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(x.length * 2);
        for (float v : x) bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v * 32767))));
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(bb.array()); }
    }
}
