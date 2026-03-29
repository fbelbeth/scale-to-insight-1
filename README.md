# Scale-to-Insight

Ecossistema de serviços e dados para e-commerce, com ingestão de eventos, processamento analítico e exposição de KPIs financeiros em tempo quase real, implementado com Quarkus.

Elaborado pelos alunos: André, Fabrício e Willy.

A documentação completa das decisões arquiteturais tomadas pelo grupo encontra-se em [Relatório Técnico](RELATORIO_TECNICO.md)

## O que este projeto entrega

- API de pedidos para gerar eventos de vendas.
- API financeira para consulta de indicadores.
- Gateway Nginx para entrada única de tráfego.
- Pipeline de dados Raw -> Warehouse -> Data Mart.
- Simulação de Azure Blob Storage com Azurite.
- Execução local completa com Docker Compose.
- Pipeline CI/CD com GitHub Actions.

## Arquitetura (resumo)

1. Cliente envia requisições para o Nginx.
2. Nginx roteia para serviços internos.
3. Serviço de pedidos grava dados raw e publica cópia no blob.
4. Processador ETL consolida dados no warehouse.
5. Serviço financeiro consulta o data mart e retorna KPIs.

## Stack

- Java 21
- Quarkus 3
- Maven
- Nginx
- SQLite
- Azurite (Azure Blob local)
- Docker / Docker Compose
- GitHub Actions

## Executando localmente

Pré-requisitos:

- Docker
- Docker Compose

1. Subir o ambiente:

```bash
docker compose up -d --build
```

2. Verificar saúde:

```bash
curl http://localhost:8080/
curl http://localhost:8080/orders/health
curl http://localhost:8080/finance/health
```

3. Publicar venda de teste:

```bash
curl -X POST http://localhost:8080/orders/orders \
  -H "Content-Type: application/json" \
  -d '{"amount": 150.50, "payment_method": "pix", "status": "approved"}'
```

4. Consultar KPIs:

```bash
curl http://localhost:8080/finance/kpis
```

5. Encerrar:

```bash
docker compose down -v
```

## Endpoints principais

- `GET /orders/health`
- `POST /orders/orders`
- `GET /finance/health`
- `GET /finance/kpis`

## Estrutura do repositório

- `docker-compose.yml`: orquestração local.
- `nginx/nginx.conf`: proxy reverso.
- `services/orders_service`: API de pedidos e ingestão raw.
- `services/finance_service`: API de indicadores financeiros.
- `services/processor`: ETL e atualização do data mart.
- `.github/workflows/ci-cd.yml`: pipeline CI/CD.
