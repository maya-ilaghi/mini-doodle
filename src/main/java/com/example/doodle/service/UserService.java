package com.example.doodle.service;

import com.example.doodle.domain.User;
import com.example.doodle.exception.EmailAlreadyRegisteredException;
import com.example.doodle.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User createUser(String email, String name) {
        // fast path: a clear 409 for the common case
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        try {
            // flush now so a concurrent duplicate trips the UNIQUE(email) constraint here
            return userRepository.saveAndFlush(new User(email, name));
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException(email);
        }
    }
}
