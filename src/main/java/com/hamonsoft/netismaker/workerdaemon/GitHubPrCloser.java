package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** gh CLI로 PR 병합·브랜치 삭제. GH_TOKEN 전달은 GitHubPrCreator와 같다. */
@Slf4j
public class GitHubPrCloser implements MergeRequestCloser {

    /** gh 인자(맨 앞 "gh" 제외) 실행 — 테스트 seam. stdout은 stderr 병합. */
    interface Gh {
        ProcessRunner.Result run(List<String> args) throws IOException, InterruptedException;
    }

    private static final long GH_TIMEOUT_SECONDS = 120;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Gh gh;

    public GitHubPrCloser(String githubPat) {
        this(args -> {
            List<String> cmd = new ArrayList<>();
            cmd.add("gh");
            cmd.addAll(args);
            Map<String, String> env = (githubPat == null || githubPat.isBlank()) ? Map.of() : Map.of("GH_TOKEN", githubPat);
            return ProcessRunner.run(null, cmd, env, GH_TIMEOUT_SECONDS);
        });
    }

    GitHubPrCloser(Gh gh) {
        this.gh = gh;
    }

    @Override
    public boolean supports(RepoRef ref) {
        return !ref.isGitlab();
    }

    @Override
    public String merge(RepoRef ref, int number, String headSha, String method)
            throws CloseRefusedException, IOException, InterruptedException {
        // Validate method
        if (!("merge".equals(method) || "squash".equals(method) || "rebase".equals(method))) {
            throw new IllegalArgumentException("method must be one of: merge, squash, rebase");
        }
        String n = String.valueOf(number);
        JsonNode pr = view(ref, n);
        String state = pr.path("state").asText("");
        if ("MERGED".equals(state)) {
            log.info("PR #{} 이미 병합됨 — 성공 처리", number);
            return mergeOid(pr);
        }
        if ("CLOSED".equals(state)) {
            throw new CloseRefusedException("PR #" + number + "이 병합 없이 닫혀 있습니다 — 다시 열거나 직접 처리한 뒤 재시도하세요");
        }
        String head = pr.path("headRefOid").asText("");
        if (!head.equalsIgnoreCase(headSha)) {
            throw new CloseRefusedException("head 불일치: 확인한 커밋 " + shortSha(headSha) + " 이후 브랜치가 "
                    + shortSha(head) + "(으)로 바뀌었습니다 — 확인되지 않은 커밋이라 병합하지 않습니다");
        }
        String mss = pr.path("mergeStateStatus").asText("");
        if ("DIRTY".equals(mss)) {
            throw new CloseRefusedException("충돌: base 브랜치와 충돌합니다 — 충돌을 해결한 뒤 재시도하세요");
        }
        boolean wasDraft = pr.path("isDraft").asBoolean(false);
        if (wasDraft) {
            ProcessRunner.Result ready = gh.run(List.of("pr", "ready", n, "--repo", ref.path()));
            if (ready.exitCode() != 0) throw new IOException("draft 해제 실패: " + snippet(ready.stdout()));
        }
        String undoMsg = "";
        try {
            ProcessRunner.Result r = gh.run(List.of("pr", "merge", n, "--repo", ref.path(), "--" + method,
                    "--match-head-commit", headSha));
            if (r.exitCode() != 0) {
                if (wasDraft) undoMsg = undoReady(ref, n);
                throw new CloseRefusedException(classify(mss, r.stdout()) + undoMsg);
            }
        } catch (IOException | InterruptedException e) {
            if (wasDraft) undoMsg = undoReady(ref, n);
            throw e;
        }
        return mergeOid(view(ref, n));
    }

    @Override
    public void deleteBranch(RepoRef ref, String branch) throws IOException, InterruptedException {
        ProcessRunner.Result r = gh.run(List.of("api", "-X", "DELETE",
                "repos/" + ref.path() + "/git/refs/heads/" + branch));
        if (r.exitCode() == 0) return;
        String out = r.stdout() == null ? "" : r.stdout();
        if (out.contains("Reference does not exist")) {
            log.info("브랜치 이미 없음: {}", branch);
            return;
        }
        throw new IOException("브랜치 삭제 실패: " + snippet(out));
    }

    private JsonNode view(RepoRef ref, String n) throws IOException, InterruptedException {
        ProcessRunner.Result r = gh.run(List.of("pr", "view", n, "--repo", ref.path(), "--json",
                "state,isDraft,headRefOid,mergeStateStatus,mergeCommit"));
        if (r.exitCode() != 0) throw new IOException("gh pr view 실패: " + snippet(r.stdout()));
        return JSON.readTree(r.stdout());
    }

    private String undoReady(RepoRef ref, String n) throws CloseRefusedException {
        try {
            ProcessRunner.Result r = gh.run(List.of("pr", "ready", n, "--repo", ref.path(), "--undo"));
            if (r.exitCode() != 0) {
                String msg = r.stdout() == null ? "" : r.stdout();
                log.warn("draft 원복 실패: PR #{} {}", n, GitRemotes.mask(msg));
                return " (draft 원복 실패 — 수동으로 draft 전환 필요)";
            }
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("draft 원복 중단: PR #{}", n);
            return " (draft 원복 실패 — 수동으로 draft 전환 필요)";
        } catch (IOException e) {
            String msg = e.getMessage();
            log.warn("draft 원복 실패: PR #{} {}", n, GitRemotes.mask(msg == null ? "" : msg));
            return " (draft 원복 실패 — 수동으로 draft 전환 필요)";
        }
    }

    private static String mergeOid(JsonNode pr) {
        String oid = pr.path("mergeCommit").path("oid").asText(null);
        return (oid == null || oid.isBlank()) ? null : oid;
    }

    static String classify(String mergeStateStatus, String out) {
        String o = out == null ? "" : out.toLowerCase(Locale.ROOT);
        String kind;
        if (o.contains("head branch was modified") || o.contains("head commit")) kind = "head 불일치";
        else if ("BEHIND".equals(mergeStateStatus) || o.contains("not up to date")) kind = "base보다 뒤처짐 — 브랜치 업데이트 필요";
        else if ("BLOCKED".equals(mergeStateStatus) || o.contains("status check") || o.contains("checks")) kind = "CI 대기 중·실패 또는 필수 리뷰 미충족";
        else if (o.contains("permission") || o.contains("protected")) kind = "권한·보호 규칙";
        else kind = "병합 거부";
        return kind + ": " + snippet(out);
    }

    private static String snippet(String s) {
        String m = GitRemotes.mask(s == null ? "" : s.strip());
        return m.length() <= 300 ? m : m.substring(0, 300) + "…";
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }
}
