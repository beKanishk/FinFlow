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
public class TransferResponseDTO {
    private UUID transactionId;
    private String transactionReference;
    private TransactionType type;
    private TransactionStatus status;
    private UUID sourceWalletId;
    private UUID destinationWalletId;
    private BigDecimal amount;
    private String currency;
    private BigDecimal sourceBalanceAfter;
    private BigDecimal destinationBalanceAfter;
    private String description;
}
