package com.hamonsoft.netismaker.service.deployconfig;

import com.hamonsoft.netismaker.dto.DeployOverrides;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.Target;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.DbSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeployOverrideServiceTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final RepoCatalogRepository catalogRepo = mock(RepoCatalogRepository.class);
    private final DbConnectionRepository dbRepo = mock(DbConnectionRepository.class);
    private final DbSecretCipher cipher = new DbSecretCipher("test-key", "0123456789abcdef");
    private final org.springframework.transaction.PlatformTransactionManager txManager =
            mock(org.springframework.transaction.PlatformTransactionManager.class);

    private DeployOverrideService service(DbSecretCipher c) {
        return new DeployOverrideService(taskRepo, catalogRepo, dbRepo, c, txManager);
    }

    private static DeployConfig cfg() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("SPRING_DATASOURCE_PASSWORD", "{password}");
        m.put("SPRING_DATASOURCE_USERNAME", "{username}");
        return new DeployConfig(List.of(new DbSlot("main", "주 DB", List.of(DbType.MYSQL),
                new Target(TargetKind.ENV, null, m))), List.of());
    }

    private Task task(List<DeployDbChoice> selection) {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        ReflectionTestUtils.setField(t, "repoCatalogId", 5L);
        ReflectionTestUtils.setField(t, "deployDbSelection", new ArrayList<>(selection));
        when(taskRepo.findById(7L)).thenReturn(Optional.of(t));
        return t;
    }

    private void catalog(DeployConfig cfg) {
        RepoCatalogEntry e = mock(RepoCatalogEntry.class);
        when(e.getDeployConfig()).thenReturn(cfg);
        when(catalogRepo.findById(5L)).thenReturn(Optional.of(e));
    }

    private void db(long id, String plainPassword, boolean enabled) {
        DbConnection c = DbConnection.create(DbConnectionScope.REPO, 5L, null, "개발DB", DbType.MYSQL, "192.0.2.10",
                53306, "netis", "netis", cipher.encrypt(plainPassword), "admin");
        c.setEnabled(enabled);
        ReflectionTestUtils.setField(c, "id", id);
        when(dbRepo.findById(id)).thenReturn(Optional.of(c));
    }

    @Test
    void 배포설정_없으면_null() {
        task(List.of());
        catalog(null);

        assertThat(service(cipher).forClaim(7L)).isNull();
    }

    @Test
    void 배포설정이_사라졌는데_고른_연결이_남았으면_error() {
        task(List.of(new DeployDbChoice("main", 43L)));
        catalog(null);

        DeployOverrides r = service(cipher).forClaim(7L);

        assertThat(r.error()).isEqualTo("레포 카탈로그에 배포 설정이 없어 저장된 배포 DB를 적용할 수 없습니다 — 배포 창에서 다시 고르세요");
    }

    @Test
    void 고른_연결을_복호화해_조립() {
        task(List.of(new DeployDbChoice("main", 43L)));
        catalog(cfg());
        db(43L, "s3cret", true);

        DeployOverrides r = service(cipher).forClaim(7L);

        assertThat(r.error()).isNull();
        assertThat(r.env()).contains(new EnvVar("SPRING_DATASOURCE_PASSWORD", "s3cret", true),
                new EnvVar("SPRING_DATASOURCE_USERNAME", "netis", false));
        assertThat(r.toString()).doesNotContain("s3cret");
    }

    @Test
    void 복호화_실패면_error() {
        task(List.of(new DeployDbChoice("main", 43L)));
        catalog(cfg());
        db(43L, "s3cret", true);
        DbSecretCipher otherKey = new DbSecretCipher("other-key", "0123456789abcdef");

        DeployOverrides r = service(otherKey).forClaim(7L);

        assertThat(r.error()).contains("연결 #43").doesNotContain("s3cret");
    }

    @Test
    void 암호기능_꺼짐이면_error() {
        task(List.of(new DeployDbChoice("main", 43L)));
        catalog(cfg());

        DeployOverrides r = service(new DbSecretCipher("", "")).forClaim(7L);

        assertThat(r.error()).isEqualTo("DB 접속정보 기능이 꺼져 있어 배포 DB를 적용할 수 없습니다");
    }

    @Test
    void 저장소가_예외를_던져도_failed로() {
        task(List.of(new DeployDbChoice("main", 43L)));
        catalog(cfg());
        when(dbRepo.findById(anyLong())).thenThrow(new RuntimeException("boom s3cret"));

        DeployOverrides r = service(cipher).forClaim(7L);

        assertThat(r.error()).startsWith("배포 덮어쓰기 준비 실패:").doesNotContain("s3cret");
    }

    @Test
    void 선택_없이_설정만_있으면_저장된_env로_조립() {
        task(List.of());
        catalog(cfg());

        DeployOverrides r = service(cipher).forClaim(7L);

        assertThat(r).isNotNull();
        assertThat(r.error()).isNull();
    }

    @Test
    void secretsForMasking은_비밀번호를_secret으로() {
        Task t = task(List.of(new DeployDbChoice("main", 43L), new DeployDbChoice("sub", null)));
        db(43L, "s3cret", true);

        assertThat(service(cipher).secretsForMasking(t))
                .containsExactly(new EnvVar("deploy-db:main", "s3cret", true));
    }

    @Test
    void secretsForMasking은_url인코딩된_비밀번호도_가린다() {
        Task t = task(List.of(new DeployDbChoice("main", 43L)));
        db(43L, "p@ss/w d", true);

        assertThat(service(cipher).secretsForMasking(t)).containsExactly(
                new EnvVar("deploy-db:main", "p@ss/w d", true),
                new EnvVar("deploy-db:main:url", "p%40ss%2Fw%20d", true));
    }

    @Test
    void secretsForMasking은_실패하면_빈목록() {
        Task t = task(List.of(new DeployDbChoice("main", 43L)));
        when(dbRepo.findById(anyLong())).thenThrow(new RuntimeException("x"));

        assertThat(service(cipher).secretsForMasking(t)).isEmpty();
    }

    @Test
    void 트랜잭션_시작이_실패해도_forClaim은_던지지_않는다() {
        when(txManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("no tx"));

        DeployOverrides r = service(cipher).forClaim(7L);

        assertThat(r.error()).startsWith("배포 덮어쓰기 준비 실패:");
    }

    @Test
    void 커밋이_실패해도_forClaim은_failed_secretsForMasking은_빈목록() {
        Task t = task(List.of(new DeployDbChoice("main", 43L)));
        catalog(cfg());
        db(43L, "s3cret", true);
        org.mockito.Mockito.doThrow(new org.springframework.transaction.UnexpectedRollbackException("rollback-only"))
                .when(txManager).commit(org.mockito.ArgumentMatchers.any());

        assertThat(service(cipher).forClaim(7L).error()).startsWith("배포 덮어쓰기 준비 실패:");
        assertThat(service(cipher).secretsForMasking(t)).isEmpty();
    }
}
