package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 배포 때 워커가 적용할 덮어쓰기 (스펙 2026-11-24-deploy-db-connection-select §7.3).
 * env는 최종 환경변수 목록, files는 배포 산출물 안 설정 파일 항목 덮어쓰기, summary는 로그용 한 줄,
 * error가 있으면 배포를 진행하지 않는다. toString에는 값이 나가지 않는다(키·경로·개수만).
 */
public record DeployOverrides(List<EnvVar> env, List<FileOverride> files, String summary, String error) {

    public DeployOverrides {
        env = env == null ? List.of() : List.copyOf(env);
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static DeployOverrides failed(String error) {
        return new DeployOverrides(List.of(), List.of(), null, error);
    }

    @Override
    public String toString() {
        return "DeployOverrides[envKeys=" + env.stream().map(EnvVar::key).toList()
                + ", files=" + files + ", summary=" + summary + ", error=" + error + "]";
    }

    public record FileOverride(String path, LinkedHashMap<String, String> entries) {

        public FileOverride {
            entries = entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);
        }

        @Override
        public String toString() {
            return "FileOverride[path=" + path + ", entryKeys=" + entries.keySet() + "]";
        }
    }
}
