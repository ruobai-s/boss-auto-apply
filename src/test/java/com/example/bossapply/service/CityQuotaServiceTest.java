package com.example.bossapply.service;

import com.example.bossapply.dto.DeliveryPolicyRequest;
import com.example.bossapply.model.CityPreference;
import com.example.bossapply.model.CityQuota;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityQuotaServiceTest {

    private final CityQuotaService service = new CityQuotaService();

    @Test
    void 应将每日额度精确分配到五个城市() {
        DeliveryPolicyRequest request = new DeliveryPolicyRequest(150, List.of(
                new CityPreference("北京", 1, 30, true, true),
                new CityPreference("上海", 2, 20, true, true),
                new CityPreference("深圳", 3, 20, true, true),
                new CityPreference("杭州", 4, 20, true, true),
                new CityPreference("成都", 5, 10, true, true)
        ));

        List<CityQuota> result = service.calculate(request);

        assertEquals(List.of(45, 30, 30, 30, 15), result.stream().map(CityQuota::plannedCount).toList());
        assertEquals(150, result.stream().mapToInt(CityQuota::plannedCount).sum());
    }

    @Test
    void 比例不为百分之百时拒绝保存() {
        DeliveryPolicyRequest request = new DeliveryPolicyRequest(150, List.of(
                new CityPreference("北京", 1, 50, true, true),
                new CityPreference("上海", 2, 40, true, true)
        ));

        assertThrows(IllegalArgumentException.class, () -> service.calculate(request));
    }
}
