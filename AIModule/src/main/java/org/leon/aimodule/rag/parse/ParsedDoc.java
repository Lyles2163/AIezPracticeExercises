package org.leon.aimodule.rag.parse;

import java.util.List;

public record ParsedDoc(String fileName, String title, List<ParsedPage> pages) {
    public ParsedDoc {
        pages = List.copyOf(pages);
    }
}
