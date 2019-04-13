#!/bin/sh

DIR=$(cd $(dirname "$0") && pwd)
SRC_DIR=${DIR}/..
KEY_DIR=${DIR}/demo/keys

# make sure the Docker env vars are set
if [ $(uname) = 'Darwin' -o "$1" = '--boot2docker' ]; then
  if [ "$1" = '--boot2docker' ]; then
    shift
  fi
  eval "$(boot2docker shellinit)"
fi

rm -rf $KEY_DIR
mkdir -p $KEY_DIR
ssh-keygen -q -N "" -t dsa -f ${KEY_DIR}/ssh_host_dsa_key
ssh-keygen -q -N "" -t rsa -f ${KEY_DIR}/ssh_host_rsa_key
ssh-keygen -q -N "" -t rsa -f ${KEY_DIR}/id_rsa

cd $SRC_DIR
mvn -P "grunt unix",web-war,web-war-with-gpw,web-war-with-ui-plugins clean package -DskipTests

cd $DIR
mkdir -p ${DIR}/demo/.tmp
cp ${SRC_DIR}/web/war/target/lumify-web-war-*.war ${DIR}/demo/.tmp/root.war
cp ${SRC_DIR}/config/log4j.xml ${DIR}/demo/.tmp
cp -R ${SRC_DIR}/examples/ontology-minimal ${DIR}/demo/.tmp/ontology-minimal
cp -R ${SRC_DIR}/config/knownEntities ${DIR}/demo/.tmp/knownEntities
cp -R ${SRC_DIR}/config/opencv ${DIR}/demo/.tmp/opencv
cp -R ${SRC_DIR}/config/opennlp ${DIR}/demo/.tmp/opennlp

case $(uname) in
  Linux)
    (cd ${DIR} && sudo docker build --file Dockerfile.demo --tag lumifyio/demo .)
    ;;
  Darwin)
    (cd ${DIR} && docker build --file Dockerfile.demo --tag lumifyio/demo .)
    ;;
  *)
    echo "unexpected uname: $(uname)"
    exit -1
    ;;
esac
