#!/bin/sh
#
# Local equivalent of the CI "Build with Maven" step: stamps the 1.2.3
# version placeholder with the version from etc/sveditor.info, runs the Tycho
# build, then puts the placeholder back.
#
# Requires Maven 3.9.9+ and JDK 21+ (JAVA_HOME). Extra args go to mvn.
# The p2 update site lands in releng/org.sveditor.repository/target/repository
#
set -e
cd "$(dirname "$0")/.."

base=$(grep -w 'version' etc/sveditor.info | grep -v '#' \
       | sed -e 's/.*\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*$/\1/')
version="${base}.v$(date +%Y%m%d%H%M)-local"
files=$( (find sveditor -name '*.xml'; find sveditor -name '*.MF') | xargs grep -l '1\.2\.3')

restore() {
    for file in $files; do
        sed -b -i -e "s%$version%1.2.3%g" "$file"
    done
}
trap restore EXIT

echo "Building SVEditor $version"
for file in $files; do
    sed -b -i -e "s%1\.2\.3%$version%g" "$file"
done

${MVN:-mvn} clean verify --no-transfer-progress "$@"
