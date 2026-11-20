package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.RequestCommentKind;
import com.hamonsoft.netismaker.entity.RequestIssueType;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPriority;
import com.hamonsoft.netismaker.entity.RequestStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 요청 게시판 요청/응답 모델 (스펙 2026-10-19). */
public class RequestBoardDto {

    public record CreateRequest(
            @NotNull RequestKind kind,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10000) String content,
            @Size(max = 500) String repoHint,
            RequestIssueType issueType,
            RequestPriority priority,
            LocalDate dueDate,
            Long targetRepoCatalogId
    ) {
        /** 기존 4인자 호출용(개발 요청 칸 없음). */
        public CreateRequest(RequestKind kind, String title, String content, String repoHint) {
            this(kind, title, content, repoHint, null, null, null, null);
        }
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10000) String content,
            @Size(max = 500) String repoHint,
            RequestIssueType issueType,
            RequestPriority priority,
            LocalDate dueDate,
            Long targetRepoCatalogId,
            List<Long> removeAttachmentIds
    ) {
        /** 기존 3인자 호출용(개발 요청 칸 없음). */
        public UpdateRequest(String title, String content, String repoHint) {
            this(title, content, repoHint, null, null, null, null, null);
        }
    }

    public record CommentRequest(@NotBlank @Size(max = 5000) String body) {}

    public record StatusChangeRequest(
            @NotNull RequestStatus status,
            @Size(max = 2000) String reason
    ) {}

    /** 사이트 추가(REPO) 요청 처리: 등록할 GitLab 프로젝트 id(Task 6). */
    public record AddRepoRequest(@NotNull Long gitlabProjectId) {}

    /** 관리자 배지용 건수. attention = 접수이거나 관리자가 못 본 활동이 있는 요청. */
    public record Summary(long received, long attention) {}

    public record ListItem(
            Long id,
            RequestKind kind,
            String kindLabel,
            String title,
            RequestStatus status,
            String statusLabel,
            String requesterId,
            boolean unread,
            RequestIssueType issueType,
            String issueTypeLabel,
            RequestPriority priority,
            String priorityLabel,
            LocalDate dueDate,
            String targetRepoAlias,
            long attachmentCount,
            long commentCount,
            Long taskId,
            String taskStatusLabel,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    /** 첨부 한 건. expired = 저장된 파일이 더는 없음(보존 기간 지남 등). */
    public record AttachmentView(Long id, String name, long sizeBytes, boolean expired) {}

    public record CommentView(
            Long id,
            RequestCommentKind kind,
            String authorId,
            boolean authorAdmin,
            String body,
            OffsetDateTime createdAt
    ) {}

    public record Detail(
            Long id,
            RequestKind kind,
            String kindLabel,
            String title,
            String content,
            String repoHint,
            RequestStatus status,
            String statusLabel,
            String requesterId,
            Long repoCatalogId,
            String repoAlias,
            String repoGitUrl,
            boolean repoCatalogDeleted,
            RequestIssueType issueType,
            String issueTypeLabel,
            RequestPriority priority,
            String priorityLabel,
            LocalDate dueDate,
            Long targetRepoCatalogId,
            String targetRepoAlias,
            Long taskId,
            String taskStatusLabel,
            List<AttachmentView> attachments,
            boolean editable,
            boolean cancellable,
            boolean convertible,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<CommentView> comments
    ) {}

    /** GitLab 프로젝트 후보(Task 5). registered = 이미 카탈로그에 있는 레포. */
    public record RepoCandidate(
            long id,
            String name,
            String pathWithNamespace,
            String defaultBranch,
            String description,
            boolean registered
    ) {}
}
