package finance.finflow.repository;

import com.moduleauthentication.authentication.port.AuthUser;
import com.moduleauthentication.authentication.port.AuthUserRepository;
import finance.finflow.module.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaAuthUserRepository implements AuthUserRepository {

    private final UserRepository userRepository;

    @Override
    public Optional<? extends AuthUser> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public AuthUser save(AuthUser user) {
        return userRepository.save((User) user);
    }

    @Override
    public AuthUser createUser(String username, String encodedPassword, List<String> roles) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setRoles(roles);
        return user;
    }
}
