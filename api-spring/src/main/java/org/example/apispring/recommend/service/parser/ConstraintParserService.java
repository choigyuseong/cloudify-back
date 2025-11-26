package org.example.apispring.recommend.service.parser;

import org.example.apispring.recommend.dto.CanonicalTagQuery;

public interface ConstraintParserService {
    CanonicalTagQuery parseToCanonicalTags(String text, String locale);
}
