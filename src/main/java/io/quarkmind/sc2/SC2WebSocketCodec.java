package io.quarkmind.sc2;

import java.io.*;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * SC2 WebSocket framing and handshake codec (RFC 6455).
 *
 * <p>Static utilities for encoding/decoding WebSocket frames and performing
 * WebSocket handshakes over raw sockets. Used by {@code QuarkusSC2Transport}
 * (client), {@code FakeSC2Server} (test fixture), and {@code EmulatedSC2Server}.
 *
 * <p>All methods are static — no state, no thread safety concerns.
 */
public final class SC2WebSocketCodec {

    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private SC2WebSocketCodec() {}

    /**
     * Encode a WebSocket binary frame with masking (client→server, RFC 6455 §5.3).
     * Handles all three length encodings: 1-byte (0–125), 2-byte (126–65535), 8-byte (65536+).
     */
    public static byte[] encodeClientFrame(byte[] payload) {
        byte[] mask = new byte[4];
        SECURE_RANDOM.nextBytes(mask); // RFC 6455 §10.3: mask must be unpredictable
        byte[] masked = payload.clone();
        for (int i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];

        ByteArrayOutputStream frame = new ByteArrayOutputStream(10 + payload.length);
        frame.write(0x82); // FIN=1, opcode=2 (binary)
        writeLength(frame, payload.length, true);
        frame.write(mask, 0, 4);
        frame.write(masked, 0, masked.length);
        return frame.toByteArray();
    }

    /**
     * Encode a WebSocket binary frame without masking (server→client, RFC 6455 §5.1).
     * Server-to-client frames are unmasked per the WebSocket spec.
     */
    public static byte[] encodeServerFrame(byte[] payload) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(10 + payload.length);
        frame.write(0x82); // FIN=1, opcode=2 (binary)
        writeLength(frame, payload.length, false);
        frame.write(payload, 0, payload.length);
        return frame.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream frame, int length, boolean masked) {
        int maskBit = masked ? 0x80 : 0;
        if (length < 126) {
            frame.write(maskBit | length);
        } else if (length <= 65535) {
            frame.write(maskBit | 126);
            frame.write((length >> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(maskBit | 127);
            long l = length;
            for (int i = 7; i >= 0; i--) frame.write((int) ((l >> (8 * i)) & 0xFF));
        }
    }

    /**
     * Read one complete WebSocket message from the input stream.
     * Handles fragmentation — accumulates chunks until FIN=1.
     * Returns {@code null} if the stream ends before a complete frame.
     */
    public static byte[] readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream msgBuf = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read(); if (b0 < 0) return null;
            int b1 = in.read(); if (b1 < 0) return null;
            boolean maskedFrame = (b1 & 0x80) != 0;
            int len = b1 & 0x7F;
            if (len == 126) {
                int h = in.read(), l = in.read();
                if (h < 0 || l < 0) return null;
                len = (h << 8) | l;
            } else if (len == 127) {
                long llen = 0;
                for (int i = 0; i < 8; i++) {
                    int b = in.read();
                    if (b < 0) return null;
                    llen = (llen << 8) | b;
                }
                len = (int) llen;
            }
            byte[] maskBytes = maskedFrame ? in.readNBytes(4) : new byte[0];
            byte[] chunk = in.readNBytes(len);
            if (maskedFrame) for (int i = 0; i < chunk.length; i++) chunk[i] ^= maskBytes[i % 4];
            msgBuf.write(chunk);
            boolean fin = (b0 & 0x80) != 0;
            if (fin) return msgBuf.toByteArray();
        }
    }

    /**
     * Perform a WebSocket client handshake (RFC 6455 §4.1).
     * Sends the upgrade request and validates the 101 response.
     */
    public static void performClientHandshake(OutputStream out, InputStream in, int port)
            throws Exception {
        byte[] keyBytes = new byte[16];
        new java.util.Random().nextBytes(keyBytes);
        String wsKey = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET /sc2api HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + port + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + wsKey + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes());
        out.flush();
        String response = readHttpHeaders(in);
        if (!response.contains("101"))
            throw new IOException("WebSocket upgrade failed: " + response.split("\r\n")[0]);
    }

    /**
     * Perform a WebSocket server handshake (RFC 6455 §4.2).
     * Reads the upgrade request, computes the accept hash, and sends the 101 response.
     */
    public static void performServerHandshake(InputStream in, OutputStream out) throws Exception {
        String headers = readHttpHeaders(in);
        String key = null;
        for (String line : headers.split("\r\n")) {
            if (line.startsWith("Sec-WebSocket-Key:"))
                key = line.substring("Sec-WebSocket-Key:".length()).trim();
        }
        if (key == null) throw new IOException("Missing Sec-WebSocket-Key header");
        String accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes()));
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes());
        out.flush();
    }

    private static String readHttpHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            sb.append((char) b);
            int len = sb.length();
            if (len >= 4 && sb.charAt(len-4)=='\r' && sb.charAt(len-3)=='\n'
                    && sb.charAt(len-2)=='\r' && sb.charAt(len-1)=='\n') break;
        }
        return sb.toString();
    }
}
