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
import de.featjar.base.computation.IComputation;
import de.featjar.base.data.Pair;
import de.featjar.base.data.Result;
import de.featjar.base.tree.structure.IRootedTree;
import de.featjar.feature.model.*;
import de.featjar.feature.model.io.tikz.TikzFeatureModelFormat;
import de.featjar.feature.model.transformer.ComputeFormula;
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

    private BooleanAssignmentList cnf;
    private BooleanAssignmentList slicedCnf;

    /*
    public static final Option<Path> INPUT_OPTION = Options.newOption("redundancies", Options.newChoiceOption())
            .setDescription("Path to input file(s)")
            .setValidator(Options.PathValidator);
     */

    public static final Option<Boolean> CORE_DEAD_OPTION = Options.newOption("coreDead", Options.BooleanParser, "true")
            .setDescription("Enable core and dead feature simplification (default: true)");

    public static final Option<Boolean> ATOMIC_SETS_OPTION = Options.newOption("atomic_sets", Options.BooleanParser, "true")
            .setDescription("Enable atomic sets simplification (default: true)");

    public static final Option<Path> OUTPUT_OPTION = Options.newOption("output", Options.PathParser, "../feature-model-assistance/src/main/resources/uvlModelsOutput/")
            .setDescription("Path to output directory or file (default: ../feature-model-assistance/src/main/resources/uvlModelsOutput/)");


    @Override
    public Optional<String> getDescription() {
        return Optional.of("Reduces the feature model by removing redundant features.");
    }

    @Override
    public Optional<String> getShortName() {
        return Optional.of("simplify-model");
    }

    private void updateCnfs(IFeatureModel featureModel, Predicate<IFeatureModelElement> featureFilter) {
        this.cnf = Computations.of(featureModel)
                .map(ComputeFormula::new)
                .map(ComputeNNFFormula::new)
                .map(ComputeCNFFormula::new)
                .map(ComputeBooleanClauseList::new)
                .compute();

        int[] literalsToKeep = featureModel.getFeatures().stream()
                .filter(featureFilter)
                .map(IFeature::getName)
                .map(Result::get)
                .map(cnf.getVariableMap()::get)
                .filter(Result::isPresent)
                .mapToInt(Result::get)
                .toArray();

        this.slicedCnf = Computations.of(cnf)
                .map(CNFSlicer::new)
                .set(CNFSlicer.VARIABLES_TO_KEEP, new Variables(literalsToKeep))
                .compute();
    }

    private IFeatureModel checkRedundancy(IFeatureModel slicedModel) {
        IFeatureModel workingModel = slicedModel.clone();
        BooleanAssignmentList newCnf = Computations.of(workingModel)
                .map(ComputeFormula::new)
                .map(ComputeNNFFormula::new)
                .map(ComputeCNFFormula::new)
                .map(ComputeBooleanClauseList::new)
                .compute();
        SAT4JSolutionSolver solver = new SAT4JSolutionSolver(newCnf, false);
        SAT4JClauseList clauseList = solver.getClauseList();

        for (BooleanAssignment disjunction : this.slicedCnf.getAll()) {
            BooleanAssignment remappedDisjunction =
                    disjunction.remap(this.slicedCnf.getVariableMap(), newCnf.getVariableMap());
            List<IFormula> clause = new ArrayList<>();
            for (int literal : disjunction.get()) {
                if (literal < 0) {
                    clause.add(new Literal(
                            false, cnf.getVariableMap().get(-literal).get()));
                } else {
                    clause.add(
                            new Literal(true, cnf.getVariableMap().get(literal).get()));
                }
            }
            if (solver.hasSolution(remappedDisjunction.negateInts()).orElse(Boolean.TRUE)) {
                clauseList.add(remappedDisjunction);
                workingModel.mutate().addConstraint(new Or(clause));
            }
        }
        return workingModel;
    }

    private IFeatureModel sliceFeatureModel(IFeatureModel featureModel, Result<IExpression> simplify_Expression) {
        IComputation<IFeatureModel> fmComputation = Computations.of(featureModel);
        ComputeFormula formulaComputation = fmComputation.map(ComputeFormula::new);


        IFormula formula = formulaComputation.compute();
        IExpression originalExpression = formula.cloneTree();
        IExpression modifiedExpression = simplify_Expression.get();

        LinkedHashSet<String> allFeatureNames = originalExpression.getVariableNames();
        LinkedHashSet<String> featureNamesToKeep = modifiedExpression.getVariableNames();

        // LinkedHashSet<String> featureNamesToExclude = new LinkedHashSet<>(allFeatureNames);
        // featureNamesToExclude.removeAll(featureNamesToKeep);

        //IFeatureModelElementFilter exclude = IFeatureModelElementFilter.featuresByName(featureNamesToExclude);

        // Create the combined filter
        Predicate<IFeatureModelElement> featureFilter = IFeatureModelElementFilter.featuresByName(featureNamesToKeep);

        updateCnfs(featureModel, featureFilter);

        // relevant ab hier alles in die main kopieren vom command line
        IFeatureModel slicedModel = featureModel.clone();

        List<IFeatureTree> newRoots = new ArrayList<>(slicedModel.getRoots().size());
        for (IFeatureTree rootFeature : slicedModel.getRoots()) {
            PseudoFeatureTreeRoot pseudoRoot = new PseudoFeatureTreeRoot(slicedModel);
            pseudoRoot.addChild(rootFeature);
            rootFeature
                    .postOrderStream() //travers the tree in a post order (children first)
                    .filter(node -> node.hasParent() && !featureFilter.test(node.getFeature())) // selects the nodes that fail the filter (should be removed)
                    .forEach(node -> node.mutate().removeFromTree()); // removes the nodes from the tree
            newRoots.addAll(pseudoRoot.detach()); // adds the modified roots to the collection for the final model
        }
        // Remove all existing roots and add the new filtered roots
        new ArrayList<>(slicedModel.getRoots()).forEach(root -> slicedModel.mutate().removeFeatureTreeRoot(root));
        newRoots.forEach(root -> slicedModel.mutate().addFeatureTreeRoot(root));

        Collection<IConstraint> constraints = new ArrayList<>(slicedModel.getConstraints());
        for (IConstraint constraint : constraints) {
            if (!constraint.getReferencedFeatures().stream().allMatch(featureFilter)) {
                slicedModel.mutate().removeConstraint(constraint);
            }
        }

        return slicedModel;
    }

    private void printInfo(FeatureModelAnalyzer analyzer, IFeatureModel slicedModel, IFeatureModel featureModel) {
        Set<String> slicedFeatureNames = getSlicedFeatureNames(slicedModel);
        
        printRemovedFeatures(analyzer, slicedFeatureNames);
        printMergedFeatures(analyzer, slicedFeatureNames, featureModel);
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
        
        List<Pair<List<String>, List<String>>> atomicSetsPairs = analyzer.atomicSets().orElse(new ArrayList<>());
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

    private void printMergedFeatures(FeatureModelAnalyzer analyzer, Set<String> slicedFeatureNames, IFeatureModel featureModel) {
        List<Pair<List<String>, List<String>>> atomicSets = analyzer.atomicSets().orElse(new ArrayList<>());
        Set<String> featuresWithParents = getFeaturesWithParents(featureModel);

        for (Pair<List<String>, List<String>> atomicSet : atomicSets) {
            List<String> positiveFeatures = atomicSet.getFirst();
            List<String> negativeFeatures = atomicSet.getSecond();
            List<String> allFeaturesInSet = new ArrayList<>(positiveFeatures);
            allFeaturesInSet.addAll(negativeFeatures);
            
            List<String> featuresInSetWithParents = allFeaturesInSet.stream()
                    .filter(featuresWithParents::contains)
                    .toList();
            
            if (featuresInSetWithParents.isEmpty()) {
                continue;
            }
            
            List<String> survivingFeatures = featuresInSetWithParents.stream()
                    .filter(slicedFeatureNames::contains)
                    .toList();
            
            List<String> mergedFeatures = featuresInSetWithParents.stream()
                    .filter(feature -> !slicedFeatureNames.contains(feature))
                    .toList();

            if (!survivingFeatures.isEmpty() && !mergedFeatures.isEmpty()) {
                FeatJAR.log().message("\t" + mergedFeatures + " merged into " + survivingFeatures);
            }
        }
    }

    private Set<String> getFeaturesWithParents(IFeatureModel featureModel) {
        Set<String> featuresWithParents = new java.util.HashSet<>();
        Set<IFeatureTree> rootNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        rootNodes.addAll(featureModel.getRoots());

        for (IFeatureTree root : featureModel.getRoots()) {
            root.preOrderStream()
                    .filter(node -> !rootNodes.contains(node))
                    .map(node -> node.getFeature().getName().orElse(""))
                    .filter(name -> !name.isEmpty())
                    .forEach(featuresWithParents::add);
        }
        return featuresWithParents;
    }

    private void processSingleFile(Path inputPath, FeatJARWrapper featJARWrapper, OptionList optionParser, boolean isDirectoryProcessing) {
        final IFeatureModel featureModel = featJARWrapper
                .loadFeatureModel(inputPath)
                .get();
        final FeatureModelAnalyzer analyzer = featJARWrapper.featureModelAnalyzer(featureModel);
        boolean coreDead = optionParser.get(CORE_DEAD_OPTION);
        boolean atomicSets = optionParser.get(ATOMIC_SETS_OPTION);
        Result<IExpression> simplify_Expression = analyzer.simplify(coreDead, atomicSets);

        IFeatureModel slicedModel = sliceFeatureModel(featureModel, simplify_Expression);
        IFeatureModel slicedModelWithoutRedundancies = checkRedundancy(slicedModel);

        printInfo(analyzer, slicedModel, featureModel);

        String inputFileName = inputPath.getFileName().toString();
        Path outputPath = Path.of("../feature-model-assistance/src/main/resources/uvlModelsOutput/");
        if(optionParser.has(OUTPUT_OPTION)){
            outputPath = optionParser.get(OUTPUT_OPTION);
        }
        int underscoreIndex = inputFileName.indexOf('_');
        int dotIndex = inputFileName.lastIndexOf('.');
        String namePart = (underscoreIndex != -1 && dotIndex != -1)
                ? inputFileName.substring(underscoreIndex + 1, dotIndex)
                : inputFileName.substring(0, dotIndex != -1 ? dotIndex : inputFileName.length());
        
        // Always treat as directory when processing multiple files or if path doesn't exist
        if(isDirectoryProcessing || !Files.exists(outputPath) || Files.isDirectory(outputPath)){
            try {
                Files.createDirectories(outputPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            outputPath = outputPath.resolve("slicedModel_" + namePart + ".uvl");
        }
        try {
            featJARWrapper.storeFeatureModel(slicedModelWithoutRedundancies, outputPath);
            FeatJAR.log().message("Sliced model stored at: " + outputPath.toAbsolutePath());
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
