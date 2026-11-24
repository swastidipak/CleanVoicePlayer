package com.cleanvoice.player;

import android.content.Context;
import android.net.Uri;

import com.cleanvoice.player.dsp.AudioProcessor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/** Processes a whole file offline and saves the cleaned result as a 16-bit WAV. */
final class AudioExporter {

    interface Progress {
        /** @return false to cancel */
        boolean onProgress(int percent);
    }

    static void export(Context ctx, Uri input, Uri output, float noise, float clarity, float volume,
                       Progress progress) throws IOException {
        File tmp = new File(ctx.getCacheDir(), "export_" + System.nanoTime() + ".wav");
        try {
            try (PcmDecoder dec = new PcmDecoder(ctx, input);
                 RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {

                raf.write(new byte[44]); // header placeholder
                AudioProcessor proc = null;
                int sampleRate = 0, channels = 0;
                int toSkip = AudioProcessor.latencyFrames(); // drop processor delay at the start
                long dataBytes = 0;
                long duration = Math.max(1, dec.getDurationUs());
                int lastPct = -1;
                byte[] bytes = new byte[0];

                while (true) {
                    int frames = dec.read();
                    float[] buf;
                    boolean end = frames < 0;
                    if (end) {
                        if (proc == null) break;
                        frames = AudioProcessor.latencyFrames(); // flush tail
                        buf = new float[frames * channels];
                    } else {
                        if (proc == null) {
                            sampleRate = dec.getSampleRate();
                            channels = dec.getChannels();
                            proc = new AudioProcessor(sampleRate, channels);
                            proc.setNoiseReduction(noise);
                            proc.setVoiceClarity(clarity);
                            proc.setVolumeGain(volume);
                        }
                        buf = dec.getBuffer();
                        if (dec.getChannels() != channels) continue; // ignore odd mid-file layout change
                    }
                    proc.process(buf, frames);

                    int startFrame = Math.min(frames, toSkip);
                    toSkip -= startFrame;
                    int n = (frames - startFrame) * channels;
                    if (bytes.length < n * 2) bytes = new byte[n * 2];
                    for (int i = 0; i < n; i++) {
                        int s = Math.round(buf[startFrame * channels + i] * 32767f);
                        if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
                        bytes[2 * i] = (byte) s;
                        bytes[2 * i + 1] = (byte) (s >> 8);
                    }
                    raf.write(bytes, 0, n * 2);
                    dataBytes += n * 2L;
                    if (end) break;

                    int pct = (int) Math.min(99, dec.getLastPtsUs() * 100 / duration);
                    if (pct != lastPct) {
                        lastPct = pct;
                        if (!progress.onProgress(pct)) throw new IOException("Cancelled");
                    }
                }
                if (proc == null) throw new IOException("No audio could be decoded");
                raf.seek(0);
                raf.write(wavHeader(sampleRate, channels, dataBytes));
            }

            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = ctx.getContentResolver().openOutputStream(output, "w")) {
                if (out == null) throw new IOException("Cannot write to the chosen location");
                byte[] b = new byte[64 * 1024];
                int r;
                while ((r = in.read(b)) > 0) out.write(b, 0, r);
            }
            progress.onProgress(100);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static byte[] wavHeader(int sampleRate, int channels, long dataBytes) {
        int byteRate = sampleRate * channels * 2;
        long riff = 36 + dataBytes;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, riff); put(h, 8, "WAVE");
        put(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, channels);
        le32(h, 24, sampleRate); le32(h, 28, byteRate); le16(h, 32, channels * 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataBytes);
        return h;
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
}
