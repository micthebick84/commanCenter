package com.hamonsoft.netismaker.entity;

/** 제안의 처리 상태. DB에는 name() 문자열로 저장한다. */
public enum SuggestionStatus { PENDING, ACCEPTED, DISMISSED, SUPERSEDED }
