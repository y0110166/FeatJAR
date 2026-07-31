/*
 * Copyright (C) 2026 FeatJAR-Development-Team
 *
 * This file is part of FeatJAR-feature-model.
 *
 * feature-model is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License,
 * or (at your option) any later version.
 */
package de.featjar.feature.model.io.tikz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.featjar.feature.model.FeatureModel;
import de.featjar.feature.model.IFeature;
import de.featjar.feature.model.IFeatureTree;
import org.junit.jupiter.api.Test;

class TikzFeatureModelSerializerTest {

    @Test
    void serializesRootAndNestedGroupIndicesInDirectChildOrder() {
        FeatureModel model = new FeatureModel();
        IFeatureTree root = model.mutate().addFeatureTreeRoot(model.mutate().addFeature("root"));
        root.mutate().toAndGroup();
        addBelow(model, root, "f7", 0);
        IFeatureTree f10 = addBelow(model, root, "f10", 0);
        addBelow(model, root, "f13", 0);
        int rootOrGroup = root.mutate().addOrGroup();
        addBelow(model, root, "f14", rootOrGroup);
        addBelow(model, root, "f15", rootOrGroup);

        f10.mutate().toAlternativeGroup();
        addBelow(model, f10, "f11", 0);
        addBelow(model, f10, "f12", 0);

        String tex = new TikzFeatureModelSerializer().serialize(model);

        assertEquals(1, occurrences(tex, "\\begin{forest}"));
        assertEquals(1, occurrences(tex, "[f10,"));
        assertEquals(1, occurrences(tex, "[f13,"));
        assertTrue(tex.contains(",or={4}{5}{4}"));
        assertTrue(tex.contains(",alternative={1}{2}{1}"));

        int f10Start = tex.indexOf("[f10,");
        int f10End = matchingBracket(tex, f10Start);
        assertTrue(f10Start < tex.indexOf("[f11,") && tex.indexOf("[f11,") < f10End);
        assertTrue(f10Start < tex.indexOf("[f12,") && tex.indexOf("[f12,") < f10End);
    }

    private static IFeatureTree addBelow(FeatureModel model, IFeatureTree parent, String name, int groupID) {
        IFeature feature = model.mutate().addFeature(name);
        return parent.mutate().addFeatureBelow(feature, parent.getChildrenCount(), groupID);
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
