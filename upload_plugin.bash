#!/bin/bash

. ${HOME}/.config/.graylog-plugin-uploader.conf.bash

PANIC_CHECKOUT_DIR=~/idea/hh-panic
cd ${PANIC_CHECKOUT_DIR}
mvn package
scp target/panic-1.0.jar ${SSH_USER}@${RELAY_HOST}:~
ssh -A ${SSH_USER}@${RELAY_HOST} "scp ~/panic-1.0.jar ${TARGET_HOST}:~/ru.hh.gl2plugins.panic.Panic_gl2plugin.jar"
ssh -A ${SSH_USER}@${RELAY_HOST} "ssh ${TARGET_HOST} 'sudo cp ~/ru.hh.gl2plugins.panic.Panic_gl2plugin.jar ${TARGET_FOLDER}'"
