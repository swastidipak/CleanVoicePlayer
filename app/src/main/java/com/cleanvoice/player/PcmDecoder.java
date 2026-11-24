package com.cleanvoice.player;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Decodes any audio file Android supports (mp3, m4a/aac, wav, ogg, opus, flac, amr, 3gp, mp4...)
 * into interleaved float PCM. Output is mono or stereo (multichannel is downmixed to stereo).
 */
final class PcmDecoder implements Closeable {

    private final MediaExtractor extractor = new MediaExtractor();
    private final MediaCodec codec;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final long durationUs;

    private boolean inputDone;
    private boolean outputDone;
    private int sampleRate;
    private int inChannels;
    private boolean floatPcm;
    private int formatVersion;
    private long lastPtsUs;
    private float[] buffer = new float[8192];

    PcmDecoder(Context ctx, Uri uri) throws IOException {
        extractor.setDataSource(ctx, uri, null);
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                format = f;
                break;
            }
        }
        if (format == null) {
            extractor.release();
            throw new IOException("No audio track found in this file");
        }
        durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0L;
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        inChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        String mime = format.getString(MediaFormat.KEY_MIME);
        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (Exception e) {
            extractor.release();
            throw new IOException("This audio format (" + mime + ") is not supported on this phone", e);
        }
    }

    long getDurationUs() { return durationUs; }
    int getSampleRate() { return sampleRate; }
    /** Output channels: 1 or 2. */
    int getChannels() { return Math.min(2, Math.max(1, inChannels)); }
    /** Changes whenever sample rate / channel layout changes. */
    int getFormatVersion() { return formatVersion; }
    long getLastPtsUs() { return lastPtsUs; }
    float[] getBuffer() { return buffer; }

    void seekTo(long us) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        codec.flush();
        inputDone = false;
        outputDone = false;
        lastPtsUs = us;
    }

    /**
     * Decodes the next chunk into {@link #getBuffer()}.
     * @return number of frames decoded, or -1 at end of file.
     */
    int read() throws IOException {
        int idle = 0;
        while (true) {
            if (!inputDone) feedInput();
            int idx = codec.dequeueOutputBuffer(info, 5000);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                applyOutputFormat(codec.getOutputFormat());
                continue;
            }
            if (idx < 0) {
                if (outputDone) return -1;
                if (++idle > 2000) throw new IOException("Audio decoder stopped responding");
                continue;
            }
            idle = 0;
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            int frames = 0;
            if (info.size > 0) {
                ByteBuffer ob = codec.getOutputBuffer(idx);
                if (ob != null) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    frames = convert(ob.slice().order(ByteOrder.nativeOrder()));
                }
                lastPtsUs = info.presentationTimeUs;
            }
            codec.releaseOutputBuffer(idx, false);
            if (eos) outputDone = true;
            if (frames > 0) return frames;
            if (eos) return -1;
        }
    }

    private void feedInput() {
        int idx = codec.dequeueInputBuffer(5000);
        if (idx < 0) return;
        ByteBuffer ib = codec.getInputBuffer(idx);
        int size = ib == null ? -1 : extractor.readSampleData(ib, 0);
        if (size < 0) {
            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            inputDone = true;
        } else {
            codec.queueInputBuffer(idx, 0, size, extractor.getSampleTime(), 0);
            extractor.advance();
        }
    }

    private void applyOutputFormat(MediaFormat f) {
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
        formatVersion++;
    }

    private int convert(ByteBuffer bb) {
        int inCh = Math.max(1, inChannels);
        int outCh = getChannels();
        int samples = floatPcm ? bb.remaining() / 4 : bb.remaining() / 2;
        int frames = samples / inCh;
        if (buffer.length < frames * outCh) buffer = new float[frames * outCh];
        float[] out = buffer;

        if (floatPcm) {
            FloatBuffer fb = bb.asFloatBuffer();
            if (inCh == outCh) {
                fb.get(out, 0, frames * outCh);
            } else {
                for (int f = 0; f < frames; f++) mix(out, f, outCh, inCh, fb, null, f * inCh);
            }
        } else {
            ShortBuffer sb = bb.asShortBuffer();
            if (inCh == outCh) {
                for (int i = 0; i < frames * outCh; i++) out[i] = sb.get(i) / 32768f;
            } else {
                for (int f = 0; f < frames; f++) mix(out, f, outCh, inCh, null, sb, f * inCh);
            }
        }
        return frames;
    }

    /** Downmix multichannel (>2) to stereo: even channels -> left, odd -> right. */
    private static void mix(float[] out, int frame, int outCh, int inCh, FloatBuffer fb, ShortBuffer sb, int base) {
        float l = 0, r = 0;
        int nl = 0, nr = 0;
        for (int c = 0; c < inCh; c++) {
            float v = fb != null ? fb.get(base + c) : sb.get(base + c) / 32768f;
            if ((c & 1) == 0) { l += v; nl++; } else { r += v; nr++; }
        }
        if (outCh == 1) {
            out[frame] = (l + r) / inCh;
        } else {
            out[frame * 2] = nl > 0 ? l / nl : 0;
            out[frame * 2 + 1] = nr > 0 ? r / nr : out[frame * 2];
        }
    }

    @Override
    public void close() {
        try { codec.stop(); } catch (Exception ignored) { }
        try { codec.release(); } catch (Exception ignored) { }
        try { extractor.release(); } catch (Exception ignored) { }
    }
}
