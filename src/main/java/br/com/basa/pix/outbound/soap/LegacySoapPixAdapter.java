package br.com.basa.pix.outbound.soap;

import br.com.basa.pix.domain.model.PixTransfer;
import br.com.basa.pix.outbound.soap.mapper.SoapPixMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegacySoapPixAdapter {

    private final WebServiceTemplate webServiceTemplate;
    private final SoapPixMapper mapper;

    @Retry(name = "legacySoap")
    @CircuitBreaker(name = "legacySoap", fallbackMethod = "fallback")
    public boolean efetuarTransferencia(PixTransfer transfer) {
        var request = mapper.toRequest(transfer);
        var response = (EfetuarTransferenciaResponse) webServiceTemplate.marshalSendAndReceive(request);
        log.info("Resposta SOAP legado [transactionId={}, sucesso={}, codigo={}]",
                transfer.getTransactionId(), response.isSucesso(), response.getCodigoRetorno());
        return response.isSucesso();
    }

    @SuppressWarnings("unused")
    private boolean fallback(PixTransfer transfer, Throwable ex) {
        log.error("Circuit Breaker aberto para transferência [transactionId={}]: {}",
                transfer.getTransactionId(), ex.getMessage());
        return false;
    }
}
