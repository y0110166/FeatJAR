package de.featjar.featureide.cli;

import de.featjar.analysis.sat4j.slice.CNFSlicer;
import de.featjar.analysis.sat4j.solver.SAT4JClauseList;
import de.featjar.analysis.sat4j.solver.SAT4JSolutionSolver;
import de.featjar.base.FeatJAR;
import de.featjar.base.cli.ACommand;
import de.featjar.base.cli.Option;
import de.featjar.base.cli.OptionList;
import de.featjar.base.cli.Options;
import de.featjar.base.computation.Computations;
import de.featjar.base.data.Pair;
import de.featjar.base.data.Range;
import de.featjar.base.data.Result;
import de.featjar.feature.model.*;
import de.featjar.feature.model.io.tikz.TikzFeatureModelFormat;
import de.featjar.feature.model.transformer.ComputeFormula;
import de.featjar.featureide.AtomicSetTreeIntegrator;
import de.featjar.featureide.FeatJARWrapper;
import de.featjar.featureide.FeatureModelAnalyzer;
import de.featjar.formula.assignment.BooleanAssignment;
import de.featjar.formula.assignment.BooleanAssignmentList;
import de.featjar.formula.assignment.Variables;
import de.featjar.formula.assignment.conversion.ComputeBooleanClauseList;
import de.featjar.formula.computation.ComputeCNFFormula;
import de.featjar.formula.computation.ComputeNNFFormula;
import de.featjar.formula.structure.IExpression;
import de.featjar.formula.structure.IFormula;
import de.featjar.formula.structure.connective.Or;
import de.featjar.formula.structure.predicate.Literal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 *
 * @author
 */
public class FeatureModelSimplifyerCommand extends ACommand {
    /*
    public static final Option<Path> INPUT_OPTION = Options.newOption("redundancies", Options.newChoiceOption())
            .setDescription("Path to input file(s)")
            .setValidator(Options.PathValidator);
     */

    public static final Option<Boolean> CORE_DEAD_OPTION = Options.newOption("coreDead", Options.BooleanParser, "true")
            .setDescription("Enable core and dead feature simplification (default: true)");

    public static final Option<Boolean> ATOMIC_SETS_OPTION = Options.newOption(
                    "atomic_sets", Options.BooleanParser, "true")
            .setDescription("Enable atomic sets simplification (default: true)");

    public static final Option<Path> OUTPUT_OPTION = Options.newOption(
                    "output", Options.PathParser, "../feature-model-assistance/src/main/resources/uvlModelsOutput/")
            .setDescription(
                    "Path to output directory or file (default: ../feature-model-assistance/src/main/resources/uvlModelsOutput/)");

    @Override
    public Optional<String> getDescription() {
        return Optional.of("Reduces the feature model by removing redundant features.");
    }

    @Override
    public Optional<String> getShortName() {
        return Optional.of("simplify-model");
    }

    private IFormula propositionalRepresentation(IFeatureModel featureModel) {
        return Computations.of(featureModel).map(ComputeFormula::new).compute();
    }

    private BooleanAssignmentList toCnf(IFormula propositionalRepresentation) {
        return Computations.of(propositionalRepresentation)
                .map(ComputeNNFFormula::new)
                .map(ComputeCNFFormula::new)
                .map(ComputeBooleanClauseList::new)
                .compute();
    }

    private Set<String> determineSurvivorSet(IExpression reducedPropositionalRepresentation) {
        return new LinkedHashSet<>(reducedPropositionalRepresentation.getVariableNames());
    }

    private BooleanAssignmentList existentialProjection(
            BooleanAssignmentList originalCnf, Set<String> survivingFeatureNames) {
        int[] variablesToKeep = survivingFeatureNames.stream()
                .map(originalCnf.getVariableMap()::get)
                .filter(Result::isPresent)
                .mapToInt(Result::get)
                .toArray();

        return Computations.of(originalCnf)
                .map(CNFSlicer::new)
                .set(CNFSlicer.VARIABLES_TO_KEEP, new Variables(variablesToKeep))
                .compute();
    }

    private IFeatureModel addMissingProjectedDependencies(
            IFeatureModel structuralModel,
            BooleanAssignmentList projectedCnf,
            BooleanAssignmentList structuralModelCnf) {
        SAT4JSolutionSolver solver = new SAT4JSolutionSolver(structuralModelCnf, false);
        SAT4JClauseList clauseList = solver.getClauseList();

        for (BooleanAssignment disjunction : projectedCnf.getAll()) {
            BooleanAssignment remappedDisjunction =
                    disjunction.remap(projectedCnf.getVariableMap(), structuralModelCnf.getVariableMap());
            if (solver.hasSolution(remappedDisjunction.negateInts()).orElseThrow()) {
                clauseList.add(remappedDisjunction);
                structuralModel.mutate().addConstraint(toFormulaClause(disjunction, projectedCnf));
            }
        }
        return structuralModel;
    }

    private void requireEntailment(
            BooleanAssignmentList premiseCnf, BooleanAssignmentList consequenceCnf, String failureMessage) {
        SAT4JSolutionSolver solver = new SAT4JSolutionSolver(premiseCnf, false);
        for (BooleanAssignment clause : consequenceCnf.getAll()) {
            BooleanAssignment remappedClause =
                    clause.remap(consequenceCnf.getVariableMap(), premiseCnf.getVariableMap());
            if (solver.hasSolution(remappedClause.negateInts()).orElseThrow()) {
                throw new IllegalStateException(failureMessage + ": " + clause);
            }
        }
    }

    private IFormula toFormulaClause(BooleanAssignment clause, BooleanAssignmentList clauseList) {
        List<IFormula> literals = new ArrayList<>();
        for (int literal : clause.get()) {
            String variableName =
                    clauseList.getVariableMap().get(Math.abs(literal)).orElseThrow();
            literals.add(new Literal(literal > 0, variableName));
        }
        return new Or(literals);
    }

    private void removeFeaturesNotIn(IFeatureModel featureModel, Set<String> survivingFeatureNames) {
        Predicate<IFeatureModelElement> featureFilter =
                IFeatureModelElementFilter.featuresByName(survivingFeatureNames);
        List<IFeatureTree> newRoots = new ArrayList<>(featureModel.getRoots().size());
        for (IFeatureTree rootFeature : featureModel.getRoots()) {
            PseudoFeatureTreeRoot pseudoRoot = new PseudoFeatureTreeRoot(featureModel);
            pseudoRoot.addChild(rootFeature);
            rootFeature
                    .postOrderStream()
                    .filter(node -> node.hasParent() && !featureFilter.test(node.getFeature()))
                    .forEach(this::removeNodeWithoutAddingStructure);
            newRoots.addAll(pseudoRoot.detach());
        }

        new ArrayList<>(featureModel.getRoots())
                .forEach(root -> featureModel.mutate().removeFeatureTreeRoot(root));
        newRoots.forEach(root -> featureModel.mutate().addFeatureTreeRoot(root));
    }

    /**
     * Removes one tree node while retaining only implications that are guaranteed
     * by the original ancestor relation. In particular, the removed node's parent
     * group and all promoted children are made optional. The projected CNF later
     * restores every stronger cardinality or child-group dependency that is still
     * semantically necessary.
     */
    private void removeNodeWithoutAddingStructure(IFeatureTree node) {
        IFeatureTree.IMutableFeatureTree mutableParent =
                node.getParent().orElseThrow().mutate();
        int childIndex = mutableParent.getChildIndex(node).orElseThrow();

        mutableParent.toCardinalityGroup(node.getParentGroupID(), Range.atLeast(0));
        List<IFeatureTree> children = new ArrayList<>(node.getChildren());
        mutableParent.removeChild(childIndex);

        if (!children.isEmpty()) {
            int optionalGroupID = mutableParent.addCardinalityGroup(0, Range.OPEN);
            for (IFeatureTree child : children) {
                node.mutate().removeChild(child);
                mutableParent.addChild(childIndex++, child);
                child.mutate().setParentGroupID(optionalGroupID);
            }
        }
    }

    private void removeConstraintsReferencingRemovedFeatures(
            IFeatureModel featureModel, Set<String> survivingFeatureNames) {
        Predicate<IFeatureModelElement> featureFilter =
                IFeatureModelElementFilter.featuresByName(survivingFeatureNames);
        Collection<IConstraint> constraints = new ArrayList<>(featureModel.getConstraints());
        for (IConstraint constraint : constraints) {
            if (!constraint.getReferencedFeatures().stream().allMatch(featureFilter)) {
                featureModel.mutate().removeConstraint(constraint);
            }
        }
    }

    private void removeFeatureDefinitionsNotIn(IFeatureModel featureModel, Set<String> survivingFeatureNames) {
        new ArrayList<>(featureModel.getFeatures())
                .stream()
                .filter(feature -> !survivingFeatureNames.contains(
                        feature.getName().orElse("")))
                .forEach(feature -> featureModel.mutate().removeFeature(feature));
    }

    private void printInfo(FeatureModelAnalyzer analyzer, IFeatureModel slicedModel) {
        Set<String> slicedFeatureNames = getSlicedFeatureNames(slicedModel);

        printRemovedFeatures(analyzer, slicedFeatureNames);
        printMergedFeatures(analyzer, slicedFeatureNames);
    }

    private Set<String> getSlicedFeatureNames(IFeatureModel slicedModel) {
        Set<String> slicedFeatureNames = new java.util.HashSet<>();
        for (IFeatureTree root : slicedModel.getRoots()) {
            root.preOrderStream()
                    .map(node -> node.getFeature().getName().orElse(""))
                    .filter(name -> !name.isEmpty())
                    .forEach(slicedFeatureNames::add);
        }
        return slicedFeatureNames;
    }

    private void printRemovedFeatures(FeatureModelAnalyzer analyzer, Set<String> slicedFeatureNames) {
        List<String> deadFeatures = analyzer.dead().orElse(new ArrayList<>());
        List<String> coreFeatures = analyzer.core().orElse(new ArrayList<>());

        List<String> removedDeadFeatures = getRemovedFeatures(deadFeatures, slicedFeatureNames);
        List<String> removedCoreFeatures = getRemovedFeatures(coreFeatures, slicedFeatureNames);

        List<Pair<List<String>, List<String>>> atomicSetsPairs =
                analyzer.atomicSets().orElse(new ArrayList<>());
        List<String> allAtomicSetFeatures = new ArrayList<>();
        for (Pair<List<String>, List<String>> pair : atomicSetsPairs) {
            allAtomicSetFeatures.addAll(pair.getFirst());
            allAtomicSetFeatures.addAll(pair.getSecond());
        }
        List<String> removedAtomicSets = getRemovedFeatures(allAtomicSetFeatures, slicedFeatureNames);

        FeatJAR.log().message("REMOVED DEAD FEATURES " + removedDeadFeatures);
        FeatJAR.log().message("REMOVED CORE FEATURES " + removedCoreFeatures);
        FeatJAR.log().message("REMOVED ATOMIC SETS " + removedAtomicSets);
    }

    private List<String> getRemovedFeatures(List<String> features, Set<String> slicedFeatureNames) {
        return features.stream()
                .filter(feature -> !slicedFeatureNames.contains(feature))
                .toList();
    }

    private void printMergedFeatures(FeatureModelAnalyzer analyzer, Set<String> slicedFeatureNames) {
        List<Pair<List<String>, List<String>>> atomicSets =
                analyzer.atomicSets().orElse(new ArrayList<>());

        for (Pair<List<String>, List<String>> atomicSet : atomicSets) {
            List<String> positiveFeatures = atomicSet.getFirst();
            List<String> negativeFeatures = atomicSet.getSecond();
            List<String> allFeaturesInSet = new ArrayList<>(positiveFeatures);
            allFeaturesInSet.addAll(negativeFeatures);

            List<String> survivingFeatures = allFeaturesInSet.stream()
                    .filter(slicedFeatureNames::contains)
                    .toList();
            List<String> mergedFeatures = allFeaturesInSet.stream()
                    .filter(feature -> !slicedFeatureNames.contains(feature))
                    .toList();

            if (survivingFeatures.size() == 1 && !mergedFeatures.isEmpty()) {
                String representative = survivingFeatures.get(0);
                List<String> signedMappings = formatSignedAtomicMappings(
                        positiveFeatures, representative, mergedFeatures);
                FeatJAR.log().message("\tAtomic-set representative " + representative + ": " + signedMappings);
            }
        }
    }

    static List<String> formatSignedAtomicMappings(
            List<String> positiveFeatures, String representative, List<String> mergedFeatures) {
        boolean representativeIsPositive = positiveFeatures.contains(representative);
        return mergedFeatures.stream()
                .map(feature -> {
                    boolean featureIsPositive = positiveFeatures.contains(feature);
                    String negation = featureIsPositive == representativeIsPositive ? "" : "!";
                    return feature + " = " + negation + representative;
                })
                .toList();
    }

    private void printPropositionalRepresentation(
            IFormula originalPropositionalRepresentation, BooleanAssignmentList originalCnf) {
        BooleanAssignmentList nnfClauseList = Computations.of(originalPropositionalRepresentation)
                .map(ComputeNNFFormula::new)
                .map(ComputeBooleanClauseList::new)
                .compute();

        // FeatJAR.log().message("Propositional representation of the input feature model:");
        // FeatJAR.log().message(originalPropositionalRepresentation.print());
        // FeatJAR.log().message("CNF clause list of the input feature model:");
        // FeatJAR.log().message(new BooleanAssignmentListTextFormat().serialize(originalCnf).get());
        // FeatJAR.log().message("NNF clause list of the input feature model:");
        // FeatJAR.log().message(new BooleanAssignmentListTextFormat().serialize(nnfClauseList).get());

    }

    IFeatureModel reduceFeatureModel(
            IFeatureModel originalFeatureModel,
            FeatureModelAnalyzer analyzer,
            boolean simplifyCoreAndDeadFeatures,
            boolean simplifyAtomicSets) {
        if (!analyzer.isSatisfiable().orElseThrow()) {
            throw new IllegalArgumentException("Unsatisfiable feature models cannot be simplified");
        }

        // FM -> PR_FM -> phi_FM
        IFormula originalPropositionalRepresentation = propositionalRepresentation(originalFeatureModel);
        BooleanAssignmentList originalCnf = toCnf(originalPropositionalRepresentation);
        printPropositionalRepresentation(originalPropositionalRepresentation, originalCnf);

        // This analyzer step computes core/dead features and atomic sets on
        // phi_FM, selects representatives from FM, creates the substitutions,
        // and applies them to a clone of PR_FM.
        FeatureModelAnalyzer.SimplificationResult simplification = analyzer.simplifyWithPlan(
                        simplifyCoreAndDeadFeatures, simplifyAtomicSets)
                .get();
        IExpression reducedPropositionalRepresentation = simplification.expression();
        Map<String, String> atomicRepresentatives = simplification.positiveAtomicRepresentatives();

        // K is the set of variables that remain after substitution.
        Set<String> survivingFeatureNames = determineSurvivorSet(reducedPropositionalRepresentation);

        // phi_projected = exists(V ∖ K).phi_FM
        BooleanAssignmentList projectedCnf = existentialProjection(originalCnf, survivingFeatureNames);

        // Construct the structural basis FM_prime.
        IFeatureModel structuralModel = originalFeatureModel.clone();
        AtomicSetTreeIntegrator.integrate(structuralModel, atomicRepresentatives);
        removeFeaturesNotIn(structuralModel, survivingFeatureNames);
        removeConstraintsReferencingRemovedFeatures(structuralModel, survivingFeatureNames);
        removeFeatureDefinitionsNotIn(structuralModel, survivingFeatureNames);

        // Encode FM_prime and add only projected clauses that it does not entail.
        IFormula structuralPropositionalRepresentation = propositionalRepresentation(structuralModel);
        BooleanAssignmentList structuralModelCnf = toCnf(structuralPropositionalRepresentation);
        requireEntailment(
                projectedCnf, structuralModelCnf, "Structurally reduced model excludes a projected configuration");

        IFeatureModel reducedFeatureModel =
                addMissingProjectedDependencies(structuralModel, projectedCnf, structuralModelCnf);
        BooleanAssignmentList reducedModelCnf = toCnf(propositionalRepresentation(reducedFeatureModel));
        requireEntailment(projectedCnf, reducedModelCnf, "Reduced model excludes a projected configuration");
        requireEntailment(
                reducedModelCnf, projectedCnf, "Reduced model admits a configuration absent from the projection");
        return reducedFeatureModel;
    }

    private void processSingleFile(
            Path inputPath, FeatJARWrapper featJARWrapper, OptionList optionParser, boolean isDirectoryProcessing) {
        final IFeatureModel featureModel =
                featJARWrapper.loadFeatureModel(inputPath).get();
        // System.out.println(featureModel);
        final TikzFeatureModelFormat tikzFeatureModelFormat = new TikzFeatureModelFormat();
        final FeatureModelAnalyzer analyzer = featJARWrapper.featureModelAnalyzer(featureModel);
        boolean coreDead = optionParser.get(CORE_DEAD_OPTION);
        boolean atomicSets = optionParser.get(ATOMIC_SETS_OPTION);
        IFeatureModel reducedFeatureModel = reduceFeatureModel(featureModel, analyzer, coreDead, atomicSets);
        printInfo(analyzer, reducedFeatureModel);

        String inputFileName = inputPath.getFileName().toString();
        Path outputPath = Path.of("../feature-model-assistance/src/main/resources/uvlModelsOutput/");
        if (optionParser.has(OUTPUT_OPTION)) {
            outputPath = optionParser.get(OUTPUT_OPTION);
        }
        int underscoreIndex = inputFileName.indexOf('_');
        int dotIndex = inputFileName.lastIndexOf('.');
        String namePart = (underscoreIndex != -1 && dotIndex != -1)
                ? inputFileName.substring(underscoreIndex + 1, dotIndex)
                : inputFileName.substring(0, dotIndex != -1 ? dotIndex : inputFileName.length());

        // Always treat as directory when processing multiple files or if path doesn't exist
        if (isDirectoryProcessing || !Files.exists(outputPath) || Files.isDirectory(outputPath)) {
            try {
                Files.createDirectories(outputPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            outputPath = outputPath.resolve("slicedModel_" + namePart + ".uvl");
        }
        try {
            featJARWrapper.storeFeatureModel(reducedFeatureModel, outputPath);
            FeatJAR.log().message("Sliced model stored at: " + outputPath.toAbsolutePath());
            // featJARWrapper.storeFeatureModel(featureModel, outputPath);
            Path tikzPath_sliced =
                    Path.of("../feature-model-assistance/src/main/resources/tikz/" + namePart + "_slicedModel.tex");
            featJARWrapper.storeAnything(reducedFeatureModel, tikzPath_sliced, tikzFeatureModelFormat);
            FeatJAR.log().message("Sliced model tikz stored at: " + tikzPath_sliced.toAbsolutePath());
            Path tikzPath = Path.of("../feature-model-assistance/src/main/resources/tikz/" + namePart + ".tex");
            featJARWrapper.storeAnything(
                    featJARWrapper.loadFeatureModel(inputPath).get(), tikzPath, tikzFeatureModelFormat);
            FeatJAR.log().message("Original Model tikz stored at: " + tikzPath.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /*
     run --args="simplify-model --input '../feature-model-assistance/src/main/resources/uvlModelsInput/testModel_dead.uvl'"
     run --args="simplify-model --input '../feature-model-assistance/src/main/resources/uvlModelsInput/testModel_core.uvl'"
     run --args="simplify-model --input '../feature-model-assistance/src/main/resources/uvlModelsInput/testModel_atomicSets.uvl'"
     run --args="simplify-model --input 'D:/Uni/FeatJAR/uvl/src/main/resources/uvl/featureModelSerializeResult.uvl'"
     run --args="simplify-model --input '../formula/src/testFixtures/resources/GPL/model.xml'"
     run --args="simplify-model --input 'D:/Uni/FeatJAR/formula/src/testFixtures/resources/GPL/model.xml'"
     run --args="simplify-model --input '../feature-model-assistance/src/main/resources/uvlModelsInput/'" (directory)
     run --args="simplify-model --input '../feature-model-assistance/src/main/resources/uvlModelsInput/' --coreDead true --atomic_sets true --output '../feature-model-assistance/src/main/resources/uvlModelsOutput/'"
    */
    @Override
    public int run(OptionList optionParser) {
        final FeatJARWrapper featJARWrapper = new FeatJARWrapper();
        Path inputPath = Path.of(String.valueOf(optionParser.get(INPUT_OPTION)));
        if (Files.isDirectory(inputPath)) {
            try (Stream<Path> paths = Files.walk(inputPath)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".uvl"))
                        .forEach(uvlFile -> {
                            FeatJAR.log().message("====== Processing file: " + uvlFile + " ======");
                            processSingleFile(uvlFile, featJARWrapper, optionParser, true);
                        });
            } catch (IOException e) {
                throw new RuntimeException("Error walking directory: " + inputPath, e);
            }
        } else {
            processSingleFile(inputPath, featJARWrapper, optionParser, false);
        }
        return 0;
    }
}
