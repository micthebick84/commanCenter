package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 인터뷰·질문 체크아웃 경로 규칙 (스펙 2026-10-22 §3.3). 체크아웃 루트(app.interview.work-root)를 한 곳에서 읽어
 * 경로 계산(InterviewService.claim)과 체크아웃 정리 잡이 같은 값을 쓰게 한다.
 *
 *   대상  {루트}/{localKey}/session-{세션 id}  — 첫 claim 때 work_dir에 저장하고 이후 resume마다 같은 값
 *   참조  {루트}/_refs/{세션 id}/{카탈로그 id}  — 저장하지 않고 claim마다 같은 규칙으로 계산
 *
 * 참조 폴더를 짧게 두는 이유는 Windows 경로 길이 한도(260자)다. `_refs`는 localKey의 첫 단계와 겹치지 않는다
 * (GitHub 사용자명은 '_'로 시작할 수 없고, GitLab은 `_gitlab`, 방어용 값은 `_`).
 * 구분자는 항상 '/' — 기존 work_dir 문자열 형식을 바꾸지 않는다.
 */
@Component
@Profile("api")
public class InterviewWorkPaths {

    private final String root;

    public InterviewWorkPaths(@Value("${app.interview.work-root:${user.home}/netis-maker/interviews}") String root) {
        this.root = root;
    }

    /**
     * 대상 레포 체크아웃 경로: {루트}/{localKey}/session-{id}.
     * localKey는 GitHub면 owner/repo(기존 세션의 work_dir와 동일 값), GitLab이면 _gitlab/<평탄화 경로>.
     * 단일 호스트/공유 FS 전제. 같은 세션은 resume마다 항상 같은 경로 → 동일 cwd 보장.
     */
    public String workDirFor(String githubRepo, String gitUrl, long sessionId) {
        String key = GitRemotes.localKey(RepoRef.fromSnapshot(githubRepo, gitUrl));
        if (!key.contains("/")) key = "_/" + key;   // 방어: 1단계 값(레거시 이상 데이터)
        return root + "/" + key + "/session-" + sessionId;
    }

    /** 참조 repo 폴더: {루트}/_refs/{세션 id}/{카탈로그 id}. 한 세션의 참조끼리는 카탈로그 id가 겹치지 않는다. */
    public String refDir(long sessionId, long catalogId) {
        return root + "/_refs/" + sessionId + "/" + catalogId;
    }

    /** 체크아웃 루트. 정리 잡이 스캔·삭제 범위를 이 안으로 제한할 때 쓴다. */
    public Path root() {
        return Path.of(root);
    }
}
