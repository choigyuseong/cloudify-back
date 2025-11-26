package org.example.apispring.auth.domain;

import java.util.Set;

public final class GoogleScopes {
    private GoogleScopes() {}

    public static final String OPENID  = "openid";
    public static final String EMAIL   = "email";
    public static final String PROFILE = "profile";
    public static final String YOUTUBE = "https://www.googleapis.com/auth/youtube";

    public static final Set<String> REQUIRED = Set.of(
            OPENID, EMAIL, PROFILE, YOUTUBE
    );
}
