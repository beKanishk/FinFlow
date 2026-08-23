package finance.finflow.controller;

import finance.finflow.dto.ApiResponse;
import finance.finflow.dto.DltMessageDTO;
import finance.finflow.dto.DltRetryRequest;
import finance.finflow.service.DltAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// DLT messages are internal plumbing (failed Kafka deliveries) - only an admin should be able to
// see or replay them, not any logged-in user. @PreAuthorize checks the ROLE_ADMIN authority that
// JwtAuthFilter/MappingUserDetails already build from the user's roles in the DB - see
// lib-commons' AuthConfig for where @EnableMethodSecurity is turned on.
@PreAuthorize("hasRole('ADMIN')")
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

    // Discards a message without ever republishing it to the original topic - use this instead of
    // retry when the message should never be reprocessed.
    @DeleteMapping("/{topic}")
    public ApiResponse<Void> delete(@PathVariable String topic,
                                     @RequestParam int partition,
                                     @RequestParam long offset) {
        dltAdminService.deleteMessage(topic, partition, offset);
        return ApiResponse.ok(null);
    }
}
