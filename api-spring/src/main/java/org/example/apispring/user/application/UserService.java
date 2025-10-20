package org.example.apispring.user.application;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.apispring.user.domain.User;
import org.example.apispring.user.domain.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User upsertBySub(String sub, String email, String name, String pictureUrl) {
        // TODO 예외 처리: sub null/blank → BusinessException(OAUTH_CLAIM_MISSING)
        return userRepository.findBySub(sub)
                .map(u -> {
                    u.updateProfile(email, name, pictureUrl);
                    return u;
                })
                .orElseGet(() -> userRepository.save(
                        User.builder().sub(sub).email(email).name(name).pictureUrl(pictureUrl).build()
                ));
    }

    public Optional<User> findBySub(String sub) {
        return userRepository.findBySub(sub);
    }

}