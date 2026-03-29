package com.sti.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FinanceServiceApplication {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AppConfig CONFIG = AppConfig.load();

    // Inicializa o servidor HTTP e registra endpoints de saude e consulta de KPIs.
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(5002), 0);

        // Endpoint de saude para validar disponibilidade do servico.
        registerRoute(server, "/health", "GET", exchange -> {
            sendJson(exchange, 200, Map.of("status", "ok", "service", "finance-service"));
        });

        // Endpoint que expõe o Data Mart para o setor financeiro.
        registerRoute(server, "/kpis", "GET", exchange -> {
            sendJson(exchange, 200, collectKpis());
        });

        server.start();
    }

    // Consulta o data mart e monta o payload de resumo e serie diaria de metricas.
    private static Map<String, Object> collectKpis() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_sales", 0.0);
        summary.put("total_orders", 0);
        summary.put("avg_ticket", 0.0);

        List<Map<String, Object>> byDay = new ArrayList<>();

        if (!Files.exists(CONFIG.warehouseDb())) {
            return Map.of("summary", summary, "by_day", byDay);
        }

        String dbUrl = "jdbc:sqlite:" + CONFIG.warehouseDb();
        try (Connection conn = DriverManager.getConnection(dbUrl); Statement stmt = conn.createStatement()) {
            ResultSet totals = stmt.executeQuery(
                "SELECT COALESCE(SUM(total_sales), 0), COALESCE(SUM(total_orders), 0), COALESCE(AVG(avg_ticket), 0) " +
                    "FROM dm_sales_performance"
            );
            if (totals.next()) {
                summary.put("total_sales", round2(totals.getDouble(1)));
                summary.put("total_orders", totals.getInt(2));
                summary.put("avg_ticket", round2(totals.getDouble(3)));
            }

            ResultSet rows = stmt.executeQuery(
                "SELECT sale_date, total_sales, total_orders, avg_ticket " +
                    "FROM dm_sales_performance ORDER BY sale_date DESC LIMIT 7"
            );
            while (rows.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("sale_date", rows.getString(1));
                row.put("total_sales", round2(rows.getDouble(2)));
                row.put("total_orders", rows.getInt(3));
                row.put("avg_ticket", round2(rows.getDouble(4)));
                byDay.add(row);
            }
        } catch (Exception ignored) {
            return Map.of("summary", summary, "by_day", byDay);
        }

        return Map.of("summary", summary, "by_day", byDay);
    }

    // Registra rota com validacao de metodo HTTP e tratamento padrao para falhas inesperadas.
    private static void registerRoute(HttpServer server, String path, String method, ExchangeHandler handler) {
        server.createContext(path, exchange -> {
            if (!method.equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }

            try {
                handler.handle(exchange);
            } catch (Exception ex) {
                sendJson(exchange, 500, Map.of("error", "internal server error"));
            }
        });
    }

    // Serializa payload em JSON e envia resposta HTTP com codigo e cabecalho corretos.
    private static void sendJson(HttpExchange exchange, int statusCode, Map<String, Object> payload) throws IOException {
        byte[] response = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    // Le variavel de ambiente com valor padrao caso nao esteja definida.
    private static String getEnv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // Arredonda numero para duas casas decimais para exibicao de indicadores.
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        // Contrato do manipulador de requisicao para a rota.
        void handle(HttpExchange exchange) throws Exception;
    }

    private record AppConfig(Path warehouseDb) {
        // Carrega caminho do banco analitico a partir do ambiente.
        private static AppConfig load() {
            return new AppConfig(Paths.get(getEnv("WAREHOUSE_DB", "/data/warehouse/warehouse.db")));
        }
    }
}
