package org.example.apispring.user.application;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.apispring.user.domain.User;
import org.example.apispring.user.domain.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User upsertByGoogle(String sub, String email, String name, String pictureUrl) {
        Objects.requireNonNull(sub, "google sub must not be null");

        return userRepository.findBySub(sub)
                .map(u -> {
                    boolean changed = u.updateProfileIfChanged(email, name, pictureUrl);
                    return u;
                })
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .sub(sub)
                                .email(email)
                                .name(name)
                                .pictureUrl(pictureUrl)
                                .build()
                ));
    }
}
