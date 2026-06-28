package finance.finflow.dto;

import finance.finflow.module.TransactionStatus;
import finance.finflow.module.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionHistoryDTO {

    private UUID transactionId;
    private String transactionReference;
    private TransactionType type;
    private TransactionStatus status;
    private UUID sourceWalletId;
    private String sourceUsername;
    private UUID destinationWalletId;
    private String destinationUsername;
    private BigDecimal amount;
    private String currency;
    private String description;

    private Instant createdAt;
}
