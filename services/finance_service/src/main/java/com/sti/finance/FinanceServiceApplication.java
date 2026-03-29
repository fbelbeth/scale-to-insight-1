package com.sti.finance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class FinanceServiceApplication {
    @ConfigProperty(name = "finance.warehouse-db", defaultValue = "/data/warehouse/warehouse.db")
    String warehouseDb;

    // Endpoint de saúde para validar disponibilidade do serviço.
    @GET
    @Path("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "finance-service");
    }

    // Endpoint que expõe o data mart para o setor financeiro.
    @GET
    @Path("/kpis")
    public Map<String, Object> kpis() {
        return collectKpis();
    }

    // Consulta o data mart e monta o payload de resumo e serie diaria de metricas.
    private Map<String, Object> collectKpis() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_sales", 0.0);
        summary.put("total_orders", 0);
        summary.put("avg_ticket", 0.0);

        List<Map<String, Object>> byDay = new ArrayList<>();

        java.nio.file.Path dbPath = Paths.get(warehouseDb);
        if (!Files.exists(dbPath)) {
            return Map.of("summary", summary, "by_day", byDay);
        }

        String dbUrl = "jdbc:sqlite:" + dbPath;
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

    // Arredonda numero para duas casas decimais para exibicao de indicadores.
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
