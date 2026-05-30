package com.hamonsoft.netismaker.workerdaemon.deploy;

/**
 * DeployTarget이 빌드/실행/헬스체크 실패를 진단 로그와 함께 보고할 때 던진다.
 * 호출자(DeployService)가 {@link #getLog()}를 배포 실패 로그로 보존한다.
 */
public class DeployFailedException extends Exception {

    private final String log;

    public DeployFailedException(String message, String log) {
        super(message);
        this.log = log;
    }

    public String getLog() {
        return log;
    }
}
