package com.atlasmind.ai_travel_recommendation.service;

import com.atlasmind.ai_travel_recommendation.dto.LoginUserDto;
import com.atlasmind.ai_travel_recommendation.dto.RegisterUserDto;
import com.atlasmind.ai_travel_recommendation.dto.VerifyUserDto;
import com.atlasmind.ai_travel_recommendation.exceptions.DuplicateResourceException;
import com.atlasmind.ai_travel_recommendation.exceptions.ResourceNotFoundException;
import com.atlasmind.ai_travel_recommendation.exceptions.VerificationException;
import com.atlasmind.ai_travel_recommendation.exceptions.WeakPasswordException;
import com.atlasmind.ai_travel_recommendation.models.User;
import com.atlasmind.ai_travel_recommendation.repository.UserRepository;
import com.atlasmind.ai_travel_recommendation.util.PasswordValidator;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public User signUp(RegisterUserDto signUpInfo) {
        if (!PasswordValidator.isStrong(signUpInfo.getPassword())) {
            throw new WeakPasswordException("Password must be at least 8 characters long and contain uppercase, lowercase, number, and symbol.");
        }
        // Optional is a container that may or may not hold a user. Need to unwrap it to check.
        Optional<User> optionalUser1 = userRepository.findByEmail(signUpInfo.getEmail());
        Optional<User> optionalUser2 = userRepository.findByUsername(signUpInfo.getUsername());
        if (optionalUser1.isPresent() && optionalUser2.isPresent()) {
            User userByMail = optionalUser1.get();
            User userByUsername = optionalUser2.get();
            if (userByMail.equals(userByUsername)) {
                if (userByMail.isEnabled()) throw new DuplicateResourceException("This email is already registered and verified. Please log in.");
                return theLogicSendVerificationEmail(userByMail);
            } else throw new DuplicateResourceException("Username and email are both taken.");
        } else if (optionalUser1.isPresent()) {
            User userByMail = optionalUser1.get();
            if (userByMail.isEnabled()) throw new DuplicateResourceException("An account with this email already exists!");
            userByMail.setUsername(signUpInfo.getUsername());
            userByMail.setPassword(passwordEncoder.encode(signUpInfo.getPassword()));
            return theLogicSendVerificationEmail(userByMail);
        } else if (optionalUser2.isPresent()) {
            throw new DuplicateResourceException("Username is already taken!");
        } else {
            // Hashes the password.
            User user = new User(signUpInfo.getUsername(), signUpInfo.getEmail(), passwordEncoder.encode(signUpInfo.getPassword()));
            return theLogicSendVerificationEmail(user); // Returns the saved user object.
        }
    }

    public User theLogicSendVerificationEmail (User theUser) {
        theUser.setVerificationCode(generateVerificationCode());
        theUser.setExpirationTimeOfVerificationCode(LocalDateTime.now().plusMinutes(5));
        theUser.setEnable(false); // To check if the user is allowed to log in and access the system. First need to verify, so set to false.
        sendVerificationEmail(theUser);
        return userRepository.save(theUser);
    }

    public User authenticate(LoginUserDto loginInfo) {
        User user = userRepository.findByUsername(loginInfo.getLoginInfo())
                .or(() -> userRepository.findByEmail(loginInfo.getLoginInfo()))
                .orElseThrow(() -> new ResourceNotFoundException("User", "login Info", loginInfo.getLoginInfo()));

        if (!user.isEnabled()) throw new DisabledException("Account is not verified!!");
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken( // This extends a class which implements Authentication.
                loginInfo.getLoginInfo(),
                loginInfo.getPassword()
        ));
        return user;
    }

    public void verifyUser(VerifyUserDto input) {
        User optionalUser = userRepository.findByUsername(input.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", input.getUsername()));
        if (optionalUser.getExpirationTimeOfVerificationCode().isBefore(LocalDateTime.now())) {
            throw new VerificationException("The verification code is expired!!");
        }
        if (optionalUser.getVerificationCode().equals(input.getVerificationCode())) {
            optionalUser.setEnable(true);
            optionalUser.setVerificationCode(null);
            optionalUser.setExpirationTimeOfVerificationCode(null);
            userRepository.save(optionalUser);
        } else {
            throw new VerificationException("Invalid verification code!!");
        }
    }


    public void resendVerificationCode(String email) {
        Optional<User> optionalUser = userRepository.findByEmail(email);
        if(optionalUser.isPresent()) {
            User user = optionalUser.get();
            if (user.isEnabled()) throw new VerificationException("The user was already verified!!");
            user.setVerificationCode(generateVerificationCode());
            user.setExpirationTimeOfVerificationCode(LocalDateTime.now().plusMinutes(5));
            sendVerificationEmail(user);
            userRepository.save(user);
        } else {
            throw new ResourceNotFoundException("User", "email", email);
        }
    }

    public void sendVerificationEmail(User user) {
        String subject = "Account Verification";
        String verificationCode = user.getVerificationCode();
        String htmlMessage = "<!DOCTYPE html>"
                + "<html>"
                + "<head><meta charset=\"UTF-8\"><title>Verify Your Email</title></head>"
                + "<body style=\"font-family: Arial, sans-serif; margin: 0; padding: 0;\">"
                + "  <div style=\"background-color: #f2f2f2; padding: 20px;\">"
                + "    <table align=\"center\" width=\"600\" style=\"background-color: #ffffff; padding: 20px;\">"
                + "      <tr>"
                + "        <td style=\"text-align: center; padding: 20px;\">"
                + "          <h2 style=\"color: #333;\">Verify Your Email</h2>"
                + "          <p style=\"color: #555; font-size: 16px;\">"
                + "            Use the verification code below to verify your email address:"
                + "          </p>"
                + "          <p style=\"font-size: 24px; font-weight: bold; color: #1a73e8; background-color: #f8f9fa; "
                + "                padding: 10px 20px; display: inline-block; border-radius: 5px;\">"
                +             verificationCode
                + "          </p>"
                + "          <p style=\"color: #555; font-size: 14px;\">"
                + "            If you didn't request this, please ignore this email."
                + "          </p>"
                + "        </td>"
                + "      </tr>"
                + "    </table>"
                + "  </div>"
                + "</body>"
                + "</html>";
        try {
            emailService.sendVerificationEmail(user.getEmail(), subject, htmlMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Generates a secure, random verification code as a URL-safe Base64 string.
     * @return
     */
    private String generateVerificationCode() {
        byte[] tokenArray = new byte[4];
        new SecureRandom().nextBytes(tokenArray);
        int code = (ByteBuffer.wrap(tokenArray).getInt() & 0x7FFFFFFF) % 900000 + 100000;
        return String.valueOf(code);
    }
}
