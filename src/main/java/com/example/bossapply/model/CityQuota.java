package com.example.bossapply.model;

/**
 * 城市每日投递额度。
 */
public record CityQuota(String city, int priority, double ratio, int plannedCount) {
}
