for f in ci snapshots; do
    dhall-to-yaml --omit-empty --file $f.dhall --output ../.github/workflows/$f.yml
done

dhall-to-yaml --omit-empty --file mergify.dhall --output ../.mergify.yml

dhall-to-json --file scalaVersions.dhall --output ../scalaVersions.json
