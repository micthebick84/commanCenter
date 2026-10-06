package com.hamonsoft.netismaker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * DB 접속정보 비밀번호 암복호화 (스펙 2026-10-02 §4.1). Encryptors.delux = AES-256-GCM + PBKDF2, 결과 hex.
 * 키(app.db-secret.key)나 salt(app.db-secret.salt, hex 짝수 길이 16자 이상)가 없거나 틀리면 기능만 끈다 —
 * 부팅을 막지 않는다. 키를 바꾸면 기존 행은 tryDecrypt가 empty → "다시 저장 필요"(키 교체 도구는 범위 밖).
 */
@Component
@Profile("api")
public class DbSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(DbSecretCipher.class);
    private static final String SALT_PATTERN = "^(?:[0-9a-fA-F]{2}){8,}$";

    private final TextEncryptor encryptor;   // null = 꺼짐

    public DbSecretCipher(@Value("${app.db-secret.key:}") String key,
                          @Value("${app.db-secret.salt:}") String salt) {
        this.encryptor = build(key, salt);
    }

    private static TextEncryptor build(String rawKey, String rawSalt) {
        // 키 문자열이 파생 키를 결정한다 — env 파일 등에서 섞인 앞뒤 공백·개행이 조용히 다른 키가 되지 않게 먼저 strip한다.
        String key = rawKey == null ? null : rawKey.strip();
        String salt = rawSalt == null ? null : rawSalt.strip();
        if (key == null || key.isBlank() || salt == null || salt.isBlank()) {
            log.info("DB 접속정보 기능 꺼짐 — NETISMAKER_DB_SECRET_KEY/NETISMAKER_DB_SECRET_SALT 미설정");
            return null;
        }
        if (!salt.matches(SALT_PATTERN)) {
            log.warn("NETISMAKER_DB_SECRET_SALT가 hex(짝수 길이 16자 이상)가 아님 — DB 접속정보 기능 꺼짐");
            return null;
        }
        return Encryptors.delux(key, salt);
    }

    public boolean isEnabled() {
        return encryptor != null;
    }

    public String encrypt(String plain) {
        if (encryptor == null) throw new IllegalStateException("DB 접속정보 암호화 키가 설정되지 않았습니다");
        return encryptor.encrypt(plain);
    }

    /** 꺼짐·키 불일치·손상된 암호문이면 empty. 예외 메시지는 로그로도 내보내지 않는다(호출자가 id만 남긴다). */
    public Optional<String> tryDecrypt(String enc) {
        if (encryptor == null || enc == null) return Optional.empty();
        try {
            return Optional.of(encryptor.decrypt(enc));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
