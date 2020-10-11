let c = ./common.dhall

let L = https://prelude.dhall-lang.org/List/package.dhall

let ciJobNameRules =
      L.concatMap
        Text
        Text
        ( \(scalaVersion : Text) ->
            L.map
              Text
              Text
              ( \(javaVersion : Text) ->
                  "status-success=${c.ciJobName scalaVersion javaVersion}"
              )
              c.javaVersions.all
        )
        c.scalaVersions.all

in  { pull_request_rules =
      [ { name = "automatically merge scala-steward's PRs"
        , conditions =
              [ "author=scala-steward", "body~=labels:.*semver-patch.*" ]
            # ciJobNameRules
        , actions.`merge`.method = "merge"
        }
      ]
    }
