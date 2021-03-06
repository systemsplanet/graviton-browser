#!/bin/bash -x

set -e

if [[ `uname` != "Darwin" ]]; then
    echo "This script must be run on macOS"
    exit 1
fi

v=$( ./gradlew -q printVersion )
export GRAVITON_VERSION=$v

echo "Building macOS package for Graviton $v"
echo

updatejar=`pwd`/online-update-packages/$v.mac.jar
mkdir -p online-update-packages

./gradlew copyBootstrapToLibs

srcfiles=$( cd build/install/graviton/lib; echo * | sed 's/ /:/g' )

# TODO: Configure to use the serial GC.

javapackager -deploy -nosign \
             -native dmg \
             -outdir build/packaged \
             -outfile "Graviton" \
             -name "Graviton" \
             -appclass app.graviton.shell.Graviton \
             -srcdir build/install/graviton/lib \
             -srcfiles $srcfiles \
             "-Bicons=package/macosx/Graviton.icns" \
             -Bidentifier=app.graviton \
             -BmainJar=graviton-$v.jar \
             -BappVersion=$v \
             -Bmac.CFBundleIdentifier=app.graviton \
             -verbose

if [[ "$1" == "--skip-jar" ]]; then
    exit 0
fi

hdiutil attach "build/packaged/bundles/Graviton-$v.dmg"
cd "/Volumes/Graviton/Graviton.app/Contents/$v"
jar cvf $updatejar .
cd -
umount "/Volumes/Graviton"

[[ -e keystore.p12 ]] && jarsigner -keystore keystore.p12 $updatejar mike