package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.TaskException;
import com.hamonsoft.netismaker.service.TaskService;
import com.hamonsoft.netismaker.service.applog.AppLogStreamService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 배포된 앱 컨테이너 로그 구독 (스펙 2026-11-03 §4). 권한은 빌드 로그 스트림({@code TaskController#logsStream})과 같다 —
 * 등록자 또는 관리자, 인증은 {@code ?access_token=}(EventSource는 헤더를 못 쓴다).
 * <p>
 * {@link TaskController}에 넣지 않은 이유: 그 생성자를 쓰는 시험이 여럿이고(생성자에 서비스를 더하면 모두 고쳐야 한다), 같은 파일을
 * 건드리는 다른 작업과 겹치지 않게 하려는 것이다.
 * <p>
 * HTTP 상태는 401(토큰)·403·404(`getForView`)에만 쓰고 본문은 없다 — `GlobalExceptionHandler`의 JSON 본문을 싣지 않는다.
 * `produces=text/event-stream` + `Accept: text/event-stream`(브라우저 EventSource)에서 Map 본문이 406·500으로 바뀌지 않는지는
 * 확인된 바 없고, EventSource는 어차피 본문을 못 읽는다. 나머지 거절·종료는 200 안의 `end` 이벤트다({@link AppLogStreamService}).
 * <p>
 * 메서드에 {@code @Transactional}을 달지 않는다 — 스트림이 열려 있는 동안 DB 연결을 붙잡지 않으려는 것이다
 * ({@code getForView}가 자기 트랜잭션을 열고 닫는다).
 */
@RestController
@RequestMapping("/api/tasks")
@Profile("api")
public class TaskAppLogController {

    private final TaskService taskService;
    private final AppLogStreamService appLogs;

    public TaskAppLogController(TaskService taskService, AppLogStreamService appLogs) {
        this.taskService = taskService;
        this.appLogs = appLogs;
    }

    @GetMapping(value = "/{id}/app-logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> appLogsStream(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task task;
        try {
            task = taskService.getForView(id, userId, isAdmin); // 접근 권한 검증 — 없으면 404, 남의 작업이면 403
        } catch (TaskException e) {
            return ResponseEntity.status(e.getStatus()).build();
        }
        return ResponseEntity.ok(appLogs.subscribe(task));
    }
}
