package finance.finflow.dto;

import finance.finflow.module.TransactionStatus;
import finance.finflow.module.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseDTO {
    private UUID transactionId;
    private String transactionReference;
    private TransactionType type;
    private TransactionStatus status;
    private UUID walletId;
    private BigDecimal amount;
    private String currency;
    private BigDecimal balanceAfter;
    private String description;
}
