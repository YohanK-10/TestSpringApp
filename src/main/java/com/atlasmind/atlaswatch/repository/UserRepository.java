package com.atlasmind.atlaswatch.repository;

import com.atlasmind.atlaswatch.models.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// You can create custom queries as well with @QUERY
// Usually spring writes the SQL query for me!!
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username); // Find user given username.
    Optional<User> findByEmail(String email);// Find user given email address.
    Optional<User> findByVerificationCode(String verificationCode); // Check if the given verification code is correct.
}

