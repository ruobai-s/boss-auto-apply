package com.example.bossapply.service;

import com.example.bossapply.dto.DeliveryPolicyRequest;
import com.example.bossapply.model.CityPreference;
import com.example.bossapply.model.CityQuota;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 城市投递额度服务，负责校验比例并生成每日整数额度。
 */
@Service
public class CityQuotaService {

    /**
     * 校验城市配置并计算每日整数额度。
     */
    public List<CityQuota> calculate(DeliveryPolicyRequest request) {
        List<CityPreference> cities = request.cities().stream()
                .filter(CityPreference::enabled)
                .sorted(Comparator.comparingInt(CityPreference::priority))
                .toList();
        validate(cities);

        List<QuotaRemainder> remainders = new ArrayList<>();
        int allocated = 0;
        for (CityPreference city : cities) {
            double exact = request.dailyTotal() * city.ratio() / 100.0;
            int floor = (int) Math.floor(exact);
            allocated += floor;
            remainders.add(new QuotaRemainder(city, floor, exact - floor));
        }

        int remaining = request.dailyTotal() - allocated;
        remainders.sort(Comparator.comparingDouble(QuotaRemainder::remainder).reversed()
                .thenComparingInt(item -> item.city().priority()));
        for (int index = 0; index < remaining; index++) {
            QuotaRemainder item = remainders.get(index);
            remainders.set(index, item.withFloor(item.floor() + 1));
        }

        return remainders.stream()
                .sorted(Comparator.comparingInt(item -> item.city().priority()))
                .map(item -> new CityQuota(item.city().city(), item.city().priority(), item.city().ratio(), item.floor()))
                .toList();
    }

    private void validate(List<CityPreference> cities) {
        if (cities.isEmpty()) {
            throw new IllegalArgumentException("至少需要启用一个优先城市");
        }
        if (cities.size() > 5) {
            throw new IllegalArgumentException("最多只能配置五个优先城市");
        }
        Set<String> names = new HashSet<>();
        double ratioTotal = 0;
        for (CityPreference city : cities) {
            if (city.city() == null || city.city().isBlank()) {
                throw new IllegalArgumentException("城市名称不能为空");
            }
            if (!names.add(city.city().trim())) {
                throw new IllegalArgumentException("城市不能重复：" + city.city());
            }
            if (city.ratio() <= 0) {
                throw new IllegalArgumentException("城市比例必须大于0：" + city.city());
            }
            ratioTotal += city.ratio();
        }
        if (Math.abs(ratioTotal - 100.0) > 0.0001) {
            throw new IllegalArgumentException("启用城市比例合计必须等于100%");
        }
    }

    private record QuotaRemainder(CityPreference city, int floor, double remainder) {
        private QuotaRemainder withFloor(int newFloor) {
            return new QuotaRemainder(city, newFloor, remainder);
        }
    }
}
