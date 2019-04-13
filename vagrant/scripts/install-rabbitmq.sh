#!/bin/bash -eu

ARCHIVE_DIR=/tmp/lumify/archives

# setup the archive dir
if [ ! -d "$ARCHIVE_DIR" ]; then
    mkdir -p $ARCHIVE_DIR
fi

# download the archive
if [ ! -f "$ARCHIVE_DIR/rabbitmq-server-generic-unix-3.5.7.tar.gz" ]; then
    curl -L -o $ARCHIVE_DIR/rabbitmq-server-generic-unix-3.5.7.tar.gz http://apps.k8stest.landaudev.com/lumify/rabbitmq-server-generic-unix-3.5.7.tar.gz
fi

# extract from the archive
tar -xzf $ARCHIVE_DIR/rabbitmq-server-generic-unix-3.5.7.tar.gz -C /opt

# delete the archive
rm -rf $ARCHIVE_DIR

# build the package
ln -s /opt/rabbitmq_server-3.5.7 /opt/rabbitmq
