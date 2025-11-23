package com.example.hbs;

import com.example.hbs.psi.HbPartial;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class PartialPathCompletionContributor extends CompletionContributor {

    public PartialPathCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters,
                                          @NotNull ProcessingContext context,
                                          @NotNull CompletionResultSet result) {

                PsiFile psiFile = parameters.getOriginalFile();
                if (!psiFile.getName().endsWith(".hbs")) return;

                int offset = parameters.getOffset();
                String fileText = psiFile.getText();
                if (offset > fileText.length()) return;

                String rawPath = getPartialPathAtOffset(fileText, offset);
                if (rawPath == null) return;

                boolean endsWithSlash = rawPath.endsWith("/");
                String[] segments = Arrays.stream(rawPath.split("/"))
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);

                List<String> traverse;
                String prefix;

                if (endsWithSlash) {
                    traverse = Arrays.asList(segments);
                    prefix = "";
                } else {
                    if (segments.length == 0) {
                        traverse = Collections.emptyList();
                        prefix = "";
                    } else {
                        traverse = Arrays.asList(Arrays.copyOf(segments, segments.length - 1));
                        prefix = segments[segments.length - 1];
                    }
                }

                Project project = psiFile.getProject();

                VirtualFile parentDir = ReadAction.compute(() -> {
                    if (traverse.isEmpty()) return null;

                    PathResolver resolver = new PathResolver(project);
                    return resolver.resolveSegmentToVirtualFile(traverse, traverse.size() - 1, false);
                });

                List<VirtualFile> roots = new ArrayList<>();
                roots.addAll(Arrays.asList(ProjectRootManager.getInstance(project).getContentSourceRoots()));
                roots.add(project.getBaseDir());

                List<VirtualFile> candidates = ReadAction.compute(() -> {
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
                });

                Set<String> seen = new HashSet<>();
                for (VirtualFile child : candidates) {
                    String name = child.isDirectory() ? child.getName() : child.getNameWithoutExtension();
                    String key = child.getPath() + "::" + name;
                    if (name.contains(prefix) && seen.add(key)) {
                        LookupElementBuilder builder = LookupElementBuilder.create(name)
                                .withIcon(child.isDirectory() ? AllIcons.Nodes.Folder : child.getFileType().getIcon());
                        result.addElement(builder);
                    }
                }
            }
        });
    }

    @Nullable
    private String getPartialPathAtOffset(String fileText, int offset) {
        int start = fileText.lastIndexOf("{{>", Math.max(0, offset - 1));
        if (start == -1) return null;

        int idx = start + 3;
        while (idx < fileText.length() && Character.isWhitespace(fileText.charAt(idx))) idx++;

        if (idx < fileText.length() && (fileText.charAt(idx) == '\'' || fileText.charAt(idx) == '"')) idx++;

        int end = offset;
        while (end > idx && Character.isWhitespace(fileText.charAt(end - 1))) end--;

        if (idx > end) return "";
        return fileText.substring(idx, end);
    }
}
