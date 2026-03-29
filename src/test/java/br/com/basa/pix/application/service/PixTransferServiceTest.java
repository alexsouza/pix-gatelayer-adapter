package br.com.basa.pix.application.service;

import br.com.basa.pix.domain.model.PixTransfer;
import br.com.basa.pix.domain.model.TransferStatus;
import br.com.basa.pix.domain.repository.PixTransferRepository;
import br.com.basa.pix.inbound.rest.dto.TransferRequest;
import br.com.basa.pix.inbound.rest.dto.TransferResponse;
import br.com.basa.pix.outbound.kafka.PixTransferKafkaProducer;
import br.com.basa.pix.outbound.soap.LegacySoapPixAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PixTransferServiceTest {

    @Mock
    private PixTransferRepository repository;

    @Mock
    private PixTransferKafkaProducer kafkaProducer;

    @Mock
    private LegacySoapPixAdapter soapAdapter;

    @InjectMocks
    private PixTransferService service;

    private TransferRequest request;
    private String idempotencyKey;
    private String correlationId;

    @BeforeEach
    void setUp() {
        request = new TransferRequest();
        request.setChavePix("teste@email.com");
        request.setValor(BigDecimal.valueOf(150.00));

        idempotencyKey = UUID.randomUUID().toString();
        correlationId = UUID.randomUUID().toString();
    }

    // -------------------------------------------------------------------------
    // initiateTransfer
    // -------------------------------------------------------------------------

    @Test
    void deveSalvarTransferenciaEPublicarNoKafka() {
        when(repository.saveIfAbsent(any())).thenReturn(true);

        TransferResponse response = service.initiateTransfer(request, idempotencyKey, correlationId);

        ArgumentCaptor<PixTransfer> captor = ArgumentCaptor.forClass(PixTransfer.class);
        verify(repository).saveIfAbsent(captor.capture());
        verify(kafkaProducer).send(captor.getValue());

        PixTransfer saved = captor.getValue();
        assertThat(saved.getChavePix()).isEqualTo("teste@email.com");
        assertThat(saved.getValor()).isEqualByComparingTo(BigDecimal.valueOf(150.00));
        assertThat(saved.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(saved.getCorrelationId()).isEqualTo(correlationId);
        assertThat(saved.getStatus()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(saved.getTransactionId()).isNotBlank();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        assertThat(response.getTransactionId()).isEqualTo(saved.getTransactionId());
        assertThat(response.getStatus()).isEqualTo(TransferStatus.PROCESSING);
    }

    @Test
    void deveRetornarProcessingEIgnorarKafkaQuandoIdempotente() {
        when(repository.saveIfAbsent(any())).thenReturn(false);

        TransferResponse response = service.initiateTransfer(request, idempotencyKey, correlationId);

        verify(kafkaProducer, never()).send(any());
        assertThat(response.getStatus()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(response.getTransactionId()).isNotBlank();
    }

    @Test
    void deveGerarTransactionIdUnicoACadaChamada() {
        when(repository.saveIfAbsent(any())).thenReturn(true);

        TransferResponse first = service.initiateTransfer(request, UUID.randomUUID().toString(), correlationId);
        TransferResponse second = service.initiateTransfer(request, UUID.randomUUID().toString(), correlationId);

        assertThat(first.getTransactionId()).isNotEqualTo(second.getTransactionId());
    }

    // -------------------------------------------------------------------------
    // getTransferStatus
    // -------------------------------------------------------------------------

    @Test
    void deveRetornarStatusDaTransacaoExistente() {
        String transactionId = UUID.randomUUID().toString();
        PixTransfer transfer = buildTransfer(transactionId, TransferStatus.COMPLETED);
        when(repository.findById(transactionId)).thenReturn(Optional.of(transfer));

        TransferResponse response = service.getTransferStatus(transactionId);

        assertThat(response.getTransactionId()).isEqualTo(transactionId);
        assertThat(response.getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void deveLancarNoSuchElementExceptionQuandoTransacaoNaoEncontrada() {
        String transactionId = UUID.randomUUID().toString();
        when(repository.findById(transactionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransferStatus(transactionId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(transactionId);
    }

    // -------------------------------------------------------------------------
    // processTransfer
    // -------------------------------------------------------------------------

    @Test
    void deveAtualizarStatusParaCompletedQuandoSoapRetornaSucesso() {
        PixTransfer transfer = buildTransfer(UUID.randomUUID().toString(), TransferStatus.PROCESSING);
        when(soapAdapter.efetuarTransferencia(transfer)).thenReturn(true);

        service.processTransfer(transfer);

        verify(repository).updateStatus(transfer.getTransactionId(), TransferStatus.COMPLETED);
    }

    @Test
    void deveAtualizarStatusParaFailedQuandoSoapRetornaFalha() {
        PixTransfer transfer = buildTransfer(UUID.randomUUID().toString(), TransferStatus.PROCESSING);
        when(soapAdapter.efetuarTransferencia(transfer)).thenReturn(false);

        service.processTransfer(transfer);

        verify(repository).updateStatus(transfer.getTransactionId(), TransferStatus.FAILED);
    }

    @Test
    void deveLimparMdcAposProcessamentoComSucesso() {
        PixTransfer transfer = buildTransfer(UUID.randomUUID().toString(), TransferStatus.PROCESSING);
        when(soapAdapter.efetuarTransferencia(transfer)).thenReturn(true);

        service.processTransfer(transfer);

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void deveLimparMdcMesmoQuandoSoapLancaExcecao() {
        PixTransfer transfer = buildTransfer(UUID.randomUUID().toString(), TransferStatus.PROCESSING);
        when(soapAdapter.efetuarTransferencia(transfer)).thenThrow(new RuntimeException("SOAP indisponível"));

        try {
            service.processTransfer(transfer);
        } catch (RuntimeException ignored) {
        }

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void deveColocarCorrelationIdNoMdcDuranteProcessamento() {
        String expectedCorrelationId = UUID.randomUUID().toString();
        PixTransfer transfer = buildTransfer(UUID.randomUUID().toString(), TransferStatus.PROCESSING);
        transfer.setCorrelationId(expectedCorrelationId);

        doAnswer(invocation -> {
            assertThat(MDC.get("correlationId")).isEqualTo(expectedCorrelationId);
            return true;
        }).when(soapAdapter).efetuarTransferencia(transfer);

        service.processTransfer(transfer);

        verify(soapAdapter).efetuarTransferencia(transfer);
    }

    @Test
    void deveAtualizarStatusParaFailedQuandoSoapLancaExcecao() {
        PixTransfer transfer = buildTransfer(UUID.randomUUID().toString(), TransferStatus.PROCESSING);
        when(soapAdapter.efetuarTransferencia(transfer)).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.processTransfer(transfer))
                .isInstanceOf(RuntimeException.class);

        verify(repository, never()).updateStatus(eq(transfer.getTransactionId()), any());
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private PixTransfer buildTransfer(String transactionId, TransferStatus status) {
        return PixTransfer.builder()
                .transactionId(transactionId)
                .idempotencyKey(UUID.randomUUID().toString())
                .correlationId(UUID.randomUUID().toString())
                .chavePix("teste@email.com")
                .valor(BigDecimal.valueOf(100.00))
                .status(status)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
