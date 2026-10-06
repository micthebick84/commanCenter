package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbSecretCipherTest {

    private static final String SALT = "0123456789abcdef";

    @Test
    void roundtrip_and_ciphertext_is_randomized() {
        DbSecretCipher c = new DbSecretCipher("k3y-for-test", SALT);
        assertThat(c.isEnabled()).isTrue();
        String a = c.encrypt("p@ss");
        String b = c.encrypt("p@ss");
        assertThat(a).isNotEqualTo(b).doesNotContain("p@ss");
        assertThat(c.tryDecrypt(a)).contains("p@ss");
    }

    @Test
    void other_key_cannot_decrypt() {
        String enc = new DbSecretCipher("key-A", SALT).encrypt("secret");
        assertThat(new DbSecretCipher("key-B", SALT).tryDecrypt(enc)).isEmpty();
        assertThat(new DbSecretCipher("key-A", SALT).tryDecrypt("not-hex!!")).isEmpty();
    }

    @Test
    void blank_key_or_salt_disables_without_throwing_at_construction() {
        for (DbSecretCipher c : new DbSecretCipher[] {
                new DbSecretCipher("", SALT), new DbSecretCipher("k", ""), new DbSecretCipher(null, null)}) {
            assertThat(c.isEnabled()).isFalse();
            assertThatThrownBy(() -> c.encrypt("x")).isInstanceOf(IllegalStateException.class);
            assertThat(c.tryDecrypt("abcd")).isEmpty();
        }
    }

    @Test
    void non_hex_or_short_salt_disables() {
        assertThat(new DbSecretCipher("k", "not-hex-salt-value").isEnabled()).isFalse();
        assertThat(new DbSecretCipher("k", "abcdef").isEnabled()).isFalse();     // 16자 미만
        assertThat(new DbSecretCipher("k", "0123456789abcdef0").isEnabled()).isFalse(); // 홀수 길이
    }

    @Test
    void surrounding_whitespace_in_key_and_salt_is_stripped() {
        // public.env 등에서 섞인 공백이 파생 키를 조용히 바꾸면 안 된다 — 앞뒤 공백 유무와 상관없이 호환.
        String enc = new DbSecretCipher("k3y-for-test", SALT).encrypt("p@ss");
        DbSecretCipher padded = new DbSecretCipher("  k3y-for-test\r\n", " \t" + SALT + "\n");
        assertThat(padded.isEnabled()).isTrue();
        assertThat(padded.tryDecrypt(enc)).contains("p@ss");
        assertThat(new DbSecretCipher("k3y-for-test", SALT).tryDecrypt(padded.encrypt("zz"))).contains("zz");
        assertThat(new DbSecretCipher("   ", SALT).isEnabled()).isFalse();
    }
}
