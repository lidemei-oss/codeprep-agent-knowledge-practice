package com.lm.codeprep.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps a learning note's section and paragraph context together before embedding. */
@Component
public class StructuredDocumentChunker {
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(\\d+(?:\\.\\d+){1,4})[.、]?\\s+(.+)$");
    private static final Pattern SIMPLE_NUMBERED_HEADING = Pattern.compile("^\\d+[.、]\\s+[^。！？!?]+$");
    private static final Pattern CHINESE_HEADING = Pattern.compile("^第[一二三四五六七八九十百千万零〇\\d]+([章节篇])\\s*[：:、.．]?\\s*(.+)$");
    private static final Pattern CHINESE_NUMBERED_HEADING = Pattern.compile("^[一二三四五六七八九十]+[、.]\\s*.+$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.、)]\\s+).+");
    private static final List<String> BREAKS = List.of("\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", "；", "; ", "，", ", ", " ");

    private final int maxTokens;
    private final int minTokens;
    private final int overlapTokens;

    public StructuredDocumentChunker(@Value("${codeprep.rag.chunk-max-tokens:700}") int maxTokens,
                                     @Value("${codeprep.rag.chunk-min-tokens:160}") int minTokens,
                                     @Value("${codeprep.rag.chunk-overlap-tokens:80}") int overlapTokens) {
        if (maxTokens < 100 || minTokens < 1 || minTokens > maxTokens || overlapTokens < 0 || overlapTokens >= maxTokens / 2) {
            throw new IllegalArgumentException("切块 Token 参数无效");
        }
        this.maxTokens = maxTokens;
        this.minTokens = minTokens;
        this.overlapTokens = overlapTokens;
    }

    public List<String> split(String text, String documentTitle) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u00a0', ' ').trim();
        if (normalized.isBlank()) return List.of("（文档没有可解析的正文）");

        List<Block> blocks = parseBlocks(normalized);
        List<String> result = new ArrayList<>();
        int index = 0;
        while (index < blocks.size()) {
            String section = blocks.get(index).section();
            List<Block> sectionBlocks = new ArrayList<>();
            while (index < blocks.size() && blocks.get(index).section().equals(section)) {
                sectionBlocks.add(blocks.get(index++));
            }
            packSection(sectionBlocks, prefix(documentTitle, section), result);
        }
        return List.copyOf(result);
    }

    /** A conservative local estimate; Qwen's exact tokenizer is not available through its embedding API. */
    static int estimatedTokens(String text) {
        int tokens = 0;
        int latinRun = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                tokens += (latinRun + 2) / 3;
                latinRun = 0;
            } else if (cp < 128 && Character.isLetterOrDigit(cp)) {
                latinRun++;
            } else {
                tokens += (latinRun + 2) / 3;
                latinRun = 0;
                tokens++;
            }
        }
        return tokens + (latinRun + 2) / 3;
    }

    private String prefix(String title, String section) {
        String safeTitle = title == null ? "" : title.strip();
        safeTitle = clipStart(safeTitle, Math.min(40, maxTokens / 6));
        String prefix = safeTitle.isEmpty() ? "" : "文档：" + safeTitle + "\n";
        if (!section.isEmpty()) prefix += "章节：" + clipEnd(section, Math.min(80, maxTokens / 4)) + "\n";
        return prefix;
    }

    private String clipStart(String text, int limit) {
        if (estimatedTokens(text) <= limit) return text;
        int end = 0;
        while (end < text.length()) {
            int next = end + Character.charCount(text.codePointAt(end));
            if (estimatedTokens(text.substring(0, next)) >= limit) break;
            end = next;
        }
        return text.substring(0, end).strip() + "…";
    }

    private String clipEnd(String text, int limit) {
        if (estimatedTokens(text) <= limit) return text;
        int from = text.length();
        while (from > 0) {
            int previous = text.offsetByCodePoints(from, -1);
            if (estimatedTokens(text.substring(previous)) >= limit) break;
            from = previous;
        }
        return "…" + text.substring(from).strip();
    }

    private void packSection(List<Block> blocks, String prefix, List<String> result) {
        int bodyLimit = maxTokens - estimatedTokens(prefix);
        if (bodyLimit < 40) throw new IllegalArgumentException("标题路径超过切块 Token 预算");
        int sectionStart = result.size();
        List<Block> pieces = new ArrayList<>();
        for (Block block : blocks) {
            if (block.code()) {
                pieces.addAll(splitCode(block, bodyLimit));
            } else {
                for (String part : splitLong(block.text(), bodyLimit)) pieces.add(new Block(block.section(), part, false));
            }
        }

        StringBuilder current = new StringBuilder();
        boolean lastWasCode = false;
        for (Block piece : pieces) {
            String part = piece.text().strip();
            if (part.isEmpty()) continue;
            String separator = current.isEmpty() ? "" : "\n\n";
            if (!current.isEmpty() && estimatedTokens(current + separator + part) > bodyLimit) {
                result.add(prefix + current);
                String overlap = lastWasCode ? "" : tail(current.toString(), Math.min(overlapTokens, bodyLimit / 3));
                current.setLength(0);
                if (!overlap.isBlank() && estimatedTokens(overlap + "\n\n" + part) <= bodyLimit) {
                    current.append(overlap).append("\n\n");
                }
            }
            current.append(part);
            lastWasCode = piece.code();
        }
        if (!current.isEmpty()) {
            String finalChunk = prefix + current;
            // Adjacent short paragraphs have already been coalesced within the same section.
            if (estimatedTokens(finalChunk) < minTokens && result.size() > sectionStart
                    && estimatedTokens(result.get(result.size() - 1) + "\n\n" + current) <= maxTokens) {
                int last = result.size() - 1;
                result.set(last, result.get(last) + "\n\n" + current);
            } else {
                result.add(finalChunk);
            }
        }
    }

    private List<Block> splitCode(Block block, int limit) {
        String text = block.text();
        int firstLine = text.indexOf('\n');
        if (estimatedTokens(text) <= limit || firstLine < 0) return List.of(block);
        String opening = text.substring(0, firstLine);
        int lastLine = text.lastIndexOf('\n');
        String closing = text.substring(lastLine + 1).strip();
        if (!closing.startsWith("```") && !closing.startsWith("~~~")) return splitPlainBlock(block, limit);
        String body = text.substring(firstLine + 1, lastLine);
        int bodyLimit = limit - estimatedTokens(opening + "\n" + closing + "\n");
        if (bodyLimit < 20) return splitPlainBlock(block, limit);
        List<Block> parts = new ArrayList<>();
        for (String piece : splitLong(body, bodyLimit)) {
            parts.add(new Block(block.section(), opening + "\n" + piece.strip() + "\n" + closing, true));
        }
        return parts;
    }

    private List<Block> splitPlainBlock(Block block, int limit) {
        List<Block> parts = new ArrayList<>();
        for (String piece : splitLong(block.text(), limit)) parts.add(new Block(block.section(), piece, true));
        return parts;
    }

    private List<String> splitLong(String text, int limit) {
        if (estimatedTokens(text) <= limit) return List.of(text);
        for (String separator : BREAKS) {
            List<String> parts = splitAfter(text, separator);
            if (parts.size() < 2) continue;
            List<String> result = new ArrayList<>();
            StringBuilder pending = new StringBuilder();
            for (String part : parts) {
                for (String small : splitLong(part, limit)) {
                    if (!pending.isEmpty() && estimatedTokens(pending + small) > limit) {
                        result.add(pending.toString());
                        pending.setLength(0);
                    }
                    pending.append(small);
                }
            }
            if (!pending.isEmpty()) result.add(pending.toString());
            return result;
        }
        return splitByCodePoint(text, limit);
    }

    private List<String> splitAfter(String text, String delimiter) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        int position;
        while ((position = text.indexOf(delimiter, from)) >= 0) {
            int end = position + delimiter.length();
            parts.add(text.substring(from, end));
            from = end;
        }
        if (from < text.length()) parts.add(text.substring(from));
        return parts;
    }

    private List<String> splitByCodePoint(String text, int limit) {
        List<String> result = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int end = from;
            do {
                end += Character.charCount(text.codePointAt(end));
            } while (end < text.length() && estimatedTokens(text.substring(from, end + Character.charCount(text.codePointAt(end)))) <= limit);
            result.add(text.substring(from, end));
            from = end;
        }
        return result;
    }

    private String tail(String text, int limit) {
        if (limit <= 0 || text.isBlank()) return "";
        int from = text.length();
        while (from > 0) {
            int previous = text.offsetByCodePoints(from, -1);
            if (estimatedTokens(text.substring(previous)) > limit) break;
            from = previous;
        }
        String suffix = text.substring(from).strip();
        int paragraph = suffix.indexOf("\n\n");
        if (paragraph >= 0 && paragraph + 2 < suffix.length()) suffix = suffix.substring(paragraph + 2);
        return suffix;
    }

    private List<Block> parseBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder code = new StringBuilder();
        String fence = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.strip();
            if (fence != null) {
                if (!code.isEmpty()) code.append('\n');
                code.append(line);
                if (stripped.startsWith(fence)) {
                    blocks.add(new Block(String.join(" > ", headings), code.toString(), true));
                    code.setLength(0);
                    fence = null;
                }
                continue;
            }
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                flushParagraph(blocks, headings, paragraph);
                fence = stripped.substring(0, 3);
                code.append(line);
                continue;
            }
            boolean standalone = (i == 0 || lines[i - 1].isBlank())
                    && i + 1 < lines.length && !lines[i + 1].isBlank();
            Heading heading = heading(line, standalone);
            if (heading != null) {
                flushParagraph(blocks, headings, paragraph);
                while (headings.size() >= heading.level()) headings.remove(headings.size() - 1);
                headings.add(heading.text());
                continue;
            }
            if (stripped.isEmpty()) {
                flushParagraph(blocks, headings, paragraph);
            } else if (LIST_ITEM.matcher(line).matches()) {
                flushParagraph(blocks, headings, paragraph);
                blocks.add(new Block(String.join(" > ", headings), stripped, false));
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flushParagraph(blocks, headings, paragraph);
        if (!code.isEmpty()) blocks.add(new Block(String.join(" > ", headings), code.toString(), true));
        return blocks;
    }

    private void flushParagraph(List<Block> blocks, List<String> headings, StringBuilder paragraph) {
        String value = paragraph.toString().strip();
        if (!value.isEmpty()) blocks.add(new Block(String.join(" > ", headings), value, false));
        paragraph.setLength(0);
    }

    private Heading heading(String line, boolean standalone) {
        String stripped = line.strip();
        Matcher markdown = MARKDOWN_HEADING.matcher(stripped);
        if (markdown.matches()) return new Heading(markdown.group(1).length(), markdown.group(2).strip());
        if (stripped.length() > 100) return null;
        Matcher chinese = CHINESE_HEADING.matcher(stripped);
        if (chinese.matches()) return new Heading("节".equals(chinese.group(1)) ? 2 : 1, stripped);
        Matcher numbered = NUMBERED_HEADING.matcher(stripped);
        if (numbered.matches()) return new Heading(Math.min(6, numbered.group(1).split("\\.").length), stripped);
        if (standalone && (SIMPLE_NUMBERED_HEADING.matcher(stripped).matches()
                || CHINESE_NUMBERED_HEADING.matcher(stripped).matches())) return new Heading(1, stripped);
        if (standalone && (stripped.endsWith("？") || stripped.endsWith("?"))) return new Heading(2, stripped);
        return null;
    }

    private record Heading(int level, String text) { }
    private record Block(String section, String text, boolean code) { }
}
