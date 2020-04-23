let c = ./common.dhall

let steps =
      [ c.steps.checkout
      , c.steps.java c.javaVersions.default
      , c.BuildStep.Run
          c.Run::{
          , name = "Check generated YAML files"
          , run =
              ''
              sbt convertDhall
              [[ $(git status --porcelain | wc -l) -eq 0 ]]
              ''
          }
      ]

in  { name = "Check Dhall configs"
    , on = [ "push", "pull_request" ]
    , jobs.check = c.baseJob steps
    }
