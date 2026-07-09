package com.healthupgrades.auth.application;

import com.healthupgrades.auth.domain.model.TokenPair;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.user.adapter.in.web.UserDto;
import com.healthupgrades.user.application.port.in.UserDirectory;
import com.healthupgrades.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserDirectory userDirectory;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public TokenPair register(String name, String email, String password) {
        if (userDirectory.existsByEmail(email)) {
            throw new BusinessRuleException("Email already registered: " + email);
        }
        User user = User.builder()
                .name(name)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .build();
        user = userDirectory.save(user);
        String token = tokenProvider.generateToken(user.getEmail());
        return new TokenPair(token, toDto(user));
    }

    public TokenPair login(String email, String password) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        User user = userDirectory.findByEmail(email)
                .orElseThrow(() -> new BusinessRuleException("User not found"));
        String token = tokenProvider.generateToken(user.getEmail());
        return new TokenPair(token, toDto(user));
    }

    public UserDto getMe(String email) {
        User user = userDirectory.findByEmail(email)
                .orElseThrow(() -> new BusinessRuleException("User not found"));
        return toDto(user);
    }

    private UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }
}
