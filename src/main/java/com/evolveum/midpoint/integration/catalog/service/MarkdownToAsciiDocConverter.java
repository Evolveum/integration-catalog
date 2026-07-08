/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * Converts the Markdown tutorial text (authored in the publish form's Markdown editor and stored in
 * {@code integration_method.tutorial}) into AsciiDoc, so the download bundle can ship a proper
 * {@code tutorial.adoc}. The Markdown is parsed into a CommonMark AST which is then walked to emit the
 * equivalent AsciiDoc markup; constructs without a direct mapping (raw HTML) are passed through verbatim.
 */
public final class MarkdownToAsciiDocConverter {

    private static final Parser PARSER = Parser.builder().build();
    private static final Pattern THREE_PLUS_NEWLINES = Pattern.compile("\n{3,}");
    /** Link/image destinations with these schemes become AsciiDoc URL macros; everything else uses link:. */
    private static final Pattern URL_SCHEME = Pattern.compile("^(https?|ftp|mailto):", Pattern.CASE_INSENSITIVE);

    private MarkdownToAsciiDocConverter() {
    }

    /** Converts Markdown to AsciiDoc. Returns an empty string for {@code null}/blank input. */
    public static String convert(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = PARSER.parse(markdown);
        AsciiDocVisitor visitor = new AsciiDocVisitor();
        document.accept(visitor);
        String out = THREE_PLUS_NEWLINES.matcher(visitor.sb).replaceAll("\n\n").strip();
        return out.isEmpty() ? "" : out + "\n";
    }

    private static final class AsciiDocVisitor extends AbstractVisitor {

        private final StringBuilder sb = new StringBuilder();
        /** Nesting stack of list markers: '*' for bullet lists, '.' for ordered lists. */
        private final Deque<Character> listMarkers = new ArrayDeque<>();
        private boolean listItemJustStarted = false;

        @Override
        public void visit(Document document) {
            visitChildren(document);
        }

        @Override
        public void visit(Heading heading) {
            blankLineIfNeeded();
            sb.append("=".repeat(heading.getLevel())).append(' ');
            visitChildren(heading);
            sb.append("\n\n");
        }

        @Override
        public void visit(Paragraph paragraph) {
            if (listItemJustStarted) {
                listItemJustStarted = false;
                visitChildren(paragraph);
                newlineIfNeeded();
            } else {
                blankLineIfNeeded();
                visitChildren(paragraph);
                sb.append("\n\n");
            }
        }

        @Override
        public void visit(BulletList bulletList) {
            visitList('*', bulletList);
        }

        @Override
        public void visit(OrderedList orderedList) {
            visitList('.', orderedList);
        }

        private void visitList(char marker, Node list) {
            boolean topLevel = listMarkers.isEmpty();
            if (topLevel) {
                blankLineIfNeeded();
            } else {
                newlineIfNeeded();
            }
            listMarkers.push(marker);
            visitChildren(list);
            listMarkers.pop();
            if (topLevel) {
                sb.append('\n');
            }
        }

        @Override
        public void visit(ListItem listItem) {
            newlineIfNeeded();
            char marker = listMarkers.peek() == null ? '*' : listMarkers.peek();
            sb.append(String.valueOf(marker).repeat(listMarkers.size())).append(' ');
            listItemJustStarted = true;
            visitChildren(listItem);
            listItemJustStarted = false;
            newlineIfNeeded();
        }

        @Override
        public void visit(BlockQuote blockQuote) {
            blankLineIfNeeded();
            int start = sb.length();
            visitChildren(blockQuote);
            String inner = sb.substring(start).strip();
            sb.setLength(start);
            sb.append("____\n").append(inner).append("\n____\n\n");
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            blankLineIfNeeded();
            String info = codeBlock.getInfo();
            if (info != null && !info.isBlank()) {
                String lang = info.trim();
                int space = lang.indexOf(' ');
                if (space > 0) {
                    lang = lang.substring(0, space);
                }
                sb.append("[source,").append(lang).append("]\n");
            }
            sb.append("----\n").append(stripTrailingNewline(codeBlock.getLiteral())).append("\n----\n\n");
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            blankLineIfNeeded();
            sb.append("----\n").append(stripTrailingNewline(codeBlock.getLiteral())).append("\n----\n\n");
        }

        @Override
        public void visit(ThematicBreak thematicBreak) {
            blankLineIfNeeded();
            sb.append("'''\n\n");
        }

        @Override
        public void visit(Emphasis emphasis) {
            sb.append('_');
            visitChildren(emphasis);
            sb.append('_');
        }

        @Override
        public void visit(StrongEmphasis strongEmphasis) {
            sb.append('*');
            visitChildren(strongEmphasis);
            sb.append('*');
        }

        @Override
        public void visit(Code code) {
            sb.append('`').append(code.getLiteral()).append('`');
        }

        @Override
        public void visit(Link link) {
            String dest = link.getDestination() == null ? "" : link.getDestination();
            int start = sb.length();
            visitChildren(link);
            String text = sb.substring(start);
            sb.setLength(start);
            appendLinkMacro(dest, text);
        }

        @Override
        public void visit(Image image) {
            String dest = image.getDestination() == null ? "" : image.getDestination();
            int start = sb.length();
            visitChildren(image);
            String alt = sb.substring(start);
            sb.setLength(start);
            sb.append("image:").append(dest).append('[').append(alt).append(']');
        }

        private void appendLinkMacro(String dest, String text) {
            boolean isUrl = URL_SCHEME.matcher(dest).find();
            if (!isUrl) {
                sb.append("link:");
            }
            sb.append(dest).append('[').append(text).append(']');
        }

        @Override
        public void visit(Text text) {
            sb.append(text.getLiteral());
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            sb.append(" +\n");
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            sb.append('\n');
        }

        @Override
        public void visit(HtmlInline htmlInline) {
            sb.append(htmlInline.getLiteral());
        }

        @Override
        public void visit(HtmlBlock htmlBlock) {
            blankLineIfNeeded();
            sb.append("++++\n").append(stripTrailingNewline(htmlBlock.getLiteral())).append("\n++++\n\n");
        }

        private void blankLineIfNeeded() {
            if (sb.length() == 0 || endsWith("\n\n")) {
                return;
            }
            sb.append(endsWith("\n") ? "\n" : "\n\n");
        }

        private void newlineIfNeeded() {
            if (sb.length() > 0 && !endsWith("\n")) {
                sb.append('\n');
            }
        }

        private boolean endsWith(String suffix) {
            int len = sb.length();
            int slen = suffix.length();
            if (len < slen) {
                return false;
            }
            return sb.substring(len - slen).equals(suffix);
        }

        private static String stripTrailingNewline(String s) {
            if (s == null) {
                return "";
            }
            int end = s.length();
            while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
                end--;
            }
            return s.substring(0, end);
        }
    }
}
