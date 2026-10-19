package com.hamonsoft.netismaker.service;

/** 사내 GitLab 프로젝트 조회 클라이언트. 본문은 Task 4가 채운다. */
public class GitLabProjectClient {

    /** GitLab 프로젝트 요약(검색·등록 후보). */
    public record GitLabProject(long id, String name, String pathWithNamespace,
                                String defaultBranch, String description) {}
}
