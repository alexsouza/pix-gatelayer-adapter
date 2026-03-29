package br.com.basa.pix.application.service;

import br.com.basa.pix.domain.model.PixTransfer;
import br.com.basa.pix.domain.model.TransferStatus;
import br.com.basa.pix.domain.repository.PixTransferRepository;
import br.com.basa.pix.inbound.rest.dto.TransferRequest;
import br.com.basa.pix.inbound.rest.dto.TransferResponse;
import br.com.basa.pix.outbound.kafka.PixTransferKafkaProducer;
import br.com.basa.pix.outbound.soap.LegacySoapPixAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PixTransferService {

    private final PixTransferRepository repository;
    private final PixTransferKafkaProducer kafkaProducer;
    private final LegacySoapPixAdapter soapAdapter;

    public TransferResponse initiateTransfer(TransferRequest request, String idempotencyKey, String correlationId) {
        PixTransfer transfer = PixTransfer.builder()
                .transactionId(UUID.randomUUID().toString())
                .idempotencyKey(idempotencyKey)
                .correlationId(correlationId)
                .chavePix(request.getChavePix())
                .valor(request.getValor())
                .status(TransferStatus.PROCESSING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        boolean saved = repository.saveIfAbsent(transfer);
        if (!saved) {
            log.info("Requisição idempotente ignorada [idempotencyKey={}]", idempotencyKey);
        } else {
            kafkaProducer.send(transfer);
        }

        return TransferResponse.builder()
                .transactionId(transfer.getTransactionId())
                .status(transfer.getStatus())
                .build();
    }

    public TransferResponse getTransferStatus(String transactionId) {
        PixTransfer transfer = repository.findById(transactionId)
                .orElseThrow(() -> new NoSuchElementException("Transação não encontrada: " + transactionId));
        return TransferResponse.builder()
                .transactionId(transfer.getTransactionId())
                .status(transfer.getStatus())
                .build();
    }

    public void processTransfer(PixTransfer transfer) {
        MDC.put("correlationId", transfer.getCorrelationId());
        try {
            log.info("Processando transferência Pix [transactionId={}]", transfer.getTransactionId());
            boolean sucesso = soapAdapter.efetuarTransferencia(transfer);
            TransferStatus finalStatus = sucesso ? TransferStatus.COMPLETED : TransferStatus.FAILED;
            repository.updateStatus(transfer.getTransactionId(), finalStatus);
            log.info("Transferência finalizada [transactionId={}, status={}]", transfer.getTransactionId(), finalStatus);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
