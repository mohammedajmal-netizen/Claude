package app.truelog.camera;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Video encoder, AAC audio and MP4 muxer. All timestamps are on the elapsedRealtime clock. */
final class AvRecorder {

    static final int COLOR_P010 = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010;

    /** Which encoder to use and how to feed it. */
    static final class EncoderChoice {
        String name;
        boolean hardware;
        boolean p010Input;
        boolean surfaceInput;
        int maxBitrate;
        String describe() {
            return name + (hardware ? " (hardware)" : " (software)")
                    + (p010Input ? ", P010 input" : ", surface input");
        }
    }

    /** Finds a 10-bit HEVC encoder for this size, preferring exact P010 input. */
    static EncoderChoice chooseTenBit(int w, int h, int fps) {
        EncoderChoice best = null;
        int bestScore = -1;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            } catch (IllegalArgumentException e) {
                continue;
            }
            boolean main10 = false;
            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) main10 = true;
            }
            MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
            if (!main10 || vc == null || !vc.areSizeAndRateSupported(w, h, fps)) continue;

            boolean p010 = false, surface = false;
            for (int f : caps.colorFormats) {
                if (f == COLOR_P010) p010 = true;
                if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
            }
            if (!p010 && !surface) continue;
            int score = (info.isHardwareAccelerated() ? 4 : 0) + (p010 ? 2 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = new EncoderChoice();
                best.name = info.getName();
                best.hardware = info.isHardwareAccelerated();
                best.p010Input = p010;
                best.surfaceInput = surface;
                best.maxBitrate = vc.getBitrateRange().getUpper();
            }
        }
        return best;
    }

    static final class Params {
        int width, height, fps, bitrate;
        boolean tenBit;           // HEVC Main10 when true, otherwise 8-bit HEVC
        boolean hlg;              // tag as HLG for the standard video mode
        boolean log;              // tag for S-Log3 delivery
        boolean p010Input;        // false means Surface input
        String encoderName;       // null lets the platform choose
        boolean audio;
        int orientationHint;
    }

    private final Params params;
    private final MediaCodec video;
    private final MediaMuxer muxer;
    private final Object muxLock = new Object();
    private final AtomicBoolean videoEosSent = new AtomicBoolean();
    private int expectedTracks;
    private long clockOffsetNs;
    private volatile String warning;

    private Surface inputSurface;
    private Thread videoThread, audioThread;
    private volatile boolean audioRun;
    private volatile boolean failed;
    private String failure;

    private int videoTrack = -1, audioTrack = -1;
    private boolean muxerRunning;
    private volatile long t0Ns;
    private long lastVideoUs = -1, lastAudioUs = -1;
    private long framesWritten;
    private int frameStride, frameSlice;

    AvRecorder(Params p, FileDescriptor fd) throws IOException {
        params = p;
        expectedTracks = p.audio ? 2 : 1;
        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.setOrientationHint(p.orientationHint);

        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, p.width, p.height);
        f.setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, p.fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, p.p010Input ? COLOR_P010
                : MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (p.tenBit) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
        }
        if (p.log) {
            // Log has no standard transfer tag. BT.2020 + SDR keeps players from treating it as HDR.
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        } else if (p.hlg) {
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
        }
        video = p.encoderName != null ? MediaCodec.createByCodecName(p.encoderName)
                : MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
        video.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        if (!p.p010Input) inputSurface = video.createInputSurface();
    }

    /** Added to frame timestamps to bring them onto the elapsedRealtime clock. */
    void setClockOffsetNs(long offset) {
        clockOffsetNs = offset;
    }

    /** A problem that did not stop recording, such as a missing microphone. */
    String warning() {
        return warning;
    }

    /** Surface for the camera or GL to draw into. Null in P010 mode. */
    Surface inputSurface() {
        return inputSurface;
    }

    /** Video frames older than this instant are dropped, audio begins here. */
    void start() {
        t0Ns = SystemClock.elapsedRealtimeNanos();
        video.start();
        MediaFormat in = video.getInputFormat();
        frameStride = in.containsKey(MediaFormat.KEY_STRIDE) ? in.getInteger(MediaFormat.KEY_STRIDE) : params.width;
        frameSlice = in.containsKey(MediaFormat.KEY_SLICE_HEIGHT) ? in.getInteger(MediaFormat.KEY_SLICE_HEIGHT) : params.height;
        videoThread = new Thread(this::drainVideo, "video-drain");
        videoThread.start();
        if (params.audio) {
            audioRun = true;
            audioThread = new Thread(this::audioLoop, "audio");
            audioThread.start();
        }
    }

    long startTimeNs() {
        return t0Ns;
    }

    long framesWritten() {
        synchronized (muxLock) {
            return framesWritten;
        }
    }

    boolean hasFailed() {
        return failed;
    }

    private void fail(String why) {
        if (failure == null) failure = why;
        failed = true;
    }

    /** Copies one P010 frame into the encoder. Returns false when the encoder has no free buffer. */
    boolean queueP010(ByteBuffer y, ByteBuffer uv, int w, int h, long timestampNs) {
        if (failed || timestampNs + clockOffsetNs < t0Ns) return true;
        int idx;
        try {
            idx = video.dequeueInputBuffer(0);
        } catch (IllegalStateException e) {
            fail("Encoder input: " + e);
            return false;
        }
        if (idx < 0) return false;
        int size;
        Image image = video.getInputImage(idx);
        if (image != null) {
            Image.Plane yp = image.getPlanes()[0], up = image.getPlanes()[1];
            copyPlane(y, w * 2, h, yp.getBuffer(), yp.getRowStride());
            copyPlane(uv, w * 2, h / 2, up.getBuffer(), up.getRowStride());
            size = yp.getRowStride() * h + up.getRowStride() * (h / 2);
        } else {
            ByteBuffer in = video.getInputBuffer(idx);
            int stride = Math.max(frameStride, w) * 2;
            int slice = Math.max(frameSlice, h);
            int base = in.position();
            copyPlane(y, w * 2, h, in, stride);
            in.position(base + stride * slice);
            copyPlane(uv, w * 2, h / 2, in.slice(), stride);
            size = stride * slice + stride * (h / 2);
        }
        video.queueInputBuffer(idx, 0, size, timestampNs / 1000L, 0);
        return true;
    }

    private static void copyPlane(ByteBuffer src, int rowBytes, int rows, ByteBuffer dst, int dstStride) {
        int dstBase = dst.position();
        for (int r = 0; r < rows; r++) {
            int dstPos = dstBase + r * dstStride;
            if (dstPos >= dst.limit()) break;
            int n = Math.min(rowBytes, dst.limit() - dstPos);
            src.limit(r * rowBytes + n).position(r * rowBytes);
            dst.position(dstPos);
            dst.put(src);
        }
        src.clear();
    }

    private void addTrackAndMaybeStart(boolean isVideo, MediaFormat format) {
        synchronized (muxLock) {
            int t = muxer.addTrack(format);
            if (isVideo) videoTrack = t; else audioTrack = t;
            int have = (videoTrack >= 0 ? 1 : 0) + (audioTrack >= 0 ? 1 : 0);
            if (have == expectedTracks) {
                muxer.start();
                muxerRunning = true;
                muxLock.notifyAll();
            }
        }
    }

    private void write(boolean isVideo, ByteBuffer data, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            while (!muxerRunning && !failed) {
                try {
                    muxLock.wait(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!muxerRunning) return;
            muxer.writeSampleData(isVideo ? videoTrack : audioTrack, data, info);
            if (isVideo) framesWritten++;
        }
    }

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int idx = video.dequeueOutputBuffer(info, 20_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrackAndMaybeStart(true, video.getOutputFormat());
                } else if (idx >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && info.size > 0) {
                        long us = info.presentationTimeUs + (clockOffsetNs - t0Ns) / 1000L;
                        if (us >= 0 && us > lastVideoUs) {
                            lastVideoUs = us;
                            info.presentationTimeUs = us;
                            ByteBuffer out = video.getOutputBuffer(idx);
                            out.position(info.offset).limit(info.offset + info.size);
                            write(true, out, info);
                        }
                    }
                    video.releaseOutputBuffer(idx, false);
                    if (eos) break;
                } else if (failed) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            fail("Video encoder: " + e);
        }
    }

    private void audioLoop() {
        int rate = 48_000, channels = 2;
        int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        MediaCodec aac = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 4, 64 * 1024));
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 256_000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aac.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aac.start();
            rec.startRecording();

            long totalFrames = 0;
            long firstFrameUs = (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1000L;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean eosQueued = false;
            while (true) {
                int in = eosQueued ? -1 : aac.dequeueInputBuffer(5_000);
                if (in >= 0) {
                    ByteBuffer b = aac.getInputBuffer(in);
                    int n = audioRun ? rec.read(b, Math.min(b.remaining(), 8192)) : 0;
                    long us = firstFrameUs + totalFrames * 1_000_000L / rate;
                    if (!audioRun) {
                        aac.queueInputBuffer(in, 0, 0, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    } else if (n > 0) {
                        totalFrames += n / (2L * channels);
                        aac.queueInputBuffer(in, 0, n, us, 0);
                    } else {
                        aac.queueInputBuffer(in, 0, 0, us, 0);
                    }
                }
                int out;
                while ((out = aac.dequeueOutputBuffer(info, eosQueued ? 10_000 : 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        addTrackAndMaybeStart(false, aac.getOutputFormat());
                    } else if (out >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (!config && info.size > 0 && info.presentationTimeUs > lastAudioUs) {
                            lastAudioUs = info.presentationTimeUs;
                            ByteBuffer o = aac.getOutputBuffer(out);
                            o.position(info.offset).limit(info.offset + info.size);
                            write(false, o, info);
                        }
                        aac.releaseOutputBuffer(out, false);
                        if (eos) return;
                    }
                }
            }
        } catch (RuntimeException | IOException e) {
            audioLost(e);
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (RuntimeException ignored) { }
                rec.release();
            }
            if (aac != null) {
                try { aac.stop(); } catch (RuntimeException ignored) { }
                aac.release();
            }
        }
    }

    /** Losing the microphone should not cost the shot, so carry on with picture only. */
    private void audioLost(Exception e) {
        warning = "Recording without sound: " + e.getMessage();
        synchronized (muxLock) {
            if (!muxerRunning) {
                expectedTracks = 1;
                if (videoTrack >= 0) {
                    muxer.start();
                    muxerRunning = true;
                }
                muxLock.notifyAll();
            }
        }
    }

    /** Finishes the file. Returns an error message, or null on success. */
    String stop() {
        videoEosSent.set(true);
        try {
            if (params.p010Input) {
                int idx = -1;
                for (int i = 0; i < 100 && idx < 0; i++) idx = video.dequeueInputBuffer(20_000);
                if (idx >= 0) {
                    video.queueInputBuffer(idx, 0, 0, SystemClock.elapsedRealtimeNanos() / 1000L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    fail("Encoder did not accept end of stream");
                }
            } else {
                video.signalEndOfInputStream();
            }
        } catch (RuntimeException e) {
            fail("Stopping encoder: " + e);
        }
        audioRun = false;
        join(audioThread);
        if (failed && videoThread != null) videoThread.interrupt();
        join(videoThread);
        try { video.stop(); } catch (RuntimeException ignored) { }
        video.release();
        if (inputSurface != null) inputSurface.release();
        synchronized (muxLock) {
            try {
                if (muxerRunning) muxer.stop();
                else fail("No frames reached the file");
            } catch (RuntimeException e) {
                fail("Finishing file: " + e);
            }
            muxLock.notifyAll();
        }
        muxer.release();
        return failed ? failure : null;
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
