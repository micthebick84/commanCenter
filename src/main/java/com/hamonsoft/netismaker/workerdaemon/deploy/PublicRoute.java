package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공개 배포 라우팅(Traefik) 슬러그·라벨·URL 생성. 순수 함수 — docker 불필요, 단위테스트 대상.
 *
 * 라우터/서비스/서브도메인은 모두 같은 슬러그 task-{id}{suffix}를 쓴다.
 * suffix는 같은 base-domain을 쓰는 다른 스택과의 이름 충돌 회피용(예 "-win"). 빈 값이면 기존과 동일.
 */
public final class PublicRoute {

    private PublicRoute() {}

    /** Traefik 라우터/서비스명 + 서브도메인 공용 슬러그. */
    public static String slug(long taskId) {
        return slug(taskId, "");
    }

    public static String slug(long taskId, String suffix) {
        return "task-" + taskId + (suffix == null ? "" : suffix);
    }

    /** 공개 호스트명 {slug}.{baseDomain} — DNS 레코드 이름이자 Traefik Host 규칙. */
    public static String hostname(long taskId, String baseDomain, String suffix) {
        return slug(taskId, suffix) + "." + baseDomain;
    }

    /** https 공개 URL. */
    public static String publicUrl(long taskId, String baseDomain) {
        return publicUrl(taskId, baseDomain, "");
    }

    public static String publicUrl(long taskId, String baseDomain, String suffix) {
        return "https://" + hostname(taskId, baseDomain, suffix);
    }

    /**
     * docker run 에 부착할 Traefik 라벨 3종 (key=value).
     * Host 규칙의 백틱은 ProcessRunner가 셸 미경유 exec라 리터럴 안전.
     */
    public static List<String> dockerLabels(long taskId, int containerPort, String baseDomain) {
        return dockerLabels(taskId, containerPort, baseDomain, "");
    }

    public static List<String> dockerLabels(long taskId, int containerPort, String baseDomain, String suffix) {
        String s = slug(taskId, suffix);
        return List.of(
                "traefik.enable=true",
                "traefik.http.routers." + s + ".rule=Host(`" + hostname(taskId, baseDomain, suffix) + "`)",
                "traefik.http.services." + s + ".loadbalancer.server.port=" + containerPort);
    }

    /**
     * 호스트명 → task id 역파싱. 형식(task-{숫자}{suffix}.{baseDomain})이 정확히 맞을 때만 값을 준다 —
     * DNS 고아 정리가 다른 레코드(auth-win, 맥 스택 task-N 등)를 건드리지 않게.
     */
    public static OptionalLong taskIdOf(String hostname, String baseDomain, String suffix) {
        if (hostname == null || baseDomain == null) return OptionalLong.empty();
        String sfx = suffix == null ? "" : suffix;
        Matcher m = Pattern.compile("task-(\\d{1,18})" + Pattern.quote(sfx) + "\\." + Pattern.quote(baseDomain))
                .matcher(hostname.trim().toLowerCase(Locale.ROOT));
        return m.matches() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
    }
}
