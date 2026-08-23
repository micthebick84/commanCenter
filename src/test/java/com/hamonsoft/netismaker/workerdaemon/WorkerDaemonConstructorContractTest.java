package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 워커 빈 생성자 계약 테스트 — 부팅 잠복버그 재발 방지 게이트.
 *
 * 테스트용+주입용 생성자가 둘 다 있는 @Component는 주입 생성자에 @Autowired가 없으면
 * Spring이 사용할 생성자를 결정하지 못해 워커 기동이 실패한다(실전 전례 있음).
 * workerdaemon 패키지(하위 포함)의 모든 @Component에 대해 리플렉션으로 검증한다.
 */
class WorkerDaemonConstructorContractTest {

    @Test
    void every_multi_constructor_component_has_exactly_one_autowired_constructor() throws Exception {
        // @Profile("worker") 클래스가 조건 평가로 스캔에서 빠지지 않도록 worker 프로파일 활성 환경으로 스캔
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("worker");
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, env);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<Class<?>> components = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.hamonsoft.netismaker.workerdaemon")) {
            components.add(Class.forName(bd.getBeanClassName()));
        }
        assertThat(components).isNotEmpty();

        List<String> multiCtor = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Class<?> clazz : components) {
            Constructor<?>[] ctors = clazz.getDeclaredConstructors();
            if (ctors.length < 2) continue;
            multiCtor.add(clazz.getSimpleName());
            long autowired = Arrays.stream(ctors)
                    .filter(c -> c.isAnnotationPresent(Autowired.class))
                    .count();
            if (autowired != 1) {
                violations.add(clazz.getSimpleName() + " — 생성자 " + ctors.length + "개 중 @Autowired "
                        + autowired + "개 (정확히 1개여야 기동 가능)");
            }
        }

        // 스캔이 실제로 알려진 다중 생성자 빈을 포착하는지 자가 검증 (테스트 무력화 방지)
        assertThat(multiCtor).contains("ResultReporter", "SilentLossTracker", "DeadLetterReplayJob");
        assertThat(violations).isEmpty();
    }
}
