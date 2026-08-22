package finance.finflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DltRetryRequest {

    @NotNull
    private Integer partition;

    @NotNull
    private Long offset;
}
