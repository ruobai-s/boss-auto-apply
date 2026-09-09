package com.example.bossapply.dto;

import com.example.bossapply.model.CityPreference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 城市投递策略请求。
 */
public record DeliveryPolicyRequest(
        @Min(value = 1, message = "每日投递总量必须大于0")
        @Max(value = 150, message = "每日投递总量不能超过150")
        int dailyTotal,
        @NotNull(message = "城市配置不能为空")
        @Size(min = 1, max = 5, message = "优先城市数量必须在1到5之间")
        @Valid
        List<CityPreference> cities
) {
}
