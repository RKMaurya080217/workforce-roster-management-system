package com.weeklyroster.repository;

import com.weeklyroster.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    java.util.List<User> findByRole(com.weeklyroster.entity.Role role);
}
