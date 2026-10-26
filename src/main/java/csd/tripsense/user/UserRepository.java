package csd.tripsense.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

import org.springframework.data.domain.Page;

import org.springframework.data.domain.Pageable;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByUsernameIgnoreCase(String username);

    /** Admin search: part of the email OR part of the username, any capitalisation. */
    Page<User> findByEmailContainingIgnoreCaseOrUsernameContainingIgnoreCase(
            String email, String username, Pageable pageable);
}
