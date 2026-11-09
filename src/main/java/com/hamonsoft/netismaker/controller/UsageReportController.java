package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.SubscriptionFeeRequest;
import com.hamonsoft.netismaker.dto.SubscriptionFeeView;
import com.hamonsoft.netismaker.usage.UsageSubscriptionFeeService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 사용량·비용 보고 관리자 API. 클래스 단위로 관리자 전용 — 기존 UsageController(인증 사용자 전원)와 분리했다.
 *   GET/POST /api/usage/subscription-fees
 */
@RestController
@RequestMapping("/api/usage")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Profile("api")
public class UsageReportController {

    private final UsageSubscriptionFeeService fees;

    public UsageReportController(UsageSubscriptionFeeService fees) {
        this.fees = fees;
    }

    @GetMapping("/subscription-fees")
    public List<SubscriptionFeeView> subscriptionFees() {
        return fees.list();
    }

    @PostMapping("/subscription-fees")
    public SubscriptionFeeView saveSubscriptionFee(@RequestBody @Valid SubscriptionFeeRequest req,
                                                   JwtAuthenticationToken auth) {
        return fees.save(req, AuthContext.requireUserId(auth));
    }
}
