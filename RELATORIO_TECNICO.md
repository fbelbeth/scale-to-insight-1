# Relatório Técnico - Scale-to-Insight

## 1. Objetivo técnico

Implementar uma arquitetura enxuta e reproduzível para ingestão e análise de dados de vendas, com foco em:

1. Escalabilidade modular de serviços.
2. Pipeline de dados auditável.
3. Exposição de indicadores financeiros.
4. Operação simples em ambiente local.

## 2. Requisitos funcionais cobertos

1. Dois serviços de aplicação conteinerizados.
2. Gateway de entrada com Nginx.
3. Simulação de ambiente Azure com Azurite.
4. Fluxo completo App -> Processamento -> Destino.
5. Data Lake Raw para acessos e vendas.
6. Data Warehouse e Data Mart de performance de vendas.
7. CI/CD automatizado em GitHub Actions.

## 3. Decisões arquiteturais e motivações

### 3.1 Serviços separados por responsabilidade

- Orders Service: ingestão de pedidos e escrita de eventos raw.
- Finance Service: leitura analítica e exposição de KPIs.
- Processor: transformação e consolidação de dados.

Motivação:

- Reduz acoplamento entre escrita transacional e leitura analítica.
- Facilita evolução independente de cada componente.

### 3.2 Nginx como ponto único de entrada

Motivação:

- Centraliza roteamento e simplifica contrato externo.
- Permite escalar internamente sem impactar cliente.

### 3.3 Data Lake Raw em JSONL + Blob Azure simulado

Motivação:

- JSONL simplifica append, auditoria e replay local.
- Azurite permite validar integração com Azure Blob sem custo cloud.

### 3.4 Warehouse em SQLite

Motivação:

- Escolha pragmática para execução local didática.
- SQL nativo para consolidação e agregação do data mart.

Trade-off:

- Não é opção para cenários de alta concorrência distribuída.

### 3.5 Scheduler nativo para ETL

Motivação:

- Substitui loop infinito com espera bloqueante por agendamento controlado.
- Permite encerramento seguro com shutdown hook.

## 4. Modelo de dados

### 4.1 Camada Raw

- `data/raw/access/events.jsonl`
- `data/raw/sales/events.jsonl`

### 4.2 Data Warehouse

- `dim_date`
- `fact_sales`

### 4.3 Data Mart

- `dm_sales_performance`

Métricas principais:

- total_sales
- total_orders
- avg_ticket

## 5. Fluxo operacional

1. Requisição entra pelo Nginx.
2. Pedido é recebido pelo Orders Service.
3. Evento de venda é persistido em raw e blob.
4. Processor executa ciclo ETL agendado.
5. Warehouse é atualizado.
6. Data mart é recalculado.
7. Finance Service responde KPIs.

## 6. CI/CD

Workflow: `.github/workflows/ci-cd.yml`

Etapas:

1. Checkout.
2. Validação de compose.
3. Build de imagens.
4. Deploy simulado.
5. Smoke tests de health e fluxo pedido->kpi.
6. Teardown do ambiente.

## 7. Riscos e limitações

1. Sem autenticação e autorização.
2. Sem mensageria para desacoplamento de ingestão.
3. Recálculo completo do data mart por ciclo.
4. SQLite restrito a cenários locais e didáticos.

## 8. Evidências para avaliação

1. Subida local com `docker compose up -d --build`.
2. Health checks dos dois serviços via gateway.
3. Inserção de pedido com retorno de persistência em blob.
4. Consulta de KPIs com agregação atualizada.
5. Execução automatizada destes passos no pipeline.

## 9. Diagrama de atores e componentes

\`\`\`mermaid
flowchart LR
    U[Usuário]
    F[Setor Financeiro]

    U --> N[Nginx Proxy Reverso]
    F --> N

    N --> O[Orders Service]
    N --> FI[Finance Service]

    O --> RAWA[Raw Access JSONL]
    O --> RAWS[Raw Sales JSONL]
    O --> AZ[Azurite Blob]

    RAWS --> P[Processor ETL]
    P --> DW[SQLite Warehouse]
    DW --> DM[Data Mart Sales Performance]
    FI --> DM
\`\`\`
