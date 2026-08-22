package finance.finflow.service;

import finance.finflow.module.FailureCode;
import finance.finflow.module.Transaction;
import finance.finflow.module.TransactionStatus;
import finance.finflow.module.TransactionType;
import finance.finflow.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionStatusService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction savePending(Transaction transaction) {
        UUID transactionId = UUID.randomUUID();
        String date        = LocalDate.now(IST).format(DATE_FORMAT);
        String suffix      = transactionId.toString().replace("-", "").substring(0, 8).toUpperCase();


        transaction.setTransactionId(transactionId);
        transaction.setTransactionReference(typePrefix(transaction.getType()) + "-" + date + "-" + suffix);
        transaction.setStatus(TransactionStatus.PENDING);
        return transactionRepository.save(transaction);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Transaction transaction, FailureCode code, String message) {
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureCode(code);
        transaction.setFailureMessage(message);
        transactionRepository.save(transaction);
    }

    // Self-contained (doesn't delegate to markFailed) so it stays annotated correctly even when
    // called from within the same class elsewhere — Spring's proxy-based @Transactional wouldn't
    // apply to a same-class delegation.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedById(UUID transactionId, FailureCode code, String message) {
        Transaction transaction = transactionRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found: " + transactionId));
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureCode(code);
        transaction.setFailureMessage(message);
        transactionRepository.save(transaction);
    }

    private String typePrefix(TransactionType type) {
        return switch (type) {
            case DEPOSIT    -> "DEP";
            case WITHDRAWAL -> "WDR";
            case TRANSFER   -> "TRF";
            case REFUND -> "REF";
        };
    }
}
