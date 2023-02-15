#!/usr/bin/env bash

# vars
version=${tag_ref/refs\/tags\//}

# create release directory
mkdir release

# artifacts files
cd artifacts
for file in */*
do
  cp "${file}" "../release/$(dirname "${file}")-${version}"
done

# release files
cd ../release
for file in *
do
  sha256sum "${file}" > "${file}.sha256sum"
done

tar czf "all-files-${version}.tar.gz" *
sha256sum "all-files-${version}.tar.gz" > "all-files-${version}.tar.gz.sha256sum"
all_files_sha="$( cat "all-files-${version}.tar.gz.sha256sum" | cut -f 1 -d ' ' )"

# release body
touch "../${RELEASE_BODY_FILE}"
for file in *.sha256sum
do
  echo "- $(cat "${file}")" >> "../${RELEASE_BODY_FILE}"
done

# github output
echo new_version="${version}" >> "${GITHUB_OUTPUT}"
echo all_files_sha="${all_files_sha}" > "${GITHUB_OUTPUT}"
