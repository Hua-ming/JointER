package model;

import inference.State;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.Vector;

import utils.MersenneTwister;
import config.Parameters;
import data.Data;
import data.Instance;
import data.Label;

// This is a naive implementation of the following TACL paper
// Chang, Ming-Wei, and Wen-tau Yih. 
// Dual Coordinate Descent Algorithms for Efficient Large Margin Structured Prediction.

public class DCDSSVMModel extends Model {
	protected final double cParam;
	protected final double delta = 1.0e-12;

	protected final Random rand;
	protected class Example {
		public Example(State state, double c) {
			this.state = state;
			this.alpha = 0.;
			this.c = c;
			this.diffFv = null;
		}
		private State state;
		private double alpha;
		private double c;
		private SparseFeatureVector diffFv;
		public double getAlpha() {
			return alpha;
		}
		public double getC(){
			return c;
		}
		public void setAlpha(double alpha) {
			this.alpha = alpha;
		}
		public Instance getInstance() {
			return state.getInstance();
		}
		public State getState() {
			return state;
		}
		public SparseFeatureVector getDiffFv() {
			return diffFv;
		}
		public void setDiffFv(SparseFeatureVector diffFv) {
			this.diffFv = diffFv;
		}
	}
	
	protected Map<Integer, List<Example>> workingSet;
	
	public DCDSSVMModel(Parameters params, FeatureGenerator fg) {
		super(params, fg);
		workingSet = new TreeMap<Integer, List<Example>>();
		cParam = params.getLambda();
		rand = new MersenneTwister(0);
	}
	
	private double sumAlpha(int index){
		if(!workingSet.containsKey(index))return 0.;
		double sum = 0.;		
		for(Example example:workingSet.get(index)){
			sum += example.getAlpha(); 
		}
		return sum;
	}	

	private double calcWeight(Data data, Label yGold, Label yStar){
		double c = 1.;
		double diff = 0.;
		for(int i = 0;i < yStar.size();i++){
			if(!yGold.getLabel(i).equals(yStar.getLabel(i))){
				c *= data.getLabelImportance(yGold.getLabel(i));
				c *= data.getLabelImportance(yStar.getLabel(i));
				diff++;
			}
		}
		assert c > 0.: c;
		return Math.pow(c, 1./diff*2.);
	}
	
	@Override
	public void update(List<State> updates) {
		for(State yStarState:updates){
			Instance instance = yStarState.getInstance();
			int index = instance.getIndex();
			Label yGold = instance.getGoldLabel();
			if(yStarState.isCorrect()){
				assert sumAlpha(index) >= 0.;
				continue;
			}
			SparseFeatureVector fvGold  = fg.calculateFeature(instance, yGold, yStarState.getLabel().size());
			double goldScore = evaluate(fvGold, false);
			double c = cParam;
			if(params.getUseWeighting()){
				c *= calcWeight(instance.getData(), yGold, yStarState.getLabel());
			}
			if((yStarState.getMargin() - (goldScore - yStarState.getScore()) - sumAlpha(index) / (2. * c)) > delta){
				if(!workingSet.containsKey(index)){
					workingSet.put(index, new Vector<Example>());
				}
				workingSet.get(index).add(0, new Example(yStarState, c));
			}
		}
		for(State state:updates){
			updateWeight(state.getInstance().getIndex());	
		}
	}

	public void updateWeight(int i) {
		if(!workingSet.containsKey(i))return;
		int size = workingSet.get(i).size();
		if(size < 1)return;
		List<Example> examples;
		if(size > 2){
			examples = workingSet.get(i).subList(1, size);
			Collections.shuffle(examples, rand);
			examples.add(0, workingSet.get(i).get(0));
			workingSet.put(i, examples);
		}else{
			examples = workingSet.get(i);
		}
		
		for(Iterator<Example> exampleIt = examples.iterator();exampleIt.hasNext();){
			Example example = exampleIt.next();
			Instance instance = example.getInstance();
			State yStarState = example.getState();
			double alpha = example.getAlpha();
			double c = example.getC();
			
			if(example.getDiffFv() == null){
				Label yStar = yStarState.getLabel();
				Label yGold = instance.getGoldLabel();			
				SparseFeatureVector fvStar = fg.calculateFeature(instance, yStar, yStar.size());
				SparseFeatureVector fvGold = fg.calculateFeature(instance, yGold, yStar.size());

				SparseFeatureVector diff = new SparseFeatureVector(params);
				diff.add(1, fvGold);
				diff.add(-1., fvStar);
				diff.compact();
				example.setDiffFv(diff);
			}
			SparseFeatureVector diff = example.getDiffFv();
			double d2 = yStarState.getMargin() - evaluate(diff, false) - sumAlpha(i) / (2. * c);
			if(d2 + (example.getAlpha() / (2. * c)) <= delta){
				exampleIt.remove();
				continue;
			}
			double norm = diff.getNorm();
			double d = d2 / (norm * norm + 1. / (2. * c));
			double newAlpha = Math.max(alpha+d, 0.);
			diff.addToWeight((newAlpha-alpha), weight);
			if(params.getUseAveraging()){
				diff.addToWeight((newAlpha-alpha) * trainStep, weightDiff);	
			}
			trainStep++;
			example.setAlpha(newAlpha);
			if(example.getAlpha() == 0.){
				exampleIt.remove();
			}
		}
		assert examples.size() == workingSet.get(i).size();
	}

}
