package com.example.bossapply.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 数据库基础服务，统一管理本地数据库连接和表结构初始化。
 */
@Service
public class SqliteDatabaseService {

    @Value("${app.database.path:runtime/boss-auto-apply.db}")
    private String databasePath;

    /**
     * 应用启动时创建数据库目录、业务表并执行兼容性字段升级。
     */
    @PostConstruct
    public void initialize() {
        try {
            Path path = Path.of(databasePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("PRAGMA busy_timeout = 5000");
                statement.executeUpdate("PRAGMA journal_mode = WAL");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS app_setting (
                            setting_key TEXT PRIMARY KEY,
                            setting_value TEXT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS city_preference (
                            city TEXT PRIMARY KEY,
                            priority INTEGER NOT NULL,
                            ratio REAL NOT NULL,
                            enabled INTEGER NOT NULL,
                            allow_quota_transfer INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS outsourcing_company_exclusion (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            company_name TEXT NOT NULL,
                            sort_order INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS outsourcing_condition_rule (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            field_name TEXT NOT NULL,
                            match_type TEXT NOT NULL,
                            keyword TEXT NOT NULL,
                            action TEXT NOT NULL,
                            enabled INTEGER NOT NULL,
                            sort_order INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS job_record (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            source TEXT NOT NULL,
                            source_job_id TEXT NOT NULL,
                            company_name TEXT,
                            company_introduction TEXT,
                            job_name TEXT,
                            job_description TEXT,
                            city TEXT,
                            salary TEXT,
                            job_url TEXT,
                            published_at TEXT,
                            apply_status TEXT NOT NULL DEFAULT 'DISCOVERED',
                            outsourcing_excluded INTEGER NOT NULL DEFAULT 0,
                            outsourcing_manual_review INTEGER NOT NULL DEFAULT 0,
                            outsourcing_level TEXT,
                            outsourcing_reason TEXT,
                            outsourcing_keyword TEXT,
                            first_seen_at TEXT NOT NULL,
                            last_seen_at TEXT NOT NULL,
                            UNIQUE(source, source_job_id)
                        )
                        """);
                ensureColumn(connection, "company_introduction", "TEXT");
                ensureColumn(connection, "job_description", "TEXT");
                ensureColumn(connection, "published_at", "TEXT");
                ensureColumn(connection, "outsourcing_excluded", "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(connection, "outsourcing_manual_review", "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(connection, "outsourcing_level", "TEXT");
                ensureColumn(connection, "outsourcing_reason", "TEXT");
                ensureColumn(connection, "outsourcing_keyword", "TEXT");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_job_record_status ON job_record(apply_status)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_job_record_outsourcing ON job_record(outsourcing_excluded, outsourcing_manual_review)");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS delivery_queue (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            source TEXT NOT NULL,
                            source_job_id TEXT NOT NULL,
                            planned_date TEXT NOT NULL,
                            quota_city TEXT NOT NULL,
                            city_priority INTEGER NOT NULL,
                            allocation_type TEXT NOT NULL,
                            queue_status TEXT NOT NULL DEFAULT 'QUEUED',
                            queue_rank INTEGER NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            UNIQUE(planned_date, source, source_job_id),
                            FOREIGN KEY(source, source_job_id) REFERENCES job_record(source, source_job_id)
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_delivery_queue_date_status ON delivery_queue(planned_date, queue_status)");
            }
        } catch (IOException | ClassNotFoundException | SQLException exception) {
            throw new IllegalStateException("SQLite 数据库初始化失败：" + databasePath, exception);
        }
    }

    /**
     * 打开一个 SQLite 连接，调用方负责关闭连接。
     */
    public Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    /**
     * 为已有数据库补充新字段，避免升级时删除本地历史职位。
     */
    private void ensureColumn(Connection connection, String columnName, String definition) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(job_record)")) {
            while (resultSet.next()) {
                if (columnName.equals(resultSet.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE job_record ADD COLUMN " + columnName + " " + definition);
        }
    }
}


