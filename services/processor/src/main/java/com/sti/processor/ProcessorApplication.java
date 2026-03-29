package com.sti.processor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProcessorApplication {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AppConfig CONFIG = AppConfig.load();

    // Inicializa estruturas e agenda ciclos periodicos de ETL com shutdown seguro.
    public static void main(String[] args) throws Exception {
        initDb();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // Scheduler assíncrono evita loop infinito bloqueando a thread principal.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                processNewSales();
                refreshDataMart();
            } catch (Exception ex) {
                System.err.println("ETL cycle failed: " + ex.getMessage());
            }
        }, 0, CONFIG.processIntervalSeconds(), TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }));

        new CountDownLatch(1).await();
    }

    // Cria tabelas do warehouse/data mart quando ainda nao existem.
    private static void initDb() throws Exception {
        Files.createDirectories(CONFIG.warehouseDb().getParent());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + CONFIG.warehouseDb()); Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dim_date (" +
                    "date_key INTEGER PRIMARY KEY," +
                    "full_date TEXT UNIQUE," +
                    "year INTEGER," +
                    "month INTEGER," +
                    "day INTEGER)"
            );

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS fact_sales (" +
                    "sale_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "order_id TEXT UNIQUE," +
                    "created_at TEXT," +
                    "date_key INTEGER," +
                    "amount REAL," +
                    "payment_method TEXT," +
                    "status TEXT," +
                    "FOREIGN KEY (date_key) REFERENCES dim_date(date_key))"
            );

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dm_sales_performance (" +
                    "sale_date TEXT PRIMARY KEY," +
                    "total_sales REAL," +
                    "total_orders INTEGER," +
                    "avg_ticket REAL)"
            );
        }
    }

    // Processa somente novos eventos de vendas usando controle de offset em arquivo de estado.
    private static void processNewSales() throws Exception {
        if (!Files.exists(CONFIG.rawSalesLog())) {
            return;
        }

        // Usa offset para processar apenas novos eventos e evitar duplicacao.
        long offset = loadOffset();
        long fileSize = Files.size(CONFIG.rawSalesLog());
        if (offset > fileSize) {
            offset = 0;
        }

        try (RandomAccessFile raf = new RandomAccessFile(CONFIG.rawSalesLog().toFile(), "r");
             Connection conn = DriverManager.getConnection("jdbc:sqlite:" + CONFIG.warehouseDb())) {

            raf.seek(offset);

            String line;
            while ((line = raf.readLine()) != null) {
                // RandomAccessFile returns ISO-8859-1 bytes, so we re-decode as UTF-8.
                line = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).trim();
                if (line.isEmpty()) {
                    continue;
                }

                Map<String, Object> event = MAPPER.readValue(line, new TypeReference<>() {});
                ZonedDateTime createdAt = ZonedDateTime.parse(String.valueOf(event.get("created_at")));
                int dateKey = Integer.parseInt(createdAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
                String fullDate = createdAt.toLocalDate().toString();

                upsertDimDate(conn, dateKey, fullDate, createdAt.getYear(), createdAt.getMonthValue(), createdAt.getDayOfMonth());
                insertFactSale(conn, event, dateKey);
            }

            saveOffset(raf.getFilePointer());
        }
    }

    // Garante existencia da dimensao de data para relacionamento com fatos de venda.
    private static void upsertDimDate(Connection conn, int dateKey, String fullDate, int year, int month, int day) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT OR IGNORE INTO dim_date (date_key, full_date, year, month, day) VALUES (?, ?, ?, ?, ?)")) {
            stmt.setInt(1, dateKey);
            stmt.setString(2, fullDate);
            stmt.setInt(3, year);
            stmt.setInt(4, month);
            stmt.setInt(5, day);
            stmt.executeUpdate();
        }
    }

    // Insere fato de venda no warehouse evitando duplicidade por order_id.
    private static void insertFactSale(Connection conn, Map<String, Object> event, int dateKey) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT OR IGNORE INTO fact_sales (order_id, created_at, date_key, amount, payment_method, status) VALUES (?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, String.valueOf(event.get("order_id")));
            stmt.setString(2, String.valueOf(event.get("created_at")));
            stmt.setInt(3, dateKey);
            stmt.setDouble(4, toDouble(event.get("amount")));
            stmt.setString(5, String.valueOf(event.getOrDefault("payment_method", "unknown")));
            stmt.setString(6, String.valueOf(event.getOrDefault("status", "unknown")));
            stmt.executeUpdate();
        }
    }

    // Recalcula o data mart de performance a partir dos fatos consolidados.
    private static void refreshDataMart() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + CONFIG.warehouseDb());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM dm_sales_performance");
            // Recalculo total do data mart por simplicidade e legibilidade.
            stmt.execute(
                "INSERT INTO dm_sales_performance (sale_date, total_sales, total_orders, avg_ticket) " +
                    "SELECT d.full_date, ROUND(SUM(f.amount), 2), COUNT(*), ROUND(AVG(f.amount), 2) " +
                    "FROM fact_sales f JOIN dim_date d ON d.date_key = f.date_key GROUP BY d.full_date"
            );
        }
    }

    // Le o deslocamento atual do processador para retomar do ponto correto no arquivo raw.
    private static long loadOffset() throws Exception {
        if (!Files.exists(CONFIG.stateFile())) {
            return 0L;
        }
        String raw = Files.readString(CONFIG.stateFile(), StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(raw);
    }

    // Persiste o novo deslocamento apos processamento de eventos.
    private static void saveOffset(long offset) throws Exception {
        Files.createDirectories(CONFIG.stateFile().getParent());
        Files.writeString(CONFIG.stateFile(), String.valueOf(offset), StandardCharsets.UTF_8);
    }

    // Le variavel de ambiente com fallback padrao.
    private static String getEnv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // Converte valor generico para double com tolerancia a erro de formato.
    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private record AppConfig(Path rawSalesLog, Path stateFile, Path warehouseDb, int processIntervalSeconds) {
        // Carrega configuracoes do processador a partir de variaveis de ambiente.
        private static AppConfig load() {
            return new AppConfig(
                Paths.get(getEnv("RAW_SALES_LOG", "/data/raw/sales/events.jsonl")),
                Paths.get(getEnv("STATE_FILE", "/data/warehouse/processor.state")),
                Paths.get(getEnv("WAREHOUSE_DB", "/data/warehouse/warehouse.db")),
                Integer.parseInt(getEnv("PROCESS_INTERVAL_SECONDS", "10"))
            );
        }
    }
}
