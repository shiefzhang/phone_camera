package com.example.phonecamera;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Locale;

public class MjpegServer {
    private static final String BOUNDARY = "phone-camera-frame";
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final int port;
    private final FrameProvider frameProvider;
    private final CameraControl cameraControl;
    private final ConnectionRegistry connectionRegistry;
    private volatile boolean running;
    private volatile String username = "admin";
    private volatile String password = "123456";
    private ServerSocket serverSocket;
    private Thread serverThread;

    public MjpegServer(int port, FrameProvider frameProvider, CameraControl cameraControl, ConnectionRegistry connectionRegistry) {
        this.port = port;
        this.frameProvider = frameProvider;
        this.cameraControl = cameraControl;
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
        }, "MjpegServer");
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
                Thread clientThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(socket);
                    }
                }, "MjpegClient");
                clientThread.start();
            }
        } catch (IOException ignored) {
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), ASCII));
             OutputStream output = client.getOutputStream()) {
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            String authorization = "";
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                if (header.toLowerCase(Locale.US).startsWith("authorization:")) {
                    authorization = header.substring(header.indexOf(':') + 1).trim();
                }
            }

            if (!isAuthorized(authorization)) {
                writeUnauthorized(output);
                return;
            }

            String path = requestLine.split(" ")[1];
            if ("/stream.mjpg".equals(path)) {
                String connectionId = connectionRegistry.add("MJPEG", username, client.getInetAddress().getHostAddress());
                try {
                    writeStream(output);
                } finally {
                    connectionRegistry.remove(connectionId);
                }
            } else if (path.startsWith("/api/status")) {
                writeJson(output, cameraControl.getStatusJson());
            } else if (path.startsWith("/api/zoom")) {
                writeJson(output, cameraControl.setZoom(extractIntQuery(path, "value")));
            } else if (path.startsWith("/api/switch")) {
                writeJson(output, cameraControl.switchCamera());
            } else if (path.startsWith("/api/orientation")) {
                writeJson(output, cameraControl.toggleOutputOrientation());
            } else {
                writeIndex(output);
            }
        } catch (IOException ignored) {
        }
    }

    private boolean isAuthorized(String authorization) {
        String expected = username + ":" + password;
        String token = Base64.encodeToString(expected.getBytes(UTF_8), Base64.NO_WRAP);
        return ("Basic " + token).equals(authorization);
    }

    private void writeUnauthorized(OutputStream output) throws IOException {
        String response = "HTTP/1.1 401 Unauthorized\r\n"
                + "WWW-Authenticate: Basic realm=\"Phone Camera\"\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        output.write(response.getBytes(ASCII));
        output.flush();
    }

    private void writeIndex(OutputStream output) throws IOException {
        String body = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Phone Camera</title><style>"
                + "body{margin:0;background:#0f172a;color:#e5e7eb;font-family:Arial,sans-serif}"
                + ".wrap{min-height:100vh;display:flex;flex-direction:column}"
                + ".video{flex:1;display:flex;align-items:center;justify-content:center;background:#111827}"
                + "img{max-width:100vw;max-height:calc(100vh - 128px);object-fit:contain}"
                + ".bar{padding:14px 16px;background:#fff;color:#0f172a;box-shadow:0 -2px 12px rgba(0,0,0,.25)}"
                + ".row{display:flex;gap:12px;align-items:center;max-width:880px;margin:0 auto;flex-wrap:wrap}"
                + "button{width:46px;height:42px;padding:0;border:0;border-radius:6px;background:#2563eb;color:#fff;font-size:16px;display:flex;align-items:center;justify-content:center}"
                + "button svg{width:24px;height:24px;fill:currentColor}"
                + "#orientationBtn{background:#4f46e5}button:disabled{background:#94a3b8}label{min-width:96px}input{flex:1;min-width:160px}#status{max-width:880px;margin:8px auto 0;color:#475569;font-size:14px}"
                + "</style></head><body><div class=\"wrap\"><div class=\"video\"><img src=\"/stream.mjpg\" alt=\"stream\"></div>"
                + "<div class=\"bar\"><div class=\"row\"><button id=\"switchBtn\" aria-label=\"Switch camera\" title=\"Switch camera\"></button><button id=\"orientationBtn\" aria-label=\"Switch output orientation\" title=\"Switch output orientation\"></button><label id=\"zoomLabel\">Zoom</label>"
                + "<input id=\"zoom\" type=\"range\" min=\"0\" max=\"0\" value=\"0\" disabled></div><div id=\"status\"></div></div></div>"
                + "<script>"
                + "var z=document.getElementById('zoom'),l=document.getElementById('zoomLabel'),b=document.getElementById('switchBtn'),o=document.getElementById('orientationBtn'),s=document.getElementById('status');"
                + "var cameraIcon='<svg viewBox=\"0 0 28 28\"><path d=\"M7 8l3-3h8l3 3h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z\"/><path fill=\"#0f172a\" d=\"M14 10a5.2 5.2 0 1 0 0 10.4A5.2 5.2 0 1 0 14 10\"/><path fill=\"#67e8f9\" d=\"M14 12.3a2.9 2.9 0 1 0 0 5.8A2.9 2.9 0 1 0 14 12.3\"/><path fill=\"#2563eb\" d=\"M6 12l3.5-3.5V11H15v2H9.5v2.5zM22 17l-3.5 3.5V18H13v-2h5.5v-2.5z\"/></svg>';"
                + "var portraitIcon='<svg viewBox=\"0 0 28 28\"><path fill=\"#eef2ff\" d=\"M10 3h8a2 2 0 0 1 2 2v18a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z\"/><path fill=\"#4f46e5\" d=\"M10.2 6.5h7.6v14.7h-7.6z\"/><path fill=\"#c7d2fe\" d=\"M13 22.3h2v1h-2z\"/><path fill=\"none\" stroke=\"#67e8f9\" stroke-width=\"1.9\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-dasharray=\"2.2 1.7\" d=\"M4 9h20a1.6 1.6 0 0 1 1.6 1.6v6.8A1.6 1.6 0 0 1 24 19H4a1.6 1.6 0 0 1-1.6-1.6v-6.8A1.6 1.6 0 0 1 4 9z\"/></svg>';"
                + "var landscapeIcon='<svg viewBox=\"0 0 28 28\"><path fill=\"#eef2ff\" d=\"M4 9h20a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z\"/><path fill=\"#4f46e5\" d=\"M6 11h16v6H6z\"/><path fill=\"#c7d2fe\" d=\"M23.2 13h1v2h-1z\"/><path fill=\"none\" stroke=\"#67e8f9\" stroke-width=\"1.9\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-dasharray=\"2.2 1.7\" d=\"M10 3h8a1.6 1.6 0 0 1 1.6 1.6v18.8A1.6 1.6 0 0 1 18 25h-8a1.6 1.6 0 0 1-1.6-1.6V4.6A1.6 1.6 0 0 1 10 3z\"/></svg>';"
                + "b.innerHTML=cameraIcon;"
                + "function apply(d){b.disabled=!d.multipleCameras;b.title=d.frontCamera?'Switch to rear camera':'Switch to front camera';b.setAttribute('aria-label',b.title);o.innerHTML=d.outputPortrait?portraitIcon:landscapeIcon;o.title=d.outputPortrait?'Landscape output':'Portrait output';o.setAttribute('aria-label',o.title);z.max=d.maxZoom;z.value=d.zoom;z.disabled=!d.zoomSupported;l.textContent=d.zoomSupported?'Zoom '+Math.round((d.maxZoom?d.zoom/d.maxZoom:0)*100)+'%':'Zoom not supported';s.textContent=d.message||'';}"
                + "function status(){fetch('/api/status').then(function(r){return r.json()}).then(apply).catch(function(){s.textContent='Control connection failed';});}"
                + "b.onclick=function(){fetch('/api/switch',{method:'POST'}).then(function(r){return r.json()}).then(apply);};"
                + "o.onclick=function(){fetch('/api/orientation',{method:'POST'}).then(function(r){return r.json()}).then(apply);};"
                + "z.oninput=function(){l.textContent='Zoom '+Math.round((z.max?z.value/z.max:0)*100)+'%';};"
                + "z.onchange=function(){fetch('/api/zoom?value='+encodeURIComponent(z.value),{method:'POST'}).then(function(r){return r.json()}).then(apply);};"
                + "status();setInterval(status,3000);"
                + "</script></body></html>";
        byte[] bytes = body.getBytes(UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(ASCII));
        output.write(bytes);
        output.flush();
    }

    private void writeJson(OutputStream output, String json) throws IOException {
        byte[] bytes = json.getBytes(UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(ASCII));
        output.write(bytes);
        output.flush();
    }

    private void writeStream(OutputStream output) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, ASCII));
        writer.write("HTTP/1.1 200 OK\r\n");
        writer.write("Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n");
        writer.write("Cache-Control: no-cache\r\n");
        writer.write("Connection: close\r\n\r\n");
        writer.flush();

        while (running) {
            byte[] frame = frameProvider.getLatestJpeg();
            if (frame == null) {
                sleep(100);
                continue;
            }
            writer.write("--" + BOUNDARY + "\r\n");
            writer.write("Content-Type: image/jpeg\r\n");
            writer.write("Content-Length: " + frame.length + "\r\n\r\n");
            writer.flush();
            output.write(frame);
            output.write("\r\n".getBytes(ASCII));
            output.flush();
            sleep(100);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int extractIntQuery(String path, String key) {
        String marker = key + "=";
        int start = path.indexOf(marker);
        if (start < 0) {
            return 0;
        }
        start += marker.length();
        int end = path.indexOf('&', start);
        String value = end < 0 ? path.substring(start) : path.substring(start, end);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public interface FrameProvider {
        byte[] getLatestJpeg();
    }

    public interface CameraControl {
        String getStatusJson();

        String setZoom(int value);

        String switchCamera();

        String toggleOutputOrientation();
    }
}
