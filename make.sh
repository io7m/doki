#!/bin/sh -ex

VERSION=$(head -n 1 VERSION.txt)
VERSION_ALPINE=$(head -n 1 VERSION-alpine.txt)
VERSION_JDK=$(head -n 1 VERSION-jdk.txt)

BASE_DIRECTORY="$(pwd)"

rm -rfv lib

javac \
  -source 21 \
  -target 21 \
  -d lib \
  src/main/java/Doki.java

rm -rfv build
mkdir -p build
cd build
cp "${BASE_DIRECTORY}/src/main/oci/Containerfile" .
cp -rv "${BASE_DIRECTORY}/lib" .

podman build \
--format docker \
--build-arg "version=${VERSION}" \
--build-arg "version_alpine=${VERSION_ALPINE}" \
--build-arg "version_jdk=${VERSION_JDK}" \
--iidfile "../image-id.txt" \
-t "quay.io/io7mcom/doki:${VERSION}" .
