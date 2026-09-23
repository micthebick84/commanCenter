package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * [from, to] 범위에서 사용 가능한 첫 호스트 포트를 고른다.
 * 이미 docker가 게시한 포트(inUse) + OS bind 불가 포트를 건너뛴다.
 */
public final class PortAllocator {

    private PortAllocator() {}

    /** 실제 OS bind 테스트 기반 할당. */
    public static int allocate(int from, int to, Set<Integer> inUse) {
        return allocate(from, to, inUse, PortAllocator::isBindable);
    }

    /**
     * 데몬 위치에 맞춘 할당. 원격 데몬(DOCKER_HOST=ssh://…)이면 포트는 원격 PC에 게시되므로
     * 워커 PC의 bind 시험은 무의미하다(무관한 로컬 점유로 멀쩡한 포트를 건너뛴다) — 이때는
     * 데몬이 게시 중인 포트(inUse)만 피하고, 원격 PC의 다른 프로그램과의 충돌은 docker run 실패로 드러난다.
     */
    public static int allocateFor(int from, int to, Set<Integer> inUse, boolean remoteDaemon) {
        return remoteDaemon ? allocate(from, to, inUse, p -> true) : allocate(from, to, inUse);
    }

    /** 테스트 주입용 — isFree 술어로 bindable 판정 대체. */
    static int allocate(int from, int to, Set<Integer> inUse, IntPredicate isFree) {
        for (int p = from; p <= to; p++) {
            if (inUse.contains(p)) continue;
            if (isFree.test(p)) return p;
        }
        throw new IllegalStateException("사용 가능한 포트가 범위에 없음: " + from + "-" + to);
    }

    static boolean isBindable(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
