#!/bin/bash -eux

# specialized script to run tests with random elements without flaky test detection
# no need for special parallelism parameters since we only run a single test
tmpfile=$(mktemp)
mvn -o -B --fail-never -T${MAVEN_PARALLELISM} -s ${MAVEN_SETTINGS_XML} \
    -P skip-unstable-ci,include-random-tests \
    "${MAVEN_PROPERTIES[@]}" verify | tee ${tmpfile}
