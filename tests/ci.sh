#!/usr/bin/env bash

#
# Execute the integration test suite in a Continuous Integration environment
#
# Option:
#   --all: execute the full suite of tests, including slow tests such as Chaos testing
#

set -o pipefail  # trace ERR through pipes
set -o errtrace  # trace ERR through 'time command' and other functions
set -o errexit   ## set -e : exit the script if any statement returns a non-true return value

# This script is used for publish and continuous integration.

get_script_dir () {
     SOURCE="${BASH_SOURCE[0]}"

     while [[ -h "$SOURCE" ]]; do
          DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"
          SOURCE="$( readlink "$SOURCE" )"
          [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
     done
     cd -P "$( dirname "$SOURCE" )"
     pwd
}

cd "$(get_script_dir)"

test_args="testOnly -- -l org.scalatest.tags.Slow"
for param in "$@"
do
  if [[ "--all" == "$param" ]]; then
    test_args=""
    echo "INFO: ---all option detected !"
  fi
done

if pgrep -lf sshuttle > /dev/null ; then
  echo "sshuttle detected. Please close this program as it messes with networking and prevents Docker links to work"
  exit 1
fi

if [[ $NO_SUDO || -n "$CIRCLECI" ]]; then
  DOCKER="docker"
  DOCKER_COMPOSE="docker-compose -f docker-compose-ci.yml"
elif groups "$USER" | grep &>/dev/null '\bdocker\b'; then
  DOCKER="docker"
  DOCKER_COMPOSE="docker-compose -f docker-compose-ci.yml"
else
  DOCKER="sudo docker"
  DOCKER_COMPOSE="sudo docker-compose -f docker-compose-ci.yml"
fi

export TEST_ARGS="${test_args}"

echo "Remove old running containers (if any)..."
$DOCKER_COMPOSE kill
$DOCKER_COMPOSE rm -f

echo "Deploy a Postgres server and wait for it to be ready..."
$DOCKER_COMPOSE up -d db zookeeper
$DOCKER_COMPOSE run wait_zookeeper
$DOCKER_COMPOSE up -d mesos_master
$DOCKER_COMPOSE run wait_mesos_master
$DOCKER_COMPOSE up -d mesos_slave
$DOCKER_COMPOSE build wokentest
$DOCKER_COMPOSE run wait_dbs

echo "Create databases..."
$DOCKER_COMPOSE run create_dbs

echo "Migrate woken database..."
$DOCKER_COMPOSE run woken_db_setup

echo "Migrate metadata database..."
$DOCKER_COMPOSE run sample_meta_db_setup

echo "Migrate features database..."
$DOCKER_COMPOSE run sample_data_db_setup

echo "Run containers..."
for i in 1 2 3 4 5 ; do
  $DOCKER_COMPOSE up -d chronos
  $DOCKER_COMPOSE run wait_chronos
  $DOCKER_COMPOSE logs chronos | grep java.util.concurrent.TimeoutException || break
  echo "Chronos failed to start, restarting..."
  $DOCKER_COMPOSE stop chronos
done

$DOCKER_COMPOSE up -d woken
$DOCKER_COMPOSE run wait_woken

$DOCKER_COMPOSE up -d wokenvalidation
$DOCKER_COMPOSE run wait_wokenvalidation

for i in 1 2 3 4 5 ; do
  $DOCKER_COMPOSE logs chronos | grep java.util.concurrent.TimeoutException || break
  echo "Chronos failed to start, restarting..."
  $DOCKER_COMPOSE stop chronos
  $DOCKER_COMPOSE up -d chronos
  $DOCKER_COMPOSE run wait_chronos
done

echo "The Algorithm Factory is now running on your system"

echo
echo "Running the integration tests..."
mkdir -p ../test-results/integration-tests/ ../responses/
$DOCKER_COMPOSE up wokentest
$DOCKER cp tests_wokentest_1:/build/target .
cp target/responses/*.json .. || echo Cannot export responses
cp target/test-reports/TEST-*.xml ../test-results/integration-tests/ || echo Cannot export integration test reports

mkdir -p ../logs/
$DOCKER_COMPOSE logs zookeeper > ../logs/zookeeper.log
$DOCKER_COMPOSE logs mesos_master > ../logs/mesos_master.log
$DOCKER_COMPOSE logs mesos_slave > ../logs/mesos_slave.log
$DOCKER_COMPOSE logs chronos > ../logs/chronos.log
$DOCKER_COMPOSE logs woken > ../logs/woken.log
$DOCKER_COMPOSE logs wokenvalidation > ../logs/wokenvalidation.log

exit_code="$($DOCKER inspect tests_wokentest_1 --format='{{.State.ExitCode}}')"

if [[ "$exit_code" != "0" ]]; then
  echo "Integration tests failed!"
  exit 1
fi
echo "[OK] All integration tests passed."
