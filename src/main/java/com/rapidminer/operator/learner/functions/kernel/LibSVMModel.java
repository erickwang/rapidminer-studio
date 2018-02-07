/**
 * Copyright (C) 2001-2018 by RapidMiner and the contributors
 * 
 * Complete list of developers available at our web site:
 * 
 * http://rapidminer.com
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see http://www.gnu.org/licenses/.
*/
package com.rapidminer.operator.learner.functions.kernel;

import com.rapidminer.example.Attribute;
import com.rapidminer.example.AttributeRole;
import com.rapidminer.example.Attributes;
import com.rapidminer.example.Example;
import com.rapidminer.example.ExampleSet;
import com.rapidminer.example.FastExample2SparseTransform;
import com.rapidminer.example.set.ExampleSetUtilities;
import com.rapidminer.example.table.AttributeFactory;
import com.rapidminer.operator.OperatorProgress;
import com.rapidminer.operator.ProcessStoppedException;
import com.rapidminer.operator.UserError;
import com.rapidminer.operator.learner.FormulaProvider;
import com.rapidminer.tools.Ontology;
import com.rapidminer.tools.Tools;

import libsvm.Svm;
import libsvm.svm_model;
import libsvm.svm_node;
import libsvm.svm_parameter;


/**
 * A model generated by the <a href="http://www.csie.ntu.edu.tw/~cjlin/libsvm">libsvm</a> by
 * Chih-Chung Chang and Chih-Jen Lin.
 *
 * @author Ingo Mierswa
 */
public class LibSVMModel extends KernelModel implements FormulaProvider {

	private static final long serialVersionUID = -2654603017217487365L;

	private static final int OPERATOR_PROGRESS_STEPS = 2000;

	private svm_model model;

	private int numberOfAttributes;

	private boolean confidenceForMultiClass = true;

	public LibSVMModel(ExampleSet exampleSet, svm_model model, int numberOfAttributes, boolean confidenceForMultiClass) {
		super(exampleSet, ExampleSetUtilities.SetsCompareOption.ALLOW_SUPERSET,
				ExampleSetUtilities.TypesCompareOption.ALLOW_SAME_PARENTS);
		this.model = model;
		this.numberOfAttributes = numberOfAttributes;
		this.confidenceForMultiClass = confidenceForMultiClass;
	}

	@Override
	public boolean isClassificationModel() {
		return getLabel().isNominal();
	}

	@Override
	public double getAlpha(int index) {
		return model.sv_coef[0][index];
	}

	@Override
	public String getId(int index) {
		return null;
	}

	@Override
	public int getNumberOfSupportVectors() {
		return model.SV.length;
	}

	@Override
	public int getNumberOfAttributes() {
		return numberOfAttributes;
	}

	@Override
	public double getBias() {
		if (this.model.rho.length > 0) {
			return this.model.rho[0];
		} else {
			return 0.0d;
		}
	}

	@Override
	public SupportVector getSupportVector(int index) {
		svm_node[] nodes = this.model.SV[index];
		double[] x = new double[getNumberOfAttributes()];
		for (int i = 0; i < nodes.length; i++) {
			x[nodes[i].index] = nodes[i].value;
		}
		return new SupportVector(x, getRegressionLabel(index), Math.abs(getAlpha(index)));
	}

	@Override
	public double getAttributeValue(int exampleIndex, int attributeIndex) {
		double[] dense = new double[numberOfAttributes];
		svm_node[] node = model.SV[exampleIndex];
		for (int i = 0; i < node.length; i++) {
			dense[node[i].index] = node[i].value;
		}
		return dense[attributeIndex];
	}

	@Override
	public String getClassificationLabel(int index) {
		double functionValue = getRegressionLabel(index);
		if (!Double.isNaN(functionValue)) {
			return getLabel().getMapping().mapIndex((int) functionValue);
		} else {
			return "?";
		}
	}

	@Override
	public double getRegressionLabel(int index) {
		if (model.labelValues != null) {
			return model.labelValues[index];
		} else {
			return Double.NaN;
		}
	}

	@Override
	public double getFunctionValue(int index) {
		if (getLabel().isNominal()) {
			double[] classProbs = new double[getLabel().getMapping().size()];
			Svm.svm_predict_probability(model, model.SV[index], classProbs);
			return classProbs[0];
		} else {
			return Svm.svm_predict(model, model.SV[index]);
		}
	}

	@Override
	public ExampleSet performPrediction(ExampleSet exampleSet, Attribute predictedLabel)
			throws UserError, ProcessStoppedException {
		FastExample2SparseTransform ripper = new FastExample2SparseTransform(exampleSet);
		Attribute label = getLabel();

		// initialize progress
		OperatorProgress progress = null;
		if (getShowProgress() && getOperator() != null && getOperator().getProgress() != null) {
			progress = getOperator().getProgress();
			progress.setTotal(exampleSet.size());
		}
		int progressCounter = 0;

		// check if one class SVM is used
		if (model.param.svm_type == LibSVMLearner.SVM_TYPE_ONE_CLASS) {
			// if yes, then clear predictedLabel mapping: We use a fixed one
			predictedLabel.getMapping().clear();
			predictedLabel.getMapping().mapString("outside");
			predictedLabel.getMapping().mapString("inside");

			// create own confidence attribute
			Attribute confidenceAttribute = AttributeFactory.createAttribute(Attributes.CONFIDENCE_NAME + "(inside)",
					Ontology.REAL);
			exampleSet.getExampleTable().addAttribute(confidenceAttribute);
			AttributeRole confidenceRole = new AttributeRole(confidenceAttribute);
			confidenceRole.setSpecial(Attributes.CONFIDENCE_NAME + "_inside");
			exampleSet.getAttributes().add(confidenceRole);

			// now calculate weights
			int counter = 0;
			double[] allConfidences = new double[exampleSet.size()];
			int[] allLabels = new int[exampleSet.size()];
			double maxConfidence = Double.NEGATIVE_INFINITY;
			double minConfidence = Double.POSITIVE_INFINITY;
			double confidence;

			for (Example example : exampleSet) {
				svm_node[] currentNodes = LibSVMLearner.makeNodes(example, ripper);

				double[] prob = new double[1];
				Svm.svm_predict_values(model, currentNodes, prob);

				allLabels[counter] = prob[0] >= 0 ? 1 : 0;
				allConfidences[counter] = prob[0];
				minConfidence = Math.min(minConfidence, prob[0]);
				maxConfidence = Math.max(maxConfidence, prob[0]);

				counter++;

				if (progress != null && ++progressCounter % OPERATOR_PROGRESS_STEPS == 0) {
					progress.setCompleted(progressCounter);
				}
			}

			counter = 0;
			for (Example example : exampleSet) {
				confidence = allConfidences[counter]; // (allConfidences[counter] - minConfidence) /
				// (maxConfidence - minConfidence);
				example.setValue(predictedLabel, allLabels[counter]);
				example.setValue(confidenceAttribute, confidence);
				counter++;
			}

		} else {
			// performing regular classification or regression

			Attribute[] confidenceAttributes = null;
			if (label.isNominal() && label.getMapping().size() >= 2) {
				confidenceAttributes = new Attribute[model.label.length];
				for (int j = 0; j < model.label.length; j++) {
					String labelName = label.getMapping().mapIndex(model.label[j]);
					confidenceAttributes[j] = exampleSet.getAttributes()
							.getSpecial(Attributes.CONFIDENCE_NAME + "_" + labelName);
				}
			}

			for (Example example : exampleSet) {
				if (label.isNominal()) {
					// set prediction
					svm_node[] currentNodes = LibSVMLearner.makeNodes(example, ripper);

					// set class probs (properly calculated during training)
					if (model.probA != null && model.probB != null) {
						double[] classProbs = new double[model.nr_class];
						int nr_class = model.nr_class;
						double[] dec_values = new double[nr_class * (nr_class - 1) / 2];
						Svm.svm_predict_values(model, currentNodes, dec_values);

						double min_prob = 1e-7;
						double[][] pairwise_prob = new double[nr_class][nr_class];

						int k = 0;
						for (int a = 0; a < nr_class; a++) {
							for (int j = a + 1; j < nr_class; j++) {
								pairwise_prob[a][j] = Math.min(Math
										.max(Svm.sigmoid_predict(dec_values[k], model.probA[k], model.probB[k]), min_prob),
										1 - min_prob);
								pairwise_prob[j][a] = 1 - pairwise_prob[a][j];
								k++;
							}
						}
						Svm.multiclass_probability(nr_class, pairwise_prob, classProbs);

						for (k = 0; k < nr_class; k++) {
							example.setValue(confidenceAttributes[k], classProbs[k]);
						}

						if (confidenceForMultiClass) { // use highest confidence
							double predictedClass = Svm.svm_predict_probability(model, currentNodes, classProbs);
							example.setValue(predictedLabel, predictedClass);
						} else { // binary majority vote over 1-vs-1 classifiers
							double predictedClass = Svm.svm_predict(model, currentNodes);
							example.setValue(predictedLabel, predictedClass);
						}
					} else {
						double predictedClass = Svm.svm_predict(model, currentNodes);
						example.setValue(predictedLabel, predictedClass);
						// use simple calculation for binary cases...
						if (label.getMapping().size() == 2) {
							double[] functionValues = new double[model.nr_class];
							Svm.svm_predict_values(model, currentNodes, functionValues);
							double prediction = functionValues[0];
							if (confidenceAttributes != null && confidenceAttributes.length > 0) {
								example.setValue(confidenceAttributes[0], 1.0d / (1.0d + java.lang.Math.exp(-prediction)));
								if (confidenceAttributes.length > 1) {
									example.setValue(confidenceAttributes[1],
											1.0d / (1.0d + java.lang.Math.exp(prediction)));
								}
							}
						} else {
							// ...or no proper confidence value for the multi class setting
							// here the confidence attribute calculated above cannot be used
							example.setConfidence(getLabel().getMapping().mapIndex((int) predictedClass), 1.0d);
						}
					}
				} else {
					example.setValue(predictedLabel, Svm.svm_predict(model, LibSVMLearner.makeNodes(example, ripper)));
				}

				if (progress != null && ++progressCounter % OPERATOR_PROGRESS_STEPS == 0) {
					progress.setCompleted(progressCounter);
				}
			}
		}
		return exampleSet;
	}

	@Override
	protected boolean supportsConfidences(Attribute label) {
		return super.supportsConfidences(label) && model.param.svm_type != LibSVMLearner.SVM_TYPE_ONE_CLASS;
	}

	@Override
	public String toString() {
		StringBuffer result = new StringBuffer(super.toString() + Tools.getLineSeparator());
		result.append("number of classes: " + model.nr_class + Tools.getLineSeparator());
		if (getLabel().isNominal() && getLabel().getMapping().size() >= 2 && model.nSV != null) {
			for (int i = 0; i < model.nSV.length; i++) {
				result.append("number of support vectors for class " + getLabel().getMapping().mapIndex(model.label[i])
						+ ": " + model.nSV[i] + Tools.getLineSeparator());
			}
		} else {
			result.append("number of support vectors: " + model.l + Tools.getLineSeparator());
		}
		return result.toString();
	}

	@Override
	public String getFormula() {
		StringBuffer result = new StringBuffer();

		int kernelType = this.model.param.kernel_type;
		if (kernelType == svm_parameter.PRECOMPUTED) {
			return "Precomputed kernel, no formula possible.";
		} else if (kernelType == svm_parameter.RBF) {
			return "RBF kernel, no formula possible.";
		}

		boolean first = true;
		for (int i = 0; i < getNumberOfSupportVectors(); i++) {
			SupportVector sv = getSupportVector(i);
			if (sv != null) {
				double alpha = sv.getAlpha();
				if (!Tools.isZero(alpha)) {
					result.append(Tools.getLineSeparator());
					double[] x = sv.getX();
					double y = sv.getY();
					double factor = y * alpha;
					if (factor < 0.0d) {
						if (first) {
							result.append("- " + Math.abs(factor));
						} else {
							result.append("- " + Math.abs(factor));
						}
					} else {
						if (first) {
							result.append("  " + factor);
						} else {
							result.append("+ " + factor);
						}
					}

					result.append(" * (" + getDistanceFormula(x, getAttributeConstructions()) + ")");
					first = false;
				}
			}
		}

		double bias = getBias();
		if (!Tools.isZero(bias)) {
			result.append(Tools.getLineSeparator());
			if (bias < 0.0d) {
				if (first) {
					result.append("- " + Math.abs(bias));
				} else {
					result.append("- " + Math.abs(bias));
				}
			} else {
				if (first) {
					result.append(bias);
				} else {
					result.append("+ " + bias);
				}
			}
		}

		return result.toString();
	}

	private String getDistanceFormula(double[] x, String[] attributeConstructions) {
		int kernelType = this.model.param.kernel_type;
		switch (kernelType) {
			case svm_parameter.LINEAR:
				StringBuffer result = new StringBuffer();
				boolean first = true;
				for (int i = 0; i < x.length; i++) {
					double value = x[i];
					if (!Tools.isZero(value)) {
						if (value < 0.0d) {
							if (first) {
								result.append("-" + Math.abs(value) + " * " + attributeConstructions[i]);
							} else {
								result.append(" - " + Math.abs(value) + " * " + attributeConstructions[i]);
							}
						} else {
							if (first) {
								result.append(value + " * " + attributeConstructions[i]);
							} else {
								result.append(" + " + value + " * " + attributeConstructions[i]);
							}
						}
						first = false;
					}
				}
				return result.toString();
			case svm_parameter.POLY:
				StringBuffer dotResult = new StringBuffer();
				first = true;
				for (int i = 0; i < x.length; i++) {
					double value = x[i];
					if (!Tools.isZero(value)) {
						if (value < 0.0d) {
							if (first) {
								dotResult.append("-" + Math.abs(value) + " * " + attributeConstructions[i]);
							} else {
								dotResult.append(" - " + Math.abs(value) + " * " + attributeConstructions[i]);
							}
						} else {
							if (first) {
								dotResult.append(value + " * " + attributeConstructions[i]);
							} else {
								dotResult.append(" + " + value + " * " + attributeConstructions[i]);
							}
						}
						first = false;
					}
				}

				return "pow((" + model.param.gamma + " * (" + dotResult.toString() + ") + " + model.param.coef0 + "), "
						+ model.param.degree + ")";
			case svm_parameter.SIGMOID:
				dotResult = new StringBuffer();
				first = true;
				for (int i = 0; i < x.length; i++) {
					double value = x[i];
					if (!Tools.isZero(value)) {
						if (value < 0.0d) {
							if (first) {
								dotResult.append("-" + Math.abs(value) + " * " + attributeConstructions[i]);
							} else {
								dotResult.append(" - " + Math.abs(value) + " * " + attributeConstructions[i]);
							}
						} else {
							if (first) {
								dotResult.append(value + " * " + attributeConstructions[i]);
							} else {
								dotResult.append(" + " + value + " * " + attributeConstructions[i]);
							}
						}
						first = false;
					}
				}

				return "tanh(" + model.param.gamma + " * (" + dotResult.toString() + ") + " + model.param.coef0 + ")";
			default:
				return "";
		}
	}
}
