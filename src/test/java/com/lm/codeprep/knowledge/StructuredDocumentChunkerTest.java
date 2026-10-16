package com.lm.codeprep.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuredDocumentChunkerTest {
    private final StructuredDocumentChunker chunker = new StructuredDocumentChunker(180, 35, 25);

    @Test
    void keepsMarkdownSectionAndCodeBlockTogether() {
        String source = """
                # Java
                ## HashMap
                HashMap 在容量达到阈值后扩容。

                代码示例：

                ```java
                Map<String, Integer> map = new HashMap<>();
                map.put("key", 1);
                ```

                扩容后元素会重新分布。
                """;

        List<String> pieces = chunker.split(source, "集合学习笔记");

        assertEquals(1, pieces.size());
        assertTrue(pieces.get(0).contains("章节：Java > HashMap"));
        assertTrue(pieces.get(0).contains("```java\nMap<String, Integer> map = new HashMap<>();\nmap.put(\"key\", 1);\n```"));
        assertTrue(pieces.get(0).contains("扩容后元素会重新分布"));
    }

    @Test
    void doesNotMergeDifferentSectionsAndKeepsWithinBudget() {
        String source = "# Redis\n缓存击穿需要保护热点键。\n\n# MySQL\n索引有助于减少扫描。";
        List<String> pieces = chunker.split(source, "数据库知识点");

        assertEquals(2, pieces.size());
        assertTrue(pieces.get(0).contains("章节：Redis"));
        assertFalse(pieces.get(0).contains("MySQL"));
        assertTrue(pieces.get(1).contains("章节：MySQL"));
        assertTrue(pieces.stream().allMatch(piece -> StructuredDocumentChunker.estimatedTokens(piece) <= 180));
    }

    @Test
    void recursivelySplitsLongChineseParagraphWithoutLosingContent() {
        String sentence = "缓存穿透可以使用布隆过滤器处理。";
        String source = "# Redis\n" + sentence.repeat(100);
        List<String> pieces = chunker.split(source, "Redis 手册");

        assertTrue(pieces.size() > 2);
        assertTrue(pieces.stream().allMatch(piece -> StructuredDocumentChunker.estimatedTokens(piece) <= 180));
        assertTrue(pieces.stream().allMatch(piece -> piece.contains("章节：Redis")));
        assertTrue(pieces.stream().allMatch(piece -> !piece.isBlank()));
    }

    @Test
    void repeatsContextOnlyWithinTheSameSection() {
        StringBuilder source = new StringBuilder("# Redis\n");
        for (int i = 0; i < 30; i++) {
            source.append("条目").append(i).append("：连接池配置需要检查最大连接数和等待队列。\n\n");
        }
        List<String> pieces = chunker.split(source.toString(), "缓存笔记");

        assertTrue(pieces.size() > 1);
        String prefix = "文档：缓存笔记\n章节：Redis\n";
        String first = pieces.get(0).substring(prefix.length());
        String second = pieces.get(1).substring(prefix.length());
        boolean hasOverlap = false;
        for (int length = 12; length <= Math.min(first.length(), second.length()); length++) {
            if (first.endsWith(second.substring(0, length))) hasOverlap = true;
        }
        assertTrue(hasOverlap);
    }

    @Test
    void splitsOversizedCodeAndPreservesFences() {
        String code = "# Redis\n```java\n" + "System.out.println(\"cache\");\n".repeat(90) + "```";
        List<String> pieces = chunker.split(code, "代码笔记");

        assertTrue(pieces.size() > 1);
        assertTrue(pieces.stream().allMatch(piece -> piece.contains("```java") && piece.strip().endsWith("```")));
        assertTrue(pieces.stream().allMatch(piece -> StructuredDocumentChunker.estimatedTokens(piece) <= 180));
    }

    @Test
    void treatsStandaloneStudyQuestionAsSection() {
        String source = "一、缓存\n缓存问题包括穿透和击穿。\n\n什么是缓存穿透？\n请求不存在的键。";
        List<String> pieces = chunker.split(source, "Redis 问答");

        assertEquals(2, pieces.size());
        assertTrue(pieces.get(1).contains("章节：一、缓存 > 什么是缓存穿透？"));
    }
}
