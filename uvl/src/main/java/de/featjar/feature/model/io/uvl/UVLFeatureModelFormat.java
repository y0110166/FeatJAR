/*
 * Copyright (C) 2026 FeatJAR-Development-Team
 *
 * This file is part of FeatJAR-uvl.
 *
 * uvl is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License,
 * or (at your option) any later version.
 *
 * uvl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with uvl. If not, see <https://www.gnu.org/licenses/>.
 *
 * See <https://github.com/FeatureIDE/FeatJAR-uvl> for further information.
 */
package de.featjar.feature.model.io.uvl;

import de.featjar.base.data.Problem;
import de.featjar.base.data.Result;
import de.featjar.base.io.input.AInputMapper;
import de.featjar.base.tree.Trees;
import de.featjar.feature.model.IConstraint;
import de.featjar.feature.model.IFeature;
import de.featjar.feature.model.IFeatureModel;
import de.featjar.feature.model.IFeatureTree;
import de.featjar.feature.model.io.IFeatureModelFormat;
import de.featjar.feature.model.io.uvl.visitor.FeatureTreeToUVLFeatureModelVisitor;
import de.featjar.feature.model.io.uvl.visitor.FormulaToUVLConstraintVisitor;
import de.vill.model.FeatureModel;
import de.vill.model.constraint.Constraint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and writes feature models from and to UVL files.
 *
 * @author Sebastian Krieter
 * @author Andreas Gerasimow
 */
public class UVLFeatureModelFormat extends AUVLFormat<IFeatureModel> implements IFeatureModelFormat {

    public static final String ID = UVLFeatureModelFormat.class.getCanonicalName();
    private static final Set<String> UVL_TYPE_KEYWORDS = Set.of("Boolean", "String", "Integer", "Real");

    @Override
    public String getIdentifier() {
        return ID;
    }

    @Override
    public UVLFeatureModelFormat getInstance() {
        return this;
    }

    @Override
    public boolean supportsWrite() {
        return true;
    }

    @Override
    public boolean supportsParse() {
        return true;
    }

    @Override
    public Result<IFeatureModel> parse(AInputMapper inputMapper) {
        try {
            return UVLFeatureModelToFeatureTree.toFeatureModel(parseUVLModel(inputMapper));
        } catch (Exception e) {
            return Result.empty(e);
        }
    }

    @Override
    public Result<String> serialize(IFeatureModel fm) {
        List<Problem> problems = new ArrayList<>();
        try {
            if (fm.getRootFeatures().isEmpty()) {
                problems.add(new Problem("No root features exists.", Problem.Severity.ERROR));
                return Result.empty(problems);
            }

            IFeature rootFeature = fm.getRootFeatures().get(0);
            if (fm.getRootFeatures().size() > 1) {
                problems.add(new Problem(
                        "UVL supports only one root feature. If there are more than one root features in the model, the first one will be used.",
                        Problem.Severity.WARNING));
            }

            Result<IFeatureTree> featureTree = fm.getFeatureTree(rootFeature);
            problems.addAll(featureTree.getProblems());
            if (featureTree.isEmpty()) {
                return Result.empty(problems);
            }

            Result<FeatureModel> uvlModel =
                    Trees.traverse(featureTree.get(), new FeatureTreeToUVLFeatureModelVisitor());
            problems.addAll(uvlModel.getProblems());
            if (uvlModel.isEmpty()) {
                return Result.empty(problems);
            }
            FeatureModel model = uvlModel.get();

            for (IConstraint constraint : fm.getConstraints()) {
                Result<Constraint> uvlConstraint =
                        Trees.traverse(constraint.getFormula(), new FormulaToUVLConstraintVisitor());
                problems.addAll(uvlConstraint.getProblems());
                if (uvlConstraint.isEmpty()) {
                    return Result.empty(problems);
                }
                model.getOwnConstraints().add(uvlConstraint.get());
            }
            // fm-metamodel 1.1 does not quote feature names that collide with UVL type keywords.
            return Result.of(quoteTypeKeywordFeatureNames(model.toString(), fm), problems);
        } catch (Exception e) {
            return Result.empty(e);
        }
    }

    private static String quoteTypeKeywordFeatureNames(String serializedModel, IFeatureModel featureModel) {
        Set<String> conflictingFeatureNames = new LinkedHashSet<>();
        for (IFeature feature : featureModel.getFeatures()) {
            feature.getName().ifPresent(name -> {
                if (UVL_TYPE_KEYWORDS.contains(name)) {
                    conflictingFeatureNames.add(name);
                }
            });
        }
        if (conflictingFeatureNames.isEmpty()) {
            return serializedModel;
        }

        String lineSeparator = detectLineSeparator(serializedModel);
        String[] lines = serializedModel.split("\\R", -1);
        boolean inFeatures = false;
        boolean inConstraints = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmedLine = lines[i].trim();
            if (trimmedLine.equals("features")) {
                inFeatures = true;
                inConstraints = false;
                continue;
            }
            if (trimmedLine.equals("constraints")) {
                inFeatures = false;
                inConstraints = true;
                continue;
            }

            for (String featureName : conflictingFeatureNames) {
                if (inFeatures) {
                    lines[i] = quoteFeatureDeclaration(lines[i], featureName);
                } else if (inConstraints) {
                    lines[i] = quoteConstraintReferences(lines[i], featureName);
                }
            }
        }
        return String.join(lineSeparator, lines);
    }

    private static String quoteFeatureDeclaration(String line, String featureName) {
        int indentationLength = 0;
        while (indentationLength < line.length() && Character.isWhitespace(line.charAt(indentationLength))) {
            indentationLength++;
        }
        String indentation = line.substring(0, indentationLength);
        String declaration = line.substring(indentationLength);

        for (String typePrefix : List.of("", "Boolean ", "String ", "Integer ", "Real ")) {
            String unquotedPrefix = typePrefix + featureName;
            if (declaration.equals(unquotedPrefix)
                    || declaration.startsWith(unquotedPrefix + " cardinality ")
                    || declaration.startsWith(unquotedPrefix + " {")) {
                return indentation
                        + typePrefix
                        + '"'
                        + featureName
                        + '"'
                        + declaration.substring(unquotedPrefix.length());
            }
        }
        return line;
    }

    private static String quoteConstraintReferences(String line, String featureName) {
        Pattern unquotedReference =
                Pattern.compile("(?<![A-Za-z0-9_\\\"])(" + Pattern.quote(featureName) + ")(?![A-Za-z0-9_\\\"])");
        return unquotedReference.matcher(line).replaceAll("\\\"$1\\\"");
    }

    private static String detectLineSeparator(String text) {
        int newlineIndex = text.indexOf('\n');
        if (newlineIndex >= 0) {
            return newlineIndex > 0 && text.charAt(newlineIndex - 1) == '\r' ? "\r\n" : "\n";
        }
        return text.indexOf('\r') >= 0 ? "\r" : System.lineSeparator();
    }
}
