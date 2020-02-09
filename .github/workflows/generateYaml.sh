for f in ci snapshots; do
    cat $f.dhall | dhall | dhall-to-yaml --omitEmpty > $f.yml
done
