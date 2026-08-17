package com.atlasmind.atlaswatch.config;

import com.atlasmind.atlaswatch.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class ApplicationConfig {
    private final UserRepository userRepository;

    @Bean
    public UserDetailsService userDetailsService() {
        // email is the username/parameter.
        return info -> userRepository.findByUsername(info)
                .or(() -> userRepository.findByEmail(info))
                .orElseThrow(() -> new UsernameNotFoundException("User was not found!!"));
    }

    // PasswordEncoder helps compare computed hashed password with stored hashed password.
    @Bean
    public AuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService());
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    @Bean
    public PasswordEncoder PasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // AuthenticationConfiguration object is created during startup, so I'M NOT CREATING MY OWN MANAGER,
    // BUT AM JUST GETTING WHAT SPRING BUILT BEHIND THE SCENES USING THE CURRENT SECURITY SETUP.

    /**
     *
     * Implementation is ProviderManager which has all the AuthenticationProviders.
     * It loops through all providers to see which supports the authtoken for which
     * DaoAuthenticationProvider returns true. Few examples are below based on the type of tokens an AuthenticationProvider can handle.
     * UsernamePasswordAuthenticationToken → for DB-based login
     * JwtAuthenticationToken → for token-based login
     * OtpAuthenticationToken → for OTP-based login
     * @param configuration
     * @return
     * @throws Exception
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}

