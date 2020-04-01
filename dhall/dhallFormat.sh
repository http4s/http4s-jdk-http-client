cd $(dirname "$0")
find -name '*.dhall' -exec dhall --ascii format --inplace {} \;
