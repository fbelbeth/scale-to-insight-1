package com.sti.orders;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OrdersServiceApplication {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AppConfig CONFIG = AppConfig.load();
    private static final Path ACCESS_LOG_FILE = CONFIG.rawRoot().resolve("access/events.jsonl");
    private static final Path SALES_LOG_FILE = CONFIG.rawRoot().resolve("sales/events.jsonl");

    // Inicializa o servidor HTTP e registra os endpoints do servico de pedidos.
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(5001), 0);

        // Health endpoint usado por monitoramento e smoke tests do pipeline.
        registerRoute(server, "/health", "GET", exchange -> {
            logAccess(exchange);
            sendJson(exchange, 200, Map.of("status", "ok", "service", "orders-service"));
        });

        // Endpoint de origem de dados de vendas (camada App -> Raw).
        registerRoute(server, "/orders", "POST", exchange -> {
            logAccess(exchange);

            Map<String, Object> payload = parseJsonBody(exchange);

            Map<String, Object> saleEvent = new HashMap<>();
            saleEvent.put("order_id", UUID.randomUUID().toString());
            saleEvent.put("created_at", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
            saleEvent.put("amount", toDouble(payload.getOrDefault("amount", 100.0)));
            saleEvent.put("payment_method", String.valueOf(payload.getOrDefault("payment_method", "credit_card")));
            saleEvent.put("status", String.valueOf(payload.getOrDefault("status", "approved")));

            appendJsonLine(SALES_LOG_FILE, saleEvent);
            boolean uploaded = uploadToAzureBlob(saleEvent);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "order created");
            response.put("order", saleEvent);
            response.put("azure_blob_uploaded", uploaded);
            sendJson(exchange, 201, response);
        });

        server.start();
    }

    // Registra um evento de acesso em arquivo raw para auditoria basica.
    private static void logAccess(HttpExchange exchange) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            event.put("method", exchange.getRequestMethod());
            event.put("path", exchange.getRequestURI().getPath());
            event.put("remote_addr", exchange.getRemoteAddress().toString());
            event.put("user_agent", exchange.getRequestHeaders().getFirst("User-Agent"));
            appendJsonLine(ACCESS_LOG_FILE, event);
        } catch (Exception ignored) {
            // Access log failures should not block request processing.
        }
    }

    // Grava um objeto JSON por linha (JSONL), criando diretorio/arquivo quando necessario.
    private static void appendJsonLine(Path file, Map<String, Object> payload) throws IOException {
        Files.createDirectories(file.getParent());
        String line = MAPPER.writeValueAsString(payload) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8,
            Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
    }

    // Envia uma copia do evento de venda para o blob storage (Azurite) e informa sucesso/falha.
    private static boolean uploadToAzureBlob(Map<String, Object> saleEvent) {
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
                .connectionString(CONFIG.azureStorageConnectionString())
                .buildClient();

            BlobContainerClient containerClient = client.getBlobContainerClient(CONFIG.azureRawContainer());
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

    // Registra uma rota com validacao de metodo HTTP e tratamento padrao de erro interno.
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

    // Faz parse do corpo JSON da requisicao e retorna mapa vazio quando invalido/ausente.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonBody(HttpExchange exchange) {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length == 0) {
                return new HashMap<>();
            }
            return MAPPER.readValue(body, Map.class);
        } catch (Exception ex) {
            return new HashMap<>();
        }
    }

    // Serializa um payload para JSON e envia resposta HTTP com status e content-type adequados.
    private static void sendJson(HttpExchange exchange, int statusCode, Map<String, Object> payload) throws IOException {
        byte[] response = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    // Le variavel de ambiente com fallback para valor padrao.
    private static String getEnv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // Converte valor generico para double, aplicando valor padrao em caso de erro.
    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 100.0;
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        // Contrato de handler para processar uma troca HTTP.
        void handle(HttpExchange exchange) throws Exception;
    }

    private record AppConfig(Path rawRoot, String azureStorageConnectionString, String azureRawContainer) {
        // Carrega configuracoes do servico a partir de variaveis de ambiente.
        private static AppConfig load() {
            return new AppConfig(
                Paths.get(getEnv("RAW_ROOT", "/data/raw")),
                getEnv(
                    "AZURE_STORAGE_CONNECTION_STRING",
                    "DefaultEndpointsProtocol=http;"
                        + "AccountName=devstoreaccount1;"
                        + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                        + "BlobEndpoint=http://azurite:10000/devstoreaccount1;"
                ),
                getEnv("AZURE_RAW_CONTAINER", "raw")
            );
        }
    }
}
