package finance.finflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateWalletRequest {

    @NotBlank(message = "Currency is required")
    private String currency;
}
