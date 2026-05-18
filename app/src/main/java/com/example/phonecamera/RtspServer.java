package com.example.phonecamera;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class RtspServer {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int RTP_PAYLOAD_TYPE_H264 = 96;
    private static final int RTP_PAYLOAD_TYPE_AAC = 97;
    private static final int MAX_RTP_PAYLOAD = 1200;

    private final int port;
    private final FrameProvider frameProvider;
    private final ConnectionRegistry connectionRegistry;
    private volatile boolean running;
    private volatile String username = "admin";
    private volatile String password = "123456";
    private ServerSocket serverSocket;
    private Thread serverThread;

    public RtspServer(int port, FrameProvider frameProvider, ConnectionRegistry connectionRegistry) {
        this.port = port;
        this.frameProvider = frameProvider;
        this.connectionRegistry = connectionRegistry;
    }

    public void setCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public void start() {
        running = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        }, "RtspServer");
        serverThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        try {
            serverSocket = new ServerSocket(port);
            while (running) {
                final Socket socket = serverSocket.accept();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        new RtspSession(socket).run();
                    }
                }, "RtspClient").start();
            }
        } catch (IOException ignored) {
        }
    }

    private boolean isAuthorized(String authorization) {
        String expected = username + ":" + password;
        String token = Base64.encodeToString(expected.getBytes(UTF_8), Base64.NO_WRAP);
        return ("Basic " + token).equals(authorization);
    }

    private class RtspSession {
        private final Socket socket;
        private final String sessionId = String.valueOf(Math.abs(new Random().nextInt()));
        private OutputStream output;
        private InetAddress clientAddress;
        private DatagramSocket videoUdpSocket;
        private DatagramSocket audioUdpSocket;
        private int videoClientRtpPort;
        private int audioClientRtpPort;
        private boolean videoTcpTransport;
        private boolean audioTcpTransport;
        private int videoRtpChannel;
        private int audioRtpChannel;
        private volatile boolean streaming;
        private String connectionId;
        private int videoSequence = new Random().nextInt(0xffff);
        private int audioSequence = new Random().nextInt(0xffff);
        private int videoSsrc = new Random().nextInt();
        private int audioSsrc = new Random().nextInt();
        private H264Encoder.H264Frame lastVideoFrame;
        private AacAudioEncoder.AacFrame lastAudioFrame;

        RtspSession(Socket socket) {
            this.socket = socket;
            this.clientAddress = socket.getInetAddress();
        }

        void run() {
            try {
                output = socket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), ASCII));
                while (running && !socket.isClosed()) {
                    String requestLine = reader.readLine();
                    if (requestLine == null) {
                        break;
                    }
                    if (requestLine.length() == 0) {
                        continue;
                    }
                    Map<String, String> headers = readHeaders(reader);
                    String cseq = getHeader(headers, "cseq", "1");
                    if (!isAuthorized(getHeader(headers, "authorization", ""))) {
                        writeUnauthorized(cseq);
                        continue;
                    }
                    String method = requestLine.split(" ")[0];
                    if ("OPTIONS".equals(method)) {
                        writeResponse(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n", "");
                    } else if ("DESCRIBE".equals(method)) {
                        writeDescribe(cseq);
                    } else if ("SETUP".equals(method)) {
                        setupTransport(cseq, requestLine, getHeader(headers, "transport", ""));
                    } else if ("PLAY".equals(method)) {
                        writeResponse(cseq, "Session: " + sessionId + "\r\n"
                                + "RTP-Info: url=trackID=0;seq=" + videoSequence + ",url=trackID=1;seq=" + audioSequence + "\r\n", "");
                        if (connectionId == null) {
                            connectionId = connectionRegistry.add("RTSP H.264/AAC", username, clientAddress.getHostAddress());
                        }
                        streamLoop();
                    } else if ("TEARDOWN".equals(method)) {
                        writeResponse(cseq, "Session: " + sessionId + "\r\n", "");
                        break;
                    } else {
                        writeResponse(cseq, "", "");
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        private Map<String, String> readHeaders(BufferedReader reader) throws IOException {
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }
            return headers;
        }

        private String getHeader(Map<String, String> headers, String key, String fallback) {
            String value = headers.get(key);
            return value == null ? fallback : value;
        }

        private void writeUnauthorized(String cseq) throws IOException {
            String response = "RTSP/1.0 401 Unauthorized\r\n"
                    + "CSeq: " + cseq + "\r\n"
                    + "WWW-Authenticate: Basic realm=\"Phone Camera\"\r\n\r\n";
            output.write(response.getBytes(ASCII));
            output.flush();
        }

        private void writeDescribe(String cseq) throws IOException {
            waitForCodecConfig();
            String profile = profileLevelId(frameProvider.getSps());
            String sprop = spropParameterSets();
            String sdp = "v=0\r\n"
                    + "o=- 0 0 IN IP4 0.0.0.0\r\n"
                    + "s=Phone Camera H264\r\n"
                    + "t=0 0\r\n"
                    + "a=control:*\r\n"
                    + "m=video 0 RTP/AVP " + RTP_PAYLOAD_TYPE_H264 + "\r\n"
                    + "a=rtpmap:" + RTP_PAYLOAD_TYPE_H264 + " H264/90000\r\n"
                    + "a=fmtp:" + RTP_PAYLOAD_TYPE_H264 + " packetization-mode=1;profile-level-id=" + profile + sprop + "\r\n"
                    + "a=control:trackID=0\r\n"
                    + "m=audio 0 RTP/AVP " + RTP_PAYLOAD_TYPE_AAC + "\r\n"
                    + "a=rtpmap:" + RTP_PAYLOAD_TYPE_AAC + " MPEG4-GENERIC/44100/1\r\n"
                    + "a=fmtp:" + RTP_PAYLOAD_TYPE_AAC + " streamtype=5;profile-level-id=1;mode=AAC-hbr;config="
                    + frameProvider.getAacConfig()
                    + ";SizeLength=13;IndexLength=3;IndexDeltaLength=3\r\n"
                    + "a=control:trackID=1\r\n";
            String headers = "Content-Base: rtsp://0.0.0.0:" + port + "/camera/\r\n"
                    + "Content-Type: application/sdp\r\n"
                    + "Content-Length: " + sdp.getBytes(ASCII).length + "\r\n";
            writeResponse(cseq, headers, sdp);
        }

        private void waitForCodecConfig() {
            long deadline = System.currentTimeMillis() + 2000;
            while (running && System.currentTimeMillis() < deadline) {
                if (frameProvider.getSps() != null && frameProvider.getPps() != null) {
                    return;
                }
                sleep(50);
            }
        }

        private String profileLevelId(byte[] sps) {
            if (sps != null && sps.length >= 4) {
                return toHex(sps[1]) + toHex(sps[2]) + toHex(sps[3]);
            }
            return "42e01f";
        }

        private String toHex(byte value) {
            String hex = Integer.toHexString(value & 0xff);
            return hex.length() == 1 ? "0" + hex : hex;
        }

        private String spropParameterSets() {
            byte[] sps = frameProvider.getSps();
            byte[] pps = frameProvider.getPps();
            if (sps == null || pps == null) {
                return "";
            }
            return ";sprop-parameter-sets="
                    + Base64.encodeToString(sps, Base64.NO_WRAP)
                    + ","
                    + Base64.encodeToString(pps, Base64.NO_WRAP);
        }

        private void setupTransport(String cseq, String requestLine, String transport) throws IOException {
            boolean audioTrack = requestLine.indexOf("trackID=1") >= 0;
            boolean tcpTransport = transport.indexOf("RTP/AVP/TCP") >= 0;
            if (tcpTransport) {
                int rtpChannel = extractTransportValue(transport, "interleaved", audioTrack ? 2 : 0);
                if (audioTrack) {
                    audioTcpTransport = true;
                    audioRtpChannel = rtpChannel;
                } else {
                    videoTcpTransport = true;
                    videoRtpChannel = rtpChannel;
                }
                writeResponse(cseq, "Session: " + sessionId + "\r\n"
                        + "Transport: RTP/AVP/TCP;unicast;interleaved=" + rtpChannel + "-" + (rtpChannel + 1) + "\r\n", "");
                return;
            }
            int clientRtpPort = extractTransportValue(transport, "client_port", 0);
            DatagramSocket udpSocket = new DatagramSocket();
            if (audioTrack) {
                audioClientRtpPort = clientRtpPort;
                audioUdpSocket = udpSocket;
            } else {
                videoClientRtpPort = clientRtpPort;
                videoUdpSocket = udpSocket;
            }
            writeResponse(cseq, "Session: " + sessionId + "\r\n"
                    + "Transport: RTP/AVP;unicast;client_port=" + clientRtpPort + "-" + (clientRtpPort + 1)
                    + ";server_port=" + udpSocket.getLocalPort() + "-" + (udpSocket.getLocalPort() + 1) + "\r\n", "");
        }

        private int extractTransportValue(String transport, String key, int fallback) {
            int start = transport.indexOf(key + "=");
            if (start < 0) {
                return fallback;
            }
            start += key.length() + 1;
            int end = transport.indexOf(';', start);
            String value = end < 0 ? transport.substring(start) : transport.substring(start, end);
            int dash = value.indexOf('-');
            if (dash >= 0) {
                value = value.substring(0, dash);
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private void streamLoop() throws IOException {
            streaming = true;
            while (running && streaming && !socket.isClosed()) {
                boolean sent = false;
                H264Encoder.H264Frame videoFrame = frameProvider.getLatestH264Frame();
                if (videoFrame != null && videoFrame != lastVideoFrame) {
                    lastVideoFrame = videoFrame;
                    sendVideoAccessUnit(videoFrame);
                    sent = true;
                }
                AacAudioEncoder.AacFrame audioFrame = frameProvider.getLatestAacFrame();
                if (audioFrame != null && audioFrame != lastAudioFrame) {
                    lastAudioFrame = audioFrame;
                    sendAudioAccessUnit(audioFrame);
                    sent = true;
                }
                if (!sent) {
                    sleep(10);
                }
            }
        }

        private void sendVideoAccessUnit(H264Encoder.H264Frame frame) throws IOException {
            List<byte[]> nals = frame.nals;
            for (int i = 0; i < nals.size(); i++) {
                byte[] nal = nals.get(i);
                boolean marker = i == nals.size() - 1;
                sendVideoNal(nal, marker, frame.timestamp);
            }
        }

        private void sendVideoNal(byte[] nal, boolean marker, int timestamp) throws IOException {
            if (nal.length <= MAX_RTP_PAYLOAD) {
                sendRtp(nal, marker, timestamp, false);
                return;
            }
            int nalHeader = nal[0] & 0xff;
            int fuIndicator = (nalHeader & 0xe0) | 28;
            int nalType = nalHeader & 0x1f;
            int offset = 1;
            boolean first = true;
            while (offset < nal.length) {
                int payloadSize = Math.min(MAX_RTP_PAYLOAD - 2, nal.length - offset);
                boolean last = offset + payloadSize >= nal.length;
                byte[] payload = new byte[payloadSize + 2];
                payload[0] = (byte) fuIndicator;
                payload[1] = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0) | nalType);
                System.arraycopy(nal, offset, payload, 2, payloadSize);
                sendRtp(payload, marker && last, timestamp, false);
                offset += payloadSize;
                first = false;
            }
        }

        private void sendAudioAccessUnit(AacAudioEncoder.AacFrame frame) throws IOException {
            byte[] payload = new byte[4 + frame.data.length];
            payload[0] = 0;
            payload[1] = 16;
            int size = frame.data.length << 3;
            payload[2] = (byte) ((size >> 8) & 0xff);
            payload[3] = (byte) (size & 0xff);
            System.arraycopy(frame.data, 0, payload, 4, frame.data.length);
            sendRtp(payload, true, frame.timestamp, true);
        }

        private void sendRtp(byte[] payload, boolean marker, int timestamp, boolean audio) throws IOException {
            byte[] packet = new byte[12 + payload.length];
            packet[0] = (byte) 0x80;
            packet[1] = (byte) ((marker ? 0x80 : 0) | (audio ? RTP_PAYLOAD_TYPE_AAC : RTP_PAYLOAD_TYPE_H264));
            int sequence = audio ? audioSequence : videoSequence;
            int ssrc = audio ? audioSsrc : videoSsrc;
            packet[2] = (byte) ((sequence >> 8) & 0xff);
            packet[3] = (byte) (sequence & 0xff);
            packet[4] = (byte) ((timestamp >> 24) & 0xff);
            packet[5] = (byte) ((timestamp >> 16) & 0xff);
            packet[6] = (byte) ((timestamp >> 8) & 0xff);
            packet[7] = (byte) (timestamp & 0xff);
            packet[8] = (byte) ((ssrc >> 24) & 0xff);
            packet[9] = (byte) ((ssrc >> 16) & 0xff);
            packet[10] = (byte) ((ssrc >> 8) & 0xff);
            packet[11] = (byte) (ssrc & 0xff);
            System.arraycopy(payload, 0, packet, 12, payload.length);
            if (audio) {
                audioSequence = (audioSequence + 1) & 0xffff;
            } else {
                videoSequence = (videoSequence + 1) & 0xffff;
            }

            boolean tcpTransport = audio ? audioTcpTransport : videoTcpTransport;
            int rtpChannel = audio ? audioRtpChannel : videoRtpChannel;
            DatagramSocket udpSocket = audio ? audioUdpSocket : videoUdpSocket;
            int clientRtpPort = audio ? audioClientRtpPort : videoClientRtpPort;
            if (tcpTransport) {
                synchronized (output) {
                    output.write('$');
                    output.write(rtpChannel);
                    output.write((packet.length >> 8) & 0xff);
                    output.write(packet.length & 0xff);
                    output.write(packet);
                    output.flush();
                }
            } else if (udpSocket != null && clientRtpPort > 0) {
                DatagramPacket datagram = new DatagramPacket(packet, packet.length, clientAddress, clientRtpPort);
                udpSocket.send(datagram);
            }
        }

        private void writeResponse(String cseq, String headers, String body) throws IOException {
            String response = "RTSP/1.0 200 OK\r\n"
                    + "CSeq: " + cseq + "\r\n"
                    + headers
                    + "\r\n"
                    + body;
            output.write(response.getBytes(ASCII));
            output.flush();
        }

        private void close() {
            streaming = false;
            if (videoUdpSocket != null) {
                videoUdpSocket.close();
            }
            if (audioUdpSocket != null) {
                audioUdpSocket.close();
            }
            if (connectionId != null) {
                connectionRegistry.remove(connectionId);
                connectionId = null;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public interface FrameProvider {
        H264Encoder.H264Frame getLatestH264Frame();

        byte[] getSps();

        byte[] getPps();

        AacAudioEncoder.AacFrame getLatestAacFrame();

        String getAacConfig();
    }
}
