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

public class StepActivateJobAndThrowError extends AbstractExecutionStep {

  private final String jobType;
  private final String errorCode;

  public StepActivateJobAndThrowError(final String jobType, final String errorCode) {
    super();
    this.jobType = jobType;
    this.errorCode = errorCode;
  }

  @Override
  public boolean isAutomatic() {
    return false;
  }

  public String getJobType() {
    return jobType;
  }

  public String getErrorCode() {
    return errorCode;
  }

  @Override
  public Duration getDeltaTime() {
    return VIRTUALLY_NO_TIME;
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

    final StepActivateJobAndThrowError that = (StepActivateJobAndThrowError) o;

    if (jobType != null ? !jobType.equals(that.jobType) : that.jobType != null) {
      return false;
    }
    if (errorCode != null ? !errorCode.equals(that.errorCode) : that.errorCode != null) {
      return false;
    }
    return variables.equals(that.variables);
  }

  @Override
  public int hashCode() {
    int result = jobType != null ? jobType.hashCode() : 0;
    result = errorCode != null ? errorCode.hashCode() : 0;
    result = 31 * result + variables.hashCode();
    return result;
  }
}
