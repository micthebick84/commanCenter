package com.hamonsoft.netismaker.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * TaskStatus enum ↔ DB VARCHAR(30) 한글 문자열.
 * @Enumerated(EnumType.STRING)을 못 쓰는 이유: Java enum 이름과 DB 값이 다름.
 */
@Converter(autoApply = true)
public class TaskStatusConverter implements AttributeConverter<TaskStatus, String> {

    @Override
    public String convertToDatabaseColumn(TaskStatus status) {
        return status == null ? null : status.dbValue();
    }

    @Override
    public TaskStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : TaskStatus.fromDb(dbValue);
    }
}
