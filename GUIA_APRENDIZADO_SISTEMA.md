# Guia de Implementação e Aprendizado - Scale-to-Insight

Este guia foi escrito para quem quer entender e reproduzir um projeto semelhante, do zero, usando arquitetura de serviços, pipeline de dados e operação local com Docker.

## 1. O que você vai construir

1. Um gateway Nginx.
2. Um serviço de ingestão de pedidos.
3. Um serviço de consulta de indicadores.
4. Um processador ETL agendado.
5. Um data lake raw e um warehouse local.
6. Uma simulação de Azure Blob com Azurite.

## 2. Ordem recomendada de implementação

1. Subir infraestrutura base com Docker Compose.
2. Implementar health checks dos serviços.
3. Implementar endpoint de pedido (origem de dados).
4. Persistir eventos raw.
5. Implementar ETL incremental com offset.
6. Implementar data mart de performance.
7. Implementar endpoint de KPI.
8. Integrar CI/CD e smoke tests.

## 3. Entendendo cada módulo

### 3.1 Nginx (gateway)

Papel:

- Expor um único endpoint externo (`:8080`).
- Encaminhar requisições para os serviços internos.

Por que é importante:

- Separa cliente da topologia interna.
- Facilita evolução de roteamento sem mudar as APIs consumidoras.

### 3.2 Orders Service (ingestão)

Papel:

- Receber pedidos em `POST /orders/orders`.
- Gerar evento de venda.
- Gravar acesso e venda em raw.
- Publicar cópia da venda no blob (Azurite).

Conceitos-chave:

- JSONL para escrita append-only.
- Configuração centralizada via `AppConfig`.
- Abstração de rota para evitar repetição de validações.

### 3.3 Processor (ETL)

Papel:

- Ler novos eventos de venda.
- Consolidar dados no warehouse.
- Atualizar data mart periodicamente.

Conceitos-chave:

- Offset em arquivo de estado para processar apenas novos registros.
- Scheduler com `ScheduledExecutorService` em vez de loop bloqueante.
- Shutdown hook para parada segura do processo.

### 3.4 Finance Service (consumo analítico)

Papel:

- Expor `GET /finance/kpis`.
- Retornar resumo agregado e série temporal curta.

Conceitos-chave:

- Serviço de leitura separado da escrita transacional.
- Mesma estratégia de abstração de rota e configuração centralizada.

### 3.5 Azurite (Azure local)

Papel:

- Simular Azure Blob Storage localmente.
- Validar integração de upload sem dependência de conta cloud.

## 4. Camadas de dados na prática

### 4.1 Raw

- `data/raw/access/events.jsonl`
- `data/raw/sales/events.jsonl`

Uso:

- Fonte de verdade para replay/processamento.

### 4.2 Warehouse

- Banco SQLite em `data/warehouse/warehouse.db`.
- Tabelas: `dim_date`, `fact_sales`.

Uso:

- Estrutura para consultas analíticas com SQL.

### 4.3 Data Mart

- Tabela: `dm_sales_performance`.

Uso:

- Respostas rápidas para KPI financeiro.

## 5. Fluxo ponta a ponta

1. Cliente chama `POST /orders/orders`.
2. Orders grava raw e envia blob.
3. Processor roda ciclo ETL agendado.
4. Warehouse é atualizado.
5. Data mart é recalculado.
6. Finance responde `GET /finance/kpis`.

## 6. Boas práticas aplicadas

1. Separação por responsabilidade de módulo.
2. Configuração centralizada por serviço.
3. Roteamento HTTP com helper reutilizável.
4. ETL incremental com controle de offset.
5. Pipeline CI com validação funcional mínima.

## 7. Como validar que implementou corretamente

1. Suba com `docker compose up -d --build`.
2. Verifique health dos dois serviços.
3. Publique uma venda de teste.
4. Aguarde um ciclo de ETL.
5. Consulte `GET /finance/kpis` e confira valores.

## 8. Erros comuns e como evitar

1. Blob não recebe eventos:
- Verifique string de conexão completa do Azurite.

2. ETL não atualiza KPI:
- Verifique arquivo raw de vendas e intervalo de processamento.

3. Endpoint retorna 405:
- Verifique método HTTP esperado na rota.

4. Endpoint retorna 502 no Nginx:
- Verifique se container do serviço está ativo e sem erro de startup.

## 9. Evoluções naturais

1. Adicionar autenticação entre gateway e serviços.
2. Trocar polling por mensageria/event bus.
3. Escalar warehouse para engine distribuída.
4. Separar módulo compartilhado de utilitários HTTP/config.
