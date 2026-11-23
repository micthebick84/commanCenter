package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.QuestionSuggestion;
import com.hamonsoft.netismaker.entity.SuggestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionSuggestionRepository extends JpaRepository<QuestionSuggestion, Long> {

    List<QuestionSuggestion> findBySessionIdOrderByIdAsc(Long sessionId);

    List<QuestionSuggestion> findBySessionIdAndStatusOrderByIdAsc(Long sessionId, SuggestionStatus status);
}
