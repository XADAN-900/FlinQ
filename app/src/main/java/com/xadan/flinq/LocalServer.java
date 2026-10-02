package com.xadan.flinq;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * assets/www klasöründeki dosyaları 127.0.0.1 üzerinden sunan minik HTTP sunucu.
 * Sadece loopback'e bağlanır, dışarıdan erişilemez.
 */
public class LocalServer {

    private static final String ROOT = "www";

    private final AssetManager assets;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;

    public LocalServer(AssetManager assets) {
        this.assets = assets;
    }

    /** Tercih edilen portu dener (localStorage için origin sabit kalsın), olmazsa boş port alır. */
    public int start(int preferredPort) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        try {
            ss.bind(new InetSocketAddress(loopback, preferredPort), 50);
        } catch (IOException e) {
            ss.close();
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(loopback, 0), 50);
        }
        serverSocket = ss;
        pool = Executors.newFixedThreadPool(6);
        running = true;

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    final Socket client = serverSocket.accept();
                    pool.execute(() -> handle(client));
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        }, "flinq-local-server");
        acceptThread.setDaemon(true);
        acceptThread.start();

        return serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            // Header'ları tüket
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // gerek yok
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendSimple(client, 400, "Bad Request");
                return;
            }
            String method = parts[0];
            String rawPath = parts[1];

            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendSimple(client, 405, "Method Not Allowed");
                return;
            }

            int q = rawPath.indexOf('?');
            if (q >= 0) rawPath = rawPath.substring(0, q);
            int h = rawPath.indexOf('#');
            if (h >= 0) rawPath = rawPath.substring(0, h);

            String path;
            try {
                path = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                sendSimple(client, 400, "Bad Request");
                return;
            }

            if (path.contains("..") || path.contains("\\")) {
                sendSimple(client, 403, "Forbidden");
                return;
            }
            if (path.equals("/") || path.isEmpty()) path = "/index.html";

            byte[] body = readAsset(ROOT + path);
            if (body == null) {
                sendSimple(client, 404, "Not Found");
                return;
            }

            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + mimeFor(path) + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(header.getBytes(StandardCharsets.ISO_8859_1));
            if (method.equals("GET")) out.write(body);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void sendSimple(Socket client, int code, String text) throws IOException {
        byte[] body = (code + " " + text).getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + text + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private byte[] readAsset(String assetPath) {
        try (InputStream in = assets.open(assetPath)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null; // dosya yok ya da klasör
        }
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".ogg")) return "audio/ogg";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
