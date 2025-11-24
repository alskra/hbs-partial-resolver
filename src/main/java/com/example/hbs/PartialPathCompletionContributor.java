package com.example.hbs;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.LookupEvent;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
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
 * and restores them on insert or when the lookup is cancelled.
 */
public class PartialPathCompletionContributor extends CompletionContributor {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^/]+)");
    private static final Key<QuoteRemovalInfo> REMOVED_QUOTES_KEY = Key.create("hbs.removedQuotes");
    private static final Key<LookupListener> LOOKUP_LISTENER_KEY = Key.create("hbs.lookup.listener");

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
                if (parentDir == null || !parentDir.exists()) return;

                boolean firstSegment = traverse.isEmpty();
                List<VirtualFile> candidates = getCandidates(parentDir, firstSegment, project);

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
            if (c == quoteChar) { closeQuotePos = search; break; }
            else if (c == '}' || Character.isWhitespace(c)) break;
            search++;
        }
        if (closeQuotePos == -1) return;

        final int openQuoteOffset = idx;
        final int closeQuoteOffset = closeQuotePos;

        // perform deletion inside write command (beforeCompletion allowed to write)
        WriteCommandAction.runWriteCommandAction(project, () -> {
            if (openQuoteOffset < 0 || closeQuoteOffset >= doc.getTextLength()) return;
            CharSequence cs = doc.getCharsSequence();
            if (cs.charAt(openQuoteOffset) != quoteChar || cs.charAt(closeQuoteOffset) != quoteChar) return;

            // delete closing first (higher index), then opening
            doc.deleteString(closeQuoteOffset, closeQuoteOffset + 1);
            doc.deleteString(openQuoteOffset, openQuoteOffset + 1);

            PsiDocumentManager.getInstance(project).commitDocument(doc);

            // mark that we removed quotes and which char
            editor.putUserData(REMOVED_QUOTES_KEY, new QuoteRemovalInfo(quoteChar));

            // attach lookup listener so we can restore quotes if lookup is cancelled
            attachLookupListenerForEditor(project, editor);
        });
    }

    // attach listener to active lookup (or shortly after if not yet created)
    private void attachLookupListenerForEditor(Project project, Editor editor) {
        Lookup active = LookupManager.getInstance(project).getActiveLookup(editor);
        if (active != null) {
            addListenerToLookup(active, editor, project);
            return;
        }
        // lookup might be created just after beforeCompletion — try again on EDT
        ApplicationManager.getApplication().invokeLater(() -> {
            Lookup later = LookupManager.getInstance(project).getActiveLookup(editor);
            if (later != null) addListenerToLookup(later, editor, project);
        });
    }

    private void addListenerToLookup(Lookup lookup, Editor editor, Project project) {
        if (lookup == null) return;
        // if already attached, skip
        LookupListener existing = editor.getUserData(LOOKUP_LISTENER_KEY);
        if (existing != null) return;

        LookupListener listener = new LookupListener() {
            @Override
            public void itemSelected(@NotNull LookupEvent event) {
                // selection — cleanup listener (InsertHandler will restore quotes)
                editor.putUserData(LOOKUP_LISTENER_KEY, null);
            }

            @Override
            public void lookupCanceled(@NotNull LookupEvent event) {
                // canceled -> restore quotes physically
                restoreQuotesPhysically(editor, project);
                editor.putUserData(LOOKUP_LISTENER_KEY, null);
            }
        };
        lookup.addLookupListener(listener);
        editor.putUserData(LOOKUP_LISTENER_KEY, listener);
    }

    // physically restore quotes around current path (used on cancel)
    private void restoreQuotesPhysically(Editor editor, Project project) {
        Document doc = editor.getDocument();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            String text = doc.getText();
            int caret = editor.getCaretModel().getOffset();

            int start = text.lastIndexOf("{{>", Math.max(0, caret - 1));
            if (start == -1) return;
            int idx = start + 3;
            while (idx < text.length() && Character.isWhitespace(text.charAt(idx))) idx++;
            if (idx >= text.length()) return;

            // if quotes already present, nothing to do
            char first = text.charAt(idx);
            boolean alreadyQuoted = (first == '"' || first == '\'');
            if (alreadyQuoted) return;

            // find end of path (stop at whitespace or '}' to avoid capturing params)
            int pathEnd = idx;
            while (pathEnd < text.length()) {
                char c = text.charAt(pathEnd);
                if (Character.isWhitespace(c) || c == '}') break;
                pathEnd++;
            }

            // preserve original quote char if available
            QuoteRemovalInfo info = editor.getUserData(REMOVED_QUOTES_KEY);
            char q = (info != null) ? info.quoteChar : '"';

            boolean opened = false;
            // insert opening
            doc.insertString(idx, String.valueOf(q));
            opened = true;
            // insert closing at pathEnd+1 (because we inserted one char before)
            int closePos = Math.min(doc.getTextLength(), pathEnd + 1);
            doc.insertString(closePos, String.valueOf(q));

            PsiDocumentManager.getInstance(project).commitDocument(doc);

            // move caret to correct position:
            // caret was the position before restore; after inserting opening quote before caret,
            // caret should shift by +1 if it was after insertion point.
            int newCaret = caret;
            if (caret >= idx) newCaret = caret + (opened ? 1 : 0);
            newCaret = Math.max(0, Math.min(newCaret, doc.getTextLength()));
            editor.getCaretModel().moveToOffset(newCaret);

            // clear removal marker
            editor.putUserData(REMOVED_QUOTES_KEY, null);
        });
    }

    @Nullable
    private VirtualFile resolveParentDir(Project project, List<String> traverse) {
        PathResolver resolver = new PathResolver(project);
        if (traverse.isEmpty()) {
            // первый сегмент — берем корни проекта
            return project.getBaseDir();
        }
        return resolver.resolveSegmentToVirtualFile(traverse, traverse.size() - 1, false);
    }

    private List<VirtualFile> getCandidates(VirtualFile parentDir, boolean firstSegment, Project project) {
        if (parentDir == null || !parentDir.exists()) return Collections.emptyList();

        List<VirtualFile> roots = new ArrayList<>();
        if (firstSegment) {
            roots.addAll(Arrays.asList(ProjectRootManager.getInstance(project).getContentSourceRoots()));
            roots.add(parentDir); // Project Root
        } else {
            roots.add(parentDir);
        }

        List<VirtualFile> result = new ArrayList<>();
        for (VirtualFile root : roots) {
            result.addAll(Arrays.stream(root.getChildren())
                    .filter(f -> f.isDirectory() || f.getName().endsWith(".hbs"))
                    .collect(Collectors.toList()));
        }
        return result;
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
                String text = fileText.substring(idx, extractEnd);
                return new TextRangeWithQuotes(idx, extractEnd, text, true);
            }
        }

        if (idx > end) return new TextRangeWithQuotes(idx, idx, "", hadQuotes);
        String text = fileText.substring(idx, end);
        return new TextRangeWithQuotes(idx, end, text, hadQuotes);
    }

    private static class TextRangeWithQuotes {
        final int startOffset, endOffset;
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

        RestoreQuotesInsertHandler(Project project) { this.project = project; }

        @Override
        public void handleInsert(@NotNull InsertionContext ctx, @NotNull LookupElement item) {
            final Editor editor = ctx.getEditor();
            // If we attached a lookup listener, remove it (selection path)
            LookupListener listener = editor.getUserData(LOOKUP_LISTENER_KEY);
            if (listener != null) {
                Lookup active = LookupManager.getInstance(project).getActiveLookup(editor);
                if (active != null) active.removeLookupListener(listener);
                editor.putUserData(LOOKUP_LISTENER_KEY, null);
            }

            final QuoteRemovalInfo info = editor.getUserData(REMOVED_QUOTES_KEY);
            if (info == null) return;

            final Document doc = ctx.getDocument();
            final int insertStart = ctx.getStartOffset();
            final int insertEnd = ctx.getTailOffset();

            WriteCommandAction.runWriteCommandAction(project, () -> {
                // get fresh text & chars
                String text = doc.getText();
                CharSequence cs = doc.getCharsSequence();

                // start of path (after "{{>")
                int pathStart = text.lastIndexOf("{{>", Math.max(0, insertStart - 1)) + 3;
                while (pathStart < doc.getTextLength() && Character.isWhitespace(cs.charAt(pathStart))) pathStart++;

                // determine path end: stop at first whitespace or '}' (so parameters are not included)
                int pathEnd = pathStart;
                while (pathEnd < doc.getTextLength()) {
                    char c = cs.charAt(pathEnd);
                    if (Character.isWhitespace(c) || c == '}') break;
                    pathEnd++;
                }

                boolean opened = false;
                // insert opening quote if missing
                if (pathStart >= doc.getTextLength() || cs.charAt(pathStart) != info.quoteChar) {
                    doc.insertString(pathStart, String.valueOf(info.quoteChar));
                    opened = true;
                    pathEnd += 1;
                }

                // refresh cs after potential insertion
                cs = doc.getCharsSequence();

                // insert closing quote if missing at pathEnd
                if (pathEnd >= doc.getTextLength() || cs.charAt(pathEnd) != info.quoteChar) {
                    doc.insertString(pathEnd, String.valueOf(info.quoteChar));
                }

                // cursor immediately after inserted segment (account for inserted opening quote)
                int newCaret = insertEnd + (opened ? 1 : 0);
                newCaret = Math.max(0, Math.min(newCaret, doc.getTextLength()));
                editor.getCaretModel().moveToOffset(newCaret);

                PsiDocumentManager.getInstance(project).commitDocument(doc);
                editor.putUserData(REMOVED_QUOTES_KEY, null);
            });
        }
    }
}
