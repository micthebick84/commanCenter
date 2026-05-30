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
