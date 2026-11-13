package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;

import java.io.IOException;
import java.util.List;

/**
 * 종료 단계의 PR/MR 병합과 head 브랜치 삭제 (스펙 2026-10-13 §4 ①·④). MergeRequestCreator와 같은 supports 분기.
 * 병합은 확인된 head SHA에 고정한다 — 그 뒤 브랜치에 커밋이 올라왔으면 거부.
 * 병합 불가(CI·충돌·head 불일치·권한)는 아무것도 바꾸지 않은 상태로 CloseRefusedException을 던진다
 * (draft를 해제했으면 되돌린 뒤).
 */
public interface MergeRequestCloser {

    /** 충돌 거부 문구 — 충돌을 직접 풀면 브랜치 끝이 바뀌어 재시도가 head 불일치가 되므로 '기준 브랜치 반영'으로 안내한다. */
    String CONFLICT_REFUSED = "충돌: 기준 브랜치와 충돌합니다 — 작업 상세의 '기준 브랜치 반영'으로 해결하세요";

    /** head 불일치 거부 문구 뒤에 붙는 다음 행동 안내. */
    String HEAD_MISMATCH_HINT = " — '종료 취소' 뒤 재배포(또는 '기준 브랜치 반영')로 새 커밋을 확인하고 다시 종료하세요";

    boolean supports(RepoRef ref);

    /** @return 병합 커밋 SHA(호스트가 알려주지 않으면 null). 이미 병합된 PR이면 그 커밋. */
    String merge(RepoRef ref, int number, String headSha, String method)
            throws CloseRefusedException, IOException, InterruptedException;

    /** 원격 head 브랜치 삭제. 이미 없으면 정상 종료. */
    void deleteBranch(RepoRef ref, String branch) throws IOException, InterruptedException;

    /**
     * 지우려는 브랜치를 target(base)으로 쓰는 열린 PR/MR을 newTarget으로 옮긴다 — 브랜치를 지우기 전에 부른다.
     * 그 브랜치를 기준으로 등록한 다른 작업의 MR이 없는 target을 가리켜 병합할 수 없게 되는 것을 막는다.
     * @return 옮긴 PR/MR 표기(예: "!2", "#5")
     */
    List<String> retargetDependents(RepoRef ref, String branch, String newTarget) throws IOException, InterruptedException;

    /** 병합 거부 — 메시지는 failure_reason에 그대로 들어가는 한국어 분류 사유. */
    class CloseRefusedException extends Exception {
        public CloseRefusedException(String message) {
            super(message);
        }
    }
}
