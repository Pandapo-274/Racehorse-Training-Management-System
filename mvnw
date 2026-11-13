#!/bin/sh
basedir=$(dirname "$0")
cd "$basedir/backend" && exec ./mvnw "$@"
