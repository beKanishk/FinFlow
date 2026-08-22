package finance.finflow.repository;

import finance.finflow.module.TransactionStatus;

import java.util.UUID;

// A "closed projection" - Spring Data JPA sees this interface as the return type and only
// selects the transaction_id and status columns, instead of the whole transactions row. This
// matters here because Transaction.sourceWallet/destinationWallet are @ManyToOne with no
// fetch=LAZY, so loading a full Transaction always eager-loads both linked Wallet rows too - extra
// work callers that only need to check a status don't want.
public interface TransactionStatusView {
    UUID getTransactionId();
    TransactionStatus getStatus();
}
