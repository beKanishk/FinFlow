package finance.finflow.controller;

import finance.finflow.dto.ApiResponse;
import finance.finflow.dto.DltMessageDTO;
import finance.finflow.dto.DltRetryRequest;
import finance.finflow.service.DltAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/dlt")
@RequiredArgsConstructor
public class DltAdminController {

    private final DltAdminService dltAdminService;

    // topic here is the ORIGINAL topic name (e.g. "payment-completed"), not the dead-letter one -
    // the service adds the ".DLT" suffix itself, so callers don't need to know that convention.
    @GetMapping("/{topic}")
    public ApiResponse<List<DltMessageDTO>> list(@PathVariable String topic,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(dltAdminService.listMessages(topic, page, size));
    }

    @PostMapping("/{topic}/retry")
    public ApiResponse<Void> retry(@PathVariable String topic, @Valid @RequestBody DltRetryRequest request) {
        dltAdminService.retryMessage(topic, request.getPartition(), request.getOffset());
        return ApiResponse.ok(null);
    }
}
