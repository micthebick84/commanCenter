package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPostTest {

    @Test
    void create_drops_hint_for_non_repo() {
        RequestPost feature = RequestPost.create(RequestKind.FEATURE, "t", "c", "some/repo", "u1");
        RequestPost repo = RequestPost.create(RequestKind.REPO, "t", "c", "some/repo", "u1");

        assertThat(feature.getRepoHint()).isNull();
        assertThat(repo.getRepoHint()).isEqualTo("some/repo");
        assertThat(repo.getStatus()).isEqualTo(RequestStatus.RECEIVED);
        assertThat(repo.isOwnedBy("u1")).isTrue();
        assertThat(repo.isOwnedBy("u2")).isFalse();
        assertThat(repo.getLastAdminActivityAt()).isNull();
        assertThat(repo.getAdminReadAt()).isNull();
    }

    @Test
    void requester_unread_after_admin_activity() {
        RequestPost p = RequestPost.create(RequestKind.FEATURE, "t", "c", null, "u1");
        assertThat(p.unreadFor(false)).isFalse();

        OffsetDateTime t1 = p.getCreatedAt().plusMinutes(1);
        p.touchAdmin(t1);
        assertThat(p.unreadFor(false)).isTrue();
        assertThat(p.getUpdatedAt()).isEqualTo(t1);
        assertThat(p.getAdminReadAt()).isEqualTo(t1);

        p.markRead(true, false, t1.plusMinutes(1));
        assertThat(p.unreadFor(false)).isFalse();
    }

    @Test
    void admin_unread_for_new_post() {
        RequestPost p = RequestPost.create(RequestKind.FEATURE, "t", "c", null, "u1");
        assertThat(p.unreadFor(true)).isTrue();

        p.markRead(false, true, p.getCreatedAt().plusMinutes(1));
        assertThat(p.unreadFor(true)).isFalse();

        p.touchRequester(p.getCreatedAt().plusMinutes(2));
        assertThat(p.unreadFor(true)).isTrue();
        assertThat(p.unreadFor(false)).isFalse();
    }
}
