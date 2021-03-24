/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.test.util.record;

import static java.util.Comparator.comparing;
import static java.util.Map.entry;
import static java.util.Map.ofEntries;
import static org.apache.commons.lang3.StringUtils.leftPad;
import static org.apache.commons.lang3.StringUtils.rightPad;

import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.protocol.record.value.DeploymentRecordValue;
import io.zeebe.protocol.record.value.IncidentRecordValue;
import io.zeebe.protocol.record.value.JobBatchRecordValue;
import io.zeebe.protocol.record.value.JobRecordValue;
import io.zeebe.protocol.record.value.MessageRecordValue;
import io.zeebe.protocol.record.value.MessageStartEventSubscriptionRecordValue;
import io.zeebe.protocol.record.value.MessageSubscriptionRecordValue;
import io.zeebe.protocol.record.value.ProcessInstanceCreationRecordValue;
import io.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.zeebe.protocol.record.value.ProcessMessageSubscriptionRecordValue;
import io.zeebe.protocol.record.value.TimerRecordValue;
import io.zeebe.protocol.record.value.VariableRecordValue;
import io.zeebe.protocol.record.value.deployment.DeployedProcess;
import io.zeebe.protocol.record.value.deployment.DeploymentResource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompactRecordLogger {

  private static final Logger LOG = LoggerFactory.getLogger("io.zeebe.test");
  private static final String BLOCK_SEPARATOR = " - ";

  private static final Map<String, String> ABBREVIATIONS =
      ofEntries(
          entry("PROCESS", "PROC"),
          entry("MESSAGE", "MSG"),
          entry("SUBSCRIPTION", "SUB"),
          entry("SEQUENCE", "SEQ"),
          entry("DISTRIBUTED", "DISTR"),
          entry("ELEMENT", "ELMNT"));

  private final Map<ValueType, Function<Record<?>, String>> valueLoggers = new HashMap<>();
  private final int keyDigits;
  private final int valueTypeChars;
  private final int intentChars;
  private final boolean singlePartition;
  private final Map<Long, String> substitutions = new HashMap<>();
  private final ArrayList<Record<?>> records;

  {
    valueLoggers.put(ValueType.DEPLOYMENT, this::summarizeDeployment);
    valueLoggers.put(ValueType.INCIDENT, this::summarizeIncident);
    valueLoggers.put(ValueType.JOB, this::summarizeJob);
    valueLoggers.put(ValueType.JOB_BATCH, this::summarizeJobBatch);
    valueLoggers.put(ValueType.MESSAGE, this::summarizeMessage);
    valueLoggers.put(
        ValueType.MESSAGE_START_EVENT_SUBSCRIPTION, this::summarizeMessageStartEventSubscription);
    valueLoggers.put(ValueType.MESSAGE_SUBSCRIPTION, this::summarizeMessageSubscription);
    valueLoggers.put(ValueType.PROCESS, this::summarizeProcess);
    valueLoggers.put(ValueType.PROCESS_INSTANCE, this::summarizeProcessInstance);
    valueLoggers.put(ValueType.PROCESS_INSTANCE_CREATION, this::summarizeProcessInstanceCreation);
    valueLoggers.put(
        ValueType.PROCESS_MESSAGE_SUBSCRIPTION, this::summarizeProcessInstanceSubscription);
    valueLoggers.put(ValueType.VARIABLE, this::summarizeVariable);
    valueLoggers.put(ValueType.TIMER, this::summarizeTimer);
    // TODO please extend list
  }

  public CompactRecordLogger(final Collection<Record<?>> records) {
    this.records = new ArrayList<>(records);

    singlePartition =
        records.stream()
                .mapToLong(Record::getKey)
                .filter(key -> key != -1)
                .map(Protocol::decodePartitionId)
                .distinct()
                .count()
            < 2;
    final var highestPosition = this.records.get(this.records.size() - 1).getPosition();

    int digits = 0;
    long num = highestPosition;
    while (num != 0) {
      // num = num/10
      num /= 10;
      ++digits;
    }

    keyDigits = digits;

    valueTypeChars =
        records.stream()
            .map(Record::getValueType)
            .map(ValueType::name)
            .map(this::abbreviate)
            .mapToInt(String::length)
            .max()
            .orElse(0);

    intentChars =
        records.stream()
            .map(Record::getIntent)
            .map(Intent::name)
            .map(this::abbreviate)
            .mapToInt(String::length)
            .max()
            .orElse(0);
  }

  public void log() {
    final var bulkMessage = new StringBuilder().append("Compact log representation:\n");
    bulkMessage
        .append("--------\n")
        .append(
            "\t['C'ommand/'E'event/'R'ejection] [valueType] [intent] - #[position]->#[source record position]  P[partitionId]K[key] - [summary of value]\n")
        .append(
            "\tP9K999 - key; #999 - record position; \"ID\" element/process id; @\"elementid\"/[P9K999] - element with ID and key\n")
        .append(
            "\tKeys are decomposed into partition id and per partition key (e.g. 2251799813685253 -> P1K005). If single partition, the partition is omitted.\n")
        .append(
            "\tLong IDs are shortened (e.g. 'startEvent_5d56488e-0570-416c-ba2d-36d2a3acea78' -> 'star..acea78'\n")
        .append("--------\n");

    records.forEach(
        record -> {
          bulkMessage.append(summarizeRecord(record)).append("\n");
        });

    bulkMessage.append("--------\n").append("Decomposed keys (for debugging):\n");

    substitutions.entrySet().stream()
        .sorted(comparing(Entry::getValue))
        .forEach(
            entry ->
                bulkMessage
                    .append(entry.getValue())
                    .append(" <-> ")
                    .append(entry.getKey())
                    .append("\n"));

    LOG.info(bulkMessage.toString());
  }

  private StringBuilder summarizeRecord(final Record<?> record) {
    final StringBuilder message = new StringBuilder();

    if (record.getRecordType() != RecordType.COMMAND_REJECTION) {
      message.append(summarizeIntent(record));
      message.append(summarizePositionFields(record));
      message.append(summarizeValue(record));
    } else {
      message.append(summarizeRejection(record));
      message.append(summarizePositionFields(record));
    }

    return message;
  }

  private StringBuilder summarizePositionFields(final Record<?> record) {
    return new StringBuilder()
        .append(formatPosition(record.getPosition()))
        .append("->")
        .append(formatPosition(record.getSourceRecordPosition()))
        .append(" ")
        .append(shortenKey(record.getKey()))
        .append(BLOCK_SEPARATOR);
  }

  private StringBuilder summarizeIntent(final Record<?> record) {
    final var valueType = record.getValueType();

    return new StringBuilder()
        .append(record.getRecordType().toString().charAt(0))
        .append(" ")
        .append(rightPad(abbreviate(valueType.name()), valueTypeChars))
        .append(" ")
        .append(rightPad(abbreviate(record.getIntent().name()), intentChars))
        .append(BLOCK_SEPARATOR);
  }

  private String summarizeValue(final Record<?> record) {
    return valueLoggers.getOrDefault(record.getValueType(), this::summarizeMiscValue).apply(record);
  }

  private String summarizeMiscValue(final Record<?> record) {
    return record.getValue().getClass().getSimpleName() + " " + record.getValue().toJson();
  }

  private String summarizeDeployment(final Record<?> record) {
    final var value = (DeploymentRecordValue) record.getValue();

    return value.getResources().stream()
        .map(DeploymentResource::getResourceName)
        .collect(Collectors.joining());
  }

  private String summarizeElementInformation(
      final String elementId, final long elementInstanceKey) {
    return String.format(" @%s[%s]", formatId(elementId), shortenKey(elementInstanceKey));
  }

  private String summarizeProcessInformation(
      final String bpmnProcessId, final long processInstancekey) {
    if (!StringUtils.isEmpty(bpmnProcessId)) {
      return String.format(
          " in <process %s[%s]>", formatId(bpmnProcessId), shortenKey(processInstancekey));
    } else {
      return " in <process ?>";
    }
  }

  private String summarizeVariables(final Map<String, Object> variables) {
    if (variables != null && !variables.isEmpty()) {
      return " with variables: " + variables;
    } else {
      return " (no vars)";
    }
  }

  private String summarizeIncident(final Record<?> record) {
    final var value = (IncidentRecordValue) record.getValue();

    final var result = new StringBuilder();

    if (record.getIntent() != IncidentIntent.RESOLVE) {
      result.append(value.getErrorType()).append(" ").append(value.getErrorMessage()).append(", ");

      if (value.getJobKey() != -1) {
        result.append("joBKey: ").append(shortenKey(value.getJobKey())).append(" ");
      }

      result
          .append(summarizeElementInformation(value.getElementId(), value.getElementInstanceKey()))
          .append(
              summarizeProcessInformation(value.getBpmnProcessId(), value.getProcessInstanceKey()));
    } else {
      result.append(shortenKey(record.getKey()));
    }
    return result.toString();
  }

  private String summarizeJob(final Record<?> record) {
    final var value = (JobRecordValue) record.getValue();

    return summarizeJobRecordValue(record.getKey(), value);
  }

  private String summarizeJobRecordValue(final long jobKey, final JobRecordValue value) {
    final var result = new StringBuilder();

    if (jobKey != -1) {
      result.append(shortenKey(jobKey));
    }
    if (!StringUtils.isEmpty(value.getType())) {
      result
          .append(" \"")
          .append(value.getType())
          .append("\"")
          .append(summarizeElementInformation(value.getElementId(), value.getElementInstanceKey()));
    }

    result.append(" ").append(value.getRetries()).append(" retries,");

    if (!StringUtils.isEmpty(value.getErrorCode())) {
      result.append(" ").append(value.getErrorCode()).append(":").append(value.getErrorMessage());
    }

    result
        .append(
            summarizeProcessInformation(value.getBpmnProcessId(), value.getProcessInstanceKey()))
        .append(summarizeVariables(value.getVariables()));

    return result.toString();
  }

  private String summarizeJobBatch(final Record<?> record) {
    final var value = (JobBatchRecordValue) record.getValue();
    final var jobKeys = value.getJobKeys();

    final var result = new StringBuilder();

    result.append("\"").append(value.getType()).append("\" ");
    if (jobKeys != null && !jobKeys.isEmpty()) {
      result.append(jobKeys.size()).append("/").append(value.getMaxJobsToActivate());
    } else {
      result.append("max: ").append(value.getMaxJobsToActivate());
    }

    if (value.isTruncated()) {
      result.append(" (truncated)");
    }

    if (jobKeys != null && !jobKeys.isEmpty()) {
      for (int i = 0; i < jobKeys.size(); i++) {
        final var jobKey = jobKeys.get(i);
        final var job = value.getJobs().get(i);

        result
            .append(StringUtils.rightPad("\n", 8 + valueTypeChars))
            .append(summarizeJobRecordValue(jobKey, job));
      }
    }

    return result.toString();
  }

  private String summarizeMessage(final Record<?> record) {
    final var value = (MessageRecordValue) record.getValue();

    final var result = new StringBuilder().append("\"").append(value.getName()).append("\"");

    if (!StringUtils.isEmpty(value.getCorrelationKey())) {
      result.append(" correlationKey: ").append(value.getCorrelationKey());
    }

    result.append(summarizeVariables(value.getVariables()));

    return result.toString();
  }

  private String summarizeMessageStartEventSubscription(final Record<?> record) {
    final var value = (MessageStartEventSubscriptionRecordValue) record.getValue();

    return new StringBuilder()
        .append("\"")
        .append(value.getMessageName())
        .append("\"")
        .append(" starting <process ")
        .append(formatId(value.getBpmnProcessId()))
        .append(summarizeVariables(value.getVariables()))
        .toString();
  }

  private String summarizeMessageSubscription(final Record<?> record) {
    final var value = (MessageSubscriptionRecordValue) record.getValue();

    final var result =
        new StringBuilder().append("\"").append(value.getMessageName()).append("\" ");

    if (value.isInterrupting()) {
      result.append("(inter.) ");
    }

    if (!StringUtils.isEmpty(value.getCorrelationKey())) {
      result.append("correlationKey: ").append(value.getCorrelationKey()).append(" ");
    }

    result
        .append("@[")
        .append(shortenKey(value.getElementInstanceKey()))
        .append("]")
        .append(
            summarizeProcessInformation(value.getBpmnProcessId(), value.getProcessInstanceKey()))
        .append(summarizeVariables(value.getVariables()));
    return result.toString();
  }

  private String summarizeProcess(final Record<?> record) {
    final var value = (DeployedProcess) record.getValue();

    return new StringBuilder()
        .append(value.getResourceName())
        .append("->")
        .append(formatId(value.getBpmnProcessId()))
        .append(" (version:")
        .append(value.getVersion())
        .append(")")
        .toString();
  }

  private String summarizeProcessInstance(final Record<?> record) {
    final var value = (ProcessInstanceRecordValue) record.getValue();
    return new StringBuilder()
        .append(value.getBpmnElementType())
        .append(" ")
        .append(formatId(value.getElementId()))
        .append(
            summarizeProcessInformation(value.getBpmnProcessId(), value.getProcessInstanceKey()))
        .toString();
  }

  private String summarizeProcessInstanceCreation(final Record<?> record) {
    final var value = (ProcessInstanceCreationRecordValue) record.getValue();
    return new StringBuilder()
        .append("new <process ")
        .append(formatId(value.getBpmnProcessId()))
        .append(">")
        .append(summarizeVariables(value.getVariables()))
        .toString();
  }

  private String summarizeProcessInstanceSubscription(final Record<?> record) {
    final var value = (ProcessMessageSubscriptionRecordValue) record.getValue();

    final var result =
        new StringBuilder().append("\"").append(value.getMessageName()).append("\" ");

    if (value.isInterrupting()) {
      result.append("(inter.) ");
    }

    if (!StringUtils.isEmpty(value.getCorrelationKey())) {
      result.append("correlationKey: ").append(value.getCorrelationKey()).append(" ");
    }

    result
        .append("@[")
        .append(shortenKey(value.getElementInstanceKey()))
        .append("]")
        .append(
            summarizeProcessInformation(value.getBpmnProcessId(), value.getProcessInstanceKey()))
        .append(summarizeVariables(value.getVariables()));

    return result.toString();
  }

  private String summarizeVariable(final Record<?> record) {
    final var value = (VariableRecordValue) record.getValue();

    return new StringBuilder()
        .append(value.getName())
        .append("->")
        .append(value.getValue())
        .append(" in <process ")
        .append("[")
        .append(shortenKey(value.getProcessInstanceKey()))
        .append("]>")
        .toString();
  }

  private StringBuilder summarizeRejection(final Record<?> record) {
    return new StringBuilder()
        .append(record.getRejectionType())
        .append(" ")
        .append(record.getRejectionReason());
  }

  private String summarizeTimer(final Record<?> record) {
    final var value = (TimerRecordValue) record.getValue();
    final var builder = new StringBuilder();
    final var dueTime = Instant.ofEpochMilli(value.getDueDate()).atZone(ZoneId.systemDefault());

    builder
        .append(
            summarizeElementInformation(value.getTargetElementId(), value.getElementInstanceKey()))
        .append(" ")
        .append(
            summarizeProcessInformation(
                shortenKey(value.getProcessDefinitionKey()), value.getProcessInstanceKey()))
        .append(" due ")
        .append(shortenDateTime(dueTime));

    if (value.getRepetitions() > 1) {
      builder.append(value.getRepetitions()).append(" reps");
    }

    return builder.toString();
  }

  private String shortenKey(final long input) {
    return substitutions.computeIfAbsent(input, this::formatKey);
  }

  private String formatKey(final long key) {
    final var result = new StringBuilder();

    if (!singlePartition) {
      if (key > 0) {
        result.append("P").append(Protocol.decodePartitionId(key));
      } else {
        result.append("  ");
      }
    }

    if (key > 0) {
      result.append(
          "K" + leftPad(Long.toString(Protocol.decodeKeyInPartition(key)), keyDigits, '0'));
    } else {
      result.append(leftPad(Long.toString(key), keyDigits + 1, ' '));
    }

    return result.toString();
  }

  private String formatPosition(final long input) {
    if (input >= 0) {
      return "#" + leftPad(Long.toString(input), keyDigits, '0');
    } else {
      return leftPad(Long.toString(input), keyDigits + 1, ' ');
    }
  }

  private String formatId(final String input) {
    return "\"" + StringUtils.abbreviateMiddle(input, "..", 16) + "\"";
  }

  private String abbreviate(final String input) {
    String result = input;

    for (final String longForm : ABBREVIATIONS.keySet()) {
      result = result.replace(longForm, ABBREVIATIONS.get(longForm));
    }

    return result;
  }

  // omit the date part if it's the same as right now
  private String shortenDateTime(final ZonedDateTime time) {
    final ZonedDateTime now = ZonedDateTime.now();
    final StringBuilder builder = new StringBuilder();

    if (!now.toLocalDate().isEqual(time.toLocalDate())) {
      builder.append(DateTimeFormatter.ISO_LOCAL_DATE.format(time));
    }

    builder.append("T").append(DateTimeFormatter.ISO_LOCAL_TIME.format(time));
    return builder.toString();
  }
}
