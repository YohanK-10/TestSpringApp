package com.atlasmind.atlaswatch.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;

@NoArgsConstructor
@AllArgsConstructor
@Entity
@Getter
@Setter
@Table(name = "Users") // only needed if the table name in the database doesn’t match the class name, just entity can save it in the database.
public class User implements UserDetails {
    @Id // Used to mark as the primary key!!
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_seq")
    @SequenceGenerator(name = "user_seq", sequenceName = "users_seq", allocationSize = 50)
    private Long id;

    @Column(name = "Username", unique = true, nullable = false)
    private String username;

    @Column(name = "Email", unique = true, nullable = false)
    private String email;

    @Column(name = "Password", nullable = false)
    private String password;
    @Column(name = "verificationCodes")
    private String verificationCode;
    @Column(name = "expirationTimeOfVerificationCodes")
    private LocalDateTime expirationTimeOfVerificationCode;
    @Column(name = "passwordResetCode")
    private String passwordResetCode;
    @Column(name = "expirationTimeOfPasswordResetCode")
    private LocalDateTime expirationTimeOfPasswordResetCode;
    private Boolean enable;
    private Boolean locked; //TODO: CHECK THIS!!

    public User(String username, String email, String password) {
        this.username = username;
        this.email = email;
        this.password = password;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singleton(new SimpleGrantedAuthority("USER"));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !Boolean.TRUE.equals(this.locked);
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return this.enable;
    }
}

