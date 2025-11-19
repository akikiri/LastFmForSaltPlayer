public interface SessionStore {
    void saveSessionKey(String sessionKey) throws Exception;
    String loadSessionKey() throws Exception;
    void saveToken(String token) throws Exception;
    String loadToken() throws Exception;
}
