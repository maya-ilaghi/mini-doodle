package com.example.doodle.service;

import com.example.doodle.domain.User;
import com.example.doodle.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User createUser(String email, String name) {
        return userRepository.save(new User(email, name));
    }
}