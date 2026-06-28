package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.List;

/**
 * 공개 배포 라우팅(Traefik) 슬러그·라벨·URL 생성. 순수 함수 — docker 불필요, 단위테스트 대상.
 *
 * 라우터/서비스/서브도메인은 모두 같은 슬러그 task-{id}를 쓴다.
 */
public final class PublicRoute {

    private PublicRoute() {}

    /** Traefik 라우터/서비스명 + 서브도메인 공용 슬러그. */
    public static String slug(long taskId) {
        return "task-" + taskId;
    }

    /** https 공개 URL. */
    public static String publicUrl(long taskId, String baseDomain) {
        return "https://" + slug(taskId) + "." + baseDomain;
    }

    /**
     * docker run 에 부착할 Traefik 라벨 3종 (key=value).
     * Host 규칙의 백틱은 ProcessRunner가 셸 미경유 exec라 리터럴 안전.
     */
    public static List<String> dockerLabels(long taskId, int containerPort, String baseDomain) {
        String s = slug(taskId);
        return List.of(
                "traefik.enable=true",
                "traefik.http.routers." + s + ".rule=Host(`" + s + "." + baseDomain + "`)",
                "traefik.http.services." + s + ".loadbalancer.server.port=" + containerPort);
    }
}
