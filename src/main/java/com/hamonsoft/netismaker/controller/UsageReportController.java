package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.SubscriptionFeeRequest;
import com.hamonsoft.netismaker.dto.SubscriptionFeeView;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse;
import com.hamonsoft.netismaker.usage.UsageSubscriptionFeeService;
import com.hamonsoft.netismaker.usage.UsageSummaryService;
import com.hamonsoft.netismaker.usage.UsageSummaryService.CsvFile;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 사용량·비용 보고 관리자 API. 클래스 단위로 관리자 전용 — 기존 UsageController(인증 사용자 전원)와 분리했다.
 *   GET/POST /api/usage/subscription-fees
 *   GET /api/usage/summary?period=day|week|month&date=YYYY-MM-DD
 *   GET /api/usage/summary.csv?period=…&date=…
 */
@RestController
@RequestMapping("/api/usage")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Profile("api")
public class UsageReportController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final UsageSubscriptionFeeService fees;
    private final UsageSummaryService summaries;

    public UsageReportController(UsageSubscriptionFeeService fees, UsageSummaryService summaries) {
        this.fees = fees;
        this.summaries = summaries;
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

    @GetMapping("/summary")
    public UsageSummaryResponse summary(@RequestParam(required = false) String period,
                                        @RequestParam(required = false) String date) {
        return summaries.summary(period, date);
    }

    @GetMapping("/summary.csv")
    public ResponseEntity<byte[]> summaryCsv(@RequestParam(required = false) String period,
                                             @RequestParam(required = false) String date) {
        CsvFile f = summaries.csv(period, date);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(f.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(TEXT_CSV)
                .body(f.body());
    }
}
