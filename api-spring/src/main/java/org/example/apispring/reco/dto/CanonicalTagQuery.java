package org.example.apispring.reco.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * LLM ↔ 서버 간 계약 스키마.
 * - tags: 반드시 1개 이상
 * - keywords: 기본 exclude ["live","cover"]
 * - filters: 기본 allow_live=false, allow_cover=false
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CanonicalTagQuery {

    // ---- nested records (JSON shape 유지) ----
    public record Tag(String id) {}
    public record Keywords(List<String> include, List<String> exclude) {}
    public record Filters(boolean allow_live, boolean allow_cover) {}

    // ---- fields ----
    @NotNull
    private List<Tag> tags;

    private Keywords keywords; // 기본 exclude ["live","cover"]
    private Filters filters;   // 기본 false/false

    // ---- constructors ----
    public CanonicalTagQuery() {
        this.tags = List.of();
        this.keywords = new Keywords(List.of(), List.of("live", "cover"));
        this.filters  = new Filters(false, false);
    }

    public CanonicalTagQuery(List<Tag> tags) {
        this.tags = Objects.requireNonNullElse(tags, List.of());
        this.keywords = new Keywords(List.of(), List.of("live", "cover"));
        this.filters  = new Filters(false, false);
    }

    /** LlmConstraintParserService 등에서 사용: 세 필드를 모두 지정 */
    public CanonicalTagQuery(List<Tag> tags, Keywords keywords, Filters filters) {
        this.tags = Objects.requireNonNullElse(tags, List.of());
        this.keywords = (keywords != null) ? keywords : new Keywords(List.of(), List.of("live", "cover"));
        this.filters = (filters != null) ? filters : new Filters(false, false);
    }

    // ---- getters / setters ----
    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public Keywords getKeywords() { return keywords; }
    public void setKeywords(Keywords keywords) { this.keywords = keywords; }

    public Filters getFilters() { return filters; }
    public void setFilters(Filters filters) { this.filters = filters; }
}