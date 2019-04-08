#!/bin/bash

cd `dirname $0`
cd ..

BASE_DIR=`pwd`
TEST_DIR=$BASE_DIR/target
ES_VERSION=`grep '<elasticsearch.version>' $BASE_DIR/pom.xml | sed -e "s/.*>\(.*\)<.*/\1/"`
ES_HOST=localhost
ES_PORT=9200
TMP_FILE=$TEST_DIR/tmp.$$

ZIP_FILE=$HOME/.m2/repository/elasticsearch-$ES_VERSION.zip
if [ ! -f $ZIP_FILE ] ; then
  curl -o $ZIP_FILE -L https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-$ES_VERSION.zip
fi

mkdir -p $TEST_DIR
cd $TEST_DIR

echo "Installing Elasticsearch..."
rm -rf elasticsearch-$ES_VERSION > /dev/null
unzip $ZIP_FILE
./elasticsearch-$ES_VERSION/bin/elasticsearch-plugin install org.codelibs:elasticsearch-sstmpl:6.6.0 -b
./elasticsearch-$ES_VERSION/bin/elasticsearch-plugin install file:`ls $BASE_DIR/target/releases/elasticsearch-*.zip` -b

echo "Starting Elasticsearch..."
./elasticsearch-$ES_VERSION/bin/elasticsearch &
ES_PID=`echo $!`

RET=-1
COUNT=0
while [ $RET != 0 -a $COUNT -lt 60 ] ; do
  echo "Waiting for ${ES_HOST}..."
  curl --connect-timeout 60 --retry 10 -s "$ES_HOST:$ES_PORT/_cluster/health?wait_for_status=green&timeout=3m"
  RET=$?
  COUNT=`expr $COUNT + 1`
  sleep 1
done
curl "$ES_HOST:$ES_PORT"

echo "=== Start Testing ==="
count=1
while [ $count -le 1000 ] ; do
  curl -s -H "Content-Type: application/json" -XPOST "$ES_HOST:$ES_PORT/sample/_doc/$count" -d "{\"id\":\"$count\",\"msg\":\"test $count\",\"counter\":$count,\"category\":"`expr $count % 10`"}" > /dev/null
  count=`expr $count + 1`
done
curl -s -H "Content-Type: application/json" -XPOST "$ES_HOST:$ES_PORT/_refresh" > /dev/null
curl -s -XPOST "$ES_HOST:$ES_PORT/_cat/indices"

echo "add velocity template"
curl -s -H "Content-Type: application/json" -XPOST "$ES_HOST:$ES_PORT/_scripts/index_search_query" \
  -d "{\"script\":{\"lang\":\"velocity\",\"source\":\"{\\\"query\\\":{\\\"match\\\":{\\\"\${my_field}\\\":\\\"\${my_value}\\\"}},\\\"size\\\":\\\"\${my_size}\\\"}\"}}"

echo "search by inline"
curl -s -o $TMP_FILE -H "Content-Type: application/json" -XPOST "$ES_HOST:$ES_PORT/_search/script_template" \
  -d "{\"lang\":\"velocity\",\"template\":{\"query\":{\"match\":{\"\${my_field}\":\"\${my_value}\"}},\"size\":\"\${my_size}\"},\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"5\"}}"
cat $TMP_FILE | jq '.'
RET=`cat $TMP_FILE | jq '.hits.total'`
if [ "x$RET" != "x100" ] ; then
  echo "[ERROR] hits.total is not 100."
  kill $ES_PID
  exit 1
fi

echo "search by stored template"
curl -s -o $TMP_FILE -H "Content-Type: application/json" -XPOST "$ES_HOST:$ES_PORT/_search/script_template" \
  -d "{\"lang\":\"velocity\",\"id\":\"index_search_query\",\"params\":{\"my_field\":\"category\",\"my_value\":\"1\",\"my_size\":\"5\"}}"
cat $TMP_FILE | jq '.'
RET=`cat $TMP_FILE | jq '.hits.total'`
if [ "x$RET" != "x100" ] ; then
  echo "[ERROR] hits.total is not 100."
  kill $ES_PID
  exit 1
fi

echo "=== Finish Testing ==="

echo "Stopping Elasticsearch..."
kill $ES_PID
