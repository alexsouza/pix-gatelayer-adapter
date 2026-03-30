package br.com.basa.pix;

import br.com.basa.pix.domain.model.PixTransfer;
import br.com.basa.pix.domain.model.TransferStatus;
import br.com.basa.pix.domain.repository.PixTransferRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "pix-transfer-events-test" }, brokerProperties = {
        "listeners=PLAINTEXT://localhost:0", "port=0" })
@EnableWireMock(@ConfigureWireMock(name = "pix-soap-service"))
class PixTransferIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PixTransferRepository pixTransferRepository;

    @InjectWireMock("pix-soap-service")
    private com.github.tomakehurst.wiremock.WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer.stubFor(
                WireMock.post(urlEqualTo("/ws/pix"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                                .withBody(
                                        """
                                                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                                                    <SOAP-ENV:Body>
                                                        <ns2:EfetuarTransferenciaResponse xmlns:ns2="http://legado.basa.com.br/pix">
                                                            <sucesso>true</sucesso>
                                                            <codigoRetorno>00</codigoRetorno>
                                                            <mensagem>Transferência realizada com sucesso</mensagem>
                                                        </ns2:EfetuarTransferenciaResponse>
                                                    </SOAP-ENV:Body>
                                                </SOAP-ENV:Envelope>
                                                """)));
    }

    @Test
    void deveRetornar202AoIniciarTransferenciaPix() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();

        mockMvc.perform(post("/v1/pix/transferencias")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "chavePix": "teste@email.com",
                            "valor": 150.00
                        }
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void deveRetornar202SemCorrelationIdEGerarUmAutomaticamente() throws Exception {
        mockMvc.perform(post("/v1/pix/transferencias")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "chavePix": "11999999999",
                            "valor": 50.00
                        }
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").isNotEmpty());
    }

    @Test
    void deveRetornar400QuandoChavePixAusente() throws Exception {
        mockMvc.perform(post("/v1/pix/transferencias")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "valor": 100.00
                        }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deveIgnorarRequisicaoDuplicadaPorIdempotencyKey() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        PixTransfer existing = PixTransfer.builder()
                .transactionId(UUID.randomUUID().toString())
                .idempotencyKey(idempotencyKey)
                .correlationId(UUID.randomUUID().toString())
                .chavePix("duplicado@email.com")
                .valor(BigDecimal.TEN)
                .status(TransferStatus.PROCESSING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        pixTransferRepository.saveIfAbsent(existing);

        mockMvc.perform(post("/v1/pix/transferencias")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "chavePix": "duplicado@email.com",
                            "valor": 10.00
                        }
                        """))
                .andExpect(status().isAccepted());
    }

    @Test
    void deveRetornar200ComStatusDaTransacao() throws Exception {
        String transactionId = UUID.randomUUID().toString();
        PixTransfer transfer = PixTransfer.builder()
                .transactionId(transactionId)
                .idempotencyKey(UUID.randomUUID().toString())
                .correlationId(UUID.randomUUID().toString())
                .chavePix("consulta@email.com")
                .valor(BigDecimal.valueOf(200.00))
                .status(TransferStatus.COMPLETED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        pixTransferRepository.saveIfAbsent(transfer);

        mockMvc.perform(get("/v1/pix/transferencias/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transactionId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void deveRetornar404QuandoTransacaoNaoEncontrada() throws Exception {
        mockMvc.perform(get("/v1/pix/transferencias/{transactionId}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }
}
