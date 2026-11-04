package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.util.StackSecretEnv;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱 로그에서 비밀 값을 가리는 규칙 — 작업 env(secret) + API 프로세스 스택 비밀 + URL 자격증명 + 맨 토큰. */
class SecretMaskerTest {

    private static final SecretMasker NONE = SecretMasker.of(List.of(), Map.of());

    private static SecretMasker withSecrets(String... values) {
        return SecretMasker.of(Arrays.stream(values).map(v -> new EnvVar("K", v, true)).toList(), Map.of());
    }

    @Test
    void 비밀_env는_4자_이상만() {
        SecretMasker masker = SecretMasker.of(List.of(
                new EnvVar("DB_PW", "pw12", true),
                new EnvVar("K", "abc", true),
                new EnvVar("URL", "visible1", false)), Map.of());

        assertThat(masker.mask("pw12")).isEqualTo("••••");
        assertThat(masker.mask("abc")).isEqualTo("abc");
        assertThat(masker.mask("visible1")).isEqualTo("visible1");
    }

    @Test
    void 스택_비밀은_이름으로_대소문자_무시() {
        SecretMasker masker = SecretMasker.of(List.of(),
                Map.of("spring_datasource_password", "ntflowLive", "DOCKER_HOST", "ssh://u@10.1.1.75"));

        assertThat(masker.mask("jdbc pw=ntflowLive;")).isEqualTo("jdbc pw=••••;");
        assertThat(masker.mask("host ssh://u@10.1.1.75")).isEqualTo("host ssh://u@10.1.1.75");
    }

    @Test
    void 스택_비밀은_StackSecretEnv의_모든_이름을_본다() {
        for (String name : StackSecretEnv.NAMES) {
            SecretMasker masker = SecretMasker.of(List.of(), Map.of(name, "v" + name));

            assertThat(masker.mask("x v" + name + " y")).as(name).isEqualTo("x •••• y");
        }
    }

    @Test
    void 스택_비밀이_비어_있거나_공백뿐이면_후보가_아니다() {
        // replace("", "••••")는 글자 사이마다 끼워 넣는다 — 빈 값이 후보가 되면 모든 줄이 망가진다
        SecretMasker masker = SecretMasker.of(List.of(),
                Map.of("PGPASSWORD", "", "MAIL_PASSWORD", "  ", "GH_TOKEN", "realtoken12"));

        assertThat(masker.mask("abc def realtoken12")).isEqualTo("abc def ••••");
    }

    @Test
    void 작업_env의_공백뿐인_비밀과_빈_줄은_후보가_아니다() {
        SecretMasker masker = withSecrets("      ", "head-secret\n      \ntail-secret");

        assertThat(masker.mask("a      b")).isEqualTo("a      b");
        assertThat(masker.mask("tail-secret")).isEqualTo("••••");
    }

    @Test
    void 정규식_특수문자도_문자그대로() {
        SecretMasker masker = withSecrets("pa$$(w)rd\\1");

        assertThat(masker.mask("login pa$$(w)rd\\1 ok")).isEqualTo("login •••• ok");
    }

    @Test
    void 긴_값부터() {
        SecretMasker masker = withSecrets("abcd", "abcdefgh");

        assertThat(masker.mask("abcdefgh")).isEqualTo("••••");
        assertThat(masker.mask("abcd efgh abcdefgh")).isEqualTo("•••• efgh ••••");
    }

    @Test
    void 출처가_달라도_긴_값부터() {
        // 작업 env의 짧은 값이 스택 비밀(긴 값)의 일부여도 긴 쪽이 통째로 가려진다
        SecretMasker masker = SecretMasker.of(List.of(new EnvVar("K", "ntfl", true)),
                Map.of("SPRING_DATASOURCE_PASSWORD", "ntflowLive"));

        assertThat(masker.mask("pw=ntflowLive")).isEqualTo("pw=••••");
    }

    @Test
    void 여러_줄_비밀은_줄마다() {
        SecretMasker masker = withSecrets("-----BEGIN KEY-----\nMIIEvQIBADANBg\n-----END KEY-----");

        assertThat(masker.mask("k=MIIEvQIBADANBg")).isEqualTo("k=••••");
        assertThat(masker.mask("-----BEGIN KEY-----")).isEqualTo("••••");
    }

    @Test
    void 여러_줄_비밀은_CRLF여도_CR_없이_줄마다() {
        SecretMasker masker = withSecrets("-----BEGIN KEY-----\r\nMIIEvQIBADANBg\r\n-----END KEY-----");

        assertThat(masker.mask("k=MIIEvQIBADANBg")).isEqualTo("k=••••");
        assertThat(masker.mask("-----END KEY-----")).isEqualTo("••••");
    }

    @Test
    void 여러_줄_비밀의_짧은_줄은_후보가_아니다() {
        // 줄 단위 후보도 4자 이상만 — "ab" 같은 줄이 모든 로그를 건드리지 않게
        SecretMasker masker = withSecrets("first-line-secret\nab\nlast-line-secret");

        assertThat(masker.mask("ab cd")).isEqualTo("ab cd");
        assertThat(masker.mask("last-line-secret")).isEqualTo("••••");
    }

    @Test
    void URL_자격증명과_맨_토큰() {
        assertThat(NONE.mask("jdbc:postgresql://app:pw@db:5432/x")).isEqualTo("jdbc:postgresql://***@db:5432/x");
        assertThat(NONE.mask("token ghp_" + "a".repeat(20))).isEqualTo("token ***");
    }

    @Test
    void 비밀이_없으면_본문은_그대로() {
        assertThat(NONE.mask("plain log line 한글")).isEqualTo("plain log line 한글");
        assertThat(NONE.mask("")).isEmpty();
        assertThat(NONE.mask(null)).isNull();
    }

    @Test
    void 같은_값이_여러_번_나와도_모두_가린다() {
        SecretMasker masker = withSecrets("secret99");

        assertThat(masker.mask("secret99 and secret99")).isEqualTo("•••• and ••••");
    }

    @Test
    void 같은_값이_env와_스택에_둘_다_있어도_한_번만_가린다() {
        SecretMasker masker = SecretMasker.of(List.of(new EnvVar("K", "samePw12", true)),
                Map.of("GH_TOKEN", "samePw12"));

        assertThat(masker.mask("x samePw12 y")).isEqualTo("x •••• y");
    }

    @Test
    void null_인자는_빈_것으로() {
        SecretMasker masker = SecretMasker.of(null, null);

        assertThat(masker.mask("abc")).isEqualTo("abc");
    }
}
