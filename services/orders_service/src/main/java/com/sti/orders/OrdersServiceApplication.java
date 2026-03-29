package com.sti.orders;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class OrdersServiceApplication {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "orders.raw-root", defaultValue = "/data/raw")
    String rawRoot;

    @ConfigProperty(name = "orders.azure-storage-connection-string")
    String azureStorageConnectionString;

    @ConfigProperty(name = "orders.azure-raw-container", defaultValue = "raw")
    String azureRawContainer;

    // Endpoint de saúde para monitoramento e smoke tests.
    @GET
    @Path("/health")
    public Map<String, Object> health() {
        logAccess("GET", "/health", null);
        return Map.of("status", "ok", "service", "orders-service");
    }

    // Endpoint de origem de dados de vendas (camada App -> Raw).
    @POST
    @Path("/orders")
    public Response createOrder(Map<String, Object> payload) throws IOException {
        Map<String, Object> requestPayload = payload == null ? new HashMap<>() : payload;
        logAccess("POST", "/orders", null);

        Map<String, Object> saleEvent = new HashMap<>();
        saleEvent.put("order_id", UUID.randomUUID().toString());
        saleEvent.put("created_at", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
        saleEvent.put("amount", toDouble(requestPayload.getOrDefault("amount", 100.0)));
        saleEvent.put("payment_method", String.valueOf(requestPayload.getOrDefault("payment_method", "credit_card")));
        saleEvent.put("status", String.valueOf(requestPayload.getOrDefault("status", "approved")));

        appendJsonLine(getSalesLogFile(), saleEvent);
        boolean uploaded = uploadToAzureBlob(saleEvent);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "order created");
        response.put("order", saleEvent);
        response.put("azure_blob_uploaded", uploaded);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    // Registra um evento de acesso em arquivo raw para auditoria básica.
    private void logAccess(String method, String path, String userAgent) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            event.put("method", method);
            event.put("path", path);
            event.put("remote_addr", "n/a");
            event.put("user_agent", userAgent);
            appendJsonLine(getAccessLogFile(), event);
        } catch (Exception ignored) {
            // Access log failures should not block request processing.
        }
    }

    // Grava um objeto JSON por linha (JSONL), criando diretório/arquivo quando necessário.
    private void appendJsonLine(java.nio.file.Path file, Map<String, Object> payload) throws IOException {
        Files.createDirectories(file.getParent());
        String line = MAPPER.writeValueAsString(payload) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8,
            Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
    }

    // Envia uma cópia do evento para o blob storage (Azurite) e informa sucesso/falha.
    private boolean uploadToAzureBlob(Map<String, Object> saleEvent) {
        try {
            String createdAt = String.valueOf(saleEvent.get("created_at"));
            ZonedDateTime ts = ZonedDateTime.parse(createdAt);
            // Particiona por data para simular organizacao comum de data lake.
            String blobName = String.format(
                "sales/%04d/%02d/%02d/%s.json",
                ts.getYear(),
                ts.getMonthValue(),
                ts.getDayOfMonth(),
                saleEvent.get("order_id")
            );

            BlobServiceClient client = new BlobServiceClientBuilder()
                .connectionString(azureStorageConnectionString)
                .buildClient();

            BlobContainerClient containerClient = client.getBlobContainerClient(azureRawContainer);
            if (!containerClient.exists()) {
                containerClient.create();
            }

            String content = MAPPER.writeValueAsString(saleEvent);
            containerClient.getBlobClient(blobName)
                .upload(new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), true);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private java.nio.file.Path getAccessLogFile() {
        return Paths.get(rawRoot).resolve("access/events.jsonl");
    }

    private java.nio.file.Path getSalesLogFile() {
        return Paths.get(rawRoot).resolve("sales/events.jsonl");
    }

    // Converte valor genérico para double, aplicando valor padrão em caso de erro.
    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 100.0;
        }
    }
}
