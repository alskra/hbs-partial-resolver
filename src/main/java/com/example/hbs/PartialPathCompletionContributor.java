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
 * (so user sees path without quotes), and restores quotes on insert.
 */
public class PartialPathCompletionContributor extends CompletionContributor {

    // Pattern splits segments: quoted or unquoted segment between slashes
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^/]+)");

    // Key to store info that quotes were removed in beforeCompletion
    private static final Key<QuoteRemovalInfo> REMOVED_QUOTES_KEY = Key.create("hbs.removedQuotes");

    public PartialPathCompletionContributor() {
        // provider for completion items themselves (after beforeCompletion may have removed quotes)
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

                // получаем текущий (возможно уже без кавычек) путь и диапазон
                TextRangeWithQuotes range = getPathRangeAroundOffset(document.getText(), offset);
                if (range == null) return;

                String rawPath = range.text; // это уже то, что между {{> и курсором (может быть без кавычек если beforeCompletion сработал)

                // Разбираем сегменты корректно
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

                // resolve parent dir using existing PathResolver
                VirtualFile parentDir = resolveParentDir(project, traverse);

                // build candidate list (from parent dir or from project roots)
                List<VirtualFile> roots = new ArrayList<>();
                roots.addAll(Arrays.asList(ProjectRootManager.getInstance(project).getContentSourceRoots()));
                roots.add(project.getBaseDir());

                List<VirtualFile> candidates = getCandidates(parentDir, roots);

                Set<String> seen = new HashSet<>();
                for (VirtualFile child : candidates) {
                    String name = child.isDirectory() ? child.getName() : child.getNameWithoutExtension();
                    String key = child.getPath() + "::" + name;
                    if (name.contains(prefix) && seen.add(key)) {
                        // show lookup element; show plain name in popup (quotes removed in editor)
                        LookupElementBuilder builder = LookupElementBuilder.create(name)
                                .withIcon(child.isDirectory() ? AllIcons.Nodes.Folder : child.getFileType().getIcon());

                        // Attach InsertHandler which will restore quotes if we removed them earlier
                        LookupElement le = builder.withInsertHandler(new RestoreQuotesInsertHandler(project));
                        result.addElement(le);
                    }
                }
            }
        });
    }

    // BEFORE completion: physically remove quotes if cursor is inside a quoted path
    @Override
    public void beforeCompletion(@NotNull CompletionInitializationContext ctx) {
        Editor editor = ctx.getEditor();
        Document doc = editor.getDocument();
        Project project = ctx.getProject();

        int caret = editor.getCaretModel().getOffset();
        String text = doc.getText();

        // find opening '{{>' before caret
        int open = text.lastIndexOf("{{>", Math.max(0, caret - 1));
        if (open == -1) return;

        int idx = open + 3;
        while (idx < text.length() && Character.isWhitespace(text.charAt(idx))) idx++;

        if (idx >= text.length()) return;

        char ch = text.charAt(idx);
        if (ch != '"' && ch != '\'') return; // no opening quote -> nothing to do

        final char quoteChar = ch;

        // find end of path (closing quote or whitespace or '}}')
        int search = idx + 1;
        int closeQuotePos = -1;
        while (search < text.length()) {
            char c = text.charAt(search);
            if (c == quoteChar) {
                closeQuotePos = search;
                break;
            } else if (c == '}' || Character.isWhitespace(c)) {
                // reached end without matching quote
                break;
            }
            search++;
        }

        if (closeQuotePos == -1) {
            // no matching closing quote found — abort safe
            return;
        }

        final int openQuoteOffset = idx;
        final int closeQuoteOffset = closeQuotePos;

        // perform deletion inside write command (beforeCompletion is allowed to write)
        // but still use WriteCommandAction to support undo/redo
        WriteCommandAction.runWriteCommandAction(project, () -> {
            // re-check bounds (document could change between reading and write)
            if (openQuoteOffset < 0 || closeQuoteOffset >= doc.getTextLength()) return;
            CharSequence cs = doc.getCharsSequence();
            if (cs.charAt(openQuoteOffset) != quoteChar) return;
            if (cs.charAt(closeQuoteOffset) != quoteChar) return;

            // delete closing first (higher index), then opening
            doc.deleteString(closeQuoteOffset, closeQuoteOffset + 1);
            doc.deleteString(openQuoteOffset, openQuoteOffset + 1);

            // commit changes so completion sees updated document
            PsiDocumentManager.getInstance(project).commitDocument(doc);

            // mark on the editor that we removed quotes (store quoteChar)
            // store minimal info: that quotes were removed and which char
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

    /**
     * Finds a "path range" around the offset: looks back for "{{>" and returns the substring between
     * after the opening quote (if present) and the next whitespace/}}/closing quote. Also reports if originally had quotes.
     * NOTE: this method is tolerant: it returns best-effort range whether or not quotes currently present.
     */
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
            idx++; // move inside quotes
        }

        int end = offset;
        // extend end to the next boundary (}} or whitespace) if cursor beyond that
        if (end > fileText.length()) end = fileText.length();

        // trim trailing whitespace from considered end
        while (end > idx && Character.isWhitespace(fileText.charAt(end - 1))) end--;

        // if had quotes, try to include until the closing quote if it exists
        if (hadQuotes) {
            int close = fileText.indexOf(quoteChar, idx);
            if (close != -1 && close >= idx) {
                // ensure we don't go beyond close quote for the text field
                int extractEnd = Math.min(end, close);
                String text = fileText.substring(idx, extractEnd);
                return new TextRangeWithQuotes(idx, extractEnd, text, true);
            }
        }

        // default: return substring idx..end (may be empty)
        if (idx > end) return new TextRangeWithQuotes(idx, idx, "", hadQuotes);
        String text = fileText.substring(idx, end);
        return new TextRangeWithQuotes(idx, end, text, hadQuotes);
    }

    private static class TextRangeWithQuotes {
        final int startOffset; // offset where the path (without surrounding quotes) starts in document
        final int endOffset;   // offset where the path (without surrounding quotes) ends (exclusive)
        final String text;     // text content between startOffset and endOffset
        final boolean hadQuotes;

        TextRangeWithQuotes(int startOffset, int endOffset, String text, boolean hadQuotes) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.text = text;
            this.hadQuotes = hadQuotes;
        }
    }

    // Minimal info stored when we remove quotes
    private static class QuoteRemovalInfo {
        final char quoteChar;
        QuoteRemovalInfo(char quoteChar) { this.quoteChar = quoteChar; }
    }

    /**
     * InsertHandler that restores quotes if they were removed in beforeCompletion.
     */
    private static class RestoreQuotesInsertHandler implements InsertHandler<LookupElement> {
        private final Project project;

        RestoreQuotesInsertHandler(Project project) {
            this.project = project;
        }

        @Override
        public void handleInsert(@NotNull InsertionContext ctx, @NotNull LookupElement item) {
            final Editor editor = ctx.getEditor();
            final QuoteRemovalInfo info = editor.getUserData(REMOVED_QUOTES_KEY);
            if (info == null) return; // nothing to restore

            final Document doc = ctx.getDocument();
            final int start = ctx.getStartOffset();
            final int tail = ctx.getTailOffset(); // tail after insertion

            // Use mutable holder so we can change it inside the write lambda
            final int[] tailRef = new int[]{ tail };

            // Do the restore in a write command so undo/redo works
            WriteCommandAction.runWriteCommandAction(project, () -> {
                // Safeguard: ensure we don't insert quotes where they already exist
                CharSequence cs = doc.getCharsSequence();

                boolean openPresent = start - 1 >= 0 && (cs.charAt(start - 1) == info.quoteChar);
                if (!openPresent) {
                    doc.insertString(start, String.valueOf(info.quoteChar));
                    // inserted one char before the inserted text -> tail shifts by +1
                    tailRef[0] = tailRef[0] + 1;
                }

                // recompute close presence using updated tailRef
                boolean closePresent = tailRef[0] < doc.getTextLength() && (doc.getCharsSequence().charAt(tailRef[0]) == info.quoteChar);
                if (!closePresent) {
                    int pos = Math.min(tailRef[0], doc.getTextLength());
                    doc.insertString(pos, String.valueOf(info.quoteChar));
                }

                // move caret to the end of inserted text inside quotes
                int newCaret;
                if (openPresent) {
                    newCaret = start + (tailRef[0] - start);
                } else {
                    newCaret = start + 1 + (tailRef[0] - start);
                }

                // Bound newCaret to document length
                newCaret = Math.max(0, Math.min(newCaret, doc.getTextLength()));
                ctx.getEditor().getCaretModel().moveToOffset(newCaret);
                PsiDocumentManager.getInstance(project).commitDocument(doc);

                // clear the marker so repeated inserts won't try to restore again
                editor.putUserData(REMOVED_QUOTES_KEY, null);
            });
        }
    }
}
