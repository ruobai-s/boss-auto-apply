package com.example.bossapply.service;

import com.example.bossapply.dto.DeliveryPolicyRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.CityPreference;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 SQLite 投递策略存储服务。
 */
@Service
public class PolicyStoreService {

    private final SqliteDatabaseService databaseService;

    public PolicyStoreService(SqliteDatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /**
     * 从本地 SQLite 读取当前策略，没有数据时写入默认策略。
     */
    public synchronized DeliveryPolicyRequest get() {
        try (Connection connection = databaseService.open()) {
            int dailyTotal = readDailyTotal(connection);
            List<CityPreference> cities = readCities(connection);
            if (cities.isEmpty()) {
                DeliveryPolicyRequest defaults = defaultPolicy();
                saveInternal(connection, defaults);
                return defaults;
            }
            return new DeliveryPolicyRequest(dailyTotal, cities);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取本地投递策略失败", exception);
        }
    }

    /**
     * 将策略写入本地 SQLite。
     */
    public synchronized DeliveryPolicyRequest save(DeliveryPolicyRequest request) {
        try (Connection connection = databaseService.open()) {
            saveInternal(connection, request);
            return request;
        } catch (SQLException exception) {
            throw new IllegalStateException("保存本地投递策略失败", exception);
        }
    }

    private int readDailyTotal(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT setting_value FROM app_setting WHERE setting_key = 'daily_total'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Integer.parseInt(resultSet.getString(1)) : 150;
            }
        }
    }

    private List<CityPreference> readCities(Connection connection) throws SQLException {
        List<CityPreference> cities = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT city, priority, ratio, enabled, allow_quota_transfer FROM city_preference ORDER BY priority")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    cities.add(new CityPreference(
                            resultSet.getString("city"),
                            resultSet.getInt("priority"),
                            resultSet.getDouble("ratio"),
                            resultSet.getInt("enabled") == 1,
                            resultSet.getInt("allow_quota_transfer") == 1
                    ));
                }
            }
        }
        return cities;
    }

    private void saveInternal(Connection connection, DeliveryPolicyRequest request) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement setting = connection.prepareStatement(
                    "INSERT INTO app_setting(setting_key, setting_value) VALUES('daily_total', ?) "
                            + "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value")) {
                setting.setString(1, String.valueOf(request.dailyTotal()));
                setting.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM city_preference")) {
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO city_preference(city, priority, ratio, enabled, allow_quota_transfer) VALUES(?, ?, ?, ?, ?)")) {
                for (CityPreference city : request.cities()) {
                    insert.setString(1, city.city().trim());
                    insert.setInt(2, city.priority());
                    insert.setDouble(3, city.ratio());
                    insert.setInt(4, city.enabled() ? 1 : 0);
                    insert.setInt(5, city.allowQuotaTransfer() ? 1 : 0);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static DeliveryPolicyRequest defaultPolicy() {
        return new DeliveryPolicyRequest(150, List.of(
                new CityPreference("北京", 1, 30, true, true),
                new CityPreference("上海", 2, 20, true, true),
                new CityPreference("深圳", 3, 20, true, true),
                new CityPreference("杭州", 4, 20, true, true),
                new CityPreference("成都", 5, 10, true, true)
        ));
    }
}
