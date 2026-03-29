package br.com.basa.pix.inbound.rest;

import br.com.basa.pix.application.service.PixTransferService;
import br.com.basa.pix.inbound.rest.dto.TransferRequest;
import br.com.basa.pix.inbound.rest.dto.TransferResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/v1/pix")
@RequiredArgsConstructor
public class PixTransferController {

    private final PixTransferService pixTransferService;

    @PostMapping("/transferencias")
    public ResponseEntity<TransferResponse> iniciarTransferencia(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody TransferRequest request) {

        String resolvedCorrelationId = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : UUID.randomUUID().toString();

        TransferResponse response = pixTransferService.initiateTransfer(request, idempotencyKey, resolvedCorrelationId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/transferencias/{transactionId}")
    public ResponseEntity<TransferResponse> consultarTransferencia(@PathVariable String transactionId) {
        try {
            return ResponseEntity.ok(pixTransferService.getTransferStatus(transactionId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
