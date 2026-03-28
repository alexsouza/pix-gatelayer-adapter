package br.com.basa.pix.outbound.soap.mapper;

import br.com.basa.pix.domain.model.PixTransfer;
import br.com.basa.pix.outbound.soap.EfetuarTransferenciaRequest;
import org.springframework.stereotype.Component;

@Component
public class SoapPixMapper {

    public EfetuarTransferenciaRequest toRequest(PixTransfer transfer) {
        var request = new EfetuarTransferenciaRequest();
        request.setChavePix(transfer.getChavePix());
        request.setValor(transfer.getValor());
        request.setIdTransacao(transfer.getTransactionId());
        return request;
    }
}
