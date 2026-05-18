package com.example.phonecamera;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import java.nio.ByteBuffer;

public class AacAudioEncoder {
    private static final String MIME_TYPE = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_COUNT = 1;
    private static final int BIT_RATE = 64000;

    private volatile boolean running;
    private AudioRecord audioRecord;
    private MediaCodec codec;
    private Thread audioThread;
    private volatile AacFrame latestFrame;

    public boolean start() {
        stop();
        try {
            int minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 5);
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                stop();
                return false;
            }
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            codec = MediaCodec.createEncoderByType(MIME_TYPE);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            running = true;
            audioThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    encodeLoop(bufferSize);
                }
            }, "AacAudioEncoder");
            audioThread.start();
            return true;
        } catch (Exception e) {
            stop();
            return false;
        }
    }

    public void stop() {
        running = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }

    public AacFrame getLatestFrame() {
        return latestFrame;
    }

    public String getAudioSpecificConfigHex() {
        return "1208";
    }

    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    public int getChannelCount() {
        return CHANNEL_COUNT;
    }

    private void encodeLoop(int bufferSize) {
        byte[] pcm = new byte[bufferSize];
        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            running = false;
            return;
        }
        while (running) {
            int read = audioRecord.read(pcm, 0, pcm.length);
            if (read <= 0) {
                continue;
            }
            queueInput(pcm, read);
            drainOutput();
        }
    }

    private void queueInput(byte[] pcm, int length) {
        try {
            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            int inputIndex = codec.dequeueInputBuffer(0);
            if (inputIndex < 0) {
                return;
            }
            ByteBuffer input = inputBuffers[inputIndex];
            input.clear();
            input.put(pcm, 0, length);
            codec.queueInputBuffer(inputIndex, 0, length, System.nanoTime() / 1000L, 0);
        } catch (Exception ignored) {
        }
    }

    private void drainOutput() {
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            while (true) {
                int outputIndex = codec.dequeueOutputBuffer(info, 0);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    return;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                }
                if (outputIndex < 0) {
                    continue;
                }
                ByteBuffer output = outputBuffers[outputIndex];
                byte[] data = new byte[info.size];
                output.position(info.offset);
                output.limit(info.offset + info.size);
                output.get(data);
                codec.releaseOutputBuffer(outputIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && data.length > 0) {
                    latestFrame = new AacFrame(data, (int) ((info.presentationTimeUs * SAMPLE_RATE / 1000000L) & 0xffffffffL));
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static class AacFrame {
        public final byte[] data;
        public final int timestamp;

        AacFrame(byte[] data, int timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
    }
}
