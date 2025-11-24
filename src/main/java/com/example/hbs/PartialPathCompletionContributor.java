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
 * Completion contributor that removes quotes before completion
 * and restores them on insert or cancel, preserving cursor position.
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

                Editor editor = parameters.getEditor();
                Document doc = editor.getDocument();
                int offset = parameters.getOffset();
                String text = doc.getText();

                TextRangeWithQuotes range = getPathRangeAroundOffset(text, offset);
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

                VirtualFile parentDir = resolveParentDir(psiFile.getProject(), traverse);
                if (parentDir == null || !parentDir.exists()) return;

                boolean firstSegment = traverse.isEmpty();
                List<VirtualFile> candidates = getCandidates(parentDir, firstSegment, psiFile.getProject());

                Set<String> seen = new HashSet<>();
                for (VirtualFile child : candidates) {
                    String name = child.isDirectory() ? child.getName() : child.getNameWithoutExtension();
                    String key = child.getPath() + "::" + name;
                    if (name.contains(prefix) && seen.add(key)) {
                        LookupElementBuilder builder = LookupElementBuilder.create(name)
                                .withIcon(child.isDirectory() ? AllIcons.Nodes.Folder : child.getFileType().getIcon());
                        result.addElement(builder.withInsertHandler(new RestoreQuotesInsertHandler(psiFile.getProject(), range, offset)));
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

        int start = text.lastIndexOf("{{>", Math.max(0, caret - 1));
        if (start == -1) return;

        int idx = start + 3;
        while (idx < text.length() && Character.isWhitespace(text.charAt(idx))) idx++;
        if (idx >= text.length()) return;

        int pathEnd = idx;
        while (pathEnd < text.length()) {
            char c = text.charAt(pathEnd);
            if (Character.isWhitespace(c) || c == '}') break;
            pathEnd++;
        }
        if (caret > pathEnd) return;

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

        WriteCommandAction.runWriteCommandAction(project, () -> {
            CharSequence cs = doc.getCharsSequence();
            if (cs.charAt(openQuoteOffset) != quoteChar || cs.charAt(closeQuoteOffset) != quoteChar) return;

            doc.deleteString(closeQuoteOffset, closeQuoteOffset + 1);
            doc.deleteString(openQuoteOffset, openQuoteOffset + 1);

            PsiDocumentManager.getInstance(project).commitDocument(doc);

            editor.putUserData(REMOVED_QUOTES_KEY, new QuoteRemovalInfo(quoteChar, openQuoteOffset));
            attachLookupListenerForEditor(project, editor);
        });
    }

    private void attachLookupListenerForEditor(Project project, Editor editor) {
        Lookup active = LookupManager.getInstance(project).getActiveLookup(editor);
        if (active != null) {
            addListenerToLookup(active, editor, project);
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            Lookup later = LookupManager.getInstance(project).getActiveLookup(editor);
            if (later != null) addListenerToLookup(later, editor, project);
        });
    }

    private void addListenerToLookup(Lookup lookup, Editor editor, Project project) {
        if (lookup == null) return;
        LookupListener existing = editor.getUserData(LOOKUP_LISTENER_KEY);
        if (existing != null) return;

        LookupListener listener = new LookupListener() {
            @Override
            public void itemSelected(@NotNull LookupEvent event) {
                editor.putUserData(LOOKUP_LISTENER_KEY, null);
            }

            @Override
            public void lookupCanceled(@NotNull LookupEvent event) {
                restoreQuotesPhysically(editor, project);
                editor.putUserData(LOOKUP_LISTENER_KEY, null);
            }
        };
        lookup.addLookupListener(listener);
        editor.putUserData(LOOKUP_LISTENER_KEY, listener);
    }

    private void restoreQuotesPhysically(Editor editor, Project project) {
        Document doc = editor.getDocument();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            QuoteRemovalInfo info = editor.getUserData(REMOVED_QUOTES_KEY);
            if (info == null) return;

            int caret = editor.getCaretModel().getOffset();
            String text = doc.getText();

            int start = text.lastIndexOf("{{>", Math.max(0, caret - 1));
            if (start == -1) return;

            int idx = start + 3;
            while (idx < text.length() && Character.isWhitespace(text.charAt(idx))) idx++;
            if (idx >= text.length()) return;

            int pathEnd = idx;
            while (pathEnd < text.length()) {
                char c = text.charAt(pathEnd);
                if (Character.isWhitespace(c) || c == '}') break;
                pathEnd++;
            }

            doc.insertString(idx, String.valueOf(info.quoteChar));
            doc.insertString(pathEnd + 1, String.valueOf(info.quoteChar));

            PsiDocumentManager.getInstance(project).commitDocument(doc);

            // курсор после сегмента
            editor.getCaretModel().moveToOffset(pathEnd + 1);
            editor.putUserData(REMOVED_QUOTES_KEY, null);
        });
    }

    @Nullable
    private VirtualFile resolveParentDir(Project project, List<String> traverse) {
        PathResolver resolver = new PathResolver(project);
        if (traverse.isEmpty()) return project.getBaseDir();
        return resolver.resolveSegmentToVirtualFile(traverse, traverse.size() - 1, false);
    }

    private List<VirtualFile> getCandidates(VirtualFile parentDir, boolean firstSegment, Project project) {
        if (parentDir == null || !parentDir.exists()) return Collections.emptyList();

        List<VirtualFile> roots = new ArrayList<>();
        if (firstSegment) {
            roots.addAll(Arrays.asList(ProjectRootManager.getInstance(project).getContentSourceRoots()));
            roots.add(parentDir);
        } else roots.add(parentDir);

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

        int pathEnd = idx;
        while (pathEnd < fileText.length()) {
            char c = fileText.charAt(pathEnd);
            if (Character.isWhitespace(c) || c == '}') break;
            pathEnd++;
        }
        if (offset > pathEnd) return null;

        boolean hadQuotes = false;
        char quoteChar = 0;
        if (fileText.charAt(idx) == '"' || fileText.charAt(idx) == '\'') {
            hadQuotes = true;
            quoteChar = fileText.charAt(idx);
            idx++;
        }

        String text = fileText.substring(idx, offset);
        return new TextRangeWithQuotes(idx, offset, text, hadQuotes);
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
        final int originalOffset;
        QuoteRemovalInfo(char quoteChar, int originalOffset) {
            this.quoteChar = quoteChar;
            this.originalOffset = originalOffset;
        }
    }

    private static class RestoreQuotesInsertHandler implements InsertHandler<LookupElement> {
        private final Project project;
        private final TextRangeWithQuotes range;
        private final int caretOffset;

        RestoreQuotesInsertHandler(Project project, TextRangeWithQuotes range, int caretOffset) {
            this.project = project;
            this.range = range;
            this.caretOffset = caretOffset;
        }

        @Override
        public void handleInsert(@NotNull InsertionContext ctx, @NotNull LookupElement item) {
            final Editor editor = ctx.getEditor();
            LookupListener listener = editor.getUserData(LOOKUP_LISTENER_KEY);
            if (listener != null) {
                Lookup active = LookupManager.getInstance(project).getActiveLookup(editor);
                if (active != null) active.removeLookupListener(listener);
                editor.putUserData(LOOKUP_LISTENER_KEY, null);
            }

            final QuoteRemovalInfo info = editor.getUserData(REMOVED_QUOTES_KEY);
            if (info == null) return;

            final Document doc = ctx.getDocument();
            WriteCommandAction.runWriteCommandAction(project, () -> {
                String text = doc.getText();
                CharSequence cs = doc.getCharsSequence();

                int pathStart = text.lastIndexOf("{{>", Math.max(0, caretOffset - 1)) + 3;
                while (pathStart < doc.getTextLength() && Character.isWhitespace(cs.charAt(pathStart))) pathStart++;

                int pathEnd = pathStart;
                while (pathEnd < doc.getTextLength()) {
                    char c = cs.charAt(pathEnd);
                    if (Character.isWhitespace(c) || c == '}') break;
                    pathEnd++;
                }

                boolean opened = false;
                if (pathStart >= doc.getTextLength() || cs.charAt(pathStart) != info.quoteChar) {
                    doc.insertString(pathStart, String.valueOf(info.quoteChar));
                    opened = true;
                    pathEnd += 1;
                }

                cs = doc.getCharsSequence();
                if (pathEnd >= doc.getTextLength() || cs.charAt(pathEnd) != info.quoteChar) {
                    doc.insertString(pathEnd, String.valueOf(info.quoteChar));
                }

                // курсор строго после вставленного сегмента
                editor.getCaretModel().moveToOffset(pathEnd);

                PsiDocumentManager.getInstance(project).commitDocument(doc);
                editor.putUserData(REMOVED_QUOTES_KEY, null);
            });
        }
    }
}
