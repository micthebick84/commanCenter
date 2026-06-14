package com.hamonsoft.netismaker.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * InterviewStatus enum ↔ DB VARCHAR(30) 한글 문자열.
 * TaskStatusConverter와 동일 패턴 — Java enum 이름과 DB 값이 달라 @Enumerated 못 씀.
 *
 * autoApply = false: TaskStatusConverter도 String 컬럼에 붙는 AttributeConverter라
 * autoApply로 켜면 두 컨버터가 모든 String/enum 컬럼에 경합한다. 따라서 자동 적용을 끄고
 * InterviewSession.status 필드에 명시적 @Convert(converter = InterviewStatusConverter.class)로만 적용.
 */
@Converter(autoApply = false)
public class InterviewStatusConverter implements AttributeConverter<InterviewStatus, String> {

    @Override
    public String convertToDatabaseColumn(InterviewStatus status) {
        return status == null ? null : status.dbValue();
    }

    @Override
    public InterviewStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : InterviewStatus.fromDb(dbValue);
    }
}
