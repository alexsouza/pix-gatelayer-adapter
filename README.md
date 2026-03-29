# pix-gatelayer-adapter

Microserviço Spring Boot que atua como **Anti-Corruption Layer (ACL)** para operações Pix. Recebe requisições REST modernas, publica eventos no Apache Kafka e repassa as transferências para um Core Bancário Legado via SOAP de forma assíncrona.

---

## Arquitetura

O projeto segue o padrão **Hexagonal (Ports and Adapters)**:

```
REST Client
     │
     ▼
[inbound.rest]          ← Porta de entrada HTTP
     │
     ▼
[application.service]   ← Orquestração e regras de negócio
     │
     ├──► [outbound.kafka]       ← Publica evento no Kafka
     │
     └──► [outbound.soap]        ← Chama Core Bancário Legado (SOAP)
               ▲
[inbound.kafka] ─────────────────┘  ← Consome evento e aciona o SOAP
     │
[outbound.repository]   ← Persistência de estado no Redis
```

### Regras de isolamento de pacotes

| Pacote | Pode depender de |
|---|---|
| `domain` | Nenhum framework externo |
| `application` | `domain`, Spring `@Service` |
| `inbound` | `application`, Spring Web/Kafka |
| `outbound` | `domain`, Spring Data/Kafka/WS |
| `outbound.soap` | Único pacote que conhece as classes JAXB do legado |

---

## Stack Tecnológica

| Tecnologia | Versão |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.5 |
| Spring Kafka | gerenciado pelo Boot |
| Spring Web Services | gerenciado pelo Boot |
| Resilience4j | 2.2.0 |
| Lombok | gerenciado pelo Boot |
| WireMock Spring Boot | 3.3.1 |

---

## Pré-requisitos

- **JDK 17+** — [Download](https://adoptium.net/)
- **Maven 3.9+** — [Download](https://maven.apache.org/download.cgi)
- **Docker** (para subir Kafka e Redis localmente)

Verifique as versões instaladas:

```bash
java -version
mvn -version
docker -version
```

---

## Infraestrutura Local (Kafka + Redis)

Crie o arquivo `docker-compose.yml` na raiz do projeto:

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
```

Suba os containers:

```bash
docker compose up -d
```

Verifique se estão rodando:

```bash
docker compose ps
```

---

## Configuração

As configurações da aplicação estão em `src/main/resources/application.yml`.

| Propriedade | Padrão | Descrição |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Endereço do broker Kafka |
| `spring.data.redis.host` | `localhost` | Host do Redis |
| `spring.data.redis.port` | `6379` | Porta do Redis |
| `pix.soap.url` | `http://localhost:8080/ws/pix` | URL do endpoint SOAP do legado |
| `pix.kafka.topic` | `pix-transfer-events` | Tópico Kafka para eventos Pix |
| `resilience4j.circuitbreaker.instances.legacySoap.failure-rate-threshold` | `50` | % de falhas para abrir o circuit breaker |
| `resilience4j.retry.instances.legacySoap.max-attempts` | `3` | Tentativas de retry na chamada SOAP |
| `management.endpoints.web.exposure.include` | `health,info` | Endpoints do Actuator expostos |
| `management.endpoint.health.show-details` | `always` | Exibe detalhes dos health indicators |

Para sobrescrever qualquer propriedade sem alterar o arquivo, use variáveis de ambiente:

```bash
export PIX_SOAP_URL=http://meu-legado:8080/ws/pix
export SPRING_DATA_REDIS_HOST=meu-redis
```

---

## Build e Execução

### Compilar o projeto

```bash
mvn clean package -DskipTests
```

### Executar a aplicação

```bash
mvn spring-boot:run
```

Ou via JAR gerado:

```bash
java -jar target/pix-gatelayer-adapter-1.0.0-SNAPSHOT.jar
```

A aplicação sobe na porta `8080` por padrão.

---

## Healthcheck (Spring Actuator)

O Actuator está disponível em `/actuator` e expõe os endpoints `health` e `info`.

### Endpoints disponíveis

| Endpoint | Método | Descrição |
|---|---|---|
| `/actuator/health` | GET | Status geral da aplicação e de cada componente |
| `/actuator/info` | GET | Informações da aplicação (nome, versão, descrição) |

### GET /actuator/health

Retorna o status de saúde da aplicação e de cada dependência monitorada automaticamente pelo Spring Boot (Redis e Kafka).

**Resposta — 200 OK (aplicação saudável):**

```json
{
  "status": "UP",
  "components": {
    "kafka": {
      "status": "UP",
      "details": {
        "bootstrap.servers": "localhost:9092"
      }
    },
    "redis": {
      "status": "UP",
      "details": {
        "version": "7.0.0"
      }
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```

**Resposta — 503 Service Unavailable** (algum componente fora do ar):

```json
{
  "status": "DOWN",
  "components": {
    "redis": {
      "status": "DOWN",
      "details": {
        "error": "org.springframework.data.redis.RedisConnectionFailureException"
      }
    }
  }
}
```

### GET /actuator/info

```json
{
  "app": {
    "name": "pix-gatelayer-adapter",
    "version": "1.0.0-SNAPSHOT",
    "description": "Anti-Corruption Layer para operações Pix via SOAP/Kafka"
  }
}
```

---

## API REST

### POST /v1/pix/transferencias

Inicia uma transferência Pix de forma assíncrona.

**Headers obrigatórios:**

| Header | Obrigatório | Descrição |
|---|---|---|
| `Idempotency-Key` | Sim | Chave única para controle de idempotência |
| `Content-Type` | Sim | `application/json` |
| `X-Correlation-Id` | Não | ID de rastreamento. Se ausente, um UUID é gerado automaticamente |

**Body:**

```json
{
  "chavePix": "email@exemplo.com",
  "valor": 150.00
}
```

**Resposta — 202 Accepted:**

```json
{
  "transactionId": "a3f1c2d4-...",
  "status": "PROCESSING"
}
```

**Resposta — 400 Bad Request** (quando `chavePix` ou `valor` ausentes/inválidos):

```json
{
  "errors": ["chavePix é obrigatória"]
}
```

---

### GET /v1/pix/transferencias/{transactionId}

Consulta o status atual de uma transferência.

**Parâmetro de path:**

| Parâmetro | Descrição |
|---|---|
| `transactionId` | ID da transação retornado no POST |

**Resposta — 200 OK:**

```json
{
  "transactionId": "a3f1c2d4-...",
  "status": "COMPLETED"
}
```

**Resposta — 404 Not Found** (transação inexistente ou expirada):

```json
{}
```

**Valores possíveis para `status`:**

| Status | Descrição |
|---|---|
| `PROCESSING` | Evento publicado no Kafka, aguardando processamento |
| `COMPLETED` | Transferência confirmada pelo Core Bancário Legado |
| `FAILED` | Falha no processamento pelo legado ou circuit breaker aberto |

---

## Fluxo Assíncrono

```
1. POST /v1/pix/transferencias
        │
        ├── Valida Bean Validation (chavePix, valor)
        ├── Verifica idempotência via Redis (setIfAbsent)
        ├── Salva status PROCESSING no Redis
        ├── Publica evento no tópico Kafka `pix-transfer-events`
        └── Retorna 202 Accepted imediatamente

2. PixTransferKafkaListener consome o evento
        │
        └── Extrai correlationId do header Kafka
            └── Chama PixTransferService.processTransfer()
                    │
                    ├── Coloca correlationId no MDC (logs rastreáveis)
                    ├── Chama LegacySoapPixAdapter (com @Retry + @CircuitBreaker)
                    └── Atualiza status para COMPLETED ou FAILED no Redis
```

---

## Resiliência (Resilience4j)

O adaptador SOAP (`LegacySoapPixAdapter`) é protegido por duas camadas:

- **Retry** (`legacySoap`): 3 tentativas com intervalo de 500ms entre elas
- **Circuit Breaker** (`legacySoap`): abre quando 50% das últimas 10 chamadas falham, aguarda 10s antes de tentar novamente

Quando o Circuit Breaker está aberto, o método `fallback` é acionado e retorna `false`, fazendo a transferência ser marcada como `FAILED` sem lançar exceção.

---

## Integração com o Legado SOAP

As classes `EfetuarTransferenciaRequest` e `EfetuarTransferenciaResponse` são stubs JAXB criados para fins de compilação. Em produção, elas devem ser geradas automaticamente a partir do WSDL real usando o `jaxb2-maven-plugin`, que já está comentado no `pom.xml`:

1. Coloque o arquivo WSDL em `src/main/resources/wsdl/pix-legado.wsdl`
2. Descomente o bloco do `jaxb2-maven-plugin` no `pom.xml`
3. Remova os stubs manuais do pacote `outbound.soap`
4. Execute `mvn generate-sources`

---

## Testes Unitários

Os testes unitários estão em `PixTransferServiceTest` e utilizam:

- `@ExtendWith(MockitoExtension.class)` — inicializa os mocks sem subir contexto Spring
- `@Mock` — cria dublês para `PixTransferRepository`, `PixTransferKafkaProducer` e `LegacySoapPixAdapter`
- `@InjectMocks` — injeta os mocks diretamente no `PixTransferService`

> **Nenhuma infraestrutura externa é necessária.** Os testes unitários rodam sem Redis, Kafka ou qualquer container.

### Executar apenas os testes unitários

```bash
mvn test -Dtest=PixTransferServiceTest
```

### Cenários cobertos

| Teste | Método | Cenário | Resultado esperado |
|---|---|---|---|
| `deveSalvarTransferenciaEPublicarNoKafka` | `initiateTransfer` | Requisição nova — `saveIfAbsent` retorna `true` | Salva no repositório, publica no Kafka, retorna `PROCESSING` |
| `deveRetornarProcessingEIgnorarKafkaQuandoIdempotente` | `initiateTransfer` | `saveIfAbsent` retorna `false` | Não publica no Kafka, retorna `PROCESSING` |
| `deveGerarTransactionIdUnicoACadaChamada` | `initiateTransfer` | Duas chamadas consecutivas | `transactionId` distintos em cada resposta |
| `deveRetornarStatusDaTransacaoExistente` | `getTransferStatus` | `transactionId` encontrado no repositório | Retorna `transactionId` e `status` corretos |
| `deveLancarNoSuchElementExceptionQuandoTransacaoNaoEncontrada` | `getTransferStatus` | `transactionId` não encontrado | Lança `NoSuchElementException` com mensagem contendo o ID |
| `deveAtualizarStatusParaCompletedQuandoSoapRetornaSucesso` | `processTransfer` | SOAP retorna `true` | `updateStatus(COMPLETED)` chamado |
| `deveAtualizarStatusParaFailedQuandoSoapRetornaFalha` | `processTransfer` | SOAP retorna `false` | `updateStatus(FAILED)` chamado |
| `deveLimparMdcAposProcessamentoComSucesso` | `processTransfer` | Execução normal | MDC não contém `correlationId` após retorno |
| `deveLimparMdcMesmoQuandoSoapLancaExcecao` | `processTransfer` | SOAP lança exceção | MDC limpo mesmo com falha (garante o `finally`) |
| `deveColocarCorrelationIdNoMdcDuranteProcessamento` | `processTransfer` | Inspeção do MDC durante a chamada SOAP | `correlationId` presente no MDC no momento da chamada |

---

## Testes de Integração

Os testes estão em `PixTransferIntegrationTest` e utilizam:

- `@SpringBootTest` — sobe o contexto completo da aplicação
- `@EmbeddedKafka` — broker Kafka em memória, sem necessidade de infraestrutura externa
- `@EnableWireMock` — servidor HTTP mock para simular o endpoint SOAP do legado
- `MockMvc` — realiza chamadas HTTP aos endpoints REST

> **Atenção:** os testes de integração requerem um Redis disponível em `localhost:6379`. Suba o container antes de executar:
> ```bash
> docker compose up -d redis
> ```

### Executar todos os testes

```bash
mvn test
```

### Executar apenas os testes de integração

```bash
mvn test -Dtest=PixTransferIntegrationTest
```

### Cenários cobertos

| Teste | Endpoint | Cenário | Resultado esperado |
|---|---|---|---|
| `deveRetornar202AoIniciarTransferenciaPix` | POST | Requisição válida com todos os headers | 202 + `transactionId` + `status: PROCESSING` |
| `deveRetornar202SemCorrelationIdEGerarUmAutomaticamente` | POST | Sem header `X-Correlation-Id` | 202 + `transactionId` gerado |
| `deveRetornar400QuandoChavePixAusente` | POST | Body sem `chavePix` | 400 Bad Request |
| `deveIgnorarRequisicaoDuplicadaPorIdempotencyKey` | POST | `Idempotency-Key` já utilizada | 202 (idempotente) |
| `deveRetornar200ComStatusDaTransacao` | GET | `transactionId` existente no Redis | 200 + `transactionId` + `status` corretos |
| `deveRetornar404QuandoTransacaoNaoEncontrada` | GET | `transactionId` inexistente | 404 Not Found |

---

## Estrutura do Projeto

```
src/main/java/br/com/basa/pix/
├── PixGateLayerApplication.java
├── config/
│   ├── KafkaConfig.java              # Define o NewTopic pix-transfer-events
│   ├── SoapClientConfig.java         # Configura WebServiceTemplate e Jaxb2Marshaller
│   └── ResilienceConfig.java         # Placeholder (config via application.yml)
├── domain/
│   ├── model/
│   │   ├── PixTransfer.java          # Entidade de domínio
│   │   └── TransferStatus.java       # Enum: PROCESSING, COMPLETED, FAILED
│   └── repository/
│       └── PixTransferRepository.java  # Interface (porta de saída)
├── application/
│   └── service/
│       └── PixTransferService.java   # Orquestração: initiateTransfer, processTransfer, getTransferStatus
├── inbound/
│   ├── rest/
│   │   ├── PixTransferController.java  # POST e GET /v1/pix/transferencias
│   │   └── dto/
│   │       ├── TransferRequest.java
│   │       └── TransferResponse.java
│   └── kafka/
│       └── PixTransferKafkaListener.java  # Consome pix-transfer-events
└── outbound/
    ├── soap/
    │   ├── LegacySoapPixAdapter.java      # @Retry + @CircuitBreaker
    │   ├── EfetuarTransferenciaRequest.java   # Stub JAXB
    │   ├── EfetuarTransferenciaResponse.java  # Stub JAXB
    │   └── mapper/
    │       └── SoapPixMapper.java         # Converte domínio → request SOAP
    ├── kafka/
    │   └── PixTransferKafkaProducer.java  # Publica no tópico com header correlationId
    └── repository/
        └── RedisPixTransferRepository.java  # Implementa PixTransferRepository via Redis
```

---

## Logs e Rastreabilidade

Todos os logs incluem o `correlationId` via MDC do SLF4J, permitindo rastrear uma transferência de ponta a ponta:

```
2024-01-15 10:23:45 [kafka-listener-1] [correlationId=abc-123] INFO  PixTransferService - Processando transferência Pix [transactionId=xyz-456]
2024-01-15 10:23:45 [kafka-listener-1] [correlationId=abc-123] INFO  LegacySoapPixAdapter - Resposta SOAP legado [transactionId=xyz-456, sucesso=true, codigo=00]
2024-01-15 10:23:45 [kafka-listener-1] [correlationId=abc-123] INFO  PixTransferService - Transferência finalizada [transactionId=xyz-456, status=COMPLETED]
```
