package finance.finflow.controller;

import finance.finflow.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Already listed in auth.public-paths (application.properties) so it works without a JWT - useful
// as a container/load-balancer health check target on Docker/EC2.
@Slf4j
@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<String> health() {
        log.info("Health check hit");
        return ApiResponse.ok("UP");
    }
}
