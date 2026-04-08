#!/bin/bash
set -e

# Always sync settings.xml from the image into the (potentially volume-mounted) .m2 dir.
# This ensures that after an image rebuild, updated credentials/mirrors are picked up
# even when an existing named volume already contains the Maven repository cache.
cp /opt/maven-settings/settings.xml /home/appuser/.m2/settings.xml

exec java ${JAVA_OPTS} -jar /app/quarkus-run.jar "$@"
