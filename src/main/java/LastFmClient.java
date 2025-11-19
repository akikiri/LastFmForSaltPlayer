import okhttp3.*;
import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.util.*;

public class LastFmClient {

    private final String apiKey;
    private final String apiSecret;

    private final SessionStore sessionStore;
    private final OkHttpClient http = new OkHttpClient();
    private final Gson gson = new Gson();

    private static final String API_URL = "https://ws.audioscrobbler.com/2.0/";

    public LastFmClient(String apiKey, String apiSecret, SessionStore store) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.sessionStore = store;
    }

    // =======================
    //  基础 HTTP 请求方法
    // =======================

    private String sendPost(Map<String, String> params) throws IOException {
        FormBody.Builder builder = new FormBody.Builder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            builder.add(e.getKey(), e.getValue());
        }

        Request request = new Request.Builder()
                .url(API_URL)
                .post(builder.build())
                .header("User-Agent", "LastFmJavaClient/1.0")
                .build();

        Response resp = http.newCall(request).execute();
        return Objects.requireNonNull(resp.body()).string();
    }

    private String sendGet(Map<String, String> params) throws IOException {
        HttpUrl.Builder url = HttpUrl.parse(API_URL).newBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            url.addQueryParameter(e.getKey(), e.getValue());
        }

        Request request = new Request.Builder()
                .url(url.build())
                .header("User-Agent", "LastFmJavaClient/1.0")
                .build();

        Response resp = http.newCall(request).execute();
        return Objects.requireNonNull(resp.body()).string();
    }

    // ===================================================================
    // 1. 获取 Token
    // ===================================================================

    public String getToken() throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("method", "auth.getToken");
        params.put("api_key", apiKey);


        String apiSig = ApiSigner.generateApiSig(params, apiSecret);
        params.put("api_sig", apiSig);
        params.put("format", "json");

        String json = sendGet(params);
        return gson.fromJson(json, JsonObject.class)
                .get("token")
                .getAsString();
    }

    // ===================================================================
    // 2. 打开授权页面
    // ===================================================================

    public void openAuthPage(String token) {
        try {
            String url = "https://www.last.fm/api/auth/?api_key=" + apiKey + "&token=" + token;
            java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===================================================================
    // 3. 获取 SessionKey
    // ===================================================================

    public String fetchSessionKey(String token) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("method", "auth.getSession");
        params.put("api_key", apiKey);
        params.put("token", token);

        String apiSig = ApiSigner.generateApiSig(params, apiSecret);

// 之后才加 format
        params.put("format", "json");
        params.put("api_sig", apiSig);


        String json = sendGet(params);

        JsonObject obj = gson.fromJson(json, JsonObject.class);
        System.out.println(obj);

        // 检查是否有错误信息
        if (obj.has("error")) {
            throw new IOException("Last.fm API Error: " + obj.get("message"));
        }

        // 检查 session 是否存在
        if (!obj.has("session")) {
            throw new IOException("No session in response: " + json);
        }

        JsonElement sessionElement = obj.get("session");
        if (sessionElement == null || sessionElement.isJsonNull()) {
            throw new IOException("Session is null in response: " + json);
        }

        LastFmSession session =
                gson.fromJson(sessionElement, LastFmSession.class);

        if (session == null) {
            throw new IOException("Failed to parse session from response: " + json);
        }

        sessionStore.saveSessionKey(session.key);

        return session.key;
    }

    // ===================================================================
    // 4. Now Playing
    // ===================================================================

    public void updateNowPlaying(String artist, String track) throws Exception {
        String sk = sessionStore.loadSessionKey();

        Map<String, String> params = new HashMap<>();
        params.put("method", "track.updateNowPlaying");
        params.put("artist", artist);
        params.put("track", track);
        params.put("api_key", apiKey);
        params.put("sk", sk);


        String apiSig = ApiSigner.generateApiSig(params, apiSecret);
        params.put("api_sig", apiSig);
        params.put("format", "json");

        System.out.println(sendPost(params));
    }

    // ===================================================================
    // 5. 单条 Scrobble
    // ===================================================================

    public void scrobble(ScrobbleTrack track) throws Exception {
        String sk = sessionStore.loadSessionKey();

        Map<String, String> params = new HashMap<>();
        params.put("method", "track.scrobble");
        params.put("artist", track.artist);
        params.put("track", track.track);
        params.put("timestamp", String.valueOf(track.timestamp));
        params.put("api_key", apiKey);
        params.put("sk", sk);


        String apiSig = ApiSigner.generateApiSig(params, apiSecret);
        params.put("api_sig", apiSig);
        params.put("format", "json");

        System.out.println(sendPost(params));
    }

    // ===================================================================
    // 6. 批量 Scrobble（最多 50 条）
    // ===================================================================

    public void batchScrobble(List<ScrobbleTrack> list) throws Exception {
        if (list.size() > 50) {
            throw new IllegalArgumentException("最多一次 50 条 scrobble");
        }

        String sk = sessionStore.loadSessionKey();

        Map<String, String> params = new HashMap<>();
        params.put("method", "track.scrobble");
        params.put("api_key", apiKey);
        params.put("sk", sk);


        int i = 0;
        for (ScrobbleTrack t : list) {
            params.put("artist[" + i + "]", t.artist);
            params.put("track[" + i + "]", t.track);
            params.put("timestamp[" + i + "]", String.valueOf(t.timestamp));
            i++;
        }

        String apiSig = ApiSigner.generateApiSig(params, apiSecret);
        params.put("api_sig", apiSig);
        params.put("format", "json");

        System.out.println(sendPost(params));
    }
}
