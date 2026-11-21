package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuestionRouteRequest(@NotBlank @Size(max = 20000) String question) {}
