package plutonium;

public class Account {

    public enum Type {
        OFFLINE, MICROSOFT
    }

    private final Type type;
    private final String username;
    private final String email;
    private String accessToken;
    private String uuid;
    private String refreshToken;
    private String status;

    public static Account offline(String username) {
        return new Account(Type.OFFLINE, username, null, null, null, null);
    }

    public static Account microsoft(String email, String username, String accessToken, String uuid,
            String refreshToken) {
        return new Account(Type.MICROSOFT, username, email, accessToken, uuid, refreshToken);
    }

    private Account(Type type, String username, String email, String accessToken, String uuid, String refreshToken) {
        this.type = type;
        this.username = username;
        this.email = email;
        this.accessToken = accessToken;
        this.uuid = uuid;
        this.refreshToken = refreshToken;
        this.status = type == Type.MICROSOFT ? "Valid" : null;
    }

    public Type getType() {
        return type;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getUuid() {
        return uuid;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getStatus() {
        return status;
    }

    public void setAccessToken(String token) {
        this.accessToken = token;
    }

    public void setRefreshToken(String token) {
        this.refreshToken = token;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDisplayName() {
        return username;
    }

    public String getTypeTag() {
        return type == Type.MICROSOFT ? "Microsoft" : "Offline";
    }
}
