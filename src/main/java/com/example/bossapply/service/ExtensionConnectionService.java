package com.example.bossapply.service;

import com.example.bossapply.config.ExtensionConnectionProperties;
import com.example.bossapply.dto.ExtensionHeartbeatRequest;
import com.example.bossapply.dto.ExtensionJobCollectRequest;
import com.example.bossapply.dto.ExtensionPairRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.ExtensionConnectionStatus;
import com.example.bossapply.model.ExtensionHeartbeatResult;
import com.example.bossapply.model.ExtensionPairResult;
import com.example.bossapply.model.ExtensionPairingView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 管理 Chrome 扩展的一次性配对、身份校验和结构化连接心跳。
 */
@Service
public class ExtensionConnectionService {

    private static final String PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern EXTENSION_ID_PATTERN = Pattern.compile("[a-p]{32}");
    private static final Set<String> PAGE_TYPES = Set.of(
            "NONE", "UNKNOWN", "HOME", "LOGIN", "JOB_LIST", "JOB_DETAIL", "CHAT", "SECURITY");
    private static final Set<String> LOGIN_STATES = Set.of("UNKNOWN", "LOGGED_IN", "NOT_LOGGED_IN");
    private static final Set<String> SECURITY_STATES = Set.of(
            "UNKNOWN", "NORMAL", "SECURITY_CHECK_REQUIRED", "ACCESS_RESTRICTED");

    private final SqliteDatabaseService databaseService;
    private final ExtensionConnectionProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicReference<PairingSession> pairingSession = new AtomicReference<>();

    @Autowired
    public ExtensionConnectionService(SqliteDatabaseService databaseService,
                                      ExtensionConnectionProperties properties) {
        this(databaseService, properties, Clock.systemDefaultZone());
    }

    ExtensionConnectionService(SqliteDatabaseService databaseService,
                               ExtensionConnectionProperties properties,
                               Clock clock) {
        this.databaseService = databaseService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 创建仅在内存中保存摘要的一次性配对码。
     */
    public synchronized ExtensionPairingView startPairing() {
        String code = randomPairingCode();
        Instant expiresAt = clock.instant().plusSeconds(properties.getPairingTtlSeconds());
        pairingSession.set(new PairingSession(hash(normalizePairingCode(code)), expiresAt));
        return new ExtensionPairingView(code, format(expiresAt));
    }

    /**
     * 完成扩展配对。扩展令牌只返回一次，数据库仅保存摘要。
     */
    public synchronized ExtensionPairResult pair(ExtensionPairRequest request, String origin) {
        String extensionId = requireExtensionId(origin);
        PairingSession current = pairingSession.get();
        if (current == null || !clock.instant().isBefore(current.expiresAt())) {
            pairingSession.set(null);
            throw new IllegalArgumentException("配对码不存在或已过期，请在管理端重新生成");
        }
        String normalizedCode = normalizePairingCode(request.pairingCode());
        if (!constantTimeEquals(current.codeHash(), hash(normalizedCode))) {
            throw new IllegalArgumentException("配对码不正确");
        }

        String instanceId = requireText(request.instanceId(), "扩展实例标识", 80);
        String extensionVersion = requireText(request.extensionVersion(), "扩展版本", 30);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        Instant pairedAt = clock.instant();
        savePairedClient(extensionId, instanceId, extensionVersion, hash(token), pairedAt);
        pairingSession.set(null);
        return new ExtensionPairResult(token, format(pairedAt), properties.getHeartbeatIntervalSeconds());
    }

    /**
     * 接收扩展连接心跳，并更新页面、登录和安全验证状态。
     */
    public ExtensionHeartbeatResult heartbeat(ExtensionHeartbeatRequest request,
                                              String origin,
                                              String token) {
        String extensionId = requireExtensionId(origin);
        ExtensionClient client = loadClient();
        if (!authenticated(client, extensionId, token)) {
            throw new SecurityException("扩展身份校验失败，请重新配对");
        }
        String instanceId = requireText(request.instanceId(), "扩展实例标识", 80);
        if (!constantTimeEquals(client.instanceId(), instanceId)) {
            throw new SecurityException("扩展实例与已配对实例不一致");
        }

        String extensionVersion = requireText(request.extensionVersion(), "扩展版本", 30);
        String pageType = normalizeEnum(request.pageType(), PAGE_TYPES, "UNKNOWN");
        String loginState = normalizeEnum(request.loginState(), LOGIN_STATES, "UNKNOWN");
        String securityState = normalizeEnum(request.securityState(), SECURITY_STATES, "UNKNOWN");
        String message = normalizeOptional(request.message(), 160);
        Instant now = clock.instant();
        updateHeartbeat(extensionVersion, request.bossTabFound(), request.contentScriptReady(), pageType,
                loginState, securityState, message, now);
        return new ExtensionHeartbeatResult(true, status().state(), format(now));
    }

    /**
     * 校验职位采集请求只能来自当前已配对、在线且处于安全职位列表页或详情页的扩展实例。
     */
    /** 校验投递任务请求来自当前已配对扩展实例。 */
    public void validateExtensionInstance(String instanceId, String origin, String token) {
        String extensionId = requireExtensionId(origin);
        ExtensionClient client = loadClient();
        if (!authenticated(client, extensionId, token)) {
            throw new SecurityException("扩展身份校验失败，请重新配对");
        }
        if (!constantTimeEquals(client.instanceId(), requireText(instanceId, "扩展实例标识", 80))) {
            throw new SecurityException("扩展实例与已配对实例不一致");
        }
    }
    public void validateJobCollection(ExtensionJobCollectRequest request,
                                      String origin,
                                      String token) {
        String extensionId = requireExtensionId(origin);
        ExtensionClient client = loadClient();
        if (!authenticated(client, extensionId, token)) {
            throw new SecurityException("扩展身份校验失败，请重新配对");
        }

        String instanceId = requireText(request.instanceId(), "扩展实例标识", 80);
        if (!constantTimeEquals(client.instanceId(), instanceId)) {
            throw new SecurityException("扩展实例与已配对实例不一致");
        }
        String extensionVersion = requireText(request.extensionVersion(), "扩展版本", 30);
        if (!constantTimeEquals(client.extensionVersion(), extensionVersion)) {
            throw new IllegalArgumentException("扩展版本状态尚未同步，请刷新扩展状态后重试");
        }
        if (client.lastHeartbeatAt() == null
                || !client.lastHeartbeatAt().plusSeconds(properties.getHeartbeatTimeoutSeconds()).isAfter(clock.instant())) {
            throw new IllegalArgumentException("扩展心跳已超时，请刷新扩展状态后重试");
        }
        if (!client.bossTabFound() || !client.contentScriptReady()) {
            throw new IllegalArgumentException("BOSS 页面或页面连接脚本尚未就绪");
        }

        String pageType = normalizeEnum(request.pageType(), PAGE_TYPES, "UNKNOWN");
        String loginState = normalizeEnum(request.loginState(), LOGIN_STATES, "UNKNOWN");
        String securityState = normalizeEnum(request.securityState(), SECURITY_STATES, "UNKNOWN");
        boolean supportedPage = "JOB_LIST".equals(pageType) || "JOB_DETAIL".equals(pageType);
        if (!supportedPage || !pageType.equals(client.pageType())) {
            throw new IllegalArgumentException("只能采集当前 BOSS 职位列表页或职位详情页");
        }
        if (!"LOGGED_IN".equals(loginState) || !"LOGGED_IN".equals(client.loginState())) {
            throw new IllegalArgumentException("尚未确认 BOSS 登录状态，不能采集职位");
        }
        if (!"NORMAL".equals(securityState) || !"NORMAL".equals(client.securityState())) {
            throw new IllegalArgumentException("检测到访问限制或安全验证，已停止职位采集");
        }
    }
    /**
     * 供扩展安全过滤器校验来源扩展和令牌。
     */
    public boolean authenticate(String origin, String token) {
        String extensionId = extensionIdFromOrigin(origin);
        if (extensionId == null || token == null || token.isBlank()) {
            return false;
        }
        return authenticated(loadClient(), extensionId, token);
    }

    /**
     * 返回连接中心所需的脱敏状态。
     */
    public ExtensionConnectionStatus status() {
        ExtensionClient client = loadClient();
        if (client == null) {
            return disconnected("EXTENSION_UNPAIRED", "Chrome 扩展尚未配对");
        }

        boolean online = client.lastHeartbeatAt() != null
                && client.lastHeartbeatAt().plusSeconds(properties.getHeartbeatTimeoutSeconds()).isAfter(clock.instant());
        if (!online) {
            return view(client, "EXTENSION_OFFLINE", false, false,
                    "扩展已配对，但心跳已超时，请检查扩展是否启用");
        }
        if (!client.bossTabFound()) {
            return view(client, "BOSS_TAB_NOT_FOUND", true, false,
                    "扩展在线，但没有发现已打开的 BOSS 页面");
        }
        if (!client.contentScriptReady()) {
            return view(client, "CONTENT_SCRIPT_UNAVAILABLE", true, false,
                    "已发现 BOSS 页面，但页面连接脚本尚未生效，请刷新该页面");
        }
        if (isSecurityBlocked(client.securityState())) {
            return view(client, "SECURITY_CHECK_REQUIRED", true, false,
                    "检测到访问限制或安全验证，所有后续自动操作必须停止并由用户处理");
        }
        if (!"LOGGED_IN".equals(client.loginState())) {
            return view(client, "NOT_LOGGED_IN", true, false,
                    "扩展连接正常，但尚未确认 BOSS 登录状态");
        }
        return view(client, "READY", true, true,
                "Chrome 扩展连接、BOSS 页面和登录状态均已就绪");
    }

    /**
     * 解除当前扩展配对并使旧令牌立即失效。
     */
    public synchronized ExtensionConnectionStatus unpair() {
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM extension_client WHERE id = 1")) {
            statement.executeUpdate();
            pairingSession.set(null);
            return disconnected("EXTENSION_UNPAIRED", "已解除 Chrome 扩展配对");
        } catch (SQLException exception) {
            throw new IllegalStateException("解除扩展配对失败", exception);
        }
    }

    /**
     * 从扩展来源地址中提取并校验 Chrome 扩展 ID。
     */
    public String requireExtensionId(String origin) {
        String extensionId = extensionIdFromOrigin(origin);
        if (extensionId == null) {
            throw new SecurityException("请求不是来自受支持的 Chrome 扩展");
        }
        return extensionId;
    }

    private String extensionIdFromOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (!"chrome-extension".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !EXTENSION_ID_PATTERN.matcher(host.toLowerCase(Locale.ROOT)).matches()) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean authenticated(ExtensionClient client, String extensionId, String token) {
        return client != null
                && constantTimeEquals(client.extensionId(), extensionId)
                && constantTimeEquals(client.tokenHash(), hash(token));
    }

    private void savePairedClient(String extensionId,
                                  String instanceId,
                                  String extensionVersion,
                                  String tokenHash,
                                  Instant pairedAt) {
        String sql = """
                INSERT INTO extension_client(
                    id, extension_id, instance_id, token_hash, extension_version,
                    boss_tab_found, content_script_ready, page_type, login_state, security_state,
                    status_message, paired_at, last_heartbeat_at
                ) VALUES(1, ?, ?, ?, ?, 0, 0, 'NONE', 'UNKNOWN', 'UNKNOWN', '', ?, NULL)
                ON CONFLICT(id) DO UPDATE SET
                    extension_id = excluded.extension_id,
                    instance_id = excluded.instance_id,
                    token_hash = excluded.token_hash,
                    extension_version = excluded.extension_version,
                    boss_tab_found = 0,
                    content_script_ready = 0,
                    page_type = 'NONE',
                    login_state = 'UNKNOWN',
                    security_state = 'UNKNOWN',
                    status_message = '',
                    paired_at = excluded.paired_at,
                    last_heartbeat_at = NULL
                """;
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, extensionId);
            statement.setString(2, instanceId);
            statement.setString(3, tokenHash);
            statement.setString(4, extensionVersion);
            statement.setString(5, format(pairedAt));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存扩展配对信息失败", exception);
        }
    }

    private void updateHeartbeat(String extensionVersion,
                                 boolean bossTabFound,
                                 boolean contentScriptReady,
                                 String pageType,
                                 String loginState,
                                 String securityState,
                                 String message,
                                 Instant now) {
        String sql = """
                UPDATE extension_client SET
                    extension_version = ?, boss_tab_found = ?, content_script_ready = ?,
                    page_type = ?, login_state = ?, security_state = ?, status_message = ?,
                    last_heartbeat_at = ?
                WHERE id = 1
                """;
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, extensionVersion);
            statement.setInt(2, bossTabFound ? 1 : 0);
            statement.setInt(3, contentScriptReady ? 1 : 0);
            statement.setString(4, pageType);
            statement.setString(5, loginState);
            statement.setString(6, securityState);
            statement.setString(7, message);
            statement.setString(8, format(now));
            if (statement.executeUpdate() != 1) {
                throw new SecurityException("扩展配对信息已失效，请重新配对");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("更新扩展连接心跳失败", exception);
        }
    }

    private ExtensionClient loadClient() {
        String sql = """
                SELECT extension_id, instance_id, token_hash, extension_version,
                       boss_tab_found, content_script_ready, page_type, login_state,
                       security_state, status_message, paired_at, last_heartbeat_at
                FROM extension_client WHERE id = 1
                """;
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            return new ExtensionClient(
                    resultSet.getString("extension_id"),
                    resultSet.getString("instance_id"),
                    resultSet.getString("token_hash"),
                    resultSet.getString("extension_version"),
                    resultSet.getInt("boss_tab_found") == 1,
                    resultSet.getInt("content_script_ready") == 1,
                    resultSet.getString("page_type"),
                    resultSet.getString("login_state"),
                    resultSet.getString("security_state"),
                    resultSet.getString("status_message"),
                    parse(resultSet.getString("paired_at")),
                    parse(resultSet.getString("last_heartbeat_at"))
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("读取扩展连接状态失败", exception);
        }
    }

    private ExtensionConnectionStatus disconnected(String state, String message) {
        return new ExtensionConnectionStatus(state, false, false, false,
                "", "", "", false, false, false, false,
                "NONE", "UNKNOWN", "UNKNOWN", message, "", "");
    }

    private ExtensionConnectionStatus view(ExtensionClient client,
                                           String state,
                                           boolean online,
                                           boolean ready,
                                           String message) {
        return new ExtensionConnectionStatus(
                state,
                true,
                online,
                ready,
                client.extensionId(),
                client.instanceId(),
                client.extensionVersion(),
                client.bossTabFound(),
                client.contentScriptReady(),
                "LOGGED_IN".equals(client.loginState()),
                isSecurityBlocked(client.securityState()),
                client.pageType(),
                client.loginState(),
                client.securityState(),
                client.statusMessage() == null || client.statusMessage().isBlank()
                        ? message : message + "；" + client.statusMessage(),
                format(client.pairedAt()),
                format(client.lastHeartbeatAt())
        );
    }

    private boolean isSecurityBlocked(String state) {
        return "SECURITY_CHECK_REQUIRED".equals(state) || "ACCESS_RESTRICTED".equals(state);
    }

    private String normalizeEnum(String value, Set<String> supported, String defaultValue) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return supported.contains(normalized) ? normalized : defaultValue;
    }

    private String normalizeOptional(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            return normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private String requireText(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private String randomPairingCode() {
        StringBuilder builder = new StringBuilder(9);
        for (int index = 0; index < 8; index++) {
            if (index == 4) {
                builder.append('-');
            }
            builder.append(PAIRING_ALPHABET.charAt(secureRandom.nextInt(PAIRING_ALPHABET.length())));
        }
        return builder.toString();
    }

    private String normalizePairingCode(String code) {
        return code == null ? "" : code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String format(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                instant.atZone(ZoneId.systemDefault()).withNano(0));
    }

    private Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return java.time.OffsetDateTime.parse(value).toInstant();
    }

    private record PairingSession(String codeHash, Instant expiresAt) {
    }

    private record ExtensionClient(
            String extensionId,
            String instanceId,
            String tokenHash,
            String extensionVersion,
            boolean bossTabFound,
            boolean contentScriptReady,
            String pageType,
            String loginState,
            String securityState,
            String statusMessage,
            Instant pairedAt,
            Instant lastHeartbeatAt
    ) {
    }
}


