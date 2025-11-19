import java.nio.file.*;
import com.google.gson.*;

public class FileSessionStore implements SessionStore {

    private final Path path;

    public FileSessionStore(String filePath) {
        this.path = Paths.get(filePath);
    }

    @Override
    public void saveSessionKey(String sessionKey) throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("sessionKey", sessionKey);

        Files.createDirectories(path.getParent());
        Files.writeString(path, obj.toString());
    }

    @Override
    public String loadSessionKey() throws Exception {
        if (!Files.exists(path)) return null;

        String json = Files.readString(path);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonElement element = obj.get("sessionKey");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    @Override
    public void saveToken(String token) throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("token", token);

        Files.createDirectories(path.getParent());
        Files.writeString(path, obj.toString());
    }

    @Override
    public String loadToken() throws Exception {
        if (!Files.exists(path)) return null;

        String json = Files.readString(path);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonElement element = obj.get("token");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }
}
