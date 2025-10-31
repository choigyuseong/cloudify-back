package org.example.apispring.reco.service.parser;

import org.example.apispring.reco.dto.CanonicalTagQuery;

public interface ConstraintParserService {
    CanonicalTagQuery parseToCanonicalTags(String text, String locale);
}
