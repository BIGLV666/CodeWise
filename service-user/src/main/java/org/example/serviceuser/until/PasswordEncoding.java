package org.example.serviceuser.until;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordEncoding {
    static BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();
    public static String encode(CharSequence rawPassword) {
        return bCryptPasswordEncoder.encode(rawPassword);
    }
    public static boolean matches(CharSequence rawPassword, String encodedPassword) {
        return bCryptPasswordEncoder.matches(rawPassword, encodedPassword);
    }
}
