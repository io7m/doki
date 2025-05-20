#!/bin/sh -ex

VERSION=$(head -n 1 VERSION.txt)

podman push "quay.io/io7mcom/doki:${VERSION}"
