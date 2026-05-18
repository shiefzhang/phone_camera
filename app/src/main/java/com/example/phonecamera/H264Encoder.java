package com.example.phonecamera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class H264Encoder {
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 10;
    private static final int BIT_RATE = 600000;
    private static final int I_FRAME_INTERVAL = 2;
    private static final int QCOM_YUV420_SEMIPLANAR = 0x7fa30c00;

    private MediaCodec codec;
    private int width;
    private int height;
    private int colorFormat;
    private long lastInputMs;
    private volatile H264Frame latestFrame;
    private volatile byte[] sps;
    private volatile byte[] pps;

    public synchronized boolean start(int width, int height) {
        stop();
        try {
            MediaCodecInfo encoderInfo = selectEncoder();
            if (encoderInfo == null) {
                return false;
            }
            colorFormat = selectColorFormat(encoderInfo);
            if (colorFormat == 0) {
                return false;
            }
            this.width = width;
            this.height = height;
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            codec = MediaCodec.createByCodecName(encoderInfo.getName());
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            lastInputMs = 0;
            latestFrame = null;
            sps = null;
            pps = null;
            return true;
        } catch (Exception e) {
            stop();
            return false;
        }
    }

    public synchronized void stop() {
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

    public synchronized void encodeNv21(byte[] nv21) {
        if (codec == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastInputMs < 1000 / FRAME_RATE) {
            drainEncoder();
            return;
        }
        lastInputMs = now;
        try {
            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            int inputIndex = codec.dequeueInputBuffer(0);
            if (inputIndex >= 0) {
                ByteBuffer input = inputBuffers[inputIndex];
                input.clear();
                byte[] converted = convertFromNv21(nv21);
                input.put(converted);
                long presentationUs = now * 1000L;
                codec.queueInputBuffer(inputIndex, 0, converted.length, presentationUs, 0);
            }
            drainEncoder();
        } catch (Exception ignored) {
        }
    }

    public H264Frame getLatestFrame() {
        return latestFrame;
    }

    public byte[] getSps() {
        return sps;
    }

    public byte[] getPps() {
        return pps;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    private void drainEncoder() {
        if (codec == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = codec.getOutputBuffers();
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                readCodecConfig(codec.getOutputFormat());
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

            List<byte[]> nals = splitNalUnits(data);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                saveConfigNals(nals);
                continue;
            }
            if (nals.size() == 0) {
                continue;
            }
            saveConfigNals(nals);
            boolean keyFrame = containsNalType(nals, 5);
            if (keyFrame && sps != null && pps != null) {
                ArrayList<byte[]> withConfig = new ArrayList<>();
                withConfig.add(sps);
                withConfig.add(pps);
                withConfig.addAll(nals);
                nals = withConfig;
            }
            latestFrame = new H264Frame(nals, (int) ((info.presentationTimeUs * 90L / 1000L) & 0xffffffffL), keyFrame);
        }
    }

    private void readCodecConfig(MediaFormat format) {
        try {
            if (format.containsKey("csd-0")) {
                sps = firstNal(format.getByteBuffer("csd-0"));
            }
            if (format.containsKey("csd-1")) {
                pps = firstNal(format.getByteBuffer("csd-1"));
            }
        } catch (Exception ignored) {
        }
    }

    private byte[] firstNal(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        List<byte[]> nals = splitNalUnits(bytes);
        return nals.size() == 0 ? bytes : nals.get(0);
    }

    private void saveConfigNals(List<byte[]> nals) {
        for (int i = 0; i < nals.size(); i++) {
            byte[] nal = nals.get(i);
            if (nal.length == 0) {
                continue;
            }
            int type = nal[0] & 0x1f;
            if (type == 7) {
                sps = nal;
            } else if (type == 8) {
                pps = nal;
            }
        }
    }

    private boolean containsNalType(List<byte[]> nals, int nalType) {
        for (int i = 0; i < nals.size(); i++) {
            byte[] nal = nals.get(i);
            if (nal.length > 0 && (nal[0] & 0x1f) == nalType) {
                return true;
            }
        }
        return false;
    }

    private List<byte[]> splitNalUnits(byte[] data) {
        ArrayList<byte[]> nals = new ArrayList<>();
        int start = findStartCode(data, 0);
        if (start < 0) {
            nals.add(data);
            return nals;
        }
        while (start >= 0) {
            int nalStart = start + startCodeLength(data, start);
            int next = findStartCode(data, nalStart);
            int nalEnd = next < 0 ? data.length : next;
            if (nalEnd > nalStart) {
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(data, nalStart, nal, 0, nal.length);
                nals.add(nal);
            }
            start = next;
        }
        return nals;
    }

    private int findStartCode(byte[] data, int offset) {
        for (int i = offset; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
            if (i + 4 < data.length && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private int startCodeLength(byte[] data, int start) {
        return data[start + 2] == 1 ? 3 : 4;
    }

    private byte[] convertFromNv21(byte[] nv21) {
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            return nv21ToI420(nv21);
        }
        return nv21ToNv12(nv21);
    }

    private byte[] nv21ToNv12(byte[] nv21) {
        byte[] nv12 = new byte[nv21.length];
        int frameSize = width * height;
        System.arraycopy(nv21, 0, nv12, 0, frameSize);
        for (int i = frameSize; i + 1 < nv21.length; i += 2) {
            nv12[i] = nv21[i + 1];
            nv12[i + 1] = nv21[i];
        }
        return nv12;
    }

    private byte[] nv21ToI420(byte[] nv21) {
        byte[] i420 = new byte[nv21.length];
        int frameSize = width * height;
        int chromaSize = frameSize / 4;
        System.arraycopy(nv21, 0, i420, 0, frameSize);
        int uOffset = frameSize;
        int vOffset = frameSize + chromaSize;
        for (int i = 0; i < chromaSize; i++) {
            i420[uOffset + i] = nv21[frameSize + i * 2 + 1];
            i420[vOffset + i] = nv21[frameSize + i * 2];
        }
        return i420;
    }

    private MediaCodecInfo selectEncoder() {
        int count = MediaCodecList.getCodecCount();
        for (int i = 0; i < count; i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (!info.isEncoder()) {
                continue;
            }
            String[] types = info.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (MIME_TYPE.equalsIgnoreCase(types[j])) {
                    return info;
                }
            }
        }
        return null;
    }

    private int selectColorFormat(MediaCodecInfo info) {
        MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(MIME_TYPE);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int format = capabilities.colorFormats[i];
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar || format == QCOM_YUV420_SEMIPLANAR) {
                return format;
            }
        }
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int format = capabilities.colorFormats[i];
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                return format;
            }
        }
        return 0;
    }

    public static class H264Frame {
        public final List<byte[]> nals;
        public final int timestamp;
        public final boolean keyFrame;

        H264Frame(List<byte[]> nals, int timestamp, boolean keyFrame) {
            this.nals = nals;
            this.timestamp = timestamp;
            this.keyFrame = keyFrame;
        }
    }
}
