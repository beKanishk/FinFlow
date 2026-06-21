package finance.finflow.controller;

import com.moduleauthentication.authentication.service.AuthHelper;
import finance.finflow.dto.RegisterRequest;
import finance.finflow.dto.UserResponseDTO;
import finance.finflow.module.User;
import finance.finflow.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthHelper authHelper;

    @PostMapping("/register")
    public UserResponseDTO register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists: " + request.getUsername());
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setRoles(List.of("USER"));

        User saved = userRepository.save(user);
        return toDto(saved);
    }

    @GetMapping("/me")
    public UserResponseDTO getCurrentUser(@RequestHeader("Authorization") String authHeader) {
        String username = authHelper.extractUsername(authHeader);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        return toDto(user);
    }

    @GetMapping("/{username}")
    public ResponseEntity<UserResponseDTO> getByUsername(@PathVariable String username) {
        return userRepository.findByUsername(username)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private UserResponseDTO toDto(User user) {
        return new UserResponseDTO(user.getId(), user.getUsername(), user.getName(), user.getEmail(), user.getRoles());
    }
}
