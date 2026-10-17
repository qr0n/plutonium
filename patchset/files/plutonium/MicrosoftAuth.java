package plutonium;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.*;

public class MicrosoftAuth {

    private static String clientId = null;

    public static void init(File gameDir) {
        File configFile = new File(gameDir, "plutonium_azure.txt");
        System.out.println("[MicrosoftAuth] Looking for " + configFile.getAbsolutePath() + " exists=" + configFile.exists());
        if (configFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(configFile))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    clientId = line.trim();
                    System.out.println("[MicrosoftAuth] Loaded client ID: " + clientId);
                }
            } catch (IOException e) {
                System.err.println("[MicrosoftAuth] Failed to read config: " + e.getMessage());
            }
        } else {
            System.out.println("[MicrosoftAuth] No azure config file found");
        }
    }

    public static boolean isConfigured() {
        return clientId != null;
    }

    public static String getClientId() {
        return clientId;
    }

    private static final String DEVICE_CODE_URL  = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL         = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL      = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL     = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL      = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL    = "https://api.minecraftservices.com/minecraft/profile";

    public static class DeviceCode {
        public final String userCode;
        public final String verificationUri;
        public final String deviceCode;
        public final int interval;
        public final int expiresIn;

        DeviceCode(String userCode, String verificationUri, String deviceCode, int interval, int expiresIn) {
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.deviceCode = deviceCode;
            this.interval = interval;
            this.expiresIn = expiresIn;
        }
    }

    public static DeviceCode requestDeviceCode() throws AuthException {
        System.out.println("[MicrosoftAuth] Requesting device code with client_id=" + clientId);
        String body = "client_id=" + clientId + "&scope=XboxLive.signin%20offline_access";
        JSONObject resp = postForm(DEVICE_CODE_URL, body);
        System.out.println("[MicrosoftAuth] Got device code: " + resp.optString("user_code"));
        return new DeviceCode(
                resp.getString("user_code"),
                resp.getString("verification_uri"),
                resp.getString("device_code"),
                resp.optInt("interval", 5),
                resp.optInt("expires_in", 900)
        );
    }

    public static String[] pollForToken(DeviceCode code) throws AuthException {
        String body = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                + "&client_id=" + clientId
                + "&device_code=" + code.deviceCode;

        long deadline = System.currentTimeMillis() + code.expiresIn * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(code.interval * 1000L);
            } catch (InterruptedException e) {
                throw new AuthException("Cancelled");
            }

            try {
                JSONObject resp = postForm(TOKEN_URL, body);
                return new String[] {
                        resp.getString("access_token"),
                        resp.optString("refresh_token", null)
                };
            } catch (AuthException e) {
                String msg = e.getMessage();
                if (msg.contains("authorization_pending")) continue;
                if (msg.contains("slow_down")) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    continue;
                }
                throw e;
            }
        }
        throw new AuthException("Login timed out");
    }

    public static String[] refreshMsToken(String refreshToken) throws AuthException {
        String body = "grant_type=refresh_token"
                + "&client_id=" + clientId
                + "&refresh_token=" + refreshToken;
        JSONObject resp = postForm(TOKEN_URL, body);
        return new String[] {
                resp.getString("access_token"),
                resp.optString("refresh_token", refreshToken)
        };
    }

    public static Account fullLoginFromMsToken(String msAccessToken, String refreshToken) throws AuthException {
        System.out.println("[MicrosoftAuth] Got MS token, exchanging for Xbox Live...");
        // MS → Xbox Live
        JSONObject xblPayload = new JSONObject();
        JSONObject xblProps = new JSONObject();
        xblProps.put("AuthMethod", "RPS");
        xblProps.put("SiteName", "user.auth.xboxlive.com");
        xblProps.put("RpsTicket", "d=" + msAccessToken);
        xblPayload.put("Properties", xblProps);
        xblPayload.put("RelyingParty", "http://auth.xboxlive.com");
        xblPayload.put("TokenType", "JWT");
        JSONObject xblResp = postJson(XBL_AUTH_URL, xblPayload);
        String xblToken = xblResp.getString("Token");
        String userHash = xblResp.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
        System.out.println("[MicrosoftAuth] XBL OK, getting XSTS...");

        // Xbox Live → XSTS
        JSONObject xstsPayload = new JSONObject();
        JSONObject xstsProps = new JSONObject();
        xstsProps.put("SandboxId", "RETAIL");
        xstsProps.put("UserTokens", new org.json.JSONArray().put(xblToken));
        xstsPayload.put("Properties", xstsProps);
        xstsPayload.put("RelyingParty", "rp://api.minecraftservices.com/");
        xstsPayload.put("TokenType", "JWT");
        JSONObject xstsResp = postJson(XSTS_AUTH_URL, xstsPayload);
        String xstsToken = xstsResp.getString("Token");
        System.out.println("[MicrosoftAuth] XSTS OK, getting MC token...");

        // XSTS → Minecraft
        JSONObject mcPayload = new JSONObject();
        mcPayload.put("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JSONObject mcResp = postJson(MC_LOGIN_URL, mcPayload);
        String mcAccessToken = mcResp.getString("access_token");

        // Get profile
        JSONObject profile = getJson(MC_PROFILE_URL, mcAccessToken);
        if (profile.has("error")) {
            throw new AuthException("This Microsoft account doesn't own Minecraft");
        }
        String username = profile.getString("name");
        String uuid = profile.getString("id");

        System.out.println("[MicrosoftAuth] Success! Logged in as " + username + " (" + uuid + ")");
        Account account = Account.microsoft(null, username, mcAccessToken, uuid, refreshToken);
        return account;
    }

    public static Account loginWithDeviceCode(DeviceCode code) throws AuthException {
        String[] tokens = pollForToken(code);
        return fullLoginFromMsToken(tokens[0], tokens[1]);
    }

    public static boolean refreshAccount(Account account) {
        if (account.getRefreshToken() == null) return false;
        try {
            String[] tokens = refreshMsToken(account.getRefreshToken());
            Account refreshed = fullLoginFromMsToken(tokens[0], tokens[1]);
            account.setAccessToken(refreshed.getAccessToken());
            account.setRefreshToken(tokens[1]);
            account.setStatus("Valid");
            return true;
        } catch (AuthException e) {
            account.setStatus("Expired");
            return false;
        }
    }

    // --- HTTP helpers ---

    private static JSONObject postForm(String url, String formBody) throws AuthException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost req = new HttpPost(url);
            req.setHeader("Content-Type", "application/x-www-form-urlencoded");
            req.setEntity(new StringEntity(formBody, "UTF-8"));
            try (CloseableHttpResponse resp = client.execute(req)) {
                String body = EntityUtils.toString(resp.getEntity(), "UTF-8");
                JSONObject json = new JSONObject(body);
                if (json.has("error")) {
                    throw new AuthException(json.optString("error") + ": " + json.optString("error_description", ""));
                }
                return json;
            }
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("Connection failed: " + e.getMessage());
        }
    }

    private static JSONObject postJson(String url, JSONObject payload) throws AuthException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost req = new HttpPost(url);
            req.setHeader("Content-Type", "application/json");
            req.setHeader("Accept", "application/json");
            req.setEntity(new StringEntity(payload.toString(), "UTF-8"));
            try (CloseableHttpResponse resp = client.execute(req)) {
                int status = resp.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(resp.getEntity(), "UTF-8");
                if (status != 200) {
                    System.err.println("[MicrosoftAuth] HTTP " + status + " from " + url + ": " + body);
                    String msg = "HTTP " + status;
                    try {
                        JSONObject err = new JSONObject(body);
                        msg = err.optString("Message", err.optString("error", err.optString("errorMessage", msg)));
                    } catch (Exception ignored) {}
                    throw new AuthException(msg);
                }
                return new JSONObject(body);
            }
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("Connection failed: " + e.getMessage());
        }
    }

    private static JSONObject getJson(String url, String bearerToken) throws AuthException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet req = new HttpGet(url);
            req.setHeader("Authorization", "Bearer " + bearerToken);
            try (CloseableHttpResponse resp = client.execute(req)) {
                String body = EntityUtils.toString(resp.getEntity(), "UTF-8");
                return new JSONObject(body);
            }
        } catch (Exception e) {
            throw new AuthException("Connection failed: " + e.getMessage());
        }
    }

    public static class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }
    }
}
