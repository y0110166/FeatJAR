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

import java.util.*;
import java.util.function.Predicate;

/**
 *
 * @author Knut Köhnlein
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

    private int[] updateCnfs(IFeatureModel featureModel, Predicate<IFeatureModelElement> featureFilter) {
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

        return literalsToKeep;
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

        // Get feature names from both expressions
        LinkedHashSet<String> allFeatureNames = originalExpression.getVariableNames();
        LinkedHashSet<String> featureNamesToKeep = modifiedExpression.getVariableNames();

        // Features to exclude are those in original but not in modified
        LinkedHashSet<String> featureNamesToExclude = new LinkedHashSet<>(allFeatureNames);
        featureNamesToExclude.removeAll(featureNamesToKeep);

        // Create the filters
        IFeatureModelElementFilter include = IFeatureModelElementFilter.featuresByName(featureNamesToKeep);
        IFeatureModelElementFilter exclude = IFeatureModelElementFilter.featuresByName(featureNamesToExclude);

        // Create the combined filter
        Predicate<IFeatureModelElement> featureFilter = include.and(exclude.negate());

        int[] literalsToKeep = updateCnfs(featureModel, featureFilter);

        // relevant ab hier alles in die main kopieren vom command line
        IFeatureModel slicedModel = featureModel.clone();

        List<IFeatureTree> newRoots = new ArrayList<>(slicedModel.getRoots().size());
        for (IFeatureTree rootFeature : slicedModel.getRoots()) {
            PseudoFeatureTreeRoot pseudoRoot = new PseudoFeatureTreeRoot(slicedModel);
            pseudoRoot.addChild(rootFeature);
            rootFeature
                    .postOrderStream() //travers the tree in a post order (children first)
                    .filter(node -> !featureFilter.test(node.getFeature())) // selects the nodes that fail the filter (should be removed)
                    .forEach(node -> node.mutate().removeFromTree()); // removes the nodes from the tree
            newRoots.addAll(pseudoRoot.detach()); // adds the modified roots to the collection for the final model
        }

        Collection<IConstraint> constraints = new ArrayList<>(slicedModel.getConstraints());
        for (IConstraint constraint : constraints) {
            if (!constraint.getReferencedFeatures().stream().allMatch(featureFilter)) {
                slicedModel.mutate().removeConstraint(constraint);
            }
        }

        return slicedModel;
    }

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


        System.out.println("ORIGINAL FEATURE MODEL: " + featureModel);
        System.out.println("SIMPLIFIED FEATURE MODEL: " + slicedModel);
        System.out.println("SIMPLIFIED FEATURE MODEL WITHOUT REDUNDANCIES: " + slicedModelWithoutRedundancies); // dadurch sind da deutlich mehr Constraints da?
        IFormula formula1 = Computations.of(slicedModel).map(ComputeFormula::new).compute();
        IFormula formula2 = Computations.of(slicedModelWithoutRedundancies).map(ComputeFormula::new).compute();
        System.out.println("Simplified Model without and with are equal: " + formula1.equals(formula2));
        /*
        Derzeitiger Stand:
            - Beim FM werden Features gelöscht, dafür aber eine Menge an Constraints hinzugefügt.
            - Ob die Constraints notwendig sind, muss noch überprüft werden.
            - Ob die richtigen Features gelöscht werden, muss auch noch überprüft werden.
            - Ob die Core und Dead Features separat ausgewählt werden können, um das FM zu reduzieren, muss auch noch
                überprüft werden.
            - Ob die Atomic Sets am FM schon geändert werden, weiß ich gerade auch nicht.
         */


        return 0;
    }
}
