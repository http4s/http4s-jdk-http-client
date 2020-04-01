let c = ./common.dhall

let versions = { dhall = "1.31.1", dhallToYaml = "1.6.3" }

let dhallDownload =
        λ(file : Text)
      → let baseUrl =
              "https://github.com/dhall-lang/dhall-haskell/releases/download"

        in  "curl -L ${baseUrl}/${versions.dhall}/${file}-x86_64-linux.tar.bz2 | tar xj"

let steps =
      [ c.steps.checkout
      , c.BuildStep.Run
          c.Run::{
          , name = "Setup Dhall"
          , run =
              ''
              cd $(mktemp -d)
              ${dhallDownload "dhall-${versions.dhall}"}
              ${dhallDownload "dhall-json-${versions.dhallToYaml}"}
              mkdir -p $HOME/bin
              mv bin/* $HOME/bin/
              echo "::add-path::$HOME/bin"
              ''
          }
      , c.BuildStep.Run
          c.Run::{
          , name = "Check Dhall formatting"
          , run =
              ''
              [[ $(find -name '*.dhall*' -exec sh -c 'dhall format --check < {}' \; |& wc -c) -eq 0 ]]
              ''
          }
      , c.BuildStep.Run
          c.Run::{
          , name = "Check generated YAML files"
          , run =
              ''
              cd dhall
              ./generateYaml.sh
              [[ $(git status --porcelain | wc -l) -eq 0 ]]
              ''
          }
      ]

in  { name = "Check Dhall configs"
    , on = [ "push", "pull_request" ]
    , jobs.check = c.baseJob steps
    }
