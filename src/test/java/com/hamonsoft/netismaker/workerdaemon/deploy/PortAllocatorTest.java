package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.*;

class PortAllocatorTest {

    @Test
    void picks_first_free_port_in_range() {
        IntPredicate allFree = p -> true;
        int port = PortAllocator.allocate(19000, 19099, Set.of(), allFree);
        assertThat(port).isEqualTo(19000);
    }

    @Test
    void skips_ports_marked_in_use() {
        IntPredicate allFree = p -> true;
        int port = PortAllocator.allocate(19000, 19099, Set.of(19000, 19001), allFree);
        assertThat(port).isEqualTo(19002);
    }

    @Test
    void skips_ports_not_bindable() {
        IntPredicate freeExcept19000 = p -> p != 19000;
        int port = PortAllocator.allocate(19000, 19099, Set.of(), freeExcept19000);
        assertThat(port).isEqualTo(19001);
    }

    // 원격 데몬(DOCKER_HOST=ssh://…)이면 포트는 원격 PC에 게시된다. 워커 PC에서 bind를 시험하면
    // 무관한 로컬 점유 때문에 멀쩡한 원격 포트를 건너뛴다 — 원격이면 docker ps 게시 목록만 본다.
    @Test
    void remote_daemon_ignores_ports_busy_on_the_worker_machine() throws Exception {
        try (java.net.ServerSocket held = new java.net.ServerSocket(0)) {
            int busy = held.getLocalPort();
            assertThat(PortAllocator.allocateFor(busy, busy + 1, Set.of(), true)).isEqualTo(busy);
            assertThat(PortAllocator.allocateFor(busy, busy + 1, Set.of(), false)).isNotEqualTo(busy);
        }
    }

    @Test
    void remote_daemon_still_skips_ports_the_daemon_already_publishes() {
        assertThat(PortAllocator.allocateFor(19000, 19099, Set.of(19000), true)).isEqualTo(19001);
    }

    @Test
    void throws_when_no_port_available() {
        IntPredicate noneFree = p -> false;
        assertThatThrownBy(() -> PortAllocator.allocate(19000, 19001, Set.of(), noneFree))
                .isInstanceOf(IllegalStateException.class);
    }
}
