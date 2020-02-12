for f in ci snapshots; do
    cat $f.dhall | dhall | dhall-to-yaml --omitEmpty > ../.github/workflows/$f.yml
done
