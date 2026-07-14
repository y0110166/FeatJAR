package de.featjar.featureide.cli;

import de.featjar.analysis.sat4j.slice.CNFSlicer;
import de.featjar.analysis.sat4j.solver.SAT4JClauseList;
import de.featjar.analysis.sat4j.solver.SAT4JSolutionSolver;
import de.featjar.base.cli.ACommand;
import de.featjar.base.cli.OptionList;
import de.featjar.base.computation.Computations;
import de.featjar.base.computation.IComputation;
import de.featjar.base.data.Result;
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
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 *
 * @author
 */
public class FeatureModelSimplifyerCommand extends ACommand {

    private BooleanAssignmentList cnf;
    private BooleanAssignmentList slicedCnf;


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
        // auf redundanz prüfen ggf. 1 zu 1 übernehmen
        BooleanAssignmentList newCnf = Computations.of(workingModel)
                .map(ComputeFormula::new)
                .map(ComputeNNFFormula::new)
                .map(ComputeCNFFormula::new)
                .map(ComputeBooleanClauseList::new)
                .compute();
        // relevant
        SAT4JSolutionSolver solver = new SAT4JSolutionSolver(newCnf, false);
        SAT4JClauseList clauseList = solver.getClauseList();
        clauseList.addAll(newCnf);

        for (BooleanAssignment disjunction : this.slicedCnf.getAll()) {
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
            if (solver.hasSolution(disjunction.negateInts()).orElse(Boolean.TRUE)) {
                clauseList.add(disjunction);
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
    // run --args="simplify-model --input '../feature-model-assistance/src/main/resources/uvlModelsInput/testModel_dead.uvl'"
    // run --args="simplify-model --input '../feature-model-assistance/src/main/resources/uvlModelsInput/testModel_core.uvl'"
    // run --args="simplify-model --input '../feature-model-assistance/src/main/resources/uvlModelsInput/testModel_atomicSets.uvl'"
    // run --args="simplify-model --input 'D:/Uni/FeatJAR/uvl/src/main/resources/uvl/featureModelSerializeResult.uvl'"
    // run --args="simplify-model --input '../formula/src/testFixtures/resources/GPL/model.xml'"
    // run --args="simplify-model --input 'D:/Uni/FeatJAR/formula/src/testFixtures/resources/GPL/model.xml'"
    @Override
    public int run(OptionList optionParser) {
        final FeatJARWrapper featJARWrapper = new FeatJARWrapper();
        final IFeatureModel featureModel = featJARWrapper
                .loadFeatureModel(optionParser.get(INPUT_OPTION))
                .get();
        final FeatureModelAnalyzer analyzer = featJARWrapper.featureModelAnalyzer(featureModel);
        Result<IExpression> simplify_Expression = analyzer.simplify();

        IFeatureModel slicedModel = sliceFeatureModel(featureModel, simplify_Expression);
        IFeatureModel slicedModelWithoutRedundancies = checkRedundancy(slicedModel);


        System.out.println("ORIGINAL FEATURE MODEL: " + featureModel.getFeatures());
        System.out.println("ORIGINAL MODEL CONSTRAINTS " + featureModel.getConstraints());
        System.out.println("SIMPLIFIED FEATURE MODEL: " + slicedModel.getFeatures() + "\n" + slicedModel.getConstraints());
        System.out.println("SIMPLIFIED FEATURE MODEL WITHOUT REDUNDANCIES: " + slicedModelWithoutRedundancies.getFeatures() + "\n" + slicedModelWithoutRedundancies.getConstraints()); // dadurch sind da deutlich mehr Constraints da?
        IFormula formula1 = Computations.of(slicedModel).map(ComputeFormula::new).compute();
        IFormula formula2 = Computations.of(slicedModelWithoutRedundancies).map(ComputeFormula::new).compute();
        System.out.println("Simplified Model without and with are equal: " + formula1.equals(formula2));


        // final TikzFeatureModelFormat tikzFeatureModelFormat = new TikzFeatureModelFormat();
        // final TikzFeatureModelFormat tikzFeatureModelFormatWithoutRedundancies = new TikzFeatureModelFormat();
        String inputPath = String.valueOf(optionParser.get(INPUT_OPTION));
        String inputFileName = Path.of(inputPath).getFileName().toString();
        int underscoreIndex = inputFileName.indexOf('_');
        int dotIndex = inputFileName.lastIndexOf('.');
        String namePart = (underscoreIndex != -1 && dotIndex != -1)
                ? inputFileName.substring(underscoreIndex + 1, dotIndex)
                : inputFileName.substring(0, dotIndex != -1 ? dotIndex : inputFileName.length());
        Path path = Path.of("../feature-model-assistance/src/main/resources/uvlModelsOutput/" + namePart + "_slicedModel.uvl");
        try {
            featJARWrapper.storeFeatureModel(slicedModelWithoutRedundancies, path);
            System.out.println("Sliced model stored at: " + path.toAbsolutePath());
            System.out.println("SIMPLIFIED MODEL WITHOUT REDUNDANCIES features:\n" + slicedModelWithoutRedundancies);
            //Path tikzPath_sliced = Path.of("../feature-model-assistance/src/main/resources/uvlModelsOutput/" + namePart + "_slicedModel.tex");
            //featJARWrapper.storeAnything(slicedModelWithoutRedundancies, tikzPath_sliced, tikzFeatureModelFormat);
            //Path tikzPath = Path.of("../feature-model-assistance/src/main/resources/uvlModelsOutput/" + namePart + ".tex");
            //featJARWrapper.storeAnything(slicedModelWithoutRedundancies, tikzPath, tikzFeatureModelFormat);


        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }
}
