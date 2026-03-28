package br.com.basa.pix.domain.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class PixTransfer {

    private String transactionId;
    private String idempotencyKey;
    private String correlationId;
    private String chavePix;
    private BigDecimal valor;
    private TransferStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
