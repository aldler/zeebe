/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.test.util.bpmn.random.blocks;

import io.zeebe.model.bpmn.builder.AbstractFlowNodeBuilder;
import io.zeebe.model.bpmn.builder.ExclusiveGatewayBuilder;
import io.zeebe.test.util.bpmn.random.AbstractExecutionStep;
import io.zeebe.test.util.bpmn.random.BlockBuilder;
import io.zeebe.test.util.bpmn.random.BlockBuilderFactory;
import io.zeebe.test.util.bpmn.random.ConstructionContext;
import io.zeebe.test.util.bpmn.random.ExecutionPathSegment;
import io.zeebe.test.util.bpmn.random.IDGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a block with a forking exclusive gateway, a default case, a random number of
 * conditional cases, and a joining exclusive gateway. The default case and each conditional case
 * then have a nested sequence of blocks.
 *
 * <p>Hints: the conditional cases all have the condition {@code [forkGatewayId]_branch =
 * "[edge_id]"} so one only needs to set the right variables when starting the process to make sure
 * that a certain edge will be executed
 */
public class ExclusiveGatewayBlockBuilder implements BlockBuilder {

  private final List<BlockBuilder> blockBuilders = new ArrayList<>();
  private final List<String> branchIds = new ArrayList<>();
  private final String forkGatewayId;
  private final String joinGatewayId;
  private final String gatewayConditionVariable;

  public ExclusiveGatewayBlockBuilder(final ConstructionContext context) {
    final Random random = context.getRandom();
    final IDGenerator idGenerator = context.getIdGenerator();
    final int maxBranches = context.getMaxBranches();

    forkGatewayId = "fork_" + idGenerator.nextId();
    joinGatewayId = "join_" + idGenerator.nextId();

    gatewayConditionVariable = forkGatewayId + "_branch";

    final BlockSequenceBuilder.BlockSequenceBuilderFactory blockSequenceBuilderFactory =
        context.getBlockSequenceBuilderFactory();

    final int branches = Math.max(2, random.nextInt(maxBranches));

    for (int i = 0; i < branches; i++) {
      branchIds.add("edge_" + idGenerator.nextId());
      blockBuilders.add(
          blockSequenceBuilderFactory.createBlockSequenceBuilder(context.withIncrementedDepth()));
    }
  }

  @Override
  public AbstractFlowNodeBuilder<?, ?> buildFlowNodes(
      final AbstractFlowNodeBuilder<?, ?> nodeBuilder) {
    final ExclusiveGatewayBuilder forkGateway = nodeBuilder.exclusiveGateway(forkGatewayId);

    AbstractFlowNodeBuilder<?, ?> workInProgress =
        blockBuilders
            .get(0)
            .buildFlowNodes(forkGateway.defaultFlow())
            .exclusiveGateway(joinGatewayId);

    for (int i = 1; i < blockBuilders.size(); i++) {
      final String edgeId = branchIds.get(i);
      final BlockBuilder blockBuilder = blockBuilders.get(i);

      final AbstractFlowNodeBuilder<?, ?> outgoingEdge =
          workInProgress
              .moveToNode(forkGatewayId)
              .sequenceFlowId(edgeId)
              .conditionExpression(gatewayConditionVariable + " = \"" + edgeId + "\"");

      workInProgress = blockBuilder.buildFlowNodes(outgoingEdge).connectTo(joinGatewayId);
    }

    return workInProgress;
  }

  @Override
  public ExecutionPathSegment findRandomExecutionPath(final Random random) {
    final ExecutionPathSegment result = new ExecutionPathSegment();

    final int branch = random.nextInt(branchIds.size());

    if (branch == 0) {
      result.append(new StepPickDefaultCase(forkGatewayId, gatewayConditionVariable));
    } else {
      // take a non-default branch
      final var pickConditionCase =
          new StepPickConditionCase(forkGatewayId, gatewayConditionVariable, branchIds.get(branch));

      if (random.nextBoolean()) {
        // cause an incident by not removing the variable required to evaluate the branch expression
        result.append(
            new StepExpressionIncidentCase(
                forkGatewayId, gatewayConditionVariable, branchIds.get(branch), pickConditionCase));
      }

      result.append(pickConditionCase);
    }

    final BlockBuilder blockBuilder = blockBuilders.get(branch);

    result.append(blockBuilder.findRandomExecutionPath(random));

    return result;
  }

  // this class could also be called "Set variables when starting the process so that the engine
  // will select a certain condition"
  public static final class StepPickConditionCase extends AbstractExecutionStep {

    private final String forkingGatewayId;
    private final String edgeId;

    public StepPickConditionCase(
        final String forkingGatewayId, final String gatewayConditionVariable, final String edgeId) {
      this.forkingGatewayId = forkingGatewayId;
      this.edgeId = edgeId;
      variables.put(gatewayConditionVariable, edgeId);
    }

    @Override
    public boolean isAutomatic() {
      return true;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      final StepPickConditionCase that = (StepPickConditionCase) o;

      if (forkingGatewayId != null
          ? !forkingGatewayId.equals(that.forkingGatewayId)
          : that.forkingGatewayId != null) {
        return false;
      }
      if (edgeId != null ? !edgeId.equals(that.edgeId) : that.edgeId != null) {
        return false;
      }
      return variables.equals(that.variables);
    }

    @Override
    public int hashCode() {
      int result = forkingGatewayId != null ? forkingGatewayId.hashCode() : 0;
      result = 31 * result + (edgeId != null ? edgeId.hashCode() : 0);
      result = 31 * result + variables.hashCode();
      return result;
    }

    public void removeVariable(final String variable) {
      variables.remove(variable);
    }
  }

  // This class removes the variable set by the `StepPickConditionCase` to make sure an incident is
  // raised. This same variable can later be provided (through variable update) and the incident can
  // then be resolved
  public static final class StepExpressionIncidentCase extends AbstractExecutionStep {

    private final String forkingGatewayId;
    private final String edgeId;
    private final String gatewayConditionVariable;

    public StepExpressionIncidentCase(
        final String forkingGatewayId,
        final String gatewayConditionVariable,
        final String edgeId,
        final StepPickConditionCase pickConditionCase) {
      this.forkingGatewayId = forkingGatewayId;
      this.edgeId = edgeId;
      this.gatewayConditionVariable = gatewayConditionVariable;
      pickConditionCase.removeVariable(gatewayConditionVariable);
    }

    @Override
    public boolean isAutomatic() {
      return false;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      final StepExpressionIncidentCase that = (StepExpressionIncidentCase) o;

      if (forkingGatewayId != null
          ? !forkingGatewayId.equals(that.forkingGatewayId)
          : that.forkingGatewayId != null) {
        return false;
      }
      if (edgeId != null ? !edgeId.equals(that.edgeId) : that.edgeId != null) {
        return false;
      }
      return variables.equals(that.variables);
    }

    @Override
    public int hashCode() {
      int result = forkingGatewayId != null ? forkingGatewayId.hashCode() : 0;
      result = 31 * result + (edgeId != null ? edgeId.hashCode() : 0);
      result = 31 * result + variables.hashCode();
      return result;
    }

    public String getGatewayElementId() {
      return forkingGatewayId;
    }

    public String getGatewayConditionVariable() {
      return gatewayConditionVariable;
    }

    public String getEdgeId() {
      return edgeId;
    }
  }

  public static final class StepPickDefaultCase extends AbstractExecutionStep {

    private final String forkingGatewayId;

    public StepPickDefaultCase(
        final String forkingGatewayId, final String gatewayConditionVariable) {
      this.forkingGatewayId = forkingGatewayId;
      variables.put(gatewayConditionVariable, "default-case");
    }

    @Override
    public boolean isAutomatic() {
      return true;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      final StepPickDefaultCase that = (StepPickDefaultCase) o;

      if (forkingGatewayId != null
          ? !forkingGatewayId.equals(that.forkingGatewayId)
          : that.forkingGatewayId != null) {
        return false;
      }
      return variables.equals(that.variables);
    }

    @Override
    public int hashCode() {
      int result = forkingGatewayId != null ? forkingGatewayId.hashCode() : 0;
      result = 31 * result + variables.hashCode();
      return result;
    }
  }

  static class Factory implements BlockBuilderFactory {

    @Override
    public BlockBuilder createBlockBuilder(final ConstructionContext context) {
      return new ExclusiveGatewayBlockBuilder(context);
    }

    @Override
    public boolean isAddingDepth() {
      return true;
    }
  }
}
