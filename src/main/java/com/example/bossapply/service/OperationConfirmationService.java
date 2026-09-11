package com.example.bossapply.service;

import com.example.bossapply.config.AppSecurityProperties;
import com.example.bossapply.model.ConfirmationTokenView;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成并消费仅在当前进程内有效的一次性人工确认令牌，防止敏感请求被重复提交。
 */
@Service
public class OperationConfirmationService {

    private static final String QUEUE_CONFIRM = "QUEUE_CONFIRM";

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, ConfirmationEntry> confirmations = new ConcurrentHashMap<>();
    private final int tokenTtlSeconds;

    public OperationConfirmationService(AppSecurityProperties securityProperties) {
        this.tokenTtlSeconds = securityProperties.getConfirmationTokenTtlSeconds();
    }

    public ConfirmationTokenView issueQueueConfirmation(List<Long> queueIds) {
        return issue(QUEUE_CONFIRM, canonicalQueueIds(queueIds));
    }


    public void consumeQueueConfirmation(String token, List<Long> queueIds) {
        consume(token, QUEUE_CONFIRM, canonicalQueueIds(queueIds));
    }


    private ConfirmationTokenView issue(String operation, String resource) {
        cleanupExpired();
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expiresAt = Instant.now().plusSeconds(tokenTtlSeconds);
        byte[] tokenHash = sha256(token);
        confirmations.put(HexFormat.of().formatHex(tokenHash),
                new ConfirmationEntry(tokenHash, operation, resource, expiresAt));
        return new ConfirmationTokenView(token, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC).toString());
    }

    private void consume(String token, String operation, String resource) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("缺少一次性人工确认令牌");
        }
        byte[] suppliedHash = sha256(token.trim());
        String key = HexFormat.of().formatHex(suppliedHash);
        ConfirmationEntry entry = confirmations.remove(key);
        if (entry == null
                || !MessageDigest.isEqual(suppliedHash, entry.tokenHash())
                || entry.expiresAt().isBefore(Instant.now())
                || !entry.operation().equals(operation)
                || !entry.resource().equals(resource)) {
            throw new IllegalArgumentException("人工确认令牌无效、已过期或已使用");
        }
    }

    private String canonicalQueueIds(List<Long> queueIds) {
        if (queueIds == null || queueIds.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个候选岗位");
        }
        return queueIds.stream()
                .sorted()
                .distinct()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        confirmations.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
        }
    }

    private record ConfirmationEntry(byte[] tokenHash, String operation, String resource, Instant expiresAt) {
    }
}
