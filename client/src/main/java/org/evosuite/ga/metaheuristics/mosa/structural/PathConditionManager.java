package org.evosuite.ga.metaheuristics.mosa.structural;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.pathcondition.PathConditionCoverageFactory;
import org.evosuite.coverage.pathcondition.PathConditionCoverageGoalFitness;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.metaheuristics.SearchListener;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.rmi.ClientServices;
import org.evosuite.testcase.ConstantInliner;
import org.evosuite.testcase.TestCaseMinimizer;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.utils.LoggingUtils;

/**
 * This Class manages the path condition goals to consider during the search
 * @author Giovanni Denaro
 * 
 */
public class PathConditionManager extends MultiCriteriaManager implements SearchListener<TestChromosome> {

	private static final long serialVersionUID = -9076875100376403089L;

	private Map<PathConditionCoverageGoalFitness, Set<BranchCoverageGoal>> pcRelatedUncoveredBranches = new LinkedHashMap<>(); //map path conditions to the branch targets that they (lead to) traverse
	private Set<BranchCoverageGoal> coveredAndAlreadyPrunedBranches = new HashSet<>(); //cache of branches that were already pruned from path conditions
	private Map<PathConditionCoverageGoalFitness, TrackedFitnessData> bestFitnessData = new LinkedHashMap<>();
	private Set<TestFitnessFunction> alreadyEmitted = new HashSet<>();
	
	private class TrackedFitnessData {
		double bestValue;
		final LinkedList<Integer> updates;
		public TrackedFitnessData(double value, int iteration) {
			bestValue = value;
			updates = new LinkedList<>();
			updates.add(iteration);
		}
		public void update(double newValue, int iteration) {
			if (newValue < bestValue) {
				this.bestValue = newValue;
				this.updates.add(iteration);		
			}
		}
	}
	/**
	 * Constructor used to initialize the set of uncovered goals, and the initial set
	 * of goals to consider as initial contrasting objectives
	 * @param a 
	 * @param fitnessFunctions List of all FitnessFunction<T>
	 */
	public PathConditionManager(List<TestFitnessFunction> targets, GeneticAlgorithm<TestChromosome> algo){
		super(targets);
		algo.addListener(this);
		
		if (Properties.CRITERION.length == 1) { //PATHCONDITION is the only Criterion, then remove the branch targets initialized by the super class
			this.currentGoals.clear();
			this.branchCoverageFalseMap.clear();
			this.branchCoverageTrueMap.clear();
			this.branchlessMethodCoverageMap.clear();
		}
		addPathConditionGoals(targets);
	}

	private void addPathConditionGoals(List<? extends TestFitnessFunction> targets) {
		//add all path condition goals as current goals
		for (TestFitnessFunction goal : targets) {
			if (goal instanceof PathConditionCoverageGoalFitness) {
				this.currentGoals.add(goal);
				ExecutionTracer.addEvaluatorForPathCondition(((PathConditionCoverageGoalFitness) goal).getPathConditionGoal());
			}
		}
		ExecutionTracer.logEvaluatorsForPathConditions();
			
		if (Properties.PATH_CONDITION_SUSHI_BRANCH_COVERAGE_INFO != null) {
			addDependenciesBetweenPathConditionsAndRelevantBranches(this.currentGoals);
		}		
	}
	
	private void addDependenciesBetweenPathConditionsAndRelevantBranches(Set<TestFitnessFunction> goals) {
		List<BranchCoverageGoal> relevantBranches = new ArrayList<>();
		for (TestFitnessFunction goal : this.getUncoveredGoals()){
			if (goal instanceof BranchCoverageTestFitness) {
				relevantBranches.add(((BranchCoverageTestFitness) goal).getBranchGoal());
			}
		}

		for (TestFitnessFunction goal : goals) {
			if (!(goal instanceof PathConditionCoverageGoalFitness)) continue;
			PathConditionCoverageGoalFitness pc = (PathConditionCoverageGoalFitness) goal;
			
			Set<BranchCoverageGoal> branchCovInfo = PathConditionCoverageFactory._I().getBranchCovInfo(pc, relevantBranches);
						
			if (branchCovInfo != null && !branchCovInfo.isEmpty()) {
				Set<BranchCoverageGoal> uncoveredBranches = new HashSet<>(branchCovInfo);
				pcRelatedUncoveredBranches.put(pc, uncoveredBranches);
			} else {
				doneWithPathCondition(pc);				
			}
		}
	}

	protected void doneWithPathCondition(PathConditionCoverageGoalFitness pc) {
		pcRelatedUncoveredBranches.remove(pc);
		currentGoals.remove(pc);
		bestFitnessData.remove(pc);
		ExecutionTracer.removeEvaluatorForPathCondition(pc.getPathConditionGoal());
	}

	@Override
	public void calculateFitness(TestChromosome c, GeneticAlgorithm<TestChromosome> ga) {
		HashSet<TestFitnessFunction> currentGoalsBefore = new HashSet<>(this.getCurrentGoals());
		super.calculateFitness(c, ga);
		
		// If new targets were covered, try to prune the path conditions related to any newly covered branch
		if (this.getCurrentGoals().size() < currentGoalsBefore.size()) { 
			prunePathConditionsByCoveredBranches(this.getCoveredGoals());
		}
		
		// check for need to update best fitness value
		if (Properties.DISMISS_PATH_CONDITIONS_NO_IMPROVE_ITERATIONS > 0) { 
			for (TestFitnessFunction goal: this.getCurrentGoals()) {
				if (!(goal instanceof PathConditionCoverageGoalFitness)) {
					continue; 
				}
				PathConditionCoverageGoalFitness pc = (PathConditionCoverageGoalFitness) goal;
				final double currentFitness = c.getFitness(pc);
				final TrackedFitnessData bestDatum = bestFitnessData.get(pc);
				if (bestDatum == null) {
					bestFitnessData.put(pc, new TrackedFitnessData(currentFitness, ga.getAge()));
				} else if (currentFitness < bestDatum.bestValue) {
					bestDatum.update(currentFitness, ga.getAge());
				}
			}
		}
	}

	@Override
	protected void updateCoveredGoals(TestFitnessFunction goal, TestChromosome tc) {
		if (!alreadyEmitted.contains(goal)) {
			if (Properties.EMIT_TESTS_INCREMENTALLY  /*SUSHI: Incremental test cases*/) {
				emitTestCase(goal, tc);
			}
			if (goal instanceof PathConditionCoverageGoalFitness) {
				doneWithPathCondition((PathConditionCoverageGoalFitness) goal);
			}
		} 
		alreadyEmitted.add(goal);
		super.updateCoveredGoals(goal, tc); // do at the end, since it will mark a covered goal as alreadyCovered
	}
	
	public void restoreInstrumentationForAllGoals() {
		if (!Properties.EMIT_TESTS_INCREMENTALLY) {
			PathConditionCoverageFactory pathConditionFactory = PathConditionCoverageFactory._I();
			List<PathConditionCoverageGoalFitness> goals = pathConditionFactory.getCoverageGoals();

			for (PathConditionCoverageGoalFitness g : goals) {
				ExecutionTracer.addEvaluatorForPathCondition(g.getPathConditionGoal());
			}		
		} else {
			Properties.JUNIT_TESTS = false;
		}
	}


	/*SUSHI: Incremental test cases*/
	private int noPcTestNum = 0;
	private void emitTestCase(TestFitnessFunction goal, TestChromosome tc) {
		if (Properties.JUNIT_TESTS) {

			TestChromosome tcToWrite = (TestChromosome) tc.clone();

			if (Properties.MINIMIZE) {
				TestCaseMinimizer minimizer = new TestCaseMinimizer(goal);
				minimizer.minimize(tcToWrite);
			}

			if (Properties.INLINE) {
				ConstantInliner inliner = new ConstantInliner();
				inliner.inline(tcToWrite.getTestCase());
			}

			//writing the output

			TestSuiteWriter suiteWriter = new TestSuiteWriter();
			suiteWriter.insertTest(tcToWrite.getTestCase(), "Covered goal: " + goal.toString());

			final String suffix;
			if (goal instanceof PathConditionCoverageGoalFitness) {
				PathConditionCoverageGoalFitness pc = (PathConditionCoverageGoalFitness) goal;
				String evaluatorName = pc.getEvaluatorName().substring(pc.getEvaluatorName().lastIndexOf('.') + 1);
				suffix = evaluatorName.substring(evaluatorName.indexOf('_')) + "_Test";
			} else {
				String goalType = goal.getClass().getSimpleName();
				String simplifiedGoalType = goalType.substring(0, 
						goalType.lastIndexOf("CoverageTestFitness") > 0 ? goalType.lastIndexOf("CoverageTestFitness") :  
							goalType.lastIndexOf("TestFitness") > 0 ?  goalType.lastIndexOf("TestFitness") :
								goalType.lastIndexOf("Fitness") > 0 ?  goalType.lastIndexOf("Fitness") : 
									goalType.length() - 1);
				suffix = "_" + simplifiedGoalType + "_" + (++noPcTestNum)  + "_Test";
			}
			String testName = Properties.TARGET_CLASS.substring(Properties.TARGET_CLASS.lastIndexOf(".") + 1) + suffix;
			String testDir = Properties.TEST_DIR;

			suiteWriter.writeTestSuite(testName, testDir, new ArrayList());

			
			ClientServices.getInstance().getClientNode().notifyGeneratedTestCase(TestFitnessSerializationUtils.makeSerializableForNonEvosuiteClients(goal), testName);
			LoggingUtils.getEvoLogger().info("\n\n* EMITTED TEST CASE: " + goal + ", " + testName);
		}
	}

	private void prunePathConditionsByCoveredBranches(Collection<TestFitnessFunction> coveredTargets) {
		// let's check for newly covered branches and update path conditions accordingly
		if (Properties.PATH_CONDITION_SUSHI_BRANCH_COVERAGE_INFO == null) return;
		
		for (TestFitnessFunction t : coveredTargets) {
			if (t instanceof BranchCoverageTestFitness) {
				BranchCoverageGoal branch = ((BranchCoverageTestFitness) t).getBranchGoal();
				if (coveredAndAlreadyPrunedBranches.contains(branch)) continue;
				prunePathConditionsByCoveredBranches(branch);
				coveredAndAlreadyPrunedBranches.add(branch);
			}
		}
	}

	private void prunePathConditionsByCoveredBranches(BranchCoverageGoal b) {
		Set<PathConditionCoverageGoalFitness> toRemove = new LinkedHashSet<>();

		// prune b from the set of uncovered branches associated with the path conditions
		for (Entry<PathConditionCoverageGoalFitness, Set<BranchCoverageGoal>> pcEntry : pcRelatedUncoveredBranches.entrySet()) {
			
			Set<BranchCoverageGoal> uncoveredBranches = pcEntry.getValue();
			uncoveredBranches.remove(b);
			if (uncoveredBranches.isEmpty()) {
				toRemove.add(pcEntry.getKey());
			}
		}
		
		// remove the path conditions that do not associate with any target branch
		for (PathConditionCoverageGoalFitness pc : toRemove) {
			doneWithPathCondition(pc);
		}
	}


	/* 
	 * Implement the SearchListener interface to be notified at each iteration
	 */
	
	@Override
	public void iteration(GeneticAlgorithm<TestChromosome> algorithm) {
		// At given intervals, check if there are new path condition goals to consider
		if (Properties.INJECTED_PATH_CONDITIONS_CHECKING_RATE > 0 && 
				(algorithm.getAge() % Properties.INJECTED_PATH_CONDITIONS_CHECKING_RATE) == 0) {
			checkForNewlyInjectedPathConditionGoals(algorithm);
		}
		
		// Check if we can discard some path condition goal that is not improving 
		if (Properties.DISMISS_PATH_CONDITIONS_NO_IMPROVE_ITERATIONS > 0) {
			checkForPathConditionGoalsToDismiss(algorithm.getAge());
		}
	}

	private void checkForPathConditionGoalsToDismiss(int currentIteration) {
		//Check for completed path-condition goals
		ArrayList<Entry<PathConditionCoverageGoalFitness, TrackedFitnessData>> toDismiss = new ArrayList<>();
		for (Entry<PathConditionCoverageGoalFitness, TrackedFitnessData> entry: bestFitnessData.entrySet()) {
			TrackedFitnessData data = entry.getValue();
			if (currentIteration - data.updates.getLast() > Properties.DISMISS_PATH_CONDITIONS_NO_IMPROVE_ITERATIONS) {
				PathConditionCoverageGoalFitness goal = entry.getKey();
				toDismiss.add(entry);
			}
		}
		for (Entry<PathConditionCoverageGoalFitness, TrackedFitnessData> entry: toDismiss) {
			PathConditionCoverageGoalFitness goal = entry.getKey();
			TrackedFitnessData data = entry.getValue();
			doneWithPathCondition(goal);
			int[] updts = new int[data.updates.size()];
			for (int i = 0; i < updts.length; ++i) {
				updts[i] = data.updates.get(i);
			}
			ClientServices.getInstance().getClientNode().notifyDismissedFitnessGoal(goal, currentIteration, data.bestValue, updts);
			LoggingUtils.getEvoLogger().info("\n\n* DISMISSED PATH CONDITION GOAL (NO IMPROVEMENT IN " + 
					Properties.DISMISS_PATH_CONDITIONS_NO_IMPROVE_ITERATIONS + " ITERATIONS): " +
					goal.getEvaluatorName());
		}
	}

	private void checkForNewlyInjectedPathConditionGoals(GeneticAlgorithm<TestChromosome> algorithm) {
		List<PathConditionCoverageGoalFitness> newGoals = PathConditionCoverageFactory._I().getNewlyInjectedCoverageGoals();
		if (newGoals == null) { // No new goals were recently injected
			return;
		}

		LoggingUtils.getEvoLogger().info("\n\n* RETRIEVED NEWLY INJECTED PATH CONDITION GOALS: " + newGoals);
		addPathConditionGoals(newGoals);

		// We update the fitness of the population for the new goals
		List<TestChromosome> population = algorithm.getPopulation();
		for (TestChromosome chromosome: population) { 
			chromosome.setChanged(true);
			for (TestFitnessFunction ff: newGoals) {
				chromosome.getFitness(ff); 
			}
		}
	}

	@Override
	public void searchStarted(GeneticAlgorithm<TestChromosome> algorithm) { /* do nothing */ }

	@Override
	public void searchFinished(GeneticAlgorithm<TestChromosome> algorithm) { /* do nothing */ }

	@Override
	public void fitnessEvaluation(TestChromosome individual) { /* do nothing */ }

	@Override
	public void modification(TestChromosome individual) { /* do nothing */ }
	
}
