package br.com.basa.pix.inbound.rest.dto;

import br.com.basa.pix.domain.model.TransferStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransferResponse {

    private String transactionId;
    private TransferStatus status;
}
