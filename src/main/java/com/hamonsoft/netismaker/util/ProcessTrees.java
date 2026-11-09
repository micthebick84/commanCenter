package com.hamonsoft.netismaker.util;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 프로세스 트리째 종료 — 앱 로그 스트림이 `docker logs --follow`(원격 데몬이면 ssh 자식까지)를 끝낼 때 쓴다.
 * 짝 이름: {@code SlideRenderer.killTree}(soffice용). 같은 모양이지만 일부러 공유하지 않는다 — 그쪽은 프로세스마다
 * 따로 대기(최대 5초씩)하는 의미를 라이브 슬라이드 렌더링이 쓰고 있어, 여기는 전체에 하나의 대기 시간을 쓰고 성공 여부를
 * 돌려주는 점이 다르다.
 */
public final class ProcessTrees {

    private ProcessTrees() {}

    /**
     * 자손 목록을 먼저 뜬 뒤(부모가 죽으면 더는 자손으로 보이지 않는다) 자식 → 부모 순서로 `destroyForcibly`하고,
     * 전체 {@code wait} 안에 부모와 뜬 자손이 모두 끝나면 true를 돌려준다. 끝나지 않았거나 대기 중 인터럽트되면 false —
     * 인터럽트는 플래그를 되살린다. 종료 요청은 대기에 앞서 이미 보냈으므로 false여도 프로세스는 곧 정리될 수 있다.
     * {@code descendants()}를 지원하지 않는 Process 구현은 자손이 없는 것으로 본다.
     * 목록을 뜬 뒤에 새로 생긴 자손은 알 수 없다(트리 종료의 한계).
     */
    public static boolean killTree(Process p, Duration wait) {
        List<ProcessHandle> descendants;
        try {
            descendants = p.descendants().toList();
        } catch (UnsupportedOperationException e) {
            descendants = List.of();
        }
        descendants.forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();

        long deadline = System.nanoTime() + wait.toNanos();
        try {
            if (!p.waitFor(remainingNanos(deadline), TimeUnit.NANOSECONDS)) return false;
            for (ProcessHandle d : descendants) {
                d.onExit().get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }
}
