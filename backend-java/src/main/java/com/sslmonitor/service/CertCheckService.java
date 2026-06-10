package com.sslmonitor.service;

import org.springframework.stereotype.Service;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * SSL/TLS 证书检测核心。返回的 Map 使用 snake_case 键，直接与前端字段对应。
 */
@Service
public class CertCheckService {

    private static final String[] WEAK_CIPHERS = {"RC4", "3DES", "DES", "MD5", "NULL", "EXPORT", "ANON"};

    private static final Map<String, Integer> TLS_SCORES = Map.of(
            "TLSv1.3", 100,
            "TLSv1.2", 90,
            "TLSv1.1", 50,
            "TLSv1", 30,
            "SSLv3", 10,
            "SSLv2", 0
    );

    private static final int CONNECT_TIMEOUT_MS = 8000;

    /** 信任所有证书的 TrustManager —— 即便证书过期/自签也要拿到信息用于分析 */
    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return ctx;
    }

    public Map<String, Object> check(String host, int port) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", host);
        result.put("port", port);
        result.put("checked_at", Instant.now().toString());
        result.put("ok", false);
        result.put("error", null);
        result.put("cert", null);
        result.put("tls", null);
        result.put("security", null);
        result.put("availability", null);

        X509Certificate cert;
        String tlsVersion;
        String cipher;

        try {
            SSLContext ctx = trustAllContext();
            SSLSocketFactory factory = ctx.getSocketFactory();
            try (Socket plain = new Socket()) {
                plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                try (SSLSocket socket = (SSLSocket) factory.createSocket(plain, host, port, true)) {
                    socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                    // 设置 SNI
                    SSLParameters params = socket.getSSLParameters();
                    params.setServerNames(List.of(new SNIHostName(host)));
                    socket.setSSLParameters(params);

                    socket.startHandshake();
                    SSLSession session = socket.getSession();
                    tlsVersion = normalizeTlsVersion(session.getProtocol());
                    cipher = session.getCipherSuite();
                    Certificate[] chain = session.getPeerCertificates();
                    if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                        result.put("error", "未获取到 X509 证书");
                        return result;
                    }
                    cert = (X509Certificate) chain[0];
                }
            }
        } catch (Exception e) {
            result.put("error", "连接或握手失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        }

        Map<String, Object> certInfo = buildCertInfo(host, cert);
        Map<String, Object> tlsInfo = new LinkedHashMap<>();
        tlsInfo.put("version", tlsVersion);
        tlsInfo.put("cipher", cipher);
        tlsInfo.put("cipher_version", tlsVersion);
        tlsInfo.put("cipher_bits", guessCipherBits(cipher));

        Map<String, Object> security = evaluateSecurity(certInfo, tlsVersion, cipher);

        result.put("ok", true);
        result.put("cert", certInfo);
        result.put("tls", tlsInfo);
        result.put("security", security);
        result.put("availability", checkAvailability(host, port));
        return result;
    }

    private Map<String, Object> buildCertInfo(String host, X509Certificate cert) {
        Map<String, Object> info = new LinkedHashMap<>();

        Map<String, String> subject = parseDn(cert.getSubjectX500Principal().getName());
        Map<String, String> issuer = parseDn(cert.getIssuerX500Principal().getName());

        Instant notBefore = cert.getNotBefore().toInstant();
        Instant notAfter = cert.getNotAfter().toInstant();
        long daysLeft = ChronoUnit.DAYS.between(Instant.now(), notAfter);

        List<String> san = extractSan(cert);

        info.put("subject", subject);
        info.put("issuer", issuer);
        info.put("serial_number", cert.getSerialNumber().toString(16).toUpperCase());
        info.put("version", "v" + cert.getVersion());
        info.put("not_before", notBefore.toString());
        info.put("not_after", notAfter.toString());
        info.put("days_left", (int) daysLeft);
        info.put("expired", daysLeft < 0);
        info.put("san", san);
        info.put("signature_algorithm", cert.getSigAlgName());

        PublicKey pub = cert.getPublicKey();
        info.put("key_type", pub.getAlgorithm());
        info.put("key_size", keySize(pub));

        info.put("fingerprint_sha256", fingerprint(cert, "SHA-256"));
        info.put("fingerprint_sha1", fingerprint(cert, "SHA-1"));

        String cn = subject.getOrDefault("CN", "");
        info.put("hostname_match", hostnameMatches(host, cn, san));
        return info;
    }

    private Map<String, Object> evaluateSecurity(Map<String, Object> certInfo, String tlsVersion, String cipher) {
        int score = TLS_SCORES.getOrDefault(tlsVersion, 50);
        List<String> notes = new ArrayList<>();

        if (List.of("SSLv2", "SSLv3", "TLSv1", "TLSv1.1").contains(tlsVersion)) {
            notes.add("使用了不安全的协议版本 " + tlsVersion);
        }

        if (cipher != null) {
            String upper = cipher.toUpperCase();
            for (String weak : WEAK_CIPHERS) {
                if (upper.contains(weak)) {
                    score -= 30;
                    notes.add("使用了弱加密算法 " + weak);
                    break;
                }
            }
            if (upper.contains("GCM") || upper.contains("CHACHA20")) {
                // AEAD，安全
            } else if (upper.contains("CBC")) {
                score -= 5;
                notes.add("使用 CBC 模式（建议升级到 GCM/ChaCha20）");
            }
        }

        boolean expired = Boolean.TRUE.equals(certInfo.get("expired"));
        int daysLeft = (int) certInfo.getOrDefault("days_left", 0);
        boolean hostnameMatch = Boolean.TRUE.equals(certInfo.get("hostname_match"));
        String sigAlgo = String.valueOf(certInfo.get("signature_algorithm")).toUpperCase();

        if (expired) {
            score -= 50;
            notes.add(0, "证书已过期");
        } else if (daysLeft < 14) {
            score -= 20;
            notes.add(0, "证书即将过期（剩 " + daysLeft + " 天）");
        }
        if (!hostnameMatch) {
            score -= 30;
            notes.add(0, "证书不匹配请求的主机名");
        }
        if (sigAlgo.contains("MD5") || sigAlgo.startsWith("SHA1")) {
            score -= 25;
            notes.add("签名算法过弱: " + certInfo.get("signature_algorithm"));
        }

        score = Math.max(0, Math.min(100, score));
        if (notes.isEmpty()) {
            notes.add("协议与加密套件配置良好");
        }

        String grade = score >= 95 ? "A+" : score >= 85 ? "A" : score >= 70 ? "B"
                : score >= 55 ? "C" : score >= 40 ? "D" : "F";

        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("score", score);
        sec.put("grade", grade);
        sec.put("notes", notes);
        return sec;
    }

    private Map<String, Object> checkAvailability(String host, int port) {
        Map<String, Object> avail = new LinkedHashMap<>();
        String url = port == 443 ? "https://" + host : "https://" + host + ":" + port;
        long start = System.nanoTime();
        try {
            SSLContext ctx = trustAllContext();
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(ctx)
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "ssl-monitor/1.0")
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(request, HttpResponse.BodyHandlers.discarding());
            int ms = (int) ((System.nanoTime() - start) / 1_000_000);
            avail.put("reachable", true);
            avail.put("status_code", resp.statusCode());
            avail.put("response_ms", ms);
            avail.put("error", null);
        } catch (Exception e) {
            int ms = (int) ((System.nanoTime() - start) / 1_000_000);
            avail.put("reachable", false);
            avail.put("status_code", null);
            avail.put("response_ms", ms);
            avail.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return avail;
    }

    // ---------- helpers ----------

    private static String normalizeTlsVersion(String v) {
        if (v == null) return "unknown";
        return v; // Java 返回 "TLSv1.2" / "TLSv1.3"，已符合
    }

    private static Map<String, String> parseDn(String dn) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            LdapName ln = new LdapName(dn);
            for (Rdn rdn : ln.getRdns()) {
                String type = rdn.getType().toUpperCase();
                if (!map.containsKey(type)) {
                    map.put(type, String.valueOf(rdn.getValue()));
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractSan(X509Certificate cert) {
        List<String> result = new ArrayList<>();
        try {
            Collection<List<?>> alts = cert.getSubjectAlternativeNames();
            if (alts != null) {
                for (List<?> entry : alts) {
                    if (entry.size() >= 2 && entry.get(1) != null) {
                        result.add(String.valueOf(entry.get(1)));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static Integer keySize(PublicKey pub) {
        if (pub instanceof RSAPublicKey rsa) {
            return rsa.getModulus().bitLength();
        } else if (pub instanceof ECPublicKey ec) {
            return ec.getParams().getCurve().getField().getFieldSize();
        }
        return null;
    }

    private static String fingerprint(X509Certificate cert, String algo) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] der = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < der.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", der[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "-";
        }
    }

    private static Integer guessCipherBits(String cipher) {
        if (cipher == null) return null;
        String u = cipher.toUpperCase();
        if (u.contains("256")) return 256;
        if (u.contains("128")) return 128;
        if (u.contains("CHACHA20")) return 256;
        return null;
    }

    private static boolean hostnameMatches(String host, String cn, List<String> sanList) {
        List<String> candidates = new ArrayList<>();
        if (cn != null && !cn.isEmpty()) candidates.add(cn);
        candidates.addAll(sanList);
        String hostL = host.toLowerCase();
        for (String c : candidates) {
            String cl = c.toLowerCase().trim();
            if (cl.equals(hostL)) return true;
            if (cl.startsWith("*.")) {
                String suffix = cl.substring(1); // ".example.com"
                if (hostL.endsWith(suffix) && countDots(hostL) >= countDots(cl)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long countDots(String s) {
        return s.chars().filter(ch -> ch == '.').count();
    }
}
