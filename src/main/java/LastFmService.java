import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class LastFmService {

    private final LastFmClient client;
    private final SessionStore sessionStore;
    private static final String API_KEY = "a8ea2c2bdcd7ab1df30be1d11ebe50dd";
    private static final String API_SECRET = "b45ce43573b2c96229798faa75836b02";
    private static final String DEFAULT_SESSION_PATH =
            System.getenv("APPDATA") +
                    "\\Salt Player for Windows\\workshop\\data\\Last_Fm_Session.json";

    public static LastFmService defaultInstance() throws Exception {
        return new LastFmService(API_KEY, API_SECRET, DEFAULT_SESSION_PATH);
    }

    public LastFmService(String apiKey, String apiSecret, String sessionFilePath) throws Exception {
        createDefaultSessionFileIfNeeded(sessionFilePath);
        this.sessionStore = new FileSessionStore(sessionFilePath);
        this.client = new LastFmClient(apiKey, apiSecret, sessionStore);
        connect();
    }

    /**
     * 初始化连接。
     * 注意：此方法涉及网络请求，请在后台线程调用，不要在 UI 线程调用。
     *
     * @throws AuthRequiredException 当需要用户进行网页授权时抛出
     * @throws Exception             其他网络或 IO 错误
     */
    public void connect() throws Exception {
        String session = sessionStore.loadSessionKey();
        String token = sessionStore.loadToken();

        if (session != null) {
            System.out.println("SessionKey 已加载：" + session);
            return; // 已登录，直接返回
        }

        System.out.println("没有 SessionKey，检查是否处于授权流程中...");

        if (token == null) {
            // 阶段 1：完全未授权，获取 Token 并抛出异常通知 UI 引导用户
            token = client.getToken();
            sessionStore.saveToken(token);
            client.openAuthPage(token);
            // 改进：抛出特定异常，让上层决定提示用户，而不是 System.exit
            throw new AuthRequiredException("请在浏览器中完成授权，然后再次重启应用。");
        } else {
            // 阶段 2：已有 Token，尝试获取 Session
            try {
                session = client.fetchSessionKey(token);
                System.out.println("SessionKey 获取成功并保存" + session);
                // 这里通常 client 内部或 store 会保存 session，确保这一点
            } catch (Exception e) {
                System.err.println("获取 SessionKey 失败 (Token 可能过期): " + e.getMessage());
                // Token 无效，清除并重置流程
                sessionStore.saveToken(null);
                // 递归调用一次重头开始，或者抛出异常让用户重试
                connect();
            }
        }
    }

    private void createDefaultSessionFileIfNeeded(String sessionFilePath) {
        try {
            Path path = Paths.get(sessionFilePath);
            if (!Files.exists(path)) {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                JsonObject defaultObj = new JsonObject();
                defaultObj.add("sessionKey", null);
                defaultObj.add("token", null);
                Files.writeString(path, defaultObj.toString());
            }
        } catch (IOException e) {
            throw new RuntimeException("无法创建 Session 存储文件", e);
        }
    }

    public void nowPlaying(String artist, String track) throws Exception {
        executeWithRetry(() -> {
            client.updateNowPlaying(artist, track);
            return null;
        });
    }

    public void scrobble(String artist, String track) throws Exception {
        executeWithRetry(() -> {
            client.scrobble(new ScrobbleTrack(
                    artist,
                    track,
                    System.currentTimeMillis() / 1000
            ));
            return null;
        });
    }

    // 改进：使用 Callable<T> 以便能优雅地抛出异常
    private <T> void executeWithRetry(Callable<T> task) throws Exception {
        int maxRetries = 3;
        int retryDelay = 1000;

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                task.call();
                return;
            } catch (Exception e) {
                lastException = e;
                // 可以加入判断：如果是 401 Auth 错误，重试也没用，应该直接抛出

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep((long) retryDelay * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("操作被中断", ie);
                    }
                }
            }
        }
        throw lastException;
    }

    // 自定义异常类
    public static class AuthRequiredException extends Exception {
        public AuthRequiredException(String message) {
            super(message);
        }
    }
}
