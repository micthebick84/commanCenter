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

    @Test
    void throws_when_no_port_available() {
        IntPredicate noneFree = p -> false;
        assertThatThrownBy(() -> PortAllocator.allocate(19000, 19001, Set.of(), noneFree))
                .isInstanceOf(IllegalStateException.class);
    }
}
