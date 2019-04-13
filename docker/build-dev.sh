#!/bin/sh

DIR=$(cd $(dirname "$0") && pwd)
cd ${DIR}

case $(uname) in
  Linux)
    (cd ${DIR} && sudo docker build --file Dockerfile.dev -t lumifyio/dev .)
    ;;
  Darwin)
    (cd ${DIR} && docker build --file Dockerfile.dev -t lumifyio/dev .)
    ;;
  *)
    echo "unexpected uname: $(uname)"
    exit -1
    ;;
esac
