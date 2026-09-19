package com.hamonsoft.netismaker.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 질문 첨부 오피스 문서 텍스트 추출 (스펙 2026-09-13 §5.2). 에이전트의 Read가 네이티브로 못 여는
 * docx/xlsx/pptx/hwp 계열만 대상 — PDF·이미지·텍스트는 대상 아님(Optional.empty).
 *
 * 계약: <b>예외는 절대 전파하지 않는다</b>(로그 후 empty → 원본만 저장). 결과는 200,000자에서 절단 —
 * 핸들러 상한(BodyContentHandler(MAX_CHARS))이라 파싱 도중 끊기며 메모리도 함께 상한된다.
 * 업로드 요청 스레드에서 동기 실행(파일당 20MB 상한, 저트래픽 내부 도구 전제).
 * AutoDetectParser는 무상태·스레드 세이프라 인스턴스 하나를 재사용한다.
 */
@Slf4j
@Component
@Profile("api")
public class TikaExtractionService {

    static final Set<String> OFFICE_EXTENSIONS = Set.of(
            "docx", "doc", "xlsx", "xls", "pptx", "ppt", "hwp", "hwpx");

    static final int MAX_CHARS = 200_000;
    static final String TRUNCATED_SUFFIX = "…(truncated)";

    private final AutoDetectParser parser = new AutoDetectParser();

    /**
     * @param path         디스크에 이미 쓰인 원본 파일
     * @param originalName 원본 파일명 — 확장자 판정 + Tika 타입 감지 힌트
     * @return 추출 텍스트(절단 적용). 비오피스/실패/blank면 empty.
     */
    public Optional<String> extractText(Path path, String originalName) {
        if (!isOfficeDocument(originalName)) return Optional.empty();
        // 핸들러 자체에 상한을 건다 — 20MB 업로드 캡은 압축된 입력만 막지, 압축 해제된 본문(zip bomb류)의
        // 메모리 팽창은 못 막는다. 상한 도달 시 Tika가 WriteLimitReachedException을 던지면 그때까지의
        // 텍스트만 취해 표식을 붙인다(파싱을 끝까지 돌리지 않는다).
        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
        try (InputStream in = Files.newInputStream(path)) {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
            parser.parse(in, handler, metadata, new ParseContext());
            String text = handler.toString();
            if (text == null || text.isBlank()) return Optional.empty();
            return Optional.of(cap(text.strip()));
        } catch (WriteLimitReachedException e) {
            String partial = handler.toString();
            if (partial == null || partial.isBlank()) return Optional.empty();
            return Optional.of(partial.strip() + TRUNCATED_SUFFIX);
        } catch (Exception | NoClassDefFoundError e) {
            // 손상 파일·미지원 변형·파서 내부 오류 전부 여기서 흡수 — 첨부 자체는 원본만으로 저장된다.
            log.warn("첨부 텍스트 추출 실패(원본만 저장): name={} path={} cause={}",
                    originalName, path, e.toString());
            return Optional.empty();
        }
    }

    static boolean isOfficeDocument(String originalName) {
        if (originalName == null) return false;
        int dot = originalName.lastIndexOf('.');
        if (dot < 0) return false;
        return OFFICE_EXTENSIONS.contains(originalName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 200,000자 초과 시 절단 + 표식. */
    static String cap(String text) {
        if (text.length() <= MAX_CHARS) return text;
        return text.substring(0, MAX_CHARS) + TRUNCATED_SUFFIX;
    }
}
