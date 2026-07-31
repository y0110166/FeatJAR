/*
 * Copyright (C) 2026 FeatJAR-Development-Team
 *
 * This file is part of FeatJAR-uvl.
 *
 * uvl is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License,
 * or (at your option) any later version.
 */
package de.featjar.feature.model.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.featjar.base.data.Result;
import de.featjar.base.io.input.StringInputMapper;
import de.featjar.feature.model.IFeatureModel;
import de.featjar.feature.model.IFeatureTree;
import de.featjar.feature.model.io.tikz.TikzFeatureModelSerializer;
import de.featjar.feature.model.io.uvl.UVLFeatureModelFormat;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TikzFeatureModelSerializerTest {

    private static final String MODEL = """
            features
                Boolean root {name 'root', abstract true}
                    optional
                        Boolean f7 {name 'f7', abstract false}
                        Boolean f10 {name 'f10', abstract false}
                            alternative
                                Boolean f11 {name 'f11', abstract false}
                                Boolean f12 {name 'f12', abstract false}
                        Boolean f13 {name 'f13', abstract false}
                    or
                        Boolean f14 {name 'f14', abstract false}
                        Boolean f15 {name 'f15', abstract false}

            constraints
                (f7 <=> f10) & (f10 <=> f13)
            """;

    @Test
    void parsesAndSerializesNestedGroupsWithoutPromotingFeatures() {
        Result<IFeatureModel> parsed = new UVLFeatureModelFormat().parse(
                new StringInputMapper(MODEL, StandardCharsets.UTF_8, "uvl"));
        assertTrue(parsed.isPresent(), parsed.printProblems());

        IFeatureModel model = parsed.get();
        assertEquals(1, model.getRoots().size());
        IFeatureTree root = model.getRoots().get(0);
        assertEquals("root", root.getFeature().getName().get());
        assertEquals(List.of("f7", "f10", "f13", "f14", "f15"), childNames(root));

        IFeatureTree f10 = model.getFeature("f10").get().getFeatureTree().get();
        assertEquals("root", f10.getParent().get().getFeature().getName().get());
        assertEquals(List.of("f11", "f12"), childNames(f10));
        assertEquals("f10", model.getFeature("f11").get().getFeatureTree().get().getParent().get().getFeature().getName().get());
        assertEquals("f10", model.getFeature("f12").get().getFeatureTree().get().getParent().get().getFeature().getName().get());
        assertEquals("root", model.getFeature("f13").get().getFeatureTree().get().getParent().get().getFeature().getName().get());

        String tex = new TikzFeatureModelSerializer().serialize(model);
        assertEquals(1, occurrences(tex, "\\begin{forest}"));
        assertEquals(1, occurrences(tex, "[f10,"));
        assertEquals(1, occurrences(tex, "[f13,"));
        assertTrue(tex.contains(",or={4}{5}{4}"));
        assertTrue(tex.contains(",alternative={1}{2}{1}"));

        int f10Start = tex.indexOf("[f10,");
        int f10End = matchingBracket(tex, f10Start);
        int f11 = tex.indexOf("[f11,");
        int f12 = tex.indexOf("[f12,");
        assertTrue(f10Start < f11 && f11 < f10End);
        assertTrue(f10Start < f12 && f12 < f10End);
    }

    private static List<String> childNames(IFeatureTree tree) {
        return tree.getChildren().stream()
                .map(child -> child.getFeature().getName().get())
                .collect(Collectors.toList());
    }

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static int matchingBracket(String text, int openingBracket) {
        int depth = 0;
        for (int index = openingBracket; index < text.length(); index++) {
            if (text.charAt(index) == '[') {
                depth++;
            } else if (text.charAt(index) == ']' && --depth == 0) {
                return index;
            }
        }
        throw new AssertionError("Unclosed Forest node");
    }
}
