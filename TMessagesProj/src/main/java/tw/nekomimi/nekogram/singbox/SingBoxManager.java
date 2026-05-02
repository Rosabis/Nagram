package tw.nekomimi.nekogram.singbox;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.File;
import java.net.URLDecoder;

import io.nekohasekai.libbox.BoxService;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.SetupOptions;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

public class SingBoxManager {

    private static final String TAG = "SingBoxManager";
    private static final int LOCAL_SOCKS_PORT = 11789;

    private static SingBoxManager instance;
    private boolean running = false;
    private String currentLink = "";
    private BoxService currentService;

    private static final PlatformInterface minimalPlatform = new PlatformInterface() {
        @Override
        public boolean usePlatformAutoDetectInterfaceControl() { return false; }
        @Override
        public void autoDetectInterfaceControl(int fd) {}
        @Override
        public int openTun(TunOptions options) { return -1; }
        @Override
        public boolean useProcFS() { return false; }
        @Override
        public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort, String destinationAddress, int destinationPort) { return null; }
        @Override
        public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {}
        @Override
        public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {}
        @Override
        public NetworkInterfaceIterator getInterfaces() { return null; }
        @Override
        public boolean underNetworkExtension() { return false; }
        @Override
        public boolean includeAllNetworks() { return false; }
        @Override
        public void clearDNSCache() {}
        @Override
        public WIFIState readWIFIState() { return null; }
        @Override
        public LocalDNSTransport localDNSTransport() { return null; }
        @Override
        public StringIterator systemCertificates() { return null; }
    };

    public static synchronized SingBoxManager getInstance() {
        if (instance == null) {
            instance = new SingBoxManager();
        }
        return instance;
    }

    private SingBoxManager() {}

    public boolean isRunning() {
        return running;
    }

    public String getCurrentLink() {
        return currentLink;
    }

    public int getLocalSocksPort() {
        return LOCAL_SOCKS_PORT;
    }

    public boolean isSingBoxProxy(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null || TextUtils.isEmpty(proxyInfo.secret)) {
            return false;
        }
        String secret = proxyInfo.secret;
        return secret.startsWith("vless://") ||
               secret.startsWith("hysteria://") ||
               secret.startsWith("hysteria2://") ||
               secret.startsWith("hy2://") ||
               secret.startsWith("vmess://") ||
               secret.startsWith("vmess1://") ||
               secret.startsWith("trojan://") ||
               secret.startsWith("ss://");
    }

    public synchronized void start(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null || !isSingBoxProxy(proxyInfo)) {
            return;
        }
        String link = proxyInfo.secret;
        if (running && link.equals(currentLink)) {
            return;
        }
        stop();
        try {
            String config = linkToConfig(link);
            if (config == null) {
                FileLog.e(TAG + ": failed to generate config for " + link);
                return;
            }
            currentLink = link;

            File baseDir = new File(ApplicationLoader.applicationContext.getFilesDir(), "sing-box");
            baseDir.mkdirs();
            File tempDir = new File(baseDir, "temp");
            tempDir.mkdirs();

            SetupOptions setupOptions = new SetupOptions();
            setupOptions.setBasePath(baseDir.getAbsolutePath());
            setupOptions.setWorkingPath(tempDir.getAbsolutePath());
            setupOptions.setTempPath(new File(tempDir, "tmp").getAbsolutePath());
            Libbox.setup(setupOptions);

            currentService = Libbox.newService(config, minimalPlatform);
            currentService.start();
            running = true;
            FileLog.d(TAG + ": sing-box started for " + link.substring(0, Math.min(link.length(), 50)));
        } catch (Exception e) {
            FileLog.e(TAG + ": failed to start sing-box", e);
            running = false;
            currentLink = "";
        }
    }

    public void ping(SharedConfig.ProxyInfo proxyInfo, long timeoutMs, PingCallback callback) {
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            try {
                start(proxyInfo);
                if (!running) {
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(-1));
                    return;
                }
                java.net.Socket socket = new java.net.Socket();
                java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT));
                socket.connect(new java.net.InetSocketAddress("149.154.167.50", 443), (int) timeoutMs);
                long pingTime = System.currentTimeMillis() - startTime;
                socket.close();
                AndroidUtilities.runOnUIThread(() -> callback.onResult(pingTime));
            } catch (Exception e) {
                FileLog.e(TAG + ": ping failed", e);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(-1));
            }
        }).start();
    }

    public interface PingCallback {
        void onResult(long pingTime);
    }

    public synchronized void stop() {
        if (running) {
            try {
                if (currentService != null) {
                    currentService.close();
                    currentService = null;
                }
            } catch (Exception e) {
                FileLog.e(TAG + ": failed to stop sing-box", e);
            }
            running = false;
            currentLink = "";
        }
    }

    public String linkToConfig(String link) {
        try {
            if (link.startsWith("vless://")) {
                return buildVlessConfig(link);
            } else if (link.startsWith("hysteria2://") || link.startsWith("hy2://")) {
                return buildHysteria2Config(link);
            } else if (link.startsWith("hysteria://")) {
                return buildHysteriaConfig(link);
            } else if (link.startsWith("trojan://")) {
                return buildTrojanConfig(link);
            } else if (link.startsWith("vmess://") || link.startsWith("vmess1://")) {
                return buildVmessConfig(link);
            } else if (link.startsWith("ss://")) {
                return buildShadowsocksConfig(link);
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": failed to parse link", e);
        }
        return null;
    }

    private String buildBaseConfig(String outbound) throws Exception {
        JSONObject config = new JSONObject();

        JSONObject log = new JSONObject();
        log.put("level", "warn");
        config.put("log", log);

        JSONArray inbounds = new JSONArray();
        JSONObject socksIn = new JSONObject();
        socksIn.put("type", "socks");
        socksIn.put("tag", "socks-in");
        socksIn.put("listen", "127.0.0.1");
        socksIn.put("listen_port", LOCAL_SOCKS_PORT);
        inbounds.put(socksIn);
        config.put("inbounds", inbounds);

        JSONArray outbounds = new JSONArray();
        outbounds.put(new JSONObject(outbound));
        JSONObject direct = new JSONObject();
        direct.put("type", "direct");
        direct.put("tag", "direct");
        outbounds.put(direct);
        config.put("outbounds", outbounds);

        return config.toString();
    }

    private String buildVlessConfig(String link) throws Exception {
        android.net.Uri uri = android.net.Uri.parse(link);
        JSONObject outbound = new JSONObject();
        outbound.put("type", "vless");
        outbound.put("server", uri.getHost());
        int port = uri.getPort();
        if (port == -1) port = 443;
        outbound.put("server_port", port);
        outbound.put("uuid", uri.getUserInfo());

        String security = uri.getQueryParameter("security");
        if (security == null) security = "none";
        JSONObject tls = new JSONObject();
        tls.put("enabled", "tls".equals(security) || "reality".equals(security));
        if ("tls".equals(security) || "reality".equals(security)) {
            String sni = uri.getQueryParameter("sni");
            if (sni != null) tls.put("server_name", sni);
            String fp = uri.getQueryParameter("fp");
            if (fp != null) {
                JSONObject utls = new JSONObject();
                utls.put("enabled", true);
                utls.put("fingerprint", fp);
                tls.put("utls", utls);
            }
            if ("reality".equals(security)) {
                JSONObject reality = new JSONObject();
                reality.put("enabled", true);
                String pbk = uri.getQueryParameter("pbk");
                if (pbk != null) reality.put("public_key", pbk);
                String sid = uri.getQueryParameter("sid");
                if (sid != null) reality.put("short_id", sid);
                tls.put("reality", reality);
            }
            String alpn = uri.getQueryParameter("alpn");
            if (alpn != null) {
                JSONArray alpnArr = new JSONArray();
                for (String a : alpn.split(",")) {
                    alpnArr.put(a.trim());
                }
                tls.put("alpn", alpnArr);
            }
            if ("true".equals(uri.getQueryParameter("allowInsecure"))) {
                tls.put("insecure", true);
            }
        }
        outbound.put("tls", tls);

        String type = uri.getQueryParameter("type");
        if (type == null) type = "tcp";
        if (!"tcp".equals(type)) {
            JSONObject transport = new JSONObject();
            if ("ws".equals(type)) {
                transport.put("type", "ws");
                String path = uri.getQueryParameter("path");
                if (path != null) transport.put("path", URLDecoder.decode(path, "UTF-8"));
                String host = uri.getQueryParameter("host");
                if (host != null) {
                    JSONObject headers = new JSONObject();
                    headers.put("Host", host);
                    transport.put("headers", headers);
                }
            } else if ("grpc".equals(type)) {
                transport.put("type", "grpc");
                String serviceName = uri.getQueryParameter("serviceName");
                if (serviceName != null) transport.put("service_name", serviceName);
            } else if ("http".equals(type) || "h2".equals(type)) {
                transport.put("type", "http");
                String path = uri.getQueryParameter("path");
                if (path != null) transport.put("path", path);
                String host = uri.getQueryParameter("host");
                if (host != null) transport.put("host", new JSONArray().put(host));
            }
            if (transport.length() > 0) outbound.put("transport", transport);
        }

        String flow = uri.getQueryParameter("flow");
        if (flow != null) outbound.put("flow", flow);

        outbound.put("tag", "proxy");
        return buildBaseConfig(outbound.toString());
    }

    private String buildHysteria2Config(String link) throws Exception {
        android.net.Uri uri = android.net.Uri.parse(link);
        JSONObject outbound = new JSONObject();
        outbound.put("type", "hysteria2");
        outbound.put("server", uri.getHost());
        int port = uri.getPort();
        if (port == -1) port = 443;
        outbound.put("server_port", port);

        String password = uri.getUserInfo();
        if (password != null) outbound.put("password", URLDecoder.decode(password, "UTF-8"));

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        String sni = uri.getQueryParameter("sni");
        if (sni != null) tls.put("server_name", sni);
        else tls.put("server_name", uri.getHost());
        if ("1".equals(uri.getQueryParameter("insecure")) || "true".equals(uri.getQueryParameter("insecure"))) {
            tls.put("insecure", true);
        }
        outbound.put("tls", tls);

        String obfs = uri.getQueryParameter("obfs");
        if (obfs != null) {
            JSONObject obfsObj = new JSONObject();
            obfsObj.put("type", obfs);
            String obfsPassword = uri.getQueryParameter("obfs-password");
            if (obfsPassword != null) obfsObj.put("password", obfsPassword);
            outbound.put("obfs", obfsObj);
        }

        outbound.put("tag", "proxy");
        return buildBaseConfig(outbound.toString());
    }

    private String buildHysteriaConfig(String link) throws Exception {
        android.net.Uri uri = android.net.Uri.parse(link);
        JSONObject outbound = new JSONObject();
        outbound.put("type", "hysteria");
        outbound.put("server", uri.getHost());
        int port = uri.getPort();
        if (port == -1) port = 443;
        outbound.put("server_port", port);

        String auth = uri.getQueryParameter("auth");
        if (auth != null) outbound.put("auth_str", URLDecoder.decode(auth, "UTF-8"));

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        String sni = uri.getQueryParameter("peer") != null ? uri.getQueryParameter("peer") : uri.getQueryParameter("sni");
        if (sni != null) tls.put("server_name", sni);
        else tls.put("server_name", uri.getHost());
        if ("1".equals(uri.getQueryParameter("insecure")) || "true".equals(uri.getQueryParameter("insecure"))) {
            tls.put("insecure", true);
        }
        outbound.put("tls", tls);

        outbound.put("tag", "proxy");
        return buildBaseConfig(outbound.toString());
    }

    private String buildTrojanConfig(String link) throws Exception {
        android.net.Uri uri = android.net.Uri.parse(link);
        JSONObject outbound = new JSONObject();
        outbound.put("type", "trojan");
        outbound.put("server", uri.getHost());
        int port = uri.getPort();
        if (port == -1) port = 443;
        outbound.put("server_port", port);

        String password = uri.getUserInfo();
        if (password == null) password = uri.getQueryParameter("password");
        if (password != null) outbound.put("password", URLDecoder.decode(password, "UTF-8"));

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        String sni = uri.getQueryParameter("sni");
        if (sni != null) tls.put("server_name", sni);
        else tls.put("server_name", uri.getHost());
        if ("true".equals(uri.getQueryParameter("allowInsecure"))) {
            tls.put("insecure", true);
        }
        outbound.put("tls", tls);

        String type = uri.getQueryParameter("type");
        if ("ws".equals(type)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "ws");
            String path = uri.getQueryParameter("path");
            if (path != null) transport.put("path", URLDecoder.decode(path, "UTF-8"));
            String host = uri.getQueryParameter("host");
            if (host != null) {
                JSONObject headers = new JSONObject();
                headers.put("Host", host);
                transport.put("headers", headers);
            }
            outbound.put("transport", transport);
        } else if ("grpc".equals(type)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "grpc");
            String serviceName = uri.getQueryParameter("serviceName");
            if (serviceName != null) transport.put("service_name", serviceName);
            outbound.put("transport", transport);
        }

        outbound.put("tag", "proxy");
        return buildBaseConfig(outbound.toString());
    }

    private String buildVmessConfig(String link) throws Exception {
        if (link.startsWith("vmess://")) {
            String encoded = link.substring("vmess://".length());
            byte[] decoded = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
            JSONObject obj = new JSONObject(new String(decoded, "UTF-8"));

            JSONObject outbound = new JSONObject();
            outbound.put("type", "vmess");
            outbound.put("server", obj.optString("add", ""));
            outbound.put("server_port", obj.optInt("port", 443));
            outbound.put("uuid", obj.optString("id", ""));
            outbound.put("alter_id", obj.optInt("aid", 0));
            String scy = obj.optString("scy", "auto");
            if (!"auto".equals(scy)) outbound.put("security", scy);

            JSONObject tls = new JSONObject();
            String tlsVal = obj.optString("tls", "");
            tls.put("enabled", "tls".equals(tlsVal));
            if ("tls".equals(tlsVal)) {
                String sni = obj.optString("sni", "");
                if (!TextUtils.isEmpty(sni)) tls.put("server_name", sni);
                String alpn = obj.optString("alpn", "");
                if (!TextUtils.isEmpty(alpn)) {
                    JSONArray alpnArr = new JSONArray();
                    for (String a : alpn.split(",")) alpnArr.put(a.trim());
                    tls.put("alpn", alpnArr);
                }
            }
            outbound.put("tls", tls);

            String net = obj.optString("net", "tcp");
            if ("ws".equals(net)) {
                JSONObject transport = new JSONObject();
                transport.put("type", "ws");
                String path = obj.optString("path", "");
                if (!TextUtils.isEmpty(path)) transport.put("path", path);
                String host = obj.optString("host", "");
                if (!TextUtils.isEmpty(host)) {
                    JSONObject headers = new JSONObject();
                    headers.put("Host", host);
                    transport.put("headers", headers);
                }
                outbound.put("transport", transport);
            } else if ("grpc".equals(net)) {
                JSONObject transport = new JSONObject();
                transport.put("type", "grpc");
                String serviceName = obj.optString("path", "");
                if (!TextUtils.isEmpty(serviceName)) transport.put("service_name", serviceName);
                outbound.put("transport", transport);
            } else if ("h2".equals(net)) {
                JSONObject transport = new JSONObject();
                transport.put("type", "http");
                String path = obj.optString("path", "");
                if (!TextUtils.isEmpty(path)) transport.put("path", path);
                String host = obj.optString("host", "");
                if (!TextUtils.isEmpty(host)) transport.put("host", new JSONArray().put(host));
                outbound.put("transport", transport);
            }

            outbound.put("tag", "proxy");
            return buildBaseConfig(outbound.toString());
        }
        return null;
    }

    private String buildShadowsocksConfig(String link) throws Exception {
        String remaining = link.substring("ss://".length());
        int hashIdx = remaining.indexOf('#');
        if (hashIdx != -1) remaining = remaining.substring(0, hashIdx);

        String encodedPart;
        String serverPart;
        int atIdx = remaining.indexOf('@');
        if (atIdx != -1) {
            encodedPart = remaining.substring(0, atIdx);
            serverPart = remaining.substring(atIdx + 1);
        } else {
            return null;
        }

        byte[] methodPassword = android.util.Base64.decode(encodedPart, android.util.Base64.NO_WRAP);
        String mpStr = new String(methodPassword, "UTF-8");
        int colonIdx = mpStr.indexOf(':');
        String method = mpStr.substring(0, colonIdx);
        String password = mpStr.substring(colonIdx + 1);

        String[] serverPort = serverPart.split(":");
        String server = serverPort[0];
        int port = serverPort.length > 1 ? Integer.parseInt(serverPort[1]) : 8388;

        JSONObject outbound = new JSONObject();
        outbound.put("type", "shadowsocks");
        outbound.put("server", server);
        outbound.put("server_port", port);
        outbound.put("method", method);
        outbound.put("password", password);
        outbound.put("tag", "proxy");

        return buildBaseConfig(outbound.toString());
    }
}
