/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.test.util.bpmn.random.steps;

import io.zeebe.test.util.bpmn.random.AbstractExecutionStep;
import java.time.Duration;
import java.util.Map;

public final class StepActivateAndTimeoutJob extends AbstractExecutionStep {

  private final String jobType;

  public StepActivateAndTimeoutJob(final String jobType) {
    this.jobType = jobType;
  }

  public String getJobType() {
    return jobType;
  }

  @Override
  public boolean isAutomatic() {
    return false;
  }

  @Override
  public Duration getDeltaTime() {
    return DEFAULT_DELTA;
  }

  @Override
  public Map<String, Object> updateVariables(
      final Map<String, Object> variables, final Duration activationDuration) {
    return variables;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final StepActivateAndTimeoutJob that = (StepActivateAndTimeoutJob) o;

    if (jobType != null ? !jobType.equals(that.jobType) : that.jobType != null) {
      return false;
    }
    return variables.equals(that.variables);
  }

  @Override
  public int hashCode() {
    int result = jobType != null ? jobType.hashCode() : 0;
    result = 31 * result + variables.hashCode();
    return result;
  }
}
