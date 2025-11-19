import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LastFmService {

    private final LastFmClient client;
    private final SessionStore sessionStore;

    private static final String API_KEY = "a8ea2c2bdcd7ab1df30be1d11ebe50dd";
    private static final String API_SECRET = "b45ce43573b2c96229798faa75836b02";
    private static final String DEFAULT_SESSION_PATH =
            System.getenv("APPDATA") +
                    "\\Salt Player for Windows\\workshop\\data\\Last_Fm_Session.json";

    /** 默认使用自动配置 */
    public static LastFmService defaultInstance() {
        return new LastFmService(API_KEY, API_SECRET, DEFAULT_SESSION_PATH);
    }

    /** 真正的构造方法，如果你未来需要自定义也行 */
    public LastFmService(String apiKey, String apiSecret, String sessionFilePath) {
        createDefaultSessionFileIfNeeded(sessionFilePath);
        this.sessionStore = new FileSessionStore(sessionFilePath);
        this.client = new LastFmClient(apiKey, apiSecret, sessionStore);
        initSession();
    }
    /** 检查并创建默认的session文件 */
    private void createDefaultSessionFileIfNeeded(String sessionFilePath) {
        try {
            Path path = Paths.get(sessionFilePath);
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                JsonObject defaultObj = new JsonObject();
                defaultObj.addProperty("sessionKey", (String) null);
                defaultObj.addProperty("token", (String) null);
                Files.writeString(path, defaultObj.toString());
            }
        } catch (Exception e) {
            System.err.println("创建默认session文件失败: " + e.getMessage());
        }
    }

    /** 初始化 SessionKey，如果文件存在就直接加载，不存在就引导授权一次 */
    private void initSession() {
        try {

            String session = sessionStore.loadSessionKey();
            String token = sessionStore.loadToken();
            if (session == null) {
                System.out.println("没有 SessionKey，需要授权一次");
                if (token == null) {
                    token = client.getToken();
                    client.openAuthPage(token);
                    System.out.println("授权后请重新启动...");
                    sessionStore.saveToken(token);
                    System.exit(0);
                }
                session = client.fetchSessionKey(token);
                System.out.println("SessionKey 已保存：" + session);
            } else {
                System.out.println("SessionKey 已加载：" + session);
            }
        } catch (Exception e) {
            throw new RuntimeException("初始化 LastFM Session 失败", e);
        }
    }

    /** 更新 Now Playing */
    public void nowPlaying(String artist, String track) throws Exception {
        client.updateNowPlaying(artist, track);
    }

    /** 自动时间戳的 scrobble */
    public void scrobble(String artist, String track) throws Exception {
        client.scrobble(new ScrobbleTrack(
                artist,
                track,
                System.currentTimeMillis() / 1000
        ));
    }
}
