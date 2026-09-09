package com.example.bossapply.model;

/**
 * 优先城市配置。
 */
public record CityPreference(
        String city,
        int priority,
        double ratio,
        boolean enabled,
        boolean allowQuotaTransfer
) {
}
