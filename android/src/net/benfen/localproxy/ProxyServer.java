package net.benfen.localproxy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电视机本地小代理（原生版）：与 tvproxy.py 行为一致——
 * 监听 127.0.0.1:8899，转发直播流、补 CORS/PNA 头、改写 m3u8 里的地址让分片也走代理。
 * 纯 java.* 实现，可独立在 JVM 上自测（见 main）。
 */
public class ProxyServer {
    public static final int PORT = 8899;
    public static final String VERSION = "1.2";
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private static final int MAX_PLAYLIST = 8 * 1024 * 1024;

    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running = false;

    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tvproxy-conn");
                t.setDaemon(true);
                return t;
            }
        });
        running = true;
        Thread accept = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() {
                            public void run() { handle(s); }
                        });
                    } catch (IOException e) {
                        if (running) System.err.println("accept: " + e);
                    }
                }
            }
        }, "tvproxy-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    public boolean isRunning() { return running; }

    /* ---------- 请求处理 ---------- */

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String head = readHead(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            String method = first.length > 0 ? first[0] : "GET";
            String target = first.length > 1 ? first[1] : "/";
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(),
                                       lines[i].substring(c + 1).trim());
            }
            URI uri;
            try { uri = URI.create(target); }
            catch (Exception e) { sendText(out, method, 400, "请求地址不对。\n"); s.close(); return; }
            String path = uri.getPath() == null ? "/" : uri.getPath();

            if ("OPTIONS".equals(method)) {
                writeHead(out, 204, null, -1, null);
                s.close(); return;
            }
            if ("/ping".equals(path)) {
                byte[] body = ("{\"ok\":true,\"app\":\"tvproxy\",\"version\":\"" + VERSION + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                writeHead(out, 200, "application/json", body.length, null);
                if (!"HEAD".equals(method)) out.write(body);
                out.flush(); s.close(); return;
            }
            if ("/".equals(path) || path.isEmpty()) {
                byte[] body = ("电视机本地小代理正在运行 ✓\n"
                        + "回到电视机（https://app.benfen.net/tv/）设置里打开「本地代理」即可看电视。\n"
                        + "保持本应用开着，关掉就停了。").getBytes(StandardCharsets.UTF_8);
                writeHead(out, 200, "text/plain; charset=utf-8", body.length, null);
                if (!"HEAD".equals(method)) out.write(body);
                out.flush(); s.close(); return;
            }
            if ("/proxy".equals(path)) {
                proxy(out, method, parseQuery(uri.getRawQuery()), headers);
                s.close(); return;
            }
            sendText(out, method, 400, "用法：/proxy?url=直播流地址\n");
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void proxy(OutputStream out, String method, Map<String, String> q,
                       Map<String, String> reqHeaders) throws IOException {
        String target = q.get("url");
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            sendText(out, method, 400, "用法：/proxy?url=直播流地址\n");
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = openFollowing(target, method, reqHeaders);
            int code = conn.getResponseCode();
            String ctype = conn.getContentType();
            boolean playlist = (ctype != null && ctype.toLowerCase().contains("mpegurl"))
                    || target.split("\\?")[0].toLowerCase().endsWith(".m3u8");
            InputStream body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (body == null) body = new java.io.ByteArrayInputStream(new byte[0]);

            if (playlist && !"HEAD".equals(method)) {
                byte[] raw = readAll(body, MAX_PLAYLIST);
                String text = new String(raw, StandardCharsets.UTF_8);
                String rewritten = rewritePlaylist(text, conn.getURL().toString());
                byte[] bytes = rewritten.getBytes(StandardCharsets.UTF_8);
                Map<String, String> extra = new HashMap<>();
                extra.put("Content-Type", "application/vnd.apple.mpegurl");
                writeHead(out, code, null, bytes.length, extra);
                out.write(bytes);
                out.flush();
                return;
            }
            Map<String, String> extra = new HashMap<>();
            if (ctype != null) extra.put("Content-Type", ctype);
            String crange = conn.getHeaderField("Content-Range");
            if (crange != null) extra.put("Content-Range", crange);
            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            if (acceptRanges != null) extra.put("Accept-Ranges", acceptRanges);
            long len = -1;
            try { len = Long.parseLong(String.valueOf(conn.getHeaderField("Content-Length"))); }
            catch (Exception ignored) {}
            writeHead(out, code, null, len, extra);
            if (!"HEAD".equals(method)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = body.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
            out.flush();
        } catch (Exception e) {
            try {
                sendText(out, method, 502,
                        "这路流没拉到（" + humanErr(e) + "）。多半是源站的问题，换个台试试。\n");
            } catch (Exception ignored) {}
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection openFollowing(String url, String method,
                                            Map<String, String> reqHeaders) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestMethod("HEAD".equals(method) ? "GET" : method);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) tvproxy/" + VERSION);
            String range = reqHeaders.get("range");
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                String next = new URL(new URL(current), c.getHeaderField("Location")).toString();
                c.disconnect();
                current = next;
                continue;
            }
            return c;
        }
        throw new IOException("重定向太多了");
    }

    private String rewritePlaylist(String text, String baseUrl) {
        StringBuilder sb = new StringBuilder(text.length() + 256);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append(line);
            } else if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer lb = new StringBuffer();
                while (m.find()) {
                    m.appendReplacement(lb, Matcher.quoteReplacement(
                            "URI=\"" + wrap(resolve(baseUrl, m.group(1))) + "\""));
                }
                m.appendTail(lb);
                sb.append(lb);
            } else {
                sb.append(wrap(resolve(baseUrl, t)));
            }
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); }
        catch (Exception e) { return rel; }
    }

    private String wrap(String absolute) {
        try {
            return "http://127.0.0.1:" + PORT + "/proxy?url="
                    + URLEncoder.encode(absolute, "UTF-8");
        } catch (Exception e) { return absolute; }
    }

    /* ---------- 基础件 ---------- */

    private void writeHead(OutputStream out, int code, String ctype, long len,
                           Map<String, String> extra) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        h.append("Access-Control-Allow-Headers: *\r\n");
        h.append("Access-Control-Allow-Private-Network: true\r\n");
        h.append("Access-Control-Max-Age: 86400\r\n");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey().equalsIgnoreCase("Content-Type")) ctype = e.getValue();
                else h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        if (ctype != null) h.append("Content-Type: ").append(ctype).append("\r\n");
        if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendText(OutputStream out, String method, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        writeHead(out, code, "text/plain; charset=utf-8", body.length, null);
        if (!"HEAD".equals(method)) out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 502: return "Bad Gateway";
            default: return code >= 200 && code < 300 ? "OK" : "Status";
        }
    }

    private static String humanErr(Exception e) {
        String m = String.valueOf(e);
        if (m.contains("ConnectException") || m.contains("拒绝")) return "对方服务器拒绝连接";
        if (m.contains("Timeout") || m.contains("timeout")) return "对方服务器半天没回应";
        if (m.contains("UnknownHost")) return "找不到这路流的服务器";
        return "连接出错";
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0; // 匹配 \r\n\r\n
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (buf.size() > 65536) break;
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = (b == '\r') ? 1 : 0;
        }
        if (buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[65536];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
            if (buf.size() > max) break;
        }
        return buf.toByteArray();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            } catch (Exception ignored) {}
        }
        return map;
    }

    /* JVM 自测入口（安卓上不用） */
    public static void main(String[] args) throws Exception {
        ProxyServer s = new ProxyServer();
        s.start();
        System.out.println("tvproxy listening on 127.0.0.1:" + PORT);
        Thread.sleep(Long.MAX_VALUE);
    }
}
