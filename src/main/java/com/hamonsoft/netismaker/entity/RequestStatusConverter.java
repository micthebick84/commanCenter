package com.hamonsoft.netismaker.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** RequestStatus enum ↔ DB VARCHAR(16) 한글 문자열 (TaskStatusConverter와 같은 모양). */
@Converter(autoApply = true)
public class RequestStatusConverter implements AttributeConverter<RequestStatus, String> {

    @Override
    public String convertToDatabaseColumn(RequestStatus status) {
        return status == null ? null : status.dbValue();
    }

    @Override
    public RequestStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : RequestStatus.fromDb(dbValue);
    }
}
