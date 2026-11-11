package com.lumira.finance.payrolltax;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic step-by-step formula trace & input provenance explainer tree.
 * TAX01 compliance: Provides full provenance of inputs, formula expressions,
 * statutory citations, and intermediate values.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CalculationExplainTree(
        String rootNodeId,
        String calculationType,
        String targetSubjectId,
        String finalOutputValue,
        List<ExplainNode> steps
) implements Serializable {

    public CalculationExplainTree {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record ExplainNode(
            String nodeId,
            int stepIndex,
            String stepName,
            String expression,
            String policyCitation,
            List<InputProvenance> inputs,
            String outputValue,
            List<ExplainNode> subSteps
    ) implements Serializable {
        public ExplainNode {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            subSteps = subSteps == null ? List.of() : List.copyOf(subSteps);
        }
    }

    public record InputProvenance(
            String name,
            String value,
            String sourceKind,
            String sourceRef,
            String digest
    ) implements Serializable {}

    public static final class Builder {
        private final String calculationType;
        private final String targetSubjectId;
        private final List<ExplainNode> steps = new ArrayList<>();
        private String finalOutputValue = "0.00";

        public Builder(String calculationType, String targetSubjectId) {
            this.calculationType = calculationType;
            this.targetSubjectId = targetSubjectId;
        }

        public Builder addStep(
                int stepIndex,
                String stepName,
                String expression,
                String policyCitation,
                List<InputProvenance> inputs,
                String outputValue,
                List<ExplainNode> subSteps
        ) {
            steps.add(new ExplainNode(
                    UUID.randomUUID().toString(),
                    stepIndex,
                    stepName,
                    expression,
                    policyCitation,
                    inputs,
                    outputValue,
                    subSteps
            ));
            this.finalOutputValue = outputValue;
            return this;
        }

        public CalculationExplainTree build() {
            return new CalculationExplainTree(
                    UUID.randomUUID().toString(),
                    calculationType,
                    targetSubjectId,
                    finalOutputValue,
                    Collections.unmodifiableList(steps)
            );
        }
    }
}
