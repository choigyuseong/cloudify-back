package org.example.apispring.global.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

@Component
public class TokenCrypto {
    private static final String ALG = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private final SecretKey key;
    private final SecureRandom rnd = new SecureRandom();

    public TokenCrypto(@Value("${crypto.master-key-base64}") String b64) {
        byte[] k = Base64.getDecoder().decode(Objects.requireNonNull(b64));
        if (k.length < 32) throw new IllegalStateException("crypto key >= 32 bytes");
        this.key = new SecretKeySpec(k, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        byte[] iv = new byte[IV_LEN]; rnd.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance(ALG);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" +
                    Base64.getEncoder().encodeToString(ct);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public String decrypt(String enc) {
        if (enc == null) return null;
        String[] parts = enc.split(":", 2);
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] ct = Base64.getDecoder().decode(parts[1]);
        try {
            Cipher c = Cipher.getInstance(ALG);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
