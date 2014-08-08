#!/bin/bash

# defaults
SSH_USER=arybalkin
RELAY_HOST=oper.hh.ru
TARGET_HOST=graylog1.hhnet.ru
TARGET_FOLDER=/opt/graylog2-server/plugin/outputs

JAVA_HOME=/opt/jdk7-1.7.0_60
PATH=${JAVA_HOME}/bin:${JAVA_HOME}/jre/bin:${PATH}
J2SDKDIR=${JAVA_HOME}
J2REDIR=${JAVA_HOME}/jre

# overrides for above defaults
. ${HOME}/.config/.graylog-plugin-uploader.conf.bash

PANIC_CHECKOUT_DIR=~/idea/hh-panic
cd ${PANIC_CHECKOUT_DIR}
mvn package
scp target/panic-1.0.jar ${SSH_USER}@${RELAY_HOST}:~
ssh -A ${SSH_USER}@${RELAY_HOST} "scp ~/panic-1.0.jar ${TARGET_HOST}:~/ru.hh.gl2plugins.panic.Panic_gl2plugin.jar"
ssh -A ${SSH_USER}@${RELAY_HOST} "ssh ${TARGET_HOST} 'sudo cp ~/ru.hh.gl2plugins.panic.Panic_gl2plugin.jar ${TARGET_FOLDER}'"
