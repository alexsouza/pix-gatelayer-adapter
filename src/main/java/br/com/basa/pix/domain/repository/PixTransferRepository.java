package br.com.basa.pix.domain.repository;

import br.com.basa.pix.domain.model.PixTransfer;
import br.com.basa.pix.domain.model.TransferStatus;

import java.util.Optional;

public interface PixTransferRepository {

    boolean saveIfAbsent(PixTransfer transfer);

    void updateStatus(String transactionId, TransferStatus status);

    Optional<PixTransfer> findById(String transactionId);
}
