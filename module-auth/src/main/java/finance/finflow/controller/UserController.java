package finance.finflow.controller;

import com.moduleauthentication.authentication.service.AuthHelper;
import finance.finflow.dto.ApiResponse;
import finance.finflow.dto.RegisterRequest;
import finance.finflow.dto.UserResponseDTO;
import finance.finflow.module.User;
import finance.finflow.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthHelper authHelper;

    @PostMapping("/register")
    public ApiResponse<UserResponseDTO> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already exists: " + request.getUsername());
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setRoles(List.of("USER"));

        User saved = userRepository.save(user);
        return ApiResponse.ok(toDto(saved));
    }

    @GetMapping("/me")
    public ApiResponse<UserResponseDTO> getCurrentUser(@RequestHeader("Authorization") String authHeader) {
        String username = authHelper.extractUsername(authHeader);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
        return ApiResponse.ok(toDto(user));
    }

    @GetMapping("/{username}")
    public ApiResponse<UserResponseDTO> getByUsername(@PathVariable String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
        return ApiResponse.ok(toDto(user));
    }

    private UserResponseDTO toDto(User user) {
        return new UserResponseDTO(user.getId(), user.getUsername(), user.getName(), user.getEmail(), user.getRoles());
    }
}
