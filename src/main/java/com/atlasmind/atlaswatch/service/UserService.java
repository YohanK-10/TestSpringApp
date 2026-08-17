package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final EmailService emailService;
    public List<User> allUsers() {
        List<User> listOfUsers = new ArrayList<>();
        userRepository.findAll().forEach(listOfUsers::add);
        return listOfUsers;
    }
}

