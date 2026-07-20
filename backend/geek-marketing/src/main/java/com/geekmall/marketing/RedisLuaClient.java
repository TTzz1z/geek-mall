package com.geekmall.marketing;

import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
class RedisLuaClient {
    private final String host;
    private final int port;

    RedisLuaClient(@Value("${geek.redis.host}") String host, @Value("${geek.redis.port}") int port) {
        this.host = host;
        this.port = port;
    }

    Object eval(String script, List<String> keys, List<String> args) {
        List<String> c = new ArrayList<>(List.of("EVAL", script, String.valueOf(keys.size())));
        c.addAll(keys);
        c.addAll(args);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1200);
            s.setSoTimeout(1500);
            var out = s.getOutputStream();
            out.write(encode(c));
            out.flush();
            return read(s.getInputStream());
        } catch (IOException e) {
            throw new BusinessException("MK50301", "秒杀 Redis 不可用，暂不能受理请求");
        }
    }

    void set(String key, String value) {
        command(List.of("SET", key, value));
    }

    void expire(String key, long seconds) {
        command(List.of("EXPIRE", key, String.valueOf(seconds)));
    }

    String get(String key) {
        Object v = command(List.of("GET", key));
        return v == null ? null : String.valueOf(v);
    }

    private Object command(List<String> c) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1200);
            s.setSoTimeout(1500);
            var out = s.getOutputStream();
            out.write(encode(c));
            out.flush();
            return read(s.getInputStream());
        } catch (IOException e) {
            throw new BusinessException("MK50301", "秒杀 Redis 不可用，暂不能受理请求");
        }
    }

    private static byte[] encode(List<String> c) {
        var b = new ByteArrayOutputStream();
        write(b, "*" + c.size() + "\r\n");
        for (String x : c) {
            byte[] v = x.getBytes(StandardCharsets.UTF_8);
            write(b, "$" + v.length + "\r\n");
            b.writeBytes(v);
            write(b, "\r\n");
        }
        return b.toByteArray();
    }

    private static void write(ByteArrayOutputStream b, String s) {
        b.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Object read(InputStream in) throws IOException {
        int p = in.read();
        if (p == '-') throw new IOException(line(in));
        String v = line(in);
        if (p == ':') return Long.valueOf(v);
        if (p == '$') {
            int n = Integer.parseInt(v);
            if (n < 0) return null;
            byte[] bytes = in.readNBytes(n);
            in.read();
            in.read();
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (p == '*') {
            int n = Integer.parseInt(v);
            var r = new ArrayList<>();
            for (int i = 0; i < n; i++) r.add(read(in));
            return r;
        }
        throw new IOException("invalid redis response");
    }

    private static String line(InputStream in) throws IOException {
        var b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\r'; ) b.write(c);
        if (in.read() != '\n') throw new IOException("invalid redis line");
        return b.toString(StandardCharsets.UTF_8);
    }
}
