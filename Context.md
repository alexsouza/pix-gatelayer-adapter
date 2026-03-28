# Instruções para o Copiloto: Geração do Projeto `pix-gatelayer-adapter`

**Contexto:**
Você é um Arquiteto de Software Java Senior. Preciso que você gere o código completo de um microserviço Spring Boot chamado `pix-gatelayer-adapter`.
Este serviço atua como uma Anti-Corruption Layer (ACL) recebendo requisições REST modernas de Pix e repassando para um Core Bancário Legado via SOAP, de forma assíncrona utilizando Apache Kafka.

**Stack Tecnológica:**

- Java 17

- Spring Boot 3.2.x (Web, Validation, Data Redis, Kafka)

- Spring Kafka (para processamento assíncrono e mensageria)

- Spring Web Services (para o cliente SOAP)

- Resilience4j (Circuit Breaker e Retry)

- Lombok

- WireMock, MockMvc e EmbeddedKafka (para testes de integração)

**Regras Arquiteturais (Hexagonal / Ports and Adapters):**

1. O pacote `domain` não pode ter dependências do Spring (exceto anotações simples se estritamente necessário) nem de frameworks externos.

2. O pacote `inbound` conterá os Controllers REST (Portas de Entrada HTTP) e os Listeners do Kafka (Portas de Entrada de Mensageria).

3. O pacote `outbound` conterá os Adaptadores (Portas de Saída) para SOAP, Redis e Kafka (Producers).

4. Apenas o pacote `outbound.soap` pode conhecer as classes do legado (simuladas por JAXB). Não vaze XML ou jargões do SOAP para o `domain` ou `application`.

## Estrutura de Diretórios Esperada

Crie os arquivos respeitando exatamente esta estrutura:

```text
br.com.basa.pix
├── PixGateLayerApplication.java
├── config
│   ├── KafkaConfig.java
│   ├── SoapClientConfig.java
│   └── ResilienceConfig.java
├── domain
│   ├── model
│   │   ├── PixTransfer.java
│   │   └── TransferStatus.java (ENUM: PROCESSING, COMPLETED, FAILED)
│   └── repository
│       └── PixTransferRepository.java (Interface)
├── application
│   └── service
│       └── PixTransferService.java
├── inbound
│   ├── rest
│   │   ├── PixTransferController.java
│   │   └── dto
│   │       ├── TransferRequest.java
│   │       └── TransferResponse.java
│   └── kafka
│       └── PixTransferKafkaListener.java
└── outbound
    ├── soap
    │   ├── LegacySoapPixAdapter.java
    │   └── mapper
    │       └── SoapPixMapper.java
    ├── kafka
    │   └── PixTransferKafkaProducer.java
    └── repository
        └── RedisPixTransferRepository.java (Implementa PixTransferRepository)
```

## Tarefas de Geração de Código (Execute passo a passo)

Por favor, gere o código completo (sem omitir partes com comentários como "resto do código aqui") para as seguintes etapas:

### Passo 1: Configuração (pom.xml)

Gere o arquivo `pom.xml` incluindo todas as dependências mencionadas na Stack Tecnológica (incluindo `spring-kafka` e `spring-kafka-test`). Inclua o plugin `jaxb2-maven-plugin` comentado, apenas como exemplo de como geraríamos as classes SOAP a partir de um WSDL.

### Passo 2: Domínio

Gere o enum `TransferStatus`, a entidade `PixTransfer` e a interface `PixTransferRepository`.

### Passo 3: Configurações e Outbound (Kafka Producer)

Gere a `KafkaConfig` definindo o `NewTopic` para `pix-transfer-events`.
Gere a classe `PixTransferKafkaProducer` no pacote `outbound.kafka` que recebe o payload da transação e o publica no tópico `pix-transfer-events`, injetando o `KafkaTemplate`.

### Passo 4: Inbound (REST)

Gere os DTOs `TransferRequest` (com Bean Validation: chavePix, valor) e `TransferResponse` (transactionId, status).
Gere o `PixTransferController` recebendo um `POST /v1/pix/transferencias`.

- Deve exigir o header `Idempotency-Key`.

- Deve ler o header opcional `X-Correlation-Id`. Se não vier, gere um UUID.

- Deve invocar o serviço para registar a intenção e retornar HTTP 202 (Accepted) imediatamente.

### Passo 5: Application (Service)

Gere o `PixTransferService`.

- Um método `initiateTransfer` que salva no repositório como `PROCESSING` e chama o `PixTransferKafkaProducer` para enviar o evento para o Kafka, passando também o `correlationId`.

- Um método `processTransfer` que será invocado pelo Listener do Kafka. Este método chama o adaptador SOAP e atualiza o status final para `COMPLETED` ou `FAILED`. Coloque o `correlationId` no `MDC` do SLF4J no início deste método.

### Passo 6: Inbound (Kafka Listener)

Gere o `PixTransferKafkaListener` no pacote `inbound.kafka`.

- Utilize a anotação `@KafkaListener` escutando o tópico `pix-transfer-events`.

- Este listener deve extrair o payload e o `correlationId` dos headers do Kafka e invocar o método `processTransfer` do `PixTransferService`.

### Passo 7: Outbound (SOAP Adapter - ACL)

Gere o `LegacySoapPixAdapter`.

- Injete o `WebServiceTemplate`.

- Utilize as anotações `@Retry` e `@CircuitBreaker` do Resilience4j no método que faz a chamada externa.

- Crie um método de fallback que retorne `false` caso o Circuit Breaker abra.

- _Nota:_ Para fim de compilação, crie classes mockadas simples no mesmo arquivo (ou em pacote stub) representando `EfetuarTransferenciaRequest` e `EfetuarTransferenciaResponse` do legado.

### Passo 8: Outbound (Redis Repository)

Gere a implementação `RedisPixTransferRepository` usando `RedisTemplate` para simular o controle de idempotência (usando `setIfAbsent` ou operações atômicas similares) e salvar o status da transação.

### Passo 9: Testes de Integração

Gere uma classe `PixTransferIntegrationTest` usando `@SpringBootTest`, `MockMvc`, `@EmbeddedKafka` e `@WireMockTest`.

- Simule um POST no endpoint REST.

- Faça o stub no WireMock para a URL `/ws/pix` retornando um XML de sucesso SOAP.

- Valide se o retorno HTTP é 202 Accepted.
