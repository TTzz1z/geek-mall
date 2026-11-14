package com.geekmall.trade;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL migrations in isolated databases, including a V4-to-head upgrade. */
@Testcontainers
class AllServiceMigrationsIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("migration_fixture").withUsername("fixture").withPassword("fixture");

    private static final List<String> MODULES = List.of("geek-auth-system", "geek-merchant-product",
            "geek-inventory-fulfillment", "geek-search-recommend", "geek-trade", "geek-member",
            "geek-marketing", "geek-aftersale-service", "geek-service-center", "geek-settlement", "geek-ai");

    @Test
    void allServicesMigrateOnCleanMySql() throws Exception {
        for (int i = 0; i < MODULES.size(); i++) {
            String db = "clean_" + i;
            createDatabase(db);
            var flyway = flyway(db, location(MODULES.get(i)));
            assertThat(flyway.migrate().migrationsExecuted).as(MODULES.get(i)).isPositive();
            flyway.validate();
        }
    }

    @Test
    void tradeUpgradesFromV4ToHead() throws Exception {
        String root = System.getenv().getOrDefault("ACCEPTANCE_TEMP_DIR", System.getProperty("java.io.tmpdir"));
        Path staging = Files.createTempDirectory(Path.of(root), "trade-v4-");
        try {
            try (var files = Files.list(Path.of(location("geek-trade")))) {
                for (Path file : files.filter(p -> p.getFileName().toString().matches("V[1-4]__.*\\.sql")).toList()) {
                    Files.copy(file, staging.resolve(file.getFileName()));
                }
            }
            createDatabase("upgrade_trade");
            var old = flyway("upgrade_trade", staging.toString());
            assertThat(old.migrate().migrationsExecuted).isPositive();
            assertThat(old.info().current().getVersion().getVersion()).isEqualTo("4");
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl().replace("/migration_fixture", "/upgrade_trade"), "root", mysql.getPassword());
                 var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO trade_order(id,order_no,user_id,total_amount,payable_amount,status,checkout_token,paid_payment_id,inventory_status,created_at,updated_at) VALUES(901,'GM-V4',1,10,10,'PAID','V4',902,'RESERVED',NOW(),NOW())");
                statement.executeUpdate("INSERT INTO trade_refund(id,refund_no,payment_id,order_id,order_item_id,aftersale_id,quantity,amount,status,created_at,updated_at) VALUES(903,'RF-V4',902,901,904,'AS-V4',1,10,'PROCESSING',NOW(),NOW())");
            }
            var head = flyway("upgrade_trade", location("geek-trade"));
            assertThat(head.migrate().migrationsExecuted).isPositive();
            head.validate();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl().replace("/migration_fixture", "/upgrade_trade"), "root", mysql.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT task_type,business_no FROM trade_compensation_task WHERE business_no IN ('GM-V4','AS-V4')")) {
                var tasks = new java.util.HashSet<String>();
                while (rows.next()) tasks.add(rows.getString(1) + ":" + rows.getString(2));
                assertThat(tasks).contains("DEDUCT_STOCK:GM-V4", "CREATE_FULFILLMENT:GM-V4", "SETTLE_ASSETS:GM-V4", "REFUND:AS-V4");
            }
        } finally {
            try (var files = Files.list(staging)) { for (Path p : files.toList()) Files.delete(p); }
            Files.delete(staging);
        }
    }

    private static String location(String module) {
        return Path.of("..", module, "src", "main", "resources", "db", "migration").toAbsolutePath().normalize().toString();
    }

    private static void createDatabase(String database) throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static Flyway flyway(String database, String location) {
        String url = mysql.getJdbcUrl().replace("/migration_fixture", "/" + database);
        return Flyway.configure().locations("filesystem:" + location).dataSource(url, "root", mysql.getPassword()).load();
    }
}
