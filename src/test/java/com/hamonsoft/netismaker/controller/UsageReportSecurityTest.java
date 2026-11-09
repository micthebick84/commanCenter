package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.config.CorsConfig;
import com.hamonsoft.netismaker.config.SecurityConfig;
import com.hamonsoft.netismaker.config.WorkerApiKeyFilter;
import com.hamonsoft.netismaker.dto.ClaudeUsageResponse;
import com.hamonsoft.netismaker.dto.SubscriptionFeeRequest;
import com.hamonsoft.netismaker.dto.SubscriptionFeeView;
import com.hamonsoft.netismaker.service.ClaudeUsageService;
import com.hamonsoft.netismaker.service.UserProvisioner;
import com.hamonsoft.netismaker.usage.UsageSubscriptionFeeService;
import com.hamonsoft.netismaker.usage.UsageSummaryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {UsageReportController.class, UsageController.class})
@Import({SecurityConfig.class, CorsConfig.class, WorkerApiKeyFilter.class})
@AutoConfigureMockMvc
class UsageReportSecurityTest {

    private static final String URL = "/api/usage/subscription-fees";

    @Autowired private MockMvc mvc;

    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private UserProvisioner userProvisioner;
    @MockBean private UsageSubscriptionFeeService fees;
    @MockBean private ClaudeUsageService claudeUsage;
    @MockBean private UsageSummaryService summaries;

    private static RequestPostProcessor as(String username, String role) {
        return jwt().jwt(b -> b.claim("username", username).claim("authorities", List.of(role)))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private static String body(String date, String fee) {
        return "{\"effectiveFrom\":" + date + ",\"monthlyFee\":" + fee + "}";
    }

    @Test
    void 사용자는_구독료_API_403() throws Exception {
        mvc.perform(get(URL).with(as("u", "ROLE_USER"))).andExpect(status().isForbidden());
        mvc.perform(post(URL).with(as("u", "ROLE_USER")).contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"2026-10-01\"", "200")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fees);
    }

    @Test
    void 관리자는_저장한다() throws Exception {
        when(fees.save(any(), any())).thenReturn(new SubscriptionFeeView(1L, LocalDate.of(2026, 10, 1),
                new BigDecimal("200.00"), "USD", "boss", OffsetDateTime.parse("2026-11-01T10:00:00+09:00")));

        mvc.perform(post(URL).with(as("boss", "ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"2026-10-01\"", "200")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value("boss"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-01T10:00:00+09:00"));

        ArgumentCaptor<SubscriptionFeeRequest> req = ArgumentCaptor.forClass(SubscriptionFeeRequest.class);
        verify(fees).save(req.capture(), eq("boss"));
        assertThat(req.getValue().effectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(req.getValue().monthlyFee()).isEqualByComparingTo("200");
    }

    @Test
    void 관리자는_이력을_본다() throws Exception {
        when(fees.list()).thenReturn(List.of());
        mvc.perform(get(URL).with(as("boss", "ROLE_ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void 금액이_0이하이거나_소수_3자리면_400() throws Exception {
        for (String fee : new String[] {"0", "-5", "1.234"}) {
            mvc.perform(post(URL).with(as("boss", "ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                            .content(body("\"2026-10-01\"", fee)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.monthlyFee").exists());
        }
        verify(fees, never()).save(any(), any());
    }

    @Test
    void 적용시작일_형식이_틀리면_400() throws Exception {
        mvc.perform(post(URL).with(as("boss", "ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"2026-1-1\"", "200")))
                .andExpect(status().isBadRequest());
        verify(fees, never()).save(any(), any());
    }

    @Test
    void 기존_usage_claude는_사용자도_200() throws Exception {
        when(claudeUsage.snapshot()).thenReturn(new ClaudeUsageResponse(List.of()));
        mvc.perform(get("/api/usage/claude").with(as("u", "ROLE_USER"))).andExpect(status().isOk());
    }

    @Test
    void 사용자는_summary와_csv_403() throws Exception {
        mvc.perform(get("/api/usage/summary").param("period", "month").with(as("u", "ROLE_USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/usage/summary.csv").param("period", "month").with(as("u", "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(summaries);

        mvc.perform(get("/api/usage/summary").param("period", "week").param("date", "2026-11-03")
                        .with(as("boss", "ROLE_ADMIN")))
                .andExpect(status().isOk());
        verify(summaries).summary("week", "2026-11-03");

        when(summaries.csv(any(), any())).thenReturn(new UsageSummaryService.CsvFile(
                "claude-usage-month-2026-11-01.csv", ((char) 0xFEFF + "날짜\n").getBytes(StandardCharsets.UTF_8)));
        mvc.perform(get("/api/usage/summary.csv").param("period", "month").with(as("boss", "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", containsString("claude-usage-month-2026-11-01.csv")));
        verify(summaries).csv("month", null);
    }
}
