package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.Set;

/**
 * 공개 배포 호스트명의 DNS 레코드 수명주기. 구현: {@link CloudflareDnsRegistrar}.
 * 호출자는 {@link DnsRegisteringDeployTarget} 하나 — deploy 후 upsert, stop 후 delete, gc 때 listOwned로 고아 정리.
 */
public interface PublicDnsRegistrar {

    /** hostname 레코드를 이 워커의 터널로 만들거나 갱신한다 (멱등). */
    void upsert(String hostname) throws Exception;

    /** hostname 레코드 삭제. 없으면 no-op. */
    void delete(String hostname) throws Exception;

    /** 이 워커가 만든(소유 태그가 붙은) 레코드의 호스트명. */
    Set<String> listOwned() throws Exception;
}
