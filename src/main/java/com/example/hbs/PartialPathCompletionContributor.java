package com.example.hbs;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Completion contributor that physically removes surrounding quotes before completion
 * (so user sees path without quotes), and restores quotes on insert around the entire path.
 */
public class PartialPathCompletionContributor extends CompletionContributor {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^/]+)");

    private static final Key<QuoteRemovalInfo> REMOVED_QUOTES_KEY = Key.create("hbs.removedQuotes");

    public PartialPathCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters,
                                          @NotNull ProcessingContext context,
                                          @NotNull CompletionResultSet result) {

                PsiFile psiFile = parameters.getOriginalFile();
                if (!psiFile.getName().endsWith(".hbs")) return;

                Project project = psiFile.getProject();
                Editor editor = parameters.getEditor();
                Document document = editor.getDocument();
                int offset = parameters.getOffset();

                TextRangeWithQuotes range = getPathRangeAroundOffset(document.getText(), offset);
                if (range == null) return;

                String rawPath = range.text;

                List<String> segments = new ArrayList<>();
                Matcher m = SEGMENT_PATTERN.matcher(rawPath);
                while (m.find()) {
                    String seg = m.group(1);
                    if (seg == null) seg = m.group(2);
                    if (seg == null) seg = m.group(3);
                    if (seg != null) segments.add(seg);
                }

                boolean endsWithSlash = rawPath.endsWith("/");

                List<String> traverse;
                String prefix;
                if (endsWithSlash) {
                    traverse = segments;
                    prefix = "";
                } else if (!segments.isEmpty()) {
                    traverse = segments.subList(0, segments.size() - 1);
                    prefix = segments.get(segments.size() - 1);
                } else {
                    traverse = Collections.emptyList();
                    prefix = "";
                }

                VirtualFile parentDir = resolveParentDir(project, traverse);

                List<VirtualFile> roots = new ArrayList<>();
                roots.addAll(Arrays.asList(ProjectRootManager.getInstance(project).getContentSourceRoots()));
                roots.add(project.getBaseDir());

                List<VirtualFile> candidates = getCandidates(parentDir, roots);

                Set<String> seen = new HashSet<>();
                for (VirtualFile child : candidates) {
                    String name = child.isDirectory() ? child.getName() : child.getNameWithoutExtension();
                    String key = child.getPath() + "::" + name;
                    if (name.contains(prefix) && seen.add(key)) {
                        LookupElementBuilder builder = LookupElementBuilder.create(name)
                                .withIcon(child.isDirectory() ? AllIcons.Nodes.Folder : child.getFileType().getIcon());

                        LookupElement le = builder.withInsertHandler(new RestoreQuotesInsertHandler(project));
                        result.addElement(le);
                    }
                }
            }
        });
    }

    @Override
    public void beforeCompletion(@NotNull CompletionInitializationContext ctx) {
        Editor editor = ctx.getEditor();
        Document doc = editor.getDocument();
        Project project = ctx.getProject();

        int caret = editor.getCaretModel().getOffset();
        String text = doc.getText();

        int open = text.lastIndexOf("{{>", Math.max(0, caret - 1));
        if (open == -1) return;

        int idx = open + 3;
        while (idx < text.length() && Character.isWhitespace(text.charAt(idx))) idx++;

        if (idx >= text.length()) return;

        char ch = text.charAt(idx);
        if (ch != '"' && ch != '\'') return;

        final char quoteChar = ch;

        int search = idx + 1;
        int closeQuotePos = -1;
        while (search < text.length()) {
            char c = text.charAt(search);
            if (c == quoteChar) {
                closeQuotePos = search;
                break;
            } else if (c == '}' || Character.isWhitespace(c)) {
                break;
            }
            search++;
        }

        if (closeQuotePos == -1) return;

        final int openQuoteOffset = idx;
        final int closeQuoteOffset = closeQuotePos;

        WriteCommandAction.runWriteCommandAction(project, () -> {
            if (openQuoteOffset < 0 || closeQuoteOffset >= doc.getTextLength()) return;
            CharSequence cs = doc.getCharsSequence();
            if (cs.charAt(openQuoteOffset) != quoteChar || cs.charAt(closeQuoteOffset) != quoteChar) return;

            doc.deleteString(closeQuoteOffset, closeQuoteOffset + 1);
            doc.deleteString(openQuoteOffset, openQuoteOffset + 1);
            PsiDocumentManager.getInstance(project).commitDocument(doc);

            editor.putUserData(REMOVED_QUOTES_KEY, new QuoteRemovalInfo(quoteChar));
        });
    }

    @Nullable
    private VirtualFile resolveParentDir(Project project, List<String> traverse) {
        if (traverse.isEmpty()) return null;
        PathResolver resolver = new PathResolver(project);
        return resolver.resolveSegmentToVirtualFile(traverse, traverse.size() - 1, false);
    }

    private List<VirtualFile> getCandidates(VirtualFile parentDir, List<VirtualFile> roots) {
        if (parentDir != null) {
            return Arrays.stream(parentDir.getChildren())
                    .filter(f -> f.isDirectory() || f.getName().endsWith(".hbs"))
                    .collect(Collectors.toList());
        } else {
            return roots.stream()
                    .flatMap(root -> Arrays.stream(root.getChildren())
                            .filter(f -> f.isDirectory() || f.getName().endsWith(".hbs")))
                    .collect(Collectors.toList());
        }
    }

    @Nullable
    private TextRangeWithQuotes getPathRangeAroundOffset(String fileText, int offset) {
        int start = fileText.lastIndexOf("{{>", Math.max(0, offset - 1));
        if (start == -1) return null;

        int idx = start + 3;
        while (idx < fileText.length() && Character.isWhitespace(fileText.charAt(idx))) idx++;

        if (idx >= fileText.length()) return null;

        boolean hadQuotes = false;
        char quoteChar = 0;
        if (fileText.charAt(idx) == '"' || fileText.charAt(idx) == '\'') {
            hadQuotes = true;
            quoteChar = fileText.charAt(idx);
            idx++;
        }

        int end = offset;
        if (end > fileText.length()) end = fileText.length();
        while (end > idx && Character.isWhitespace(fileText.charAt(end - 1))) end--;

        if (hadQuotes) {
            int close = fileText.indexOf(quoteChar, idx);
            if (close != -1 && close >= idx) {
                int extractEnd = Math.min(end, close);
                return new TextRangeWithQuotes(idx, extractEnd, fileText.substring(idx, extractEnd), true);
            }
        }

        if (idx > end) return new TextRangeWithQuotes(idx, idx, "", hadQuotes);
        return new TextRangeWithQuotes(idx, end, fileText.substring(idx, end), hadQuotes);
    }

    private static class TextRangeWithQuotes {
        final int startOffset;
        final int endOffset;
        final String text;
        final boolean hadQuotes;

        TextRangeWithQuotes(int startOffset, int endOffset, String text, boolean hadQuotes) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.text = text;
            this.hadQuotes = hadQuotes;
        }
    }

    private static class QuoteRemovalInfo {
        final char quoteChar;
        QuoteRemovalInfo(char quoteChar) { this.quoteChar = quoteChar; }
    }

    private static class RestoreQuotesInsertHandler implements InsertHandler<LookupElement> {
        private final Project project;

        RestoreQuotesInsertHandler(Project project) {
            this.project = project;
        }

        @Override
        public void handleInsert(@NotNull InsertionContext ctx, @NotNull LookupElement item) {
            final Editor editor = ctx.getEditor();
            final QuoteRemovalInfo info = editor.getUserData(REMOVED_QUOTES_KEY);
            if (info == null) return;

            final Document doc = ctx.getDocument();

            WriteCommandAction.runWriteCommandAction(project, () -> {
                int caret = editor.getCaretModel().getOffset();
                String text = doc.getText();

                int start = text.lastIndexOf("{{>", Math.max(0, caret - 1));
                if (start == -1) return;
                start += 3;
                while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;

                int end = text.indexOf("}}", start);
                if (end == -1) end = text.length();

                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;

                CharSequence cs = doc.getCharsSequence();
                if (start >= doc.getTextLength() || cs.charAt(start) != info.quoteChar) {
                    doc.insertString(start, String.valueOf(info.quoteChar));
                    end += 1;
                }
                if (end >= doc.getTextLength() || cs.charAt(end) != info.quoteChar) {
                    doc.insertString(end, String.valueOf(info.quoteChar));
                }

                editor.getCaretModel().moveToOffset(end + 1);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                editor.putUserData(REMOVED_QUOTES_KEY, null);
            });
        }
    }
}
