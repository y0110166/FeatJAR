/*
 * Copyright (C) 2026 FeatJAR-Development-Team
 *
 * This file is part of FeatJAR-feature-model-assistance.
 *
 * feature-model-assistance is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License,
 * or (at your option) any later version.
 *
 * feature-model-assistance is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with feature-model-assistance. If not, see <https://www.gnu.org/licenses/>.
 */
package de.featjar.featureide;

import de.featjar.feature.model.FeatureTree.Group;
import de.featjar.feature.model.IFeatureModel;
import de.featjar.feature.model.IFeatureTree;
import de.featjar.feature.model.IFeatureTree.IMutableFeatureTree;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Conservatively transfers children of positively equivalent atomic-set
 * members to their designated representative.
 *
 * <p>AND children can share the representative's AND group because their
 * individual mandatory/optional cardinalities remain attached to the child
 * nodes. All other groups are copied as separate groups. In particular, OR
 * and alternative groups are never flattened because doing so would change
 * their individual cardinality obligations.</p>
 */
public final class AtomicSetTreeIntegrator {

    private AtomicSetTreeIntegrator() {}

    public static void integrate(IFeatureModel featureModel, Map<String, String> positiveAtomicRepresentatives) {
        List<Map.Entry<String, String>> replacements = new ArrayList<>(positiveAtomicRepresentatives.entrySet());
        replacements.sort(Comparator.comparingInt(
                        (Map.Entry<String, String> replacement) -> depth(featureModel, replacement.getKey()))
                .reversed()
                .thenComparing(Map.Entry::getKey));

        for (Map.Entry<String, String> replacement : replacements) {
            integrateMember(featureModel, replacement.getKey(), replacement.getValue());
        }
    }

    private static void integrateMember(IFeatureModel featureModel, String removedName, String representativeName) {
        List<? extends IFeatureTree> removedNodes = featureModel.getFeatureTreeNodes(removedName);
        List<? extends IFeatureTree> representativeNodes = featureModel.getFeatureTreeNodes(representativeName);
        if (removedNodes.size() != 1 || representativeNodes.size() != 1) {
            return;
        }

        IFeatureTree removed = removedNodes.get(0);
        IFeatureTree representative = representativeNodes.get(0);
        if (removed == representative || removed.getFeatureTreeRoot() != representative.getFeatureTreeRoot()) {
            return;
        }

        for (int groupID : removed.getChildrenGroupIDs()) {
            List<IFeatureTree> children = new ArrayList<>(removed.getChildren(groupID));
            if (children.isEmpty() || children.stream().anyMatch(child -> isAncestorOrSelf(child, representative))) {
                continue;
            }

            Group sourceGroup = removed.getChildrenGroup(groupID).orElseThrow();
            int targetGroupID = selectTargetGroup(representative, sourceGroup);
            for (IFeatureTree child : children) {
                removed.mutate().removeChild(child);
                representative.mutate().addChild(child);
                child.mutate().setParentGroupID(targetGroupID);
            }
        }
    }

    private static int selectTargetGroup(IFeatureTree representative, Group sourceGroup) {
        Group primaryGroup = representative.getChildrenGroup(0).orElseThrow();
        if (sourceGroup.isAnd() && primaryGroup.isAnd()) {
            return 0;
        }
        IMutableFeatureTree mutableRepresentative = representative.mutate();
        return mutableRepresentative.addCardinalityGroup(sourceGroup.getLowerBound(), sourceGroup.getUpperBound());
    }

    private static boolean isAncestorOrSelf(IFeatureTree possibleAncestor, IFeatureTree node) {
        IFeatureTree current = node;
        while (true) {
            if (current == possibleAncestor) {
                return true;
            }
            if (!current.hasParent()) {
                return false;
            }
            current = current.getParent().get();
        }
    }

    private static int depth(IFeatureModel featureModel, String featureName) {
        List<? extends IFeatureTree> nodes = featureModel.getFeatureTreeNodes(featureName);
        if (nodes.size() != 1) {
            return -1;
        }
        int depth = 0;
        IFeatureTree current = nodes.get(0);
        while (current.hasParent()) {
            depth++;
            current = current.getParent().get();
        }
        return depth;
    }
}
