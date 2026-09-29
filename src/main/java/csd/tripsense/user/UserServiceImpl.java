package csd.tripsense.user;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public User register(User request) {
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "email '" + request.getEmail() + "' is already taken");
        }
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "username '" + request.getUsername() + "' is already taken");
        }

        // build a fresh entity so a client cant set the id, and force role to USER
        // so there is no self-service path to ADMIN.
        User user = new User(request.getEmail(), request.getUsername(), request.getPassword(), "USER");
        return userRepository.save(user);
    }

    @Override
    public User getByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UserNotFoundException("No user found with username " + username));
    }
}
