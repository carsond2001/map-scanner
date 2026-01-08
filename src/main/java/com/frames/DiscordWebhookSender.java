package com.frames;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DiscordWebhookSender {
    public static void sendPng(String webhookUrl, byte[] pngBytes,
                               String fileName, String messageContent) throws Exception {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String boundary = "----MapRgbScannerBoundary" + System.currentTimeMillis();

        URL url = new URL(webhookUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty(
            "Content-Type",
            "multipart/form-data; boundary=" + boundary
        );

        try (OutputStream os = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(
                 new OutputStreamWriter(os, StandardCharsets.UTF_8), true)) {

            // JSON payload
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"payload_json\"\r\n");
            writer.append("Content-Type: application/json; charset=UTF-8\r\n\r\n");
            String json = "{\"content\":\"" + escape(messageContent)
                + "\",\"username\":\"MapRgbScanner\"}";
            writer.append(json).append("\r\n");
            writer.flush();

            // File part
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(fileName).append("\"\r\n");
            writer.append("Content-Type: image/png\r\n\r\n");
            writer.flush();

            os.write(pngBytes);
            os.flush();

            writer.append("\r\n--").append(boundary).append("--\r\n");
            writer.flush();
        }

        conn.getResponseCode(); // fire the request
        conn.disconnect();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    public static void sendMessage(String webhookUrl, String content) throws Exception {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        URL url = new URL(webhookUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        // Minimal Discord webhook JSON
        String json = "{\"content\":" + toJsonString(content) + "}";

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Discord webhook returned HTTP " + code);
        }
    }

    // helper (put inside DiscordWebhookSender too)
    private static String toJsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
